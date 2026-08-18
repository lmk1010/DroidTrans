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
    private static final int CHUNK = 256 * 1024;

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

    private static void sendTcp(String host, int port, UploadFileItem item,
                                InputStream in, ProgressListener listener) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setTcpNoDelay(true);
        socket.setSendBufferSize(1024 * 1024);
        try (OutputStream raw = socket.getOutputStream();
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw, CHUNK))) {
            byte[] name = item.getRelativePath().getBytes(StandardCharsets.UTF_8);
            out.write(new byte[]{'A', 'T', 'F', '1'});
            out.writeInt(name.length);
            out.write(name);
            out.writeLong(item.getSize());
            copy(in, out, item.getSize(), listener);
            out.flush();
            byte[] resp = new byte[16];
            int n = socket.getInputStream().read(resp);
            if (n <= 0 || resp[0] != 'O') {
                throw new IOException("TCP 服务器未确认");
            }
        } finally {
            socket.close();
        }
    }

    private static void sendFtp(String host, int port, UploadFileItem item,
                                InputStream in, ProgressListener listener) throws IOException {
        Socket ctrl = new Socket();
        ctrl.connect(new InetSocketAddress(host, port), 5000);
        readLine(ctrl);
        writeLine(ctrl, "USER android");
        readLine(ctrl);
        writeLine(ctrl, "PASS transfer");
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
        String url = "http://" + choice.host + ":" + choice.httpPort + "/api/fast/put";
        RequestBody body = new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.parse("application/octet-stream");
            }

            @Override
            public long contentLength() {
                return item.getSize() > 0 ? item.getSize() : -1;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                byte[] buf = new byte[CHUNK];
                long sent = 0;
                long total = item.getSize();
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (listener != null && listener.isCancelled()) {
                        throw new IOException("cancelled");
                    }
                    sink.write(buf, 0, n);
                    sent += n;
                    if (listener != null) {
                        listener.onBytes(sent, total);
                    }
                }
            }
        };
        Request request = new Request.Builder()
                .url(url)
                .header("X-Filename", item.getName())
                .header("X-Relative-Path", item.getRelativePath())
                .header("X-File-Size", String.valueOf(item.getSize()))
                .header("X-Device-Id", deviceId != null ? deviceId : "unknown")
                .put(body)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP PUT " + response.code());
            }
        }
    }

    private static void copy(InputStream in, OutputStream out, long total,
                             ProgressListener listener) throws IOException {
        byte[] buf = new byte[CHUNK];
        long sent = 0;
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
