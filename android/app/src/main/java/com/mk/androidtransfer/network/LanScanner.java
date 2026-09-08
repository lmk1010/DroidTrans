package com.mk.androidtransfer.network;

import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 扫一遍本机所在的网段，把还在监听的卓传找出来。
 *
 * <p>为什么必须有这条路 —— 真机上实测出来的两种情况，组播都救不了：
 *
 * <ul>
 *   <li><b>mDNS 缓存是脏的。</b>对面换过网络之后，手机这边的 NSD 还留着上一段的
 *       地址，而且只给那一个。实测：Mac 已经在 192.168.10.15，手机拿到的却是
 *       192.168.4.18 —— 那个地址在 Mac 上早就不存在了。</li>
 *   <li><b>路由器拦组播。</b>不少家用和几乎所有企业 AP 都拦，那时雷达永远是空的，
 *       而且不会报任何错。</li>
 * </ul>
 *
 * <p>扫的是 /24 里的 254 个地址，只敲两个端口（电脑 9500、手机 9600），
 * 连接超时 300ms，并发 48 —— 一轮两三秒。通了之后还要问一句 /api/health
 * 确认那头真是卓传，别把随便一个开着 9500 的东西放进雷达。
 */
public final class LanScanner {

    private static final String TAG = "LanScanner";
    private static final int CONNECT_MS = 300;
    private static final int THREADS = 48;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build();

    public interface Listener {
        void onFound(String name, String ip, int port, String engine);
    }

    private ExecutorService pool;
    private volatile boolean running;

    /**
     * @param selfIp 本机在 Wi-Fi 上的地址，扫它所在的 /24
     */
    public void start(String selfIp, int[] ports, Listener listener) {
        if (running || selfIp == null || selfIp.isEmpty()) {
            return;
        }
        int dot = selfIp.lastIndexOf('.');
        if (dot <= 0) {
            return;
        }
        final String prefix = selfIp.substring(0, dot + 1);
        final String self = selfIp;
        running = true;
        pool = Executors.newFixedThreadPool(THREADS);
        for (int i = 1; i <= 254; i++) {
            final String ip = prefix + i;
            if (ip.equals(self)) {
                continue;   // 自己不用扫
            }
            pool.execute(() -> {
                for (int port : ports) {
                    if (!running) {
                        return;
                    }
                    if (!knock(ip, port)) {
                        continue;
                    }
                    String[] who = identify(ip, port);
                    if (who != null && running) {
                        listener.onFound(who[0], ip, port, who[1]);
                        return;
                    }
                }
            });
        }
    }

    public void stop() {
        running = false;
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    /** 端口通不通。绝大多数地址上没人应，所以这一步要判得快。 */
    private static boolean knock(String ip, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), CONNECT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 那头是不是卓传。返回 {名字, engine}，不是就返回 null。 */
    private static String[] identify(String ip, int port) {
        Request req = new Request.Builder()
                .url("http://" + ip + ":" + port + "/api/health")
                .get()
                .build();
        try (Response res = CLIENT.newCall(req).execute()) {
            ResponseBody body = res.body();
            if (!res.isSuccessful() || body == null) {
                return null;
            }
            JSONObject j = new JSONObject(body.string());
            if (!"droidtrans".equals(j.optString("app"))) {
                return null;
            }
            String name = j.optString("name", ip);
            return new String[]{name.isEmpty() ? ip : name, j.optString("engine", "")};
        } catch (Exception e) {
            Log.w(TAG, "identify " + ip + ": " + e.getMessage());
            return null;
        }
    }
}
