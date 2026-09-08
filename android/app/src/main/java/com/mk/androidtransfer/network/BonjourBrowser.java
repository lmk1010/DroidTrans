package com.mk.androidtransfer.network;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * 发现同一局域网里用 Bonjour 广播的卓传电脑。
 */
public final class BonjourBrowser {
    private static final String TAG = "BonjourBrowser";
    public static final String SERVICE_TYPE = "_droidtrans._tcp.";

    public interface Listener {
        /**
         * 发现一台。
         *
         * <p>给的是**一组**候选地址，不是一个：mDNS 的缓存里常常留着对面
         * 换网络之前的旧地址，直接拿第一个用，结果就是「雷达上看得见、点了连不上」。
         * 真机上实测过：Mac 换到别的网段之后，手机这边还拿着上一段的
         * 192.168.4.18，那个地址在 Mac 上早就不存在了。
         * 哪个能用由调用方去探。
         */
        void onFound(String name, java.util.List<String> hosts, int port);

        /** 这个服务不再广播了（对面停了接收、退出了 App、或者离开了网络）。 */
        void onLost(String name);
    }

    private final Context app;
    private final NsdManager nsd;
    private WifiManager.MulticastLock lock;
    private NsdManager.DiscoveryListener discovery;
    private Listener listener;
    private boolean started;

    /**
     * NsdManager 同一时刻只受理一个 resolveService，并发调用会直接
     * 「listener already in use」。所以要排队，而不是把撞上的那个丢掉 ——
     * 丢掉意味着电脑和 iPhone 同时在广播时，后到的那台永远不出现在雷达上，
     * 而用户看到的只是「扫不到手机」。
     */
    private final Deque<NsdServiceInfo> queue = new ArrayDeque<>();
    private final Set<String> resolved = new HashSet<>();
    private boolean resolving;

    public BonjourBrowser(Context ctx) {
        app = ctx.getApplicationContext();
        nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
    }

    public void start(Listener listener) {
        this.listener = listener;
        if (started || nsd == null) {
            return;
        }
        started = true;
        acquireLock();
        discovery = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "start failed " + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "stop failed " + errorCode);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "started");
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.d(TAG, "stopped");
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (serviceInfo == null || serviceInfo.getServiceType() == null) {
                    return;
                }
                String type = serviceInfo.getServiceType();
                if (!type.contains("droidtrans")) {
                    return;
                }
                resolve(serviceInfo);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                if (serviceInfo == null || serviceInfo.getServiceName() == null) {
                    return;
                }
                // 下次它再出现要能重新解析，否则对面重启一次接收就再也扫不到了
                synchronized (BonjourBrowser.this) {
                    resolved.remove(serviceInfo.getServiceName());
                }
                Listener l = listener;
                if (l != null) {
                    l.onLost(serviceInfo.getServiceName());
                }
            }
        };
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            Log.e(TAG, "discover", e);
            started = false;
        }
    }

    private synchronized void resolve(NsdServiceInfo info) {
        if (info.getServiceName() != null && !resolved.add(info.getServiceName())) {
            return;   // 已经解析过了
        }
        queue.addLast(info);
        pump();
    }

    /** 队列里还有就接着解析一个。同一时刻只允许一个在飞。 */
    private synchronized void pump() {
        if (resolving || queue.isEmpty() || nsd == null) {
            return;
        }
        final NsdServiceInfo next = queue.pollFirst();
        resolving = true;
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.w(TAG, "resolve failed " + errorCode);
                    // 这台没解析成，别让它把队列堵死；也允许它下次再被发现时重来
                    synchronized (BonjourBrowser.this) {
                        if (next.getServiceName() != null) {
                            resolved.remove(next.getServiceName());
                        }
                    }
                    done();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    if (listener != null && serviceInfo != null) {
                        java.util.List<String> hosts = addressesOf(serviceInfo);
                        if (!hosts.isEmpty()) {
                            listener.onFound(serviceInfo.getServiceName(), hosts,
                                    serviceInfo.getPort());
                        }
                    }
                    done();
                }

                private void done() {
                    // NsdManager 的回调在它自己的线程上，队列这几个字段
                    // 主线程也在碰，别在这儿裸着改
                    synchronized (BonjourBrowser.this) {
                        resolving = false;
                    }
                    pump();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "resolve", e);
            resolving = false;
            if (next.getServiceName() != null) {
                resolved.remove(next.getServiceName());
            }
        }
    }

    /**
     * 这个服务通告出来的所有 IPv4 地址。
     *
     * <p>Android 14 起 NSD 能一次给出全部地址；再往下只有一个，
     * 那就只能拿那一个去试。IPv6 一律不要：桌面端和手机端都只监听 IPv4。
     */
    private static java.util.List<String> addressesOf(NsdServiceInfo info) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            for (InetAddress a : info.getHostAddresses()) {
                add(out, a);
            }
        } else {
            add(out, info.getHost());
        }
        return out;
    }

    private static void add(java.util.List<String> out, InetAddress a) {
        if (a == null) {
            return;
        }
        String ip = a.getHostAddress();
        if (ip != null && !ip.contains(":") && !out.contains(ip)) {
            out.add(ip);
        }
    }

    public synchronized void stop() {
        started = false;
        listener = null;
        resolving = false;
        queue.clear();
        resolved.clear();
        if (nsd != null && discovery != null) {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (Exception ignored) {
            }
            discovery = null;
        }
        if (lock != null && lock.isHeld()) {
            try {
                lock.release();
            } catch (Exception ignored) {
            }
        }
        lock = null;
    }

    private void acquireLock() {
        try {
            WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) {
                return;
            }
            lock = wifi.createMulticastLock("droidtrans-mdns");
            lock.setReferenceCounted(false);
            lock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "multicast lock", e);
        }
    }
}
