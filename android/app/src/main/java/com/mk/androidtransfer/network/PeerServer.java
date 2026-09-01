package com.mk.androidtransfer.network;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 这台手机当接收方。
 *
 * <p>现有协议是 HTTP：电脑当服务端，手机当客户端。两台手机之间没有天然的服务端，
 * 所以要有一台先站过去 —— 这个类就是那一半，和 iOS 的 PeerServer.swift 是同一套东西。
 *
 * <p>实现的口（与 Go 端 openPath() 和 iOS 端一一对应）：
 * <pre>
 *   GET  /api/health      在不在、是不是卓传
 *   GET  /api/wifi/info   叫什么、要不要配对、走哪条上传通道
 *   GET  /api/fast/caps   同上，发送方握手时会问
 *   POST /api/pair        六位码换令牌
 *   GET  /api/outbox      发送方拿它验令牌（这侧只收不发，永远空清单）
 *   PUT  /api/fast/put    收文件
 *   POST /api/inbox/text  收一段文字
 * </pre>
 *
 * <p>再加上 NsdManager 广播同一个 _droidtrans._tcp，对面的雷达就会像发现电脑一样
 * 发现这台手机 —— 发送方那一侧一行都不用改。
 *
 * <p>没有引 NanoHTTPD 之类的库：要实现的只有七个口，而最要紧的 PUT 必须
 * 边收边往磁盘写 —— 手机内存装不下一个 4GB 的视频，多数轻量库会先把 body 收进内存。
 */
public final class PeerServer {

    private static final String TAG = "PeerServer";

    /** 手机当接收方时监听的端口。避开桌面端的 9500，见 iOS 端 Ports.peer 的注释。 */
    public static final int PORT = 9600;

    public interface Listener {
        void onStateChanged(boolean running, String error);

        void onFileReceived(String name, long size, File file);

        /**
         * 有人在敲门。界面弹个框问一句，然后调 approve() 或 deny()。
         *
         * <p>name 是对面自己报的设备名，只当提示看，不当身份用 ——
         * 真正的把关是这台的主人点不点那个「同意」。
         */
        void onKnock(String name);
    }

    private final Context app;
    private final NsdManager nsd;
    private final Handler main = new Handler(Looper.getMainLooper());

    private ServerSocket socket;
    private ExecutorService pool;
    private WifiManager.MulticastLock lock;
    private NsdManager.RegistrationListener registration;

    private Listener listener;
    private String pairingCode = "";

    /**
     * 配过的令牌。只活在内存里 —— 这一次「我要收」结束就作废。
     * 手机不是常驻服务，没必要在磁盘上留一份长期授权。
     */
    private final Set<String> tokens =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private volatile boolean running;

    /**
     * 正等着主人点头的那一条请求。
     *
     * <p>配对码不该是主路 —— 对面在雷达上点你一下，你这台弹一句
     * 「XXX 想连过来」，同意就通，一个字都不用输。
     * 码退成兜底：手输地址、或者组播被拦掉发现不到的时候才用得上。
     */
    private java.util.concurrent.CountDownLatch knockLatch;
    private volatile String knockToken;

    public PeerServer(Context ctx) {
        app = ctx.getApplicationContext();
        nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
    }

    // ------------------------------------------------------------------ 开关

    public synchronized void start(String deviceName, Listener l) {
        if (running) {
            return;
        }
        listener = l;
        tokens.clear();
        pairingCode = String.format(Locale.US, "%06d", new Random().nextInt(1000000));

        // setReuseAddress 必须在 bind 之前设 —— new ServerSocket(PORT) 会直接
        // 连着绑上，那之后再设就是个空操作。刚 stop() 完立刻再 start() 时，
        // 上一个套接字还在 TIME_WAIT，没有这个标志就会绑不上。
        try {
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new java.net.InetSocketAddress(PORT));
        } catch (IOException e) {
            closeQuietly(socket);
            socket = null;
            fail("端口 " + PORT + " 打不开：" + e.getMessage());
            return;
        }

        running = true;
        pool = Executors.newCachedThreadPool();
        new Thread(this::acceptLoop, "peer-accept").start();

