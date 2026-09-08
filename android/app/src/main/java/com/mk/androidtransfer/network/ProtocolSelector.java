package com.mk.androidtransfer.network;

import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 探测电脑端开放的通道，按速度优先级自动选：TCP > FTP > HTTP PUT > multipart。
 */
public class ProtocolSelector {
    private static final String TAG = "ProtocolSelector";
    /** 连一个端口通不通，判死得快一点没关系 —— 通的那条毫秒级就回来了。 */
    private static final int PROBE_MS = 400;

    /**
     * 问对面「你支持哪些通道」的超时。
     *
     * <p>原来和端口探测共用 400ms，太紧了：热点、弱信号下一次 HTTP 往返
     * 超过 400ms 很常见，于是探测失败 → 退到 http_multipart，
     * 而**手机接收端根本没实现 multipart**（只有 /api/fast/put），
     * 结果是 404，用户看到「传输失败」。
     */
    private static final int CAPS_MS = 2500;

    public static class Choice {
        public final TransferProtocol protocol;
        public final String host;
        public final int httpPort;
        public final int tcpPort;
        public final int ftpPort;

        public Choice(TransferProtocol protocol, String host, int httpPort, int tcpPort, int ftpPort) {
            this.protocol = protocol;
            this.host = host;
            this.httpPort = httpPort;
            this.tcpPort = tcpPort;
            this.ftpPort = ftpPort;
        }
    }

    public static Choice select(String serverUrl) {
        // 没有 scheme 的「host:port」在 Uri 眼里没有 host，于是一路退化到
        // 127.0.0.1 —— 传输会去连本机，而用户看到的是
        // 「Failed to connect to /127.0.0.1:9500」，完全看不出发生了什么。
        // 这里把它补上，别让一个少写的 http:// 变成一条查不出的故障。
        String raw = serverUrl == null ? "" : serverUrl.trim();
        if (!raw.contains("://")) {
            raw = "http://" + raw;
        }
        Uri uri = Uri.parse(raw);
        String host = uri.getHost() != null ? uri.getHost() : "127.0.0.1";
        int httpPort = uri.getPort() > 0 ? uri.getPort() : 9500;
        int tcpPort = 9501;
        int ftpPort = 9502;

        boolean capsOk = false;
        // 对面自己报的通道清单。手机只报 http_put —— 它没有 ATF3 裸流也没有 FTP。
        Set<String> prefer = new LinkedHashSet<>();
        try {
            HttpURLConnection conn = (HttpURLConnection) new java.net.URL(
                    raw.replaceAll("/+$", "") + "/api/fast/caps").openConnection();
            conn.setConnectTimeout(CAPS_MS);
            conn.setReadTimeout(CAPS_MS);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() == 200) {
                capsOk = true;
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] tmp = new byte[4096];
                int n;
                while ((n = is.read(tmp)) != -1) {
                    bos.write(tmp, 0, n);
                }
                JSONObject json = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
                tcpPort = json.optInt("tcp_port", tcpPort);
                ftpPort = json.optInt("ftp_port", ftpPort);
                JSONArray list = json.optJSONArray("prefer");
                for (int i = 0; list != null && i < list.length(); i++) {
                    prefer.add(list.optString(i, ""));
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.w(TAG, "caps 探测失败，用默认端口", e);
        }

        // 对面说了它支持什么，就别再去敲它明说没有的端口 —— 那是两次
        // 白等的连接超时，用户只看得到「连上了但半天不动」。
        boolean allowTcp = prefer.isEmpty() || prefer.contains("tcp");
        boolean allowFtp = prefer.isEmpty() || prefer.contains("ftp");

        if (allowTcp && canConnect(host, tcpPort)) {
            return new Choice(TransferProtocol.TCP, host, httpPort, tcpPort, ftpPort);
        }
        if (allowFtp && canConnect(host, ftpPort)) {
            return new Choice(TransferProtocol.FTP, host, httpPort, tcpPort, ftpPort);
        }
        if (capsOk || httpPort == PeerServer.PORT) {
            return new Choice(TransferProtocol.HTTP_PUT, host, httpPort, tcpPort, ftpPort);
        }
        // multipart 只剩下一个用处：老版本的桌面端。手机接收端没有这个口，
        // 走到这儿就是 404，所以上面那一行专门把手机的端口挡在前面。
        return new Choice(TransferProtocol.HTTP_MULTIPART, host, httpPort, tcpPort, ftpPort);
    }

    private static boolean canConnect(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), PROBE_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
