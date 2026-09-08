package com.mk.androidtransfer.network;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 没有路由器时，接收端自己拉一个直连热点。
 *
 * <p>两条实现，按这个顺序试 —— 顺序是有理由的，别倒过来：
 *
 * <ol>
 *   <li><b>Wi-Fi Direct 自建组</b>（{@link DirectGroup}，Android 10 起）。
 *       频段能请求 5GHz，名字和密码我们自己定，而且组主可以在 P2P 上广播服务，
 *       对面<b>不用扫码</b>就能发现并连过来。</li>
 *   <li><b>LocalOnlyHotspot</b>（Android 8 起）。名字和密码由系统随机生成、读得到，
 *       所以二维码那条路照常成立；但<b>频段由系统定</b>，不少机型直接给 2.4GHz，
 *       实测 5~8 MB/s —— 那还不如走路由器。所以它只是兜底，不是首选。</li>
 * </ol>
 *
 * <p>两条出来的都是普通 AP，所以 iPhone 都能加入 —— 它不需要懂 Wi-Fi Direct，
 * 只要从二维码里拿到名字和密码。iOS 既没有扫描周围 Wi-Fi 的能力，也不懂 P2P，
 * 所以对 iPhone 来说扫码是唯一的路。
 *
 * <p>局限，界面上要如实说：开着的时候这台手机上不了网（Wi-Fi 被切去当热点了），
 * 部分厂商 ROM 两条都会拒绝。
 */
public final class DirectHotspot {

    private static final String TAG = "DirectHotspot";

    public interface Callback {
        /** 热点起来了。ssid/password 直接进二维码。 */
        void onStarted(String ssid, String password);

        /** 起不来。msg 是给用户看的一句话，不是错误码。 */
        void onFailed(String msg);

        /** 系统把它关了（用户手动关、或者切了网络）。 */
        void onStopped();
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private DirectGroup group;

    public DirectHotspot(Context ctx) {
        app = ctx.getApplicationContext();
    }

    public boolean isRunning() {
        return reservation != null || (group != null && group.isRunning());
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    /** 这次的热点是不是走的 Wi-Fi Direct —— 走它才有「对面免扫码」这条路。 */
    public boolean isDirectGroup() {
        return group != null && group.isRunning();
    }

    /**
     * @param deviceName 广播出去的设备名，对面看到的就是它
     * @param port       接收端实际监听的端口，写进 P2P 服务的 TXT 记录
     */
    public void start(String deviceName, int port, Callback cb) {
        if (!isSupported()) {
            cb.onFailed("这台手机的系统版本太低，开不了直连热点");
            return;
        }
        if (isRunning()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            final DirectGroup g = new DirectGroup(app);
            group = g;
            g.start(deviceName, port, new DirectGroup.Callback() {
                @Override
                public void onStarted(String ssid, String password) {
                    cb.onStarted(ssid, password);
                }

                @Override
                public void onFailed(String msg) {
                    // 这台不给建 P2P 组，还剩 LocalOnlyHotspot
                    group = null;
                    g.stop();
                    startLocalOnly(msg, cb);
                }
            });
            return;
        }
        startLocalOnly("", cb);
    }

    @SuppressLint("MissingPermission")
    private void startLocalOnly(String why, Callback cb) {
        WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            cb.onFailed(why.isEmpty() ? "拿不到 Wi-Fi 服务" : why);
            return;
        }
        try {
            wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
                    reservation = r;
                    String ssid = ssidOf(r);
                    String pass = passwordOf(r);
                    if (ssid == null || ssid.isEmpty()) {
                        // 拿不到名字，二维码就没法带入网信息，开着它没意义
                        stop();
                        main.post(() -> cb.onFailed(why.isEmpty() ? "系统没给出热点名称" : why));
                        return;
                    }
                    main.post(() -> cb.onStarted(ssid, pass == null ? "" : pass));
                }

                @Override
                public void onFailed(int reason) {
                    Log.w(TAG, "local only hotspot failed " + reason);
                    reservation = null;
                    // 两条都失败时，两句都要说：只报第一条，用户会以为
                    // 还有别的办法没试过；只报第二条，又丢掉了更准的那句
                    // （「这台手机不支持 Wi-Fi 直连」）。
                    final String msg = why.isEmpty()
                            ? explain(reason)
                            : why + "；" + explain(reason);
                    main.post(() -> cb.onFailed(msg));
                }

                @Override
                public void onStopped() {
                    reservation = null;
                    main.post(cb::onStopped);
                }
            }, main);
        } catch (SecurityException e) {
            cb.onFailed("要先允许「位置/附近的设备」权限，系统才肯开热点");
        } catch (IllegalStateException e) {
            cb.onFailed(why.isEmpty() ? "这台手机现在开不了直连热点：" + e.getMessage() : why);
        }
    }

    public void stop() {
        if (reservation != null) {
            try {
                reservation.close();
            } catch (Exception ignored) {
            }
            reservation = null;
        }
        if (group != null) {
            group.stop();
            group = null;
        }
    }

    @SuppressWarnings("deprecation")
    private static String ssidOf(WifiManager.LocalOnlyHotspotReservation r) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && r.getSoftApConfiguration() != null) {
            return r.getSoftApConfiguration().getSsid();
        }
        WifiConfiguration c = r.getWifiConfiguration();
        return c == null ? null : unquote(c.SSID);
    }

    @SuppressWarnings("deprecation")
    private static String passwordOf(WifiManager.LocalOnlyHotspotReservation r) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && r.getSoftApConfiguration() != null) {
            return r.getSoftApConfiguration().getPassphrase();
        }
        WifiConfiguration c = r.getWifiConfiguration();
        return c == null ? null : unquote(c.preSharedKey);
    }

    /** 老 API 给的 SSID/密码带着引号，原样塞进二维码会连不上。 */
    private static String unquote(String s) {
        if (s == null) {
            return null;
        }
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String explain(int reason) {
        switch (reason) {
            case WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL:
                return "附近信道太挤，系统没能开出热点";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED:
                return "这台手机不允许共享网络（可能是公司策略或运营商限制）";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE:
                return "当前 Wi-Fi 模式下开不了直连热点，先关掉已开的个人热点再试";
            default:
                return "系统拒绝了直连热点请求";
        }
    }
}