        acquireLock();
        advertise(deviceName);
        main.post(() -> {
            if (listener != null) {
                listener.onStateChanged(true, null);
            }
        });
    }

    public synchronized void stop() {
        running = false;
        unadvertise();
        releaseLock();
        closeQuietly(socket);
        socket = null;
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        deny();   // 别把还挂着的那条连接留在那儿等超时
        tokens.clear();
        main.post(() -> {
            if (listener != null) {
                listener.onStateChanged(false, null);
            }
        });
    }

    public boolean isRunning() {
        return running;
    }

    public String getPairingCode() {
        return pairingCode;
    }

    private void fail(String msg) {
        Log.w(TAG, msg);
        main.post(() -> {
            if (listener != null) {
                listener.onStateChanged(false, msg);
            }
        });
    }

    // ------------------------------------------------------------- Bonjour

    /**
     * 组播锁。安卓默认会把发给别人的组播包丢掉省电，不拿这把锁，
     * 对面永远发现不了这台手机 —— 而且不报错，就是安安静静地不出现。
     */
    private void acquireLock() {
        try {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("droidtrans-peer");
                lock.setReferenceCounted(true);
                lock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "multicast lock: " + e.getMessage());
        }
    }

    private void releaseLock() {
        if (lock != null && lock.isHeld()) {
            lock.release();
        }
        lock = null;
    }

    private void advertise(String deviceName) {
        if (nsd == null) {
            return;
        }
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(deviceName);
        info.setServiceType(BonjourBrowser.SERVICE_TYPE);
        info.setPort(PORT);

        registration = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo s, int err) {
                Log.w(TAG, "advertise failed " + err);
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo s, int err) {
                Log.w(TAG, "unadvertise failed " + err);
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo s) {
                Log.d(TAG, "advertising as " + s.getServiceName());
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo s) {
                Log.d(TAG, "stopped advertising");
            }
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (Exception e) {
            Log.w(TAG, "registerService: " + e.getMessage());
        }
    }

    private void unadvertise() {
        if (nsd != null && registration != null) {
            try {
                nsd.unregisterService(registration);
            } catch (Exception ignored) {
            }
        }
        registration = null;
    }

    // ---------------------------------------------------------------- 收连接

    private void acceptLoop() {
        while (running) {
            try {
                Socket c = socket.accept();
                ExecutorService p = pool;
                if (p != null) {
                    p.execute(() -> serve(c));
                }
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "accept: " + e.getMessage());
                }
                return;
            }
        }
    }

    /** 一条连接处理一个请求就关（Connection: close），省掉一整套 keep-alive 状态机。 */
    private void serve(Socket c) {
        try {
            c.setSoTimeout(60_000);
            InputStream in = c.getInputStream();
            OutputStream out = new BufferedOutputStream(c.getOutputStream());

            Head head = Head.read(in);
            if (head == null) {
                return;
            }
            route(head, in, out);
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "serve: " + e.getMessage());
        } finally {
            closeQuietly(c);
        }
    }

    private void route(Head h, InputStream in, OutputStream out) throws IOException {
        // 不在开放名单里的口先验令牌
        if (!Head.OPEN_PATHS.contains(h.path)) {
            String tok = h.header("X-DT-Token");
            if (tok == null || !tokens.contains(tok)) {
                send(out, 403, json("success", false, "error", "需要先配对",
                        "pairing_required", true));
                return;
            }
        }

        if ("GET".equals(h.method) && "/api/health".equals(h.path)) {
            send(out, 200, json("ok", true, "app", "droidtrans", "engine", "android",
                    "name", deviceName(), "version", version()));

        } else if ("GET".equals(h.method)
                && ("/api/wifi/info".equals(h.path) || "/api/fast/caps".equals(h.path))) {
            send(out, 200, info());

        } else if ("POST".equals(h.method) && "/api/pair".equals(h.path)) {
            byte[] body = h.readBody(in);
            String code = optString(body, "code").trim();
            String who = optString(body, "device_name");

            String tok;
            if (code.isEmpty()) {
                // 不带码 = 敲门。这条连接就挂在这儿，等这台的主人点「同意」
                tok = awaitApproval(who.isEmpty() ? "有人" : who);
            } else if (code.equals(pairingCode)) {
                tok = issueToken();
            } else {
                tok = null;
            }

            if (tok != null) {
                send(out, 200, json("success", true, "token", tok));
            } else {
                send(out, 403, json("success", false, "error", "配对码不对"));
            }

        } else if ("GET".equals(h.method) && "/api/outbox".equals(h.path)) {
            // 这侧只收不发，清单永远是空的。
            // 发送方拿这个口验令牌还好不好用，所以它必须存在且回 200。
            send(out, 200, json("success", true, "items", new JSONArray(),
                    "count", 0, "total_size", 0));

        } else if ("PUT".equals(h.method) && "/api/fast/put".equals(h.path)) {
            receiveFile(h, in, out);

        } else if ("POST".equals(h.method) && "/api/inbox/text".equals(h.path)) {
            saveText(optString(h.readBody(in), "text"));
            send(out, 200, json("success", true));

        } else {
            send(out, 404, json("success", false, "error", "not found"));
        }
    }

    private JSONObject info() {
        String ip = localIp();
        JSONArray ips = new JSONArray();
        if (ip != null) {
            ips.put(ip);
        }
        JSONArray prefer = new JSONArray();
        // 只实现了 HTTP PUT。ATF2 裸流和 FTP 是桌面端才有的加速，
        // 少报一个通道，发送方会自己降到 PUT，不会失败。
        prefer.put("http_put");

        return json("success", true,
                "name", deviceName(),
                "ip", ip == null ? "" : ip,
                "ips", ips,
                "port", PORT,
                "pairing_required", true,
                // 告诉对面「点一下同意就行，别让人输码」。
                // 桌面端不发这个字段，所以老流程完全不受影响。
                "pairing_mode", "approve",
                "prefer", prefer,
                "outbox_count", 0,
                "outbox_size", 0,
                "on_hotspot", false,
                "device_count", 0,
                "connected_devices", new JSONArray());
    }

    // ----------------------------------------------------------------- 收文件

    private void receiveFile(Head h, InputStream in, OutputStream out) throws IOException {
        String raw = h.header("X-Relative-Path");
        if (raw == null) {
            raw = h.header("X-Filename");
        }
        if (raw == null) {
            raw = "file";
        }
        String name = sanitize(decode(raw));
        long total = parseLong(h.header("X-File-Size"), h.contentLength());

        File dest = unique(inboxDir(), name);

        long written = 0;
        boolean ok;
        try (FileOutputStream fos = new FileOutputStream(dest)) {
            // 头读完时缓冲里往往已经躺着 body 的开头，先把它写掉
            byte[] pre = h.leftover();
            if (pre.length > 0) {
                int take = (int) Math.min(pre.length, total);
                fos.write(pre, 0, take);
                written += take;
            }
            byte[] buf = new byte[64 * 1024];
            while (written < total) {
                int want = (int) Math.min(buf.length, total - written);
                int n = in.read(buf, 0, want);
                if (n < 0) {
                    break;
                }
                fos.write(buf, 0, n);
                written += n;
            }
            ok = written == total;
        }

        if (ok) {
            final File f = dest;
            final long size = written;
            main.post(() -> {
                if (listener != null) {
                    listener.onFileReceived(f.getName(), size, f);
                }
            });
            send(out, 200, json("success", true, "skipped", false, "size", written));
        } else {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            send(out, 400, json("success", false, "error", "文件没收全"));
        }
    }

    private void saveText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        final File dest = unique(inboxDir(), "text-" + (System.currentTimeMillis() / 1000) + ".txt");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream fos = new FileOutputStream(dest)) {
            fos.write(bytes);
        } catch (IOException e) {
            Log.w(TAG, "saveText: " + e.getMessage());
            return;
        }
        main.post(() -> {
            if (listener != null) {
                listener.onFileReceived(dest.getName(), bytes.length, dest);
            }
        });
    }

    /** 收到的东西放这儿。App 专属外部目录，不需要存储权限。 */
    public File inboxDir() {
        File dir = new File(app.getExternalFilesDir(null), "Inbox");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    // ------------------------------------------------------------------ 小工具

    /** key, value, key, value… 拼一个 JSON。七个口的应答都很小，不值得建模型类。 */
    private static JSONObject json(Object... kv) {
        JSONObject j = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                j.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        } catch (Exception ignored) {
        }
        return j;
    }

    private static String optString(byte[] body, String key) {
        try {
            return new JSONObject(new String(body, StandardCharsets.UTF_8)).optString(key, "");
        } catch (Exception e) {
            return "";
        }
    }

    private void send(OutputStream out, int status, JSONObject body) throws IOException {
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
    }

    /**
     * 挂起当前这条连接，等界面上的人点头。
     *
     * <p>同时只招呼一个人：正等着一个的时候又来一个，直接回绝 ——
     * 两个弹窗叠在一起，用户根本分不清自己在给谁开门。
     *
     * <p>45 秒没人理就当拒绝。挂着不放的话对面一直转圈转到超时，
     * 而它得到的信息是「连不上」，不是「没人同意」。
     */
    private String awaitApproval(String who) {
        synchronized (this) {
            if (knockLatch != null) {
                return null;
            }
            knockLatch = new java.util.concurrent.CountDownLatch(1);
            knockToken = null;
        }
        main.post(() -> {
            if (listener != null) {
                listener.onKnock(who);
            }
        });
        try {
            knockLatch.await(45, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        String tok;
        synchronized (this) {
            tok = knockToken;
            knockLatch = null;
            knockToken = null;
        }
        return tok;
    }

    /** 界面上点了「同意」。 */
    public void approve() {
        synchronized (this) {
            if (knockLatch == null) {
                return;
            }
            knockToken = issueToken();
            knockLatch.countDown();
        }
    }

    /** 界面上点了「拒绝」，或者这一屏关掉了。 */
    public void deny() {
        synchronized (this) {
            if (knockLatch == null) {
                return;
            }
            knockToken = null;
            knockLatch.countDown();
        }
    }

    private String issueToken() {
        String tok = UUID.randomUUID().toString().replace("-", "");
        tokens.add(tok);
        return tok;
    }

    private String reason(int s) {
        switch (s) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            default:  return "Internal Server Error";
        }
    }

    private String deviceName() {
        String n = Build.MODEL;
        return n == null || n.isEmpty() ? "Android" : n;
    }

    private String version() {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** 本机在当前 Wi-Fi 下的 IPv4。手输地址那条路要用它。 */
    public String localIp() {
        try {
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
                 e.hasMoreElements(); ) {
                NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String n = ni.getName();
                // 蜂窝网（rmnet/pdp）上对面连不过来，只认 Wi-Fi 和热点
                if (n == null || (!n.startsWith("wlan") && !n.startsWith("ap"))) {
                    continue;
                }
                for (Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements(); ) {
                    InetAddress addr = a.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * 文件名不能带路径分隔符 —— 对面传一个 "../../x" 过来，
     * 不拦的话就写到 Inbox 外面去了。
     */
    private static String sanitize(String s) {
        String flat = s.replace('\\', '/');
        int i = flat.lastIndexOf('/');
        if (i >= 0) {
            flat = flat.substring(i + 1);
        }
        flat = flat.replace(":", "").trim();
        if (flat.isEmpty() || ".".equals(flat) || "..".equals(flat)) {
            return "file";
        }
        return flat;
    }

    /** 同名不覆盖，接一个 (2)。悄悄盖掉用户上一次收到的文件是不可接受的。 */
    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) {
            return f;
        }
        String stem = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            stem = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 2; ; i++) {
            File c = new File(dir, stem + " (" + i + ")" + ext);
            if (!c.exists()) {
                return c;
            }
        }
    }

    private static long parseLong(String s, long fallback) {
        try {
            return s == null ? fallback : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ 请求头

    /** 一个请求的起始行加头部。body 不在这里，按 Content-Length 另外读。 */
    static final class Head {

        /** 不需要配对也能问的路径 —— 与 Go 端 openPath() 一一对应。 */
        static final List<String> OPEN_PATHS = Arrays.asList(
                "/api/health", "/api/wifi/info", "/api/fast/caps", "/api/pair");

        String method = "";
        String path = "/";
        final Map<String, String> fields = new HashMap<>();
        private byte[] rest = new byte[0];

        String header(String name) {
            return fields.get(name.toLowerCase(Locale.US));
        }

        long contentLength() {
            return parseLong(header("Content-Length"), 0);
        }

        byte[] leftover() {
            return rest;
        }

        byte[] readBody(InputStream in) throws IOException {
            int need = (int) contentLength();
            if (need <= 0) {
                return new byte[0];
            }
            byte[] out = new byte[need];
            int have = Math.min(rest.length, need);
            System.arraycopy(rest, 0, out, 0, have);
            while (have < need) {
                int n = in.read(out, have, need - have);
                if (n < 0) {
                    break;
                }
                have += n;
            }
            return out;
        }

        /**
         * 一个字节一个字节读到头部结束为止。
         *
         * <p>慢，但头部只有几百字节，而且这样绝不会多读走 body 的第一个字节 ——
         * 多读了就得自己维护一个回退缓冲，那才是真正难查的错。
         * 超过 64KB 还没读完就当它不是正经请求。
         */
        static Head read(InputStream in) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int match = 0;
            while (buf.size() < 64 * 1024) {
                int b = in.read();
                if (b < 0) {
                    return null;
                }
                buf.write(b);
                // 滚动匹配 CR LF CR LF
                if ((match == 0 || match == 2) && b == '\r') {
                    match++;
                } else if ((match == 1 || match == 3) && b == '\n') {
                    match++;
                } else {
                    match = b == '\r' ? 1 : 0;
                }
                if (match == 4) {
                    return parse(buf.toByteArray());
                }
            }
            return null;
        }

        private static Head parse(byte[] raw) {
            String text = new String(raw, StandardCharsets.UTF_8);
            String[] lines = text.split("\r\n");
            Head h = new Head();
            if (lines.length > 0) {
                String[] parts = lines[0].split(" ");
                if (parts.length > 0) {
                    h.method = parts[0];
                }
                if (parts.length > 1) {
                    // 查询串这边一个口都用不上，切掉，路由只看路径
                    int q = parts[1].indexOf('?');
                    h.path = q >= 0 ? parts[1].substring(0, q) : parts[1];
                }
            }
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c <= 0) {
                    continue;
                }
                h.fields.put(lines[i].substring(0, c).trim().toLowerCase(Locale.US),
                        lines[i].substring(c + 1).trim());
            }
            return h;
        }
    }
}
