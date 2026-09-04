package com.mk.androidtransfer.network;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.mk.androidtransfer.model.UploadFileItem;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/**
 * 把一个文件用 TCP / FTP / HTTP PUT 推到电脑。
 */
public class FastTransferClient {
    private static final String TAG = "FastTransferClient";
    private static final int CHUNK = 1024 * 1024;

    // ATF3 应答的状态字节。必须和 desktop/internal/fast/fast.go 里的常量一致。
    private static final int STATUS_GO = 0x00;   // 继续，后面跟 8 字节大端偏移量
    private static final int STATUS_ERR = 0x01;  // 拒绝，后面跟 4 字节长度 + UTF-8 原因
    /** 小于这个大小就不问偏移量了：多一次往返比直接重传它还贵。 */
    private static final long RESUME_MIN = 8L * 1024 * 1024;

    private static final int STATUS_DONE = 0x02; // 收完了，落盘成功
    // 超出免费额度。和 STATUS_ERR 同样的帧，但分开一个状态字 ——
    // 混在普通错误里，用户只会以为传输坏了，看不到升级这条路。
    private static final int STATUS_UPGRADE = 0x03;

    /** 超出免费额度。界面据此引导升级，而不是只弹一句错误。 */
    public static class NeedsProException extends IOException {
        public NeedsProException(String message) {
            super(message);
        }
    }

    public interface ProgressListener {
        boolean isCancelled();
        void onBytes(long sent, long total);
    }

    public static void send(Context context, ProtocolSelector.Choice choice,
                            UploadFileItem item, String deviceId,
                            OkHttpClient httpClient, ProgressListener listener) throws IOException {
        InputStream raw = openStream(context, item);
        if (raw == null) {
            throw new IOException("无法读取文件");
        }
        try (InputStream in = new BufferedInputStream(raw, CHUNK)) {
            switch (choice.protocol) {
                case TCP:
                    sendTcp(choice.host, choice.tcpPort, item, in, listener);
                    break;
                case FTP:
                    sendFtp(choice.host, choice.ftpPort, item, in, listener);
                    break;
                case HTTP_PUT:
                default:
                    sendHttpPutStream(choice, item, deviceId, httpClient, in, listener);
                    break;
            }
        }
    }

    public static void sendGenerated(ProtocolSelector.Choice choice, String name, long size,
                                     InputStream in, String deviceId, OkHttpClient httpClient,
                                     ProgressListener listener) throws IOException {
        UploadFileItem item = new UploadFileItem(name, name, size, null, name);
        try (InputStream buffered = new BufferedInputStream(in, CHUNK)) {
            switch (choice.protocol) {
                case TCP:
                    sendTcp(choice.host, choice.tcpPort, item, buffered, listener);
                    break;
                case FTP:
                    sendFtp(choice.host, choice.ftpPort, item, buffered, listener);
                    break;
                default:
                    sendHttpPutStream(choice, item, deviceId, httpClient, buffered, listener);
                    break;
            }
        }
    }

    private static InputStream openStream(Context context, UploadFileItem item) throws IOException {
        if (!TextUtils.isEmpty(item.getPath())) {
            File file = new File(item.getPath());
            if (file.exists() && file.canRead()) {
                return new FileInputStream(file);
            }
        }
        if (!TextUtils.isEmpty(item.getUri())) {
            ContentResolver resolver = context.getContentResolver();
            return resolver.openInputStream(Uri.parse(item.getUri()));
        }
        return null;
    }

