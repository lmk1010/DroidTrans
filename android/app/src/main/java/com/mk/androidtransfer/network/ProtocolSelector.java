package com.mk.androidtransfer.network;

import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 探测电脑端开放的通道，按速度优先级自动选：TCP > FTP > HTTP PUT > multipart。
 */
public class ProtocolSelector {
    private static final String TAG = "ProtocolSelector";
    private static final int PROBE_MS = 400;

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
        Uri uri = Uri.parse(serverUrl);
        String host = uri.getHost() != null ? uri.getHost() : "127.0.0.1";
        int httpPort = uri.getPort() > 0 ? uri.getPort() : 9500;
        int tcpPort = 9501;
        int ftpPort = 9502;

        boolean capsOk = false;
        try {
            HttpURLConnection conn = (HttpURLConnection) new java.net.URL(
                    serverUrl.replaceAll("/+$", "") + "/api/fast/caps").openConnection();
            conn.setConnectTimeout(PROBE_MS);
            conn.setReadTimeout(PROBE_MS);
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
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.w(TAG, "caps 探测失败，用默认端口", e);
        }

        if (canConnect(host, tcpPort)) {
            return new Choice(TransferProtocol.TCP, host, httpPort, tcpPort, ftpPort);
        }
        if (canConnect(host, ftpPort)) {
            return new Choice(TransferProtocol.FTP, host, httpPort, tcpPort, ftpPort);
        }
        if (capsOk) {
            return new Choice(TransferProtocol.HTTP_PUT, host, httpPort, tcpPort, ftpPort);
        }
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
