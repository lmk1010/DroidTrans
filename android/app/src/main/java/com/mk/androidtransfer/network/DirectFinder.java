package com.mk.androidtransfer.network;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * 发送端：不用扫码，直接把附近正在接收的手机找出来。
 *
 * <p>用的是 Wi-Fi Direct 的服务发现，它是<b>预关联</b>的 —— 两台都还没连上任何
 * 网络就能互相看见。局域网里那套 NsdManager 做不到：它要求两台已经在同一个网里，
 * 而「没有路由器」恰恰是这条路存在的理由。
 *
 * <p>找到之后点一下就连：{@link #connect} 走 P2P 的组网流程，
 * <b>密码全程由系统在两端之间交换，用户一个字都看不到</b>。
 * 连上之后组主的地址就是接收端的地址，后面照旧走 HTTP 那一套。
 *
 * <p>扫码那条路仍然留着，而且对 iPhone 是唯一的路：iOS 既不懂 Wi-Fi Direct，
 * 也没有扫描周围 Wi-Fi 的能力，凭据只能靠二维码递过去。
 *
 * <p>要 Android 10（API 29）：再往下 {@code WifiP2pConfig.Builder} 没有，
 * 老的那套 {@code config.deviceAddress} 还能用，所以这里对低版本走老写法。
 */
public final class DirectFinder {

    private static final String TAG = "DirectFinder";

    /** 服务列表会过期，扫描期间要隔一会儿重新发起一次。 */
    private static final long REDISCOVER_MS = 15_000;

    public interface Listener {
        /** 附近有一台正在接收。address 是 P2P 设备地址，连的时候要用。 */
        void onFound(String name, String address, int port);

        /** 连上了，对面在这个地址上等着。 */
        void onConnected(String host, int port);

        void onFailed(String msg);
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** 设备地址 → 它广播的端口。连上之后要拿这个端口去打 HTTP。 */
    private final Map<String, Integer> ports = new HashMap<>();

    private WifiP2pManager p2p;
    private WifiP2pManager.Channel channel;
    private WifiP2pDnsSdServiceRequest request;
    private BroadcastReceiver receiver;
    private Listener listener;
    private String connecting;
    private boolean scanning;

    private final Runnable rediscover = new Runnable() {
        @Override
        public void run() {
            if (!scanning) {
                return;
            }
            discover();
            main.postDelayed(this, REDISCOVER_MS);
        }
    };

    public DirectFinder(Context ctx) {
        app = ctx.getApplicationContext();
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    // ------------------------------------------------------------------ 扫描

    @SuppressLint("MissingPermission")
    public void start(Listener l) {
        if (scanning) {
            return;
        }
        listener = l;
        p2p = (WifiP2pManager) app.getSystemService(Context.WIFI_P2P_SERVICE);
        if (p2p == null) {
            return;   // 没有 Wi-Fi 直连的机型：雷达上照常只有局域网那条路
        }
        channel = p2p.initialize(app, Looper.getMainLooper(), null);
        if (channel == null) {
            return;
        }
        scanning = true;

        p2p.setDnsSdResponseListeners(channel,
                (instanceName, registrationType, device) -> {
                    if (!DirectGroup.INSTANCE.equals(instanceName)) {
                        return;   // 别的 App 的服务
                    }
                    Integer port = ports.get(device.deviceAddress);
                    notifyFound(device, port == null ? PeerServer.PORT : port);
                },
                (fullDomain, record, device) -> {
                    // TXT 通常比服务本身先到，先记下来，出现在雷达上时才知道端口
                    String port = record.get("port");
                    String name = record.get("name");
                    if (port != null) {
                        try {
                            ports.put(device.deviceAddress, Integer.parseInt(port));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    if (name != null && !name.isEmpty()) {
                        device.deviceName = name;
                    }
                });

        request = WifiP2pDnsSdServiceRequest.newInstance(DirectGroup.SERVICE_TYPE);
        try {
            p2p.addServiceRequest(channel, request, null);
        } catch (Exception e) {
            Log.w(TAG, "addServiceRequest: " + e.getMessage());
        }
        registerReceiver();
        main.post(rediscover);
    }

    @SuppressLint("MissingPermission")
    private void discover() {
        try {
            p2p.discoverServices(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                }

                @Override
                public void onFailure(int reason) {
                    // 这条路不通不该影响局域网那条，安安静静记一笔就行
                    Log.w(TAG, "discoverServices failed " + reason);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "discoverServices: " + e.getMessage());
        }
    }

    private void notifyFound(WifiP2pDevice device, int port) {
        final String name = device.deviceName == null || device.deviceName.isEmpty()
                ? "附近的手机" : device.deviceName;
        final String address = device.deviceAddress;
        main.post(() -> {
            if (listener != null) {
                listener.onFound(name, address, port);
            }
        });
    }

    // ------------------------------------------------------------------ 连接

    /** 连一台刚发现的手机。密码由系统在两端之间交换，用户不用输任何东西。 */
    @SuppressLint("MissingPermission")
    public void connect(String deviceAddress) {
        if (p2p == null || channel == null) {
            fail("Wi-Fi 直连没准备好");
            return;
        }
        connecting = deviceAddress;

        WifiP2pConfig config;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            config = new WifiP2pConfig.Builder()
                    .setDeviceAddress(android.net.MacAddress.fromString(deviceAddress))
                    .build();
        } else {
            config = new WifiP2pConfig();
            config.deviceAddress = deviceAddress;
            config.wps.setup = android.net.wifi.WpsInfo.PBC;
        }

        try {
            p2p.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    // 真的连上要等广播，这里只是「请求已受理」
                }

                @Override
                public void onFailure(int reason) {
                    fail(reason == WifiP2pManager.P2P_UNSUPPORTED
                            ? "这台手机不支持 Wi-Fi 直连"
                            : "没能连上对方，让对方确认接收页还开着");
                }
            });
        } catch (SecurityException e) {
            fail("要先允许「附近的设备」权限");
        }
    }

    @SuppressLint("MissingPermission")
    private void onConnectionChanged() {
        if (p2p == null || channel == null) {
            return;
        }
        p2p.requestConnectionInfo(channel, (WifiP2pInfo info) -> {
            if (info == null || !info.groupFormed || info.groupOwnerAddress == null) {
                return;
            }
            // 我们永远是加入方：接收端才是组主，它的地址就是要打的地址
            if (info.isGroupOwner) {
                return;
            }
            InetAddress go = info.groupOwnerAddress;
            Integer port = connecting == null ? null : ports.get(connecting);
            final String host = go.getHostAddress();
            final int p = port == null ? PeerServer.PORT : port;
            main.post(() -> {
                if (listener != null && host != null) {
                    listener.onConnected(host, p);
                }
            });
        });
    }

    private void fail(String msg) {
        main.post(() -> {
            if (listener != null) {
                listener.onFailed(msg);
            }
        });
    }

    // ------------------------------------------------------------------ 生命周期

    private void registerReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onConnectionChanged();
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(receiver, f);
        }
    }

    /**
     * 停止扫描。
     *
     * <p><b>不断开已经建好的组</b>：传输还在上面跑。组的生命周期归接收端管，
     * 它停止接收时会 removeGroup。
     */
    public void stop() {
        scanning = false;
        main.removeCallbacks(rediscover);
        listener = null;
        if (receiver != null) {
            try {
                app.unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            receiver = null;
        }
        if (p2p != null && channel != null && request != null) {
            try {
                p2p.removeServiceRequest(channel, request, null);
            } catch (Exception ignored) {
            }
        }
        request = null;
        ports.clear();
    }
}