    /**
     * ATF3 快传。
     *
     * 比上一版多一次握手：发完文件头先等电脑回一个偏移量，再从那里开始发。
     * 多这一个来回，换来的是「传到一半断网，重连只补剩下的」——
     * 导一半卡住、只能从头再来，正是用户放弃一个传输工具的头号原因。
     *
     * ATF1（无令牌）和 ATF2（无偏移协商）都已经删掉，电脑端不再接受。
     */
    private static void sendTcp(String host, int port, UploadFileItem item,
                                InputStream in, ProgressListener listener) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setTcpNoDelay(true);
        socket.setSendBufferSize(1024 * 1024);
        try (OutputStream raw = socket.getOutputStream();
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw, CHUNK))) {
            byte[] name = item.getRelativePath().getBytes(StandardCharsets.UTF_8);
            String token = RetrofitClient.getToken();
            byte[] tok = (token == null ? "" : token).getBytes(StandardCharsets.UTF_8);

            out.write(new byte[]{'A', 'T', 'F', '3'});
            out.writeInt(tok.length);
            out.write(tok);
            out.writeInt(name.length);
            out.write(name);
            out.writeLong(item.getSize());
            // 必须先把头推出去，电脑才可能回话。少了这个 flush 就是死锁：
            // 头还在 BufferedOutputStream 里，两边都在等对方。
            out.flush();

            InputStream reply = socket.getInputStream();
            long offset = readAccept(reply);
            if (offset >= item.getSize() && item.getSize() > 0) {
                // 电脑上已经有完整的一份，一个字节都不用发
                if (listener != null) {
                    listener.onBytes(item.getSize(), item.getSize());
                }
                readFinal(reply);
                return;
            }
            skipFully(in, offset);
            copy(in, out, item.getSize(), offset, listener);
            out.flush();
            readFinal(reply);
        } finally {
            socket.close();
        }
    }

    /** 读握手应答，返回该从第几个字节开始发。 */
    private static long readAccept(InputStream in) throws IOException {
        int status = in.read();
        if (status == STATUS_UPGRADE) {
            throw new NeedsProException(readErrorMessage(in));
        }
        if (status == STATUS_ERR) {
            throw new IOException(readErrorMessage(in));
        }
        if (status != STATUS_GO) {
            throw new IOException("电脑没有按约定回应（状态 " + status + "）");
        }
        byte[] b = new byte[8];
        readFully(in, b);
        long v = 0;
        for (byte x : b) {
            v = (v << 8) | (x & 0xFFL);
        }
        return v;
    }

    /** 读收尾应答。 */
    private static void readFinal(InputStream in) throws IOException {
        int status = in.read();
        if (status == STATUS_UPGRADE) {
            throw new NeedsProException(readErrorMessage(in));
        }
        if (status == STATUS_ERR) {
            throw new IOException(readErrorMessage(in));
        }
        if (status != STATUS_DONE) {
            throw new IOException("电脑没有确认收到");
        }
    }

    private static String readErrorMessage(InputStream in) throws IOException {
        byte[] lenBuf = new byte[4];
        readFully(in, lenBuf);
        int len = 0;
        for (byte x : lenBuf) {
            len = (len << 8) | (x & 0xFF);
        }
        if (len <= 0 || len > 4096) {
            return "电脑拒绝了这次传输";
        }
        byte[] msg = new byte[len];
        readFully(in, msg);
        return new String(msg, StandardCharsets.UTF_8);
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new IOException("连接被提前关闭");
            }
            off += n;
        }
    }

    /**
     * 跳过已经传过的部分。
     *
     * InputStream.skip 允许少跳，甚至可以一直返回 0（ContentResolver 拿到的流很常见），
     * 所以少跳的时候要退回去真读一段丢掉 —— 直接信任 skip 的返回值，
     * 会让续传从错误的位置开始，文件内容静默错位。
     */
    private static void skipFully(InputStream in, long count) throws IOException {
        long left = count;
        byte[] sink = null;
        while (left > 0) {
            long n = in.skip(left);
            if (n > 0) {
                left -= n;
                continue;
            }
            if (sink == null) {
                sink = new byte[8192];
            }
            int r = in.read(sink, 0, (int) Math.min(sink.length, left));
            if (r < 0) {
                throw new IOException("文件比电脑上已有的部分还短，没法续传");
            }
            left -= r;
        }
    }

    private static void sendFtp(String host, int port, UploadFileItem item,
                                InputStream in, ProgressListener listener) throws IOException {
        Socket ctrl = new Socket();
        ctrl.connect(new InetSocketAddress(host, port), 5000);
        readLine(ctrl);
        writeLine(ctrl, "USER android");
        readLine(ctrl);
        String ftpToken = RetrofitClient.getToken();
        writeLine(ctrl, "PASS " + (ftpToken == null || ftpToken.isEmpty() ? "transfer" : ftpToken));
        readLine(ctrl);
        writeLine(ctrl, "TYPE I");
        readLine(ctrl);
        writeLine(ctrl, "PASV");
        String pasv = readLine(ctrl);
        int dataPort = parsePasvPort(pasv);
        Socket data = new Socket();
        data.connect(new InetSocketAddress(host, dataPort), 5000);
        data.setTcpNoDelay(true);
        writeLine(ctrl, "STOR " + item.getRelativePath().replace(" ", "_"));
        readLine(ctrl);
        try (OutputStream out = new BufferedOutputStream(data.getOutputStream(), CHUNK)) {
            copy(in, out, item.getSize(), listener);
            out.flush();
        }
        data.close();
        readLine(ctrl);
        writeLine(ctrl, "QUIT");
        ctrl.close();
    }

    private static void sendHttpPutStream(ProtocolSelector.Choice choice, UploadFileItem item,
                                  String deviceId, OkHttpClient httpClient,
                                  InputStream in, ProgressListener listener) throws IOException {
        String base = "http://" + choice.host + ":" + choice.httpPort;
        final long total = item.getSize();

        // 问对方已经收到多少了。小文件不问 —— 一次往返比重传它还贵。
        final long offset = total > RESUME_MIN ? askOffset(base, item, httpClient, total) : 0;
        if (offset >= total && total > 0) {
            // 对面那份已经是完整的，一个字节都不用发
            if (listener != null) {
                listener.onBytes(total, total);
            }
            return;
        }
        if (offset > 0) {
            skipFully(in, offset);
        }

        RequestBody body = new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.parse("application/octet-stream");
            }

            @Override
            public long contentLength() {
                // 这次只发剩下的那段。声明整个文件大小的话，对方会一直等
                // 那些永远不会来的字节。
                return total > 0 ? total - offset : -1;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                byte[] buf = new byte[CHUNK];
                long sent = offset;
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (listener != null && listener.isCancelled()) {
                        throw new IOException("cancelled");
                    }
                    sink.write(buf, 0, n);
                    sent += n;
                    if (listener != null) {
                        // 进度从已有的字节接着报，否则续传时进度条会从 0 重爬
                        listener.onBytes(sent, total);
                    }
                }
            }
        };
        Request request = new Request.Builder()
                .url(base + "/api/fast/put")
                .header("X-Filename", item.getName())
                .header("X-Relative-Path", item.getRelativePath())
                .header("X-File-Size", String.valueOf(total))
                .header("X-Start-Offset", String.valueOf(offset))
                .header("X-Device-Id", deviceId != null ? deviceId : "unknown")
                .put(body)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP PUT " + response.code());
            }
        }
    }

    /**
     * 问对方这个文件已经收到第几个字节了。
     *
     * <p>问不出来就当 0 —— 旧版本的对端没有这个口，从头传虽然慢，
     * 但一定是对的；这里绝不能因为续传失败就把整次传输判死。
     */
    private static long askOffset(String base, UploadFileItem item,
                                  OkHttpClient httpClient, long total) {
        try {
            HttpUrl url = HttpUrl.parse(base + "/api/fast/offset");
            if (url == null) {
                return 0;
            }
            Request req = new Request.Builder()
                    .url(url.newBuilder()
                            .addQueryParameter("name", item.getRelativePath())
                            .addQueryParameter("size", String.valueOf(total))
                            .build())
                    .get()
                    .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    return 0;
                }
                JSONObject j = new JSONObject(resp.body().string());
                long off = j.optLong("offset", 0);
                return off < 0 || off > total ? 0 : off;
            }
        } catch (IOException | JSONException e) {
            return 0;
        }
    }

    private static void copy(InputStream in, OutputStream out, long total,
                             ProgressListener listener) throws IOException {
        copy(in, out, total, 0, listener);
    }

    /**
     * base 是这个文件此前已经落在电脑上的字节数 —— 进度要从它接着报，
     * 否则续传时进度条会从 0 重新爬一遍，用户会以为又从头传了。
     */
    private static void copy(InputStream in, OutputStream out, long total, long base,
                             ProgressListener listener) throws IOException {
        byte[] buf = new byte[CHUNK];
        long sent = base;
        int n;
        while ((n = in.read(buf)) != -1) {
            if (listener != null && listener.isCancelled()) {
                throw new IOException("cancelled");
            }
            out.write(buf, 0, n);
            sent += n;
            if (listener != null) {
                listener.onBytes(sent, total);
            }
        }
    }

    private static void writeLine(Socket socket, String line) throws IOException {
        socket.getOutputStream().write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static String readLine(Socket socket) throws IOException {
        StringBuilder sb = new StringBuilder();
        InputStream in = socket.getInputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        String line = sb.toString();
        Log.d(TAG, "FTP " + line);
        return line;
    }

    private static int parsePasvPort(String pasv) throws IOException {
        int start = pasv.indexOf('(');
        int end = pasv.indexOf(')');
        if (start < 0 || end < start) {
            throw new IOException("坏的 PASV: " + pasv);
        }
        String[] parts = pasv.substring(start + 1, end).split(",");
        if (parts.length < 6) {
            throw new IOException("坏的 PASV: " + pasv);
        }
        return Integer.parseInt(parts[4].trim()) * 256 + Integer.parseInt(parts[5].trim());
    }
}
