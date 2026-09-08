package com.mk.androidtransfer.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import androidx.test.rule.GrantPermissionRule;

import org.junit.After;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 手机互传真的跑起来的那一份验证：起一个真的 PeerServer，用真的客户端去打它。
 *
 * <p>纯 JVM 单测覆盖不到这里 —— PeerServer 要 Context、要 NSD、要真的 socket，
 * 而最容易错的恰恰是「不带码的请求会不会挂住」「通道选对没有」这种
 * 只有真跑一遍才暴露的地方。
 *
 * <p>{@link #sendsFileToAnExternalPeer()} 是跨平台那一条：给它一个外部接收端
 * （比如 iOS 模拟器上跑起来的 PeerServer）就会真的连过去传一个文件。
 * 不给参数时自动跳过，平时跑整套用例不受影响：
 *
 * <pre>
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.peerHost=10.0.2.2 \
 *     -Pandroid.testInstrumentationRunnerArguments.peerPort=9600
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
public class PeerInteropTest {

    /**
     * 相册权限。真机上 photo=1 那一路要从 MediaStore 读一张照片，
     * 没有它读回来的是空的 —— 而那看起来会像「传输失败」。
     */
    @Rule
    public GrantPermissionRule photos = android.os.Build.VERSION.SDK_INT >= 33
            ? GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES)
            : GrantPermissionRule.grant(android.Manifest.permission.READ_EXTERNAL_STORAGE);

    private PeerServer server;

    private Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    /**
     * 敲门要等一个人看到弹窗、拿起手机、点一下。**8 秒根本不够** ——
     * 这里特意等到 10 秒之后才「点同意」，用的是真正的客户端 Pairing.knock。
     */
    @Test
    public void knockSurvivesASlowHuman() throws Exception {
        int port = startServer();

        final AtomicReference<Boolean> ok = new AtomicReference<>(null);
        Thread client = new Thread(() ->
                ok.set(Pairing.knock(ctx(), "127.0.0.1:" + port)));
        client.start();

        // 等对面真的把这条请求挂住了，再慢悠悠地点同意
        assertTrue("等不到敲门", awaitKnock());
        Thread.sleep(10_000);
        server.approve();

        client.join(20_000);
        assertEquals(Boolean.TRUE, ok.get());
    }

    /**
     * 选通道。手机接收端只有 /api/fast/put，选到 multipart 就是 404，
     * 而用户看到的是「传输失败」。
     */
    @Test
    public void picksHttpPutForAPhonePeer() throws Exception {
        int port = startServer();
        ProtocolSelector.Choice c = ProtocolSelector.select("http://127.0.0.1:" + port);
        assertEquals(TransferProtocol.HTTP_PUT, c.protocol);
        assertEquals(port, c.httpPort);
    }

    /** 收文件：断点续传那条路也走一遍，只补剩下的半截。 */
    @Test
    public void receivesFileAndResumesFromOffset() throws Exception {
        int port = startServer();
        String base = "http://127.0.0.1:" + port;
        String token = pairWithCode(base);

        byte[] whole = new byte[64 * 1024];
        for (int i = 0; i < whole.length; i++) {
            whole[i] = (byte) (i * 31);
        }
        int half = whole.length / 2;

        OkHttpClient http = new OkHttpClient();
        // 先发一半就断：服务端应当留着分片，而不是删掉让人从头再来
        assertEquals(400, put(http, base, token, "half.bin", whole.length, 0,
                java.util.Arrays.copyOfRange(whole, 0, half)));

        Request ask = new Request.Builder()
                .url(base + "/api/fast/offset?name=half.bin&size=" + whole.length)
                .header(Pairing.header(), token)
                .get()
                .build();
        String body;
        try (Response r = http.newCall(ask).execute()) {
            body = r.body().string();
        }
        assertEquals(half, new org.json.JSONObject(body).optInt("offset"));

        // 只补剩下的那段
        assertEquals(200, put(http, base, token, "half.bin", whole.length, half,
                java.util.Arrays.copyOfRange(whole, half, whole.length)));

        File got = new File(server.inboxDir(), "half.bin");
        assertTrue("文件没落盘", got.isFile());
        assertEquals(whole.length, got.length());
    }

    /**
     * 跨平台：连一台外部接收端（iOS 模拟器上的 PeerServer），敲门 → 传文件。
     * 没给 peerHost 就跳过。
     */
    @Test
    public void sendsFileToAnExternalPeer() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String host = args.getString("peerHost");
        Assume.assumeNotNull(host);
        int port = Integer.parseInt(args.getString("peerPort", "9600"));
        String base = "http://" + host + ":" + port;

        // 对面报的是「点一下同意」，不是「抄六位码」
        assertEquals("approve", Pairing.pairingMode(base));
        assertTrue("对面不认识敲门这套", Pairing.knock(ctx(), base));

        String token = Pairing.token(ctx(), Pairing.normalize(base));
        assertNotNull(token);

        // 通道也得按对面报的 prefer 选，不能退到它根本没有的 multipart
        assertEquals(TransferProtocol.HTTP_PUT, ProtocolSelector.select(base).protocol);

        // 默认发一小段字节；给了 photo=1 就从相册里真拿一张照片发过去，
        // 那才是用户实际会做的事（也顺带把大一点的 body 走一遍）
        byte[] payload;
        String name;
        if (InstrumentationRegistry.getArguments().getString("photo") != null) {
            android.util.Pair<String, byte[]> pic = firstPhoto();
            assertNotNull("这台手机的相册里没找到照片", pic);
            name = pic.first;
            payload = pic.second;
        } else {
            name = "from-android.txt";
            payload = "从安卓传给 iPhone 的一段字节".getBytes("UTF-8");
        }
        long started = System.currentTimeMillis();
        assertEquals(200, put(new OkHttpClient(), base, token, name,
                payload.length, 0, payload));
        long ms = Math.max(1, System.currentTimeMillis() - started);
        android.util.Log.i("PeerInteropTest", "传了 " + name + "：" + payload.length
                + " 字节，" + ms + " ms，约 "
                + (payload.length / 1024.0 / 1024.0) / (ms / 1000.0) + " MB/s");
    }

    /**
     * 反过来：这台当接收端，等外面的发送端（iOS 模拟器）连过来传一个文件。
     * 敲门自动点「同意」。没给 serve 参数就跳过。
     *
     * <pre>
     *   adb forward tcp:9600 tcp:9600
     *   ./gradlew connectedDebugAndroidTest \
     *     -Pandroid.testInstrumentationRunnerArguments.serve=1
     * </pre>
     */
    @Test
    public void servesAnExternalSender() throws Exception {
        Assume.assumeNotNull(InstrumentationRegistry.getArguments().getString("serve"));

        final CountDownLatch up = new CountDownLatch(1);
        final CountDownLatch got = new CountDownLatch(1);
        final AtomicReference<Long> size = new AtomicReference<>(0L);
        server = new PeerServer(ctx());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                server.start("安卓模拟器", new PeerServer.Listener() {
                    @Override
                    public void onStateChanged(boolean running, String error) {
                        if (running) {
                            up.countDown();
                        }
                    }

                    @Override
                    public void onFileProgress(String name, long received, long total) {
                    }

                    @Override
                    public void onFileReceived(String name, long bytes, File file) {
                        size.set(bytes);
                        got.countDown();
                    }

                    @Override
                    public void onPeerConnected(String name) {
                    }

                    @Override
                    public void onKnock(String name) {
                        server.approve();
                    }
                }));
        assertTrue("PeerServer 没起来", up.await(10, TimeUnit.SECONDS));
        assertEquals("对面按固定端口连过来，换了端口这条对跑就对不上", 9600,
                server.getBoundPort());

        assertTrue("没等到对面把文件传过来", got.await(120, TimeUnit.SECONDS));
        assertTrue("收到的文件是空的", size.get() > 0);
    }

    /**
     * 真网络上的发现：在真的 Wi-Fi 里用 NSD 把对面那台接收端找出来。
     *
     * <p>这一条在模拟器上做不到 —— emulator 的组播出不了它那层 NAT，
     * 所以「解析要排队、走了要移除」那些改动，只有在真机上才验得到。
     *
     * <pre>
     *   -Pandroid.testInstrumentationRunnerArguments.expectPeer=192.168.10.15
     * </pre>
     */
    @Test
    public void findsPeerOnTheRealNetwork() throws Exception {
        String expect = InstrumentationRegistry.getArguments().getString("expectPeer");
        Assume.assumeNotNull(expect);

        final CountDownLatch found = new CountDownLatch(1);
        final AtomicReference<String> where = new AtomicReference<>();
        BonjourBrowser browser = new BonjourBrowser(ctx());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                browser.start(new BonjourBrowser.Listener() {
                    @Override
                    public void onFound(String name, java.util.List<String> hosts, int port) {
                        if (hosts.contains(expect)) {
                            where.set(expect + ":" + port);
                            found.countDown();
                        }
                    }

                    @Override
                    public void onLost(String name) {
                    }
                }));
        try {
            assertTrue("30 秒之内没在局域网里发现 " + expect
                            + "（组播被路由器拦掉、或者解析那一路又把它丢了）",
                    found.await(30, TimeUnit.SECONDS));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(browser::stop);
        }
        assertNotNull(where.get());
    }

    // ------------------------------------------------------------------ 小工具

    /** 相册里的第一张照片。只读，不动用户的任何东西。 */
    private android.util.Pair<String, byte[]> firstPhoto() throws Exception {
        String[] cols = {android.provider.MediaStore.Images.Media._ID,
                android.provider.MediaStore.Images.Media.DISPLAY_NAME};
        try (android.database.Cursor c = ctx().getContentResolver().query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                cols, null, null,
                android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC LIMIT 1")) {
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            long id = c.getLong(0);
            String name = c.getString(1);
            android.net.Uri uri = android.content.ContentUris.withAppendedId(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = ctx().getContentResolver().openInputStream(uri)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while (in != null && (n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            return new android.util.Pair<>(name == null ? "photo.jpg" : name,
                    out.toByteArray());
        }
    }

    private int startServer() throws Exception {
        final CountDownLatch up = new CountDownLatch(1);
        server = new PeerServer(ctx());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                server.start("测试机", new PeerServer.Listener() {
                    @Override
                    public void onStateChanged(boolean running, String error) {
                        if (running) {
                            up.countDown();
                        }
                    }

                    @Override
                    public void onFileProgress(String name, long received, long total) {
                    }

                    @Override
                    public void onFileReceived(String name, long size, File file) {
                    }

                    @Override
                    public void onPeerConnected(String name) {
                    }

                    @Override
                    public void onKnock(String name) {
                        // 用例自己决定什么时候点同意
                    }
                }));
        assertTrue("PeerServer 没起来", up.await(10, TimeUnit.SECONDS));
        return server.getBoundPort();
    }

    private boolean awaitKnock() throws Exception {
        for (int i = 0; i < 100; i++) {
            if (server.hasPendingKnock()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private String pairWithCode(String base) {
        assertTrue(Pairing.pair(ctx(), base, server.getPairingCode()));
        return Pairing.token(ctx(), Pairing.normalize(base));
    }

    private int put(OkHttpClient http, String base, String token, String name,
                    long total, long offset, byte[] chunk) throws Exception {
        Request req = new Request.Builder()
                .url(base + "/api/fast/put")
                .header(Pairing.header(), token)
                .header("X-Filename", name)
                .header("X-Relative-Path", name)
                .header("X-File-Size", String.valueOf(total))
                .header("X-Start-Offset", String.valueOf(offset))
                .put(RequestBody.create(chunk, MediaType.parse("application/octet-stream")))
                .build();
        try (Response r = http.newCall(req).execute()) {
            return r.code();
        }
    }
}
