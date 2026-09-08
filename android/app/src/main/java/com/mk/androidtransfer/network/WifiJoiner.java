package com.mk.androidtransfer.network;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 扫到的码里带着 SSID 和密码，就替用户把网连上。
 *
 * <p>这是「扫一下就能传」和「先退出 App、去设置里找热点、手输一串随机密码、
 * 回来重新扫一次」之间的差别。没有路由器的场合本来就是这个 App 最该好用的场合。
 *
 * <p>两条实现：
 * <ul>
 *   <li><b>Android 10 起</b>用 WifiNetworkSpecifier 请求一个网络。系统弹一次
 *       「要连接到 XXX 吗」，用户点一下就好，密码不用他操心。这条连接是
 *       <b>只给本 App 的</b>，退出即断，不会把用户原来的 Wi-Fi 配置搅乱。
 *       连上后必须 bindProcessToNetwork —— 这个热点没有互联网，
 *       不绑的话系统会继续把流量发到有网的那张卡上，请求全部打空。</li>
 *   <li><b>Android 9 及以下</b>只能走 addNetwork 那套老 API，它会真的往
 *       系统里写一条 Wi-Fi 配置。</li>
 * </ul>
 */
public final class WifiJoiner {

    private static final String TAG = "WifiJoiner";
    /** 系统弹窗要用户点一下，给得比普通网络请求宽。 */
    private static final int JOIN_TIMEOUT_MS = 40_000;

    public interface Callback {
        void onJoined();

        void onFailed(String msg);
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback callback;

    /**
     * 全进程一个。绑定要一直活到传输结束，而扫码是在「找设备」那一屏做的 ——
     * 挂在那个 Activity 上的话，它一销毁绑定就没了，传输当场断在半路。
     */
    private static WifiJoiner instance;

    public static synchronized WifiJoiner get(Context ctx) {
        if (instance == null) {
            instance = new WifiJoiner(ctx);
        }
        return instance;
    }

    private WifiJoiner(Context ctx) {
        app = ctx.getApplicationContext();
    }

    @SuppressLint("MissingPermission")
    public void join(String ssid, String password, Callback cb) {
        if (ssid == null || ssid.isEmpty()) {
            cb.onFailed("这个码里没有网络信息");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            joinModern(ssid, password, cb);
        } else {
            joinLegacy(ssid, password, cb);
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private void joinModern(String ssid, String password, Callback cb) {
        ConnectivityManager cm =
                (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            cb.onFailed("拿不到网络服务");
            return;
        }
        release();

        WifiNetworkSpecifier.Builder spec = new WifiNetworkSpecifier.Builder().setSsid(ssid);
        if (password != null && !password.isEmpty()) {
            spec.setWpa2Passphrase(password);
        }
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                // 直连热点没有互联网。不去掉这条能力要求，系统会认为
                // 这个网络「不合格」，转头把我们踢回原来那张有网的卡上。
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec.build())
                .build();

        final boolean[] settled = {false};
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                cm.bindProcessToNetwork(network);
                if (settled[0]) {
                    return;
                }
                settled[0] = true;
                main.post(cb::onJoined);
            }

            @Override
            public void onUnavailable() {
                if (settled[0]) {
                    return;
                }
                settled[0] = true;
                main.post(() -> cb.onFailed("没连上这个热点：对方可能已经关掉了，或者你点了取消"));
            }

            @Override
            public void onLost(Network network) {
                // 网断了就把绑定解开，否则这个进程后面所有请求都发往一张
                // 已经不存在的网卡，表现是「忽然什么都连不上」
                cm.bindProcessToNetwork(null);
            }
        };
        try {
            cm.requestNetwork(request, callback, JOIN_TIMEOUT_MS);
        } catch (SecurityException e) {
            callback = null;
            cb.onFailed("没有连接 Wi-Fi 的权限");
        }
    }

    @SuppressWarnings("deprecation")
    private void joinLegacy(String ssid, String password, Callback cb) {
        WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            cb.onFailed("拿不到 Wi-Fi 服务");
            return;
        }
        try {
            WifiConfiguration c = new WifiConfiguration();
            c.SSID = "\"" + ssid + "\"";
            if (password == null || password.isEmpty()) {
                c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            } else {
                c.preSharedKey = "\"" + password + "\"";
            }
            int id = wm.addNetwork(c);
            if (id < 0) {
                cb.onFailed("系统没接受这个网络配置");
                return;
            }
            wm.disconnect();
            wm.enableNetwork(id, true);
            wm.reconnect();
            // 老 API 没有「连上了」的回调，等一下再让上层去探对面在不在
            main.postDelayed(cb::onJoined, 3000);
        } catch (Exception e) {
            Log.w(TAG, "legacy join", e);
            cb.onFailed("这台手机的系统不允许 App 直接连 Wi-Fi，请手动连接 " + ssid);
        }
    }

    /** 传完了就松开：别让这个 App 一直绑在一张没有互联网的网卡上。 */
    public void release() {
        ConnectivityManager cm =
                (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cm.bindProcessToNetwork(null);
        }
        if (callback != null) {
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (Exception ignored) {
            }
            callback = null;
        }
    }
}
