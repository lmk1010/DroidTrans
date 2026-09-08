package com.mk.androidtransfer.network;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 接收端自己当 Wi-Fi Direct 的组主。
 *
 * <p>为什么优先它而不是 LocalOnlyHotspot：
 *
 * <ul>
 *   <li><b>频段能挑。</b>LocalOnlyHotspot 开在哪个频段完全由系统定，不少机型直接给
 *       2.4GHz，实测也就 5~8 MB/s —— 那还不如走路由器，「直连」这两个字就没意义了。
 *       建 P2P 组可以请求 5GHz（{@code setGroupOperatingBand}）。</li>
 *   <li><b>名字和密码是我们自己定的。</b>所以能一开始就写进二维码，也能在
 *       重启一次接收之后保持不变。</li>
 *   <li><b>对面不用扫码也能找到。</b>组主可以在 P2P 上广播一个 DNS-SD 服务，
 *       那是<b>预关联</b>的：对面还没连上任何网络就能发现它，点一下直接连过来。
 *       这才是「两台手机放在一起就能传」该有的样子，扫码退成兜底。</li>
 * </ul>
 *
 * <p>组主本身是一个普通 AP，所以 iPhone 也能加入 —— 它不需要懂 Wi-Fi Direct，
 * 只要从二维码里拿到名字和密码（iOS 没有扫描周围 Wi-Fi 的能力，所以那一步
 * 只能靠码递过去）。
 *
 * <p>要 Android 10（API 29）：再往下 {@code createGroup} 不接受配置，
 * 密码也读不到，写不进二维码。那种机型退回 {@link DirectHotspot} 里的
 * LocalOnlyHotspot。
 */
@RequiresApi(Build.VERSION_CODES.Q)
public final class DirectGroup {

    private static final String TAG = "DirectGroup";

    /** P2P 上广播的服务类型，和局域网里那个 Bonjour 服务是同一个名字。 */
    public static final String SERVICE_TYPE = "_droidtrans._tcp";
    /** DNS-SD 的实例名。发现端按它过滤，别的 App 的服务不会混进来。 */
    public static final String INSTANCE = "droidtrans";

    public interface Callback {
        /** 组建起来了，名字和密码直接进二维码。 */
        void onStarted(String ssid, String password);

        /** 建不起来。msg 是给用户看的一句话。 */
        void onFailed(String msg);
    }

    private final Context app;
    private WifiP2pManager p2p;
    private WifiP2pManager.Channel channel;
    private WifiP2pDnsSdServiceInfo service;

    public DirectGroup(Context ctx) {
        app = ctx.getApplicationContext();
    }

    public boolean isRunning() {
        return channel != null;
    }

    /**
     * 建组并广播服务。
     *
     * @param deviceName 广播出去的设备名，对面雷达上显示的就是它
     * @param port       接收端实际监听的端口，写进服务的 TXT 记录
     */
    @SuppressLint("MissingPermission")
    public void start(String deviceName, int port, Callback cb) {
        if (channel != null) {
            return;
        }
        WifiP2pManager m = (WifiP2pManager) app.getSystemService(Context.WIFI_P2P_SERVICE);
        if (m == null) {
            cb.onFailed("这台手机没有 Wi-Fi 直连");
            return;
        }
        final WifiP2pManager.Channel ch = m.initialize(app, Looper.getMainLooper(), null);
        if (ch == null) {
            cb.onFailed("Wi-Fi 直连初始化失败");
            return;
        }

        final String ssid = networkName();
        final String pass = passphrase();

        WifiP2pConfig config;
        try {
            config = new WifiP2pConfig.Builder()
                    .setNetworkName(ssid)
                    .setPassphrase(pass)
                    // 5GHz：直连的意义就在于比走路由器快，2.4GHz 上这句话不成立。
                    // 系统开不出 5GHz 时会自己退回去，不会因此失败。
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                    .build();
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "config: " + e.getMessage());
            cb.onFailed("Wi-Fi 直连配置不被这台手机接受");
            return;
        }

        try {
            m.createGroup(ch, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    p2p = m;
                    channel = ch;
                    advertise(deviceName, port);
                    cb.onStarted(ssid, pass);
                }

                @Override
                public void onFailure(int reason) {
                    Log.w(TAG, "createGroup failed " + reason);
                    cb.onFailed(explain(reason));
                }
            });
        } catch (SecurityException e) {
            cb.onFailed("要先允许「附近的设备」权限，系统才肯开直连");
        }
    }

    /**
     * 在 P2P 上广播一个 DNS-SD 服务。
     *
     * <p>这一条是「不用扫码」的关键：它在<b>关联之前</b>就能被对面发现，
     * 对面那时候还没连上任何网络。局域网里的 NsdManager 做不到这件事 ——
     * 它要求两台已经在同一个网里，而这里的前提恰恰是「没有网」。
     */
    @SuppressLint("MissingPermission")
    private void advertise(String deviceName, int port) {
        Map<String, String> txt = new HashMap<>();
        txt.put("port", String.valueOf(port));
        txt.put("name", deviceName);
        txt.put("engine", "android");
        service = WifiP2pDnsSdServiceInfo.newInstance(INSTANCE, SERVICE_TYPE, txt);
        try {
            p2p.addLocalService(channel, service, null);
        } catch (Exception e) {
            // 广播不出去只是少了「免扫码」这条路，组还在，二维码照常能用
            Log.w(TAG, "addLocalService: " + e.getMessage());
        }
    }

    public void stop() {
        if (p2p != null && channel != null) {
            try {
                if (service != null) {
                    p2p.removeLocalService(channel, service, null);
                }
                // 不删组的话，这台手机会一直挂着一个 DIRECT-xx 热点，
                // 回到主屏也上不了网，而界面上已经没有任何地方提到它了
                p2p.removeGroup(channel, null);
            } catch (Exception ignored) {
            }
        }
        service = null;
        p2p = null;
        channel = null;
    }

    // ------------------------------------------------------------------ 凭据

    /** P2P 的网络名必须以 DIRECT- 开头，长度 9~32。 */
    private static String networkName() {
        return "DIRECT-DT-" + hex(4);
    }

    /** WPA2 密码 8~63 位。随机生成，不做成固定值 —— 那等于人人都能连。 */
    private static String passphrase() {
        return hex(12);
    }

    private static String hex(int n) {
        byte[] b = new byte[(n + 1) / 2];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format(Locale.US, "%02x", x));
        }
        return sb.substring(0, n);
    }

    private static String explain(int reason) {
        switch (reason) {
            case WifiP2pManager.P2P_UNSUPPORTED:
                return "这台手机不支持 Wi-Fi 直连";
            case WifiP2pManager.BUSY:
                return "Wi-Fi 直连正忙，稍等一下再试";
            default:
                return "系统拒绝了 Wi-Fi 直连请求";
        }
    }
}
