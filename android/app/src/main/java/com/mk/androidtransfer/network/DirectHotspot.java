package com.mk.androidtransfer.network;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 没有路由器时，接收端自己拉一个直连热点。
 *
 * <p>用的是系统的 LocalOnlyHotspot：它专为「两台设备就地互连」而设，
 * 不需要用户去设置里开个人热点，也不碰运营商的共享开关；SSID 和密码由系统随机生成，
 * 直接塞进二维码，对面扫一下就能入网 —— 这才是「不用管网络」该有的样子。
 *
 * <p>系统拒绝时（部分厂商 ROM 直接不给 LocalOnlyHotspot）退到 <b>Wi-Fi Direct</b>：
 * 建一个 P2P 组，自己当组主。组主本身就是一个普通的 AP，名字（DIRECT-xx-…）和密码
 * 都读得到，一样能进二维码 —— 所以连 iPhone 也能加入，它并不需要懂 Wi-Fi Direct。
 * 这条路要 Android 10（API 29）以上才拿得到密码，拿不到就没法写进码里，也就没有意义。
 *
 * <p>局限，界面上要如实说：
 * <ul>
 *   <li>需要 Android 8（API 26）以上；</li>
 *   <li>开着的时候这台手机上不了网 —— 它的 Wi-Fi 被系统切去当热点了；</li>
 *   <li>部分厂商 ROM 会直接拒绝（onFailed），这时只能退回「让对方开个人热点」。</li>
 * </ul>
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

    /** Wi-Fi Direct 兜底用的通道；建了组就非空，关的时候要拿它去 removeGroup。 */
    private WifiP2pManager p2p;
    private WifiP2pManager.Channel p2pChannel;

    public DirectHotspot(Context ctx) {
        app = ctx.getApplicationContext();
    }

    public boolean isRunning() {
        return reservation != null || p2pChannel != null;
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    @SuppressLint("MissingPermission")
    public void start(Callback cb) {
        if (!isSupported()) {
            cb.onFailed("这台手机的系统版本太低，开不了直连热点");
            return;
        }
        if (reservation != null) {
            return;
        }
        WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            cb.onFailed("拿不到 Wi-Fi 服务");
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
                        // 拿不到名字，二维码就没法带入网信息，这时候开着它没意义
                        stop();
                        main.post(() -> cb.onFailed("系统没给出热点名称"));
                        return;
                    }
                    main.post(() -> cb.onStarted(ssid, pass == null ? "" : pass));
                }

                @Override
                public void onFailed(int reason) {
                    Log.w(TAG, "local only hotspot failed " + reason);
                    reservation = null;
                    // 这台 ROM 不给开热点，还剩 Wi-Fi Direct 一条路
                    main.post(() -> startWifiDirect(explain(reason), cb));
                }

                @Override
                public void onStopped() {
                    reservation = null;
                    main.post(cb::onStopped);
                }
            }, main);
        } catch (SecurityException e) {
            // 没给定位/附近设备权限
            cb.onFailed("要先允许「位置/附近的设备」权限，系统才肯开热点");
        } catch (IllegalStateException e) {
            startWifiDirect("这台手机现在开不了直连热点：" + e.getMessage(), cb);
        }
    }

    // -------------------------------------------------------- Wi-Fi Direct 兜底

    /**
     * 自己当 Wi-Fi Direct 的组主。
     *
     * <p>组主就是一个普通 AP，名字和密码都读得到 —— 写进二维码之后，
     * 对面（安卓或 iPhone）当成一个普通 Wi-Fi 连进来就行，
     * 它完全不需要知道这是 Wi-Fi Direct。
     *
     * @param why 上一条路失败的原因；这条也不成时，报给用户的是这句话。
     */
    @SuppressLint("MissingPermission")
    private void startWifiDirect(String why, Callback cb) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // 拿不到组密码，码里就没法带入网信息，这条路等于没有
            cb.onFailed(why);
            return;
        }
        WifiP2pManager m = (WifiP2pManager) app.getSystemService(Context.WIFI_P2P_SERVICE);
        if (m == null) {
            cb.onFailed(why);
            return;
        }
        final WifiP2pManager.Channel ch = m.initialize(app, Looper.getMainLooper(), null);
        if (ch == null) {
            cb.onFailed(why);
            return;
        }
        try {
            m.createGroup(ch, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    p2p = m;
                    p2pChannel = ch;
                    m.requestGroupInfo(ch, group -> {
                        String ssid = group == null ? null : group.getNetworkName();
                        String pass = group == null ? null : group.getPassphrase();
                        if (ssid == null || ssid.isEmpty()) {
                            stop();
                            cb.onFailed(why);
                            return;
                        }
                        cb.onStarted(ssid, pass == null ? "" : pass);
                    });
                }

                @Override
                public void onFailure(int reason) {
                    Log.w(TAG, "createGroup failed " + reason);
                    cb.onFailed(why);
                }
            });
        } catch (SecurityException e) {
            cb.onFailed("要先允许「位置/附近的设备」权限，系统才肯开直连");
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
        if (p2p != null && p2pChannel != null) {
            try {
                // 不删组的话，这台手机会一直挂着一个 DIRECT-xx 热点，
                // 用户回到主屏也上不了网，而界面上已经没有任何地方提到它了
                p2p.removeGroup(p2pChannel, null);
            } catch (Exception ignored) {
            }
        }
        p2p = null;
        p2pChannel = null;
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
