package com.mk.androidtransfer.network;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.net.InetAddress;

/**
 * 发现同一局域网里用 Bonjour 广播的卓传电脑。
 */
public final class BonjourBrowser {
    private static final String TAG = "BonjourBrowser";
    public static final String SERVICE_TYPE = "_droidtrans._tcp.";

    public interface Listener {
        void onFound(String name, String host, int port);
    }

    private final Context app;
    private final NsdManager nsd;
    private WifiManager.MulticastLock lock;
    private NsdManager.DiscoveryListener discovery;
    private Listener listener;
    private boolean started;
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
            }
        };
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            Log.e(TAG, "discover", e);
            started = false;
        }
    }

    private void resolve(NsdServiceInfo info) {
        if (resolving) {
            return;
        }
        resolving = true;
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    resolving = false;
                    Log.w(TAG, "resolve failed " + errorCode);
                }

                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    resolving = false;
                    if (listener == null || serviceInfo == null) {
                        return;
                    }
                    InetAddress host = serviceInfo.getHost();
                    if (host == null) {
                        return;
                    }
                    String ip = host.getHostAddress();
                    if (ip == null || ip.contains(":")) {
                        return;
                    }
                    int port = serviceInfo.getPort();
                    String name = serviceInfo.getServiceName();
                    listener.onFound(name, ip, port);
                }
            });
        } catch (Exception e) {
            resolving = false;
            Log.e(TAG, "resolve", e);
        }
    }

    public void stop() {
        started = false;
        listener = null;
        resolving = false;
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
