package com.mk.androidtransfer;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import android.provider.Settings;

import com.mk.androidtransfer.model.ApiResponse;
import com.mk.androidtransfer.model.ServerInfo;
import com.mk.androidtransfer.network.ApiService;
import com.mk.androidtransfer.network.BonjourBrowser;
import com.mk.androidtransfer.network.RetrofitClient;
import com.mk.androidtransfer.util.DeviceNameGenerator;
import com.mk.androidtransfer.util.ThemeBars;
import com.mk.androidtransfer.widget.RadarScanView;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 主界面Activity - 服务器发现界面（雷达交互模式）
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int DEFAULT_PORT = 9500;
    private static final String PREFS_NAME = "ServerCache";
    private static final String KEY_CACHED_SERVERS = "cached_servers";
    private static final String KEY_LAST_IP = "last_ip";
    private static final String KEY_LAST_PORT = "last_port";
    private static final String KEY_LAST_NAME = "last_name";
    private static final long CACHE_VALIDITY_MS = 5 * 60 * 1000; // 5分钟缓存有效期
    
    // 常用的IP地址范围（优先扫描）
    private static final int[] PRIORITY_IPS = {1, 2, 100, 101, 102, 254};
    // 次优IP范围
    private static final int[] SECONDARY_IPS = {10, 11, 12, 20, 50, 150, 200, 250};

    // UI组件
    private RadarScanView radarScanView;
    private TextView chipDeviceName;
    private TextView tvScanStatus;
    private com.google.android.material.button.MaterialButton btnRefresh;
    private com.google.android.material.button.MaterialButton btnManualInput;

    // 数据
    private List<ServerInfo> discoveredServers = new ArrayList<>();
    private ExecutorService executorService;
    private Handler mainHandler;
    private boolean isScanning = false;
    private boolean hasCachedServers = false;
    private boolean didAutoJoin = false;
    private long lastScanTime = 0;
    private static final long MAINTAIN_MS = 10 * 60 * 1000L;
    private ServerInfo heartbeatServer;
    private BonjourBrowser bonjour;
    private final Runnable heartbeatTask = new Runnable() {
        @Override
        public void run() {
            if (heartbeatServer != null) {
                sendHeartbeat(heartbeatServer);
            }
            if (!isScanning) {
                startNetworkScan();
            }
            if (mainHandler != null) {
                mainHandler.postDelayed(this, MAINTAIN_MS);
            }
        }
    };

    private static final OkHttpClient SCAN_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(180, TimeUnit.MILLISECONDS)
            .readTimeout(280, TimeUnit.MILLISECONDS)
            .writeTimeout(180, TimeUnit.MILLISECONDS)
            .callTimeout(450, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .connectionPool(new ConnectionPool(12, 1, TimeUnit.SECONDS))
            .build();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        setContentView(R.layout.activity_main);
        
        // 设置沉浸式状态栏（必须在setContentView之后）
        setupImmersiveStatusBar();

        initViews();
        setupDeviceName();
        setupListeners();

        executorService = Executors.newFixedThreadPool(12);
        mainHandler = new Handler(Looper.getMainLooper());
        bonjour = new BonjourBrowser(this);
        
        // 尝试加载缓存的服务器列表
        loadCachedServers();
        mainHandler.postDelayed(heartbeatTask, MAINTAIN_MS);
    }
    
    /**
     * 设置沉浸式状态栏
     */
    private void setupImmersiveStatusBar() {
        ThemeBars.apply(this);
    }

    /**
     * 初始化UI组件
     */
    private void initViews() {
        radarScanView = findViewById(R.id.radarScanView);
        chipDeviceName = findViewById(R.id.chipDeviceName);
        tvScanStatus = findViewById(R.id.tvScanStatus);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnManualInput = findViewById(R.id.btnManualInput);
        
        // 设置返回按钮
        android.widget.ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());
        
        // 设置历史记录按钮
        android.widget.ImageButton btnHistory = findViewById(R.id.btnHistory);
        btnHistory.setOnClickListener(v -> openUploadHistory());
        
        // 设置雷达点击监听
        radarScanView.setOnServerDotClickListener(serverDot -> {
            // 点击服务器点，直接进入照片选择页面
            registerDeviceConnection(serverDot.toServerInfo());
        });
    }

    /**
     * 设置设备名称
     */
    private void setupDeviceName() {
        String deviceName = DeviceNameGenerator.getOrGenerateDeviceName(this);
        chipDeviceName.setText(deviceName);
    }

    /**
     * 设置监听器
     */
    private void setupListeners() {
        // 没有路由器时的路子：手机开热点，电脑连上来
        android.view.View btnHotspot = findViewById(R.id.btnHotspot);
        if (btnHotspot != null) {
            btnHotspot.setOnClickListener(v -> showHotspotGuide());
        }

        // 扫码连接：直接扫电脑上的二维码，省掉手打 IP
        android.view.View btnScan = findViewById(R.id.btnScanQr);
        if (btnScan != null) {
            btnScan.setOnClickListener(v -> launchScanner());
        }

        // 手动输入按钮
        if (btnManualInput != null) {
            btnManualInput.setOnClickListener(v -> showManualInputDialog());
        }
        
        // 刷新按钮
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> {
                didAutoJoin = false;
                clearServerCache();
                hasCachedServers = false;
                if (bonjour != null) {
                    bonjour.stop();
                }
                startBonjour();
                startNetworkScan();
            });
        }
    }

    private final androidx.activity.result.ActivityResultLauncher<com.journeyapps.barcodescanner.ScanOptions> qrScanner =
            registerForActivityResult(new com.journeyapps.barcodescanner.ScanContract(), result -> {
                if (result == null || result.getContents() == null) {
                    return;   // 用户取消
                }
                onScanned(result.getContents());
            });

    private final androidx.activity.result.ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startScanner();
                } else {
                    Toast.makeText(this, R.string.scan_need_camera, Toast.LENGTH_LONG).show();
                }
            });

    /** 热点直连引导：说清三步，并直接送到系统的热点设置。 */
    private void showHotspotGuide() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.hotspot_title)
                .setMessage(R.string.hotspot_steps)
                .setPositiveButton(R.string.hotspot_open, (d, w) -> openHotspotSettings())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openHotspotSettings() {
        // 热点设置页没有正式的公开 Action，先试通用的那个类名，不行就退回无线设置
        Intent tether = new Intent(Intent.ACTION_MAIN, null);
        tether.setClassName("com.android.settings", "com.android.settings.TetherSettings");
        tether.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(tether);
            return;
        } catch (Exception ignored) {
            // 换一种
        }
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, R.string.hotspot_no_settings, Toast.LENGTH_LONG).show();
        }
    }

    private void launchScanner() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startScanner();
        } else {
            cameraPermission.launch(android.Manifest.permission.CAMERA);
        }
    }

    private void startScanner() {
        com.journeyapps.barcodescanner.ScanOptions options = new com.journeyapps.barcodescanner.ScanOptions();
        options.setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE);
        options.setPrompt(getString(R.string.scan_prompt));
        options.setBeepEnabled(false);
        options.setOrientationLocked(false);
        qrScanner.launch(options);
    }

    /** 扫到的内容形如 http://192.168.1.5:9500/?c=123456，也接受纯 IP。 */
    private void onScanned(String text) {
        // 手机接收端的码里还带着直连热点的 SSID 和密码，先把网连上再谈连设备
        com.mk.androidtransfer.network.PeerLink link =
                com.mk.androidtransfer.network.PeerLink.parse(text);
        if (link != null && link.hasHotspot()) {
            Toast.makeText(this, R.string.peer_scan_joining, Toast.LENGTH_SHORT).show();
            final String rest = com.mk.androidtransfer.network.PeerLink.encode(
                    link.host, link.port, link.code, link.name, null, null);
            com.mk.androidtransfer.network.WifiJoiner.get(this).join(link.ssid, link.password,
                    new com.mk.androidtransfer.network.WifiJoiner.Callback() {
                        @Override
                        public void onJoined() {
                            onScanned(rest);
                        }

                        @Override
                        public void onFailed(String msg) {
                            // 没连上也往下走：用户可能本来就在同一个网里
                            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                            onScanned(rest);
                        }
                    });
            return;
        }
        String content = text == null ? "" : text.trim();
        if (content.isEmpty() || (!content.startsWith("http://") && !content.matches("^[0-9a-fA-F:.]+(:\\d+)?$"))) {
            Toast.makeText(this, R.string.scan_bad, Toast.LENGTH_LONG).show();
            return;
        }
        String code = "";
        int q = content.indexOf("?c=");
        if (q >= 0) {
            code = content.substring(q + 3);
            content = content.substring(0, q);
            int amp = code.indexOf('&');
            if (amp >= 0) {
                code = code.substring(0, amp);
            }
        }
        final String address = content.endsWith("/") ? content.substring(0, content.length() - 1) : content;
        if (code.isEmpty()) {
            connectToManualServer(address);
            return;
        }
        // 二维码里带了配对码，直接换令牌，用户什么都不用输
        final String finalCode = code;
        new Thread(() -> {
            boolean ok = com.mk.androidtransfer.network.Pairing.pair(this, address, finalCode);
            mainHandler.post(() -> {
                if (ok) {
                    RetrofitClient.setToken(com.mk.androidtransfer.network.Pairing.token(this, address));
                    Toast.makeText(this, R.string.pair_ok, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.pair_failed, Toast.LENGTH_LONG).show();
                }
                connectToManualServer(address);
            });
        }).start();
    }

    /** 连之前先把这台电脑的令牌装上；没有令牌又要求配对时，让用户输六位码。 */
    private void ensurePaired(String baseUrl, Runnable onReady) {
        String saved = com.mk.androidtransfer.network.Pairing.token(this, baseUrl);
        if (saved != null && !saved.isEmpty()) {
            RetrofitClient.setToken(saved);
            onReady.run();
            return;
        }
        new Thread(() -> {
            boolean needs = com.mk.androidtransfer.network.Pairing.requiresPairing(baseUrl);
            mainHandler.post(() -> {
                if (!needs) {
                    RetrofitClient.setToken("");
                    onReady.run();
                    return;
                }
                askPairCode(baseUrl, onReady);
            });
        }).start();
    }

    private void askPairCode(String baseUrl, Runnable onReady) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint("000000");
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.pair_title)
                .setMessage(R.string.pair_message)
                .setView(input)
                .setPositiveButton(R.string.connect, (d, w) -> {
                    String code = input.getText().toString().trim();
                    new Thread(() -> {
                        boolean ok = com.mk.androidtransfer.network.Pairing.pair(this, baseUrl, code);
                        mainHandler.post(() -> {
                            if (ok) {
                                RetrofitClient.setToken(com.mk.androidtransfer.network.Pairing.token(this, baseUrl));
                                Toast.makeText(this, R.string.pair_ok, Toast.LENGTH_SHORT).show();
                                onReady.run();
                            } else {
                                Toast.makeText(this, R.string.pair_failed, Toast.LENGTH_LONG).show();
                            }
                        });
                    }).start();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 显示手动输入对话框
     */
    private void showManualInputDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_manual_input, null);
        TextInputEditText etServerAddress = dialogView.findViewById(R.id.etServerAddress);

        new AlertDialog.Builder(this)
                .setTitle(R.string.manual_input)
                .setView(dialogView)
                .setPositiveButton(R.string.connect, (dialog, which) -> {
                    String address = etServerAddress.getText().toString().trim();
                    if (!address.isEmpty()) {
                        connectToManualServer(address);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 连接到手动输入的服务器
     */
    private void connectToManualServer(String address) {
        // 解析地址和端口
        String ip;
        int port = DEFAULT_PORT;

        if (address.startsWith("http://")) {
            address = address.substring(7);
        }
        if (address.endsWith("/")) {
            address = address.substring(0, address.length() - 1);
        }

        if (address.contains(":")) {
            String[] parts = address.split(":");
            ip = parts[0];
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                port = DEFAULT_PORT;
            }
        } else {
            ip = address;
        }

        // 验证服务器连接
        verifyAndAddServer(ip, port, getString(R.string.manually_added_server));
    }

    /**
     * 验证并添加服务器
     */
    private void verifyAndAddServer(String ip, int port, String name) {
        String serverUrl = "http://" + ip + ":" + port + "/";
        RetrofitClient retrofitClient = RetrofitClient.getInstance(serverUrl);
        ApiService apiService = retrofitClient.getApiService();

        apiService.getWifiInfo(null, null).enqueue(new Callback<ApiResponse<Object>>() {
            @Override
            public void onResponse(Call<ApiResponse<Object>> call, Response<ApiResponse<Object>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    ServerInfo server = new ServerInfo(name, ip, port);
                    mainHandler.post(() -> {
                        // 在雷达上添加服务器点
                        radarScanView.addServerDot(server);
                        
                        // 添加到discoveredServers列表
                        if (!discoveredServers.contains(server)) {
                            discoveredServers.add(server);
                        }
                        
                        // 保存到缓存
                        saveServerCache();
                        hasCachedServers = true;
                        if (discoveredServers.size() > 1) {
                            showServerFoundSnackbar(server);
                        }
                        scheduleAutoJoin();
                    });
                } else {
                    mainHandler.post(() ->
                        Toast.makeText(MainActivity.this, R.string.server_connect_failed, Toast.LENGTH_SHORT).show()
                    );
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<Object>> call, Throwable t) {
                mainHandler.post(() ->
                    Toast.makeText(MainActivity.this, getString(R.string.server_connect_failed_detail, t.getMessage()), Toast.LENGTH_SHORT).show()
                );
            }
        });
    }

    /**
     * 开始扫描本地网络
     */
    private void startNetworkScan() {
        if (isScanning) {
            return;
        }

        isScanning = true;
        discoveredServers.clear();
        radarScanView.clearServerDots(); // 清除雷达上的服务器点

        tvScanStatus.setText(R.string.scanning_servers_ellipsis);

        // 获取本地IP地址前缀
        new Thread(() -> {
            try {
                String localIp = getLocalIpAddress();
                if (localIp != null && !localIp.isEmpty()) {
                    String subnet = localIp.substring(0, localIp.lastIndexOf('.') + 1);
                    Log.d(TAG, "智能扫描网段: " + subnet + "0/24");

                    // 第一轮：优先扫描常用IP
                    for (int ip : PRIORITY_IPS) {
                        final String address = subnet + ip;
                        executorService.execute(() -> scanHost(address, DEFAULT_PORT));
                    }
                    
                    // 第二轮：扫描次优IP
                    for (int ip : SECONDARY_IPS) {
                        final String address = subnet + ip;
                        executorService.execute(() -> scanHost(address, DEFAULT_PORT));
                    }
                    
                    // 第三轮：扫描剩余IP（延迟启动，给优先IP时间）
                    mainHandler.postDelayed(() -> {
                        if (isScanning && discoveredServers.isEmpty()) {
                            // 只有在没找到服务器时才扫描全部
                            for (int i = 1; i <= 254; i++) {
                                // 跳过已经扫描过的IP
                                boolean isPriority = false;
                                for (int pIp : PRIORITY_IPS) {
                                    if (i == pIp) {
                                        isPriority = true;
                                        break;
                                    }
                                }
                                for (int sIp : SECONDARY_IPS) {
                                    if (i == sIp) {
                                        isPriority = true;
                                        break;
                                    }
                                }
                                
                                if (!isPriority) {
                                    final String address = subnet + i;
                                    executorService.execute(() -> scanHost(address, DEFAULT_PORT));
                                }
                            }
                        }
                    }, 1000); // 1秒后开始全面扫描
                    
                } else {
                    mainHandler.post(() -> {
                        Toast.makeText(MainActivity.this, R.string.cannot_get_ip, Toast.LENGTH_SHORT).show();
                        isScanning = false;
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "网络扫描失败", e);
                mainHandler.post(() -> {
                    Toast.makeText(MainActivity.this, getString(R.string.scan_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
                    isScanning = false;
                });
            }
        }).start();

        // 8秒后停止扫描（缩短时间）
        mainHandler.postDelayed(() -> {
            isScanning = false;
            if (discoveredServers.size() > 0) {
                tvScanStatus.setText(R.string.scan_complete);
                saveServerCache();
                hasCachedServers = true;
                scheduleAutoJoin();
            } else {
                tvScanStatus.setText(R.string.no_servers_found);
            }
        }, 8000);
    }

    private void scanHost(String ip, int port) {
        if (!isScanning) {
            return;
        }
        if (!probeServer(ip, port)) {
            return;
        }
        String serverName = getString(R.string.transfer_server_name, ip.substring(ip.lastIndexOf('.') + 1));
        ServerInfo server = new ServerInfo(serverName, ip, port);
        mainHandler.post(() -> addDiscovered(server));
        Log.d(TAG, "发现服务器: " + ip);
    }

    private void addDiscovered(ServerInfo server) {
        if (isFinishing() || isDestroyed() || server == null) {
            return;
        }
        radarScanView.addServerDot(server);
        if (!discoveredServers.contains(server)) {
            discoveredServers.add(server);
        }
        saveServerCache();
        hasCachedServers = true;
        updateScanStatus();
        if (discoveredServers.size() > 1) {
            showServerFoundSnackbar(server);
        }
        scheduleAutoJoin();
    }

    private void startBonjour() {
        if (bonjour == null) {
            bonjour = new BonjourBrowser(this);
        }
        bonjour.start(new BonjourBrowser.Listener() {
            @Override
            public void onFound(String name, String host, int port) {
                int p = port > 0 ? port : DEFAULT_PORT;
                if (executorService == null) {
                    return;
                }
                executorService.execute(() -> {
                    if (!probeServer(host, p)) {
                        return;
                    }
                    String label = (name != null && !name.isEmpty()) ? name : host;
                    ServerInfo server = new ServerInfo(label, host, p);
                    mainHandler.post(() -> addDiscovered(server));
                });
            }

            @Override
            public void onLost(String name) {
                // 这一屏的列表是「历史连过的电脑」，不是实时雷达 ——
                // 对面一时不广播就把它从首页抹掉，用户下次开机会找不到熟悉的那台。
            }
        });
    }

    /** 探测到的电脑名；电脑改过名字后，手机这边不该一直显示旧的。 */
    private volatile String probedName = "";

    private boolean probeServer(String ip, int port) {
        Request request = new Request.Builder()
                .url("http://" + ip + ":" + port + "/api/wifi/info")
                .get()
                .build();
        probedName = "";
        try (okhttp3.Response response = SCAN_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return false;
            }
            okhttp3.ResponseBody body = response.body();
            if (body != null) {
                try {
                    com.google.gson.JsonObject obj = com.google.gson.JsonParser
                            .parseString(body.string()).getAsJsonObject();
                    if (obj.has("name") && !obj.get("name").isJsonNull()) {
                        probedName = obj.get("name").getAsString();
                    }
                } catch (Exception ignored) {
                    // 名字拿不到不影响连接
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取本地IP地址
     */
    private String getLocalIpAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                String ifName = networkInterface.getName() == null ? "" : networkInterface.getName().toLowerCase();
                if (ifName.startsWith("rmnet") || ifName.startsWith("ccmni") || ifName.startsWith("tun")
                        || ifName.startsWith("ppp") || ifName.startsWith("dummy")) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address.isLoopbackAddress() || !(address instanceof java.net.Inet4Address)
                            || !address.isSiteLocalAddress()) {
                        continue;
                    }
                    String ip = address.getHostAddress();
                    if (ifName.startsWith("wlan") || ifName.startsWith("ap") || ifName.contains("wlan")) {
                        return ip;
                    }
                    if (fallback == null) {
                        fallback = ip;
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            Log.e(TAG, "获取IP失败", e);
        }
        return null;
    }

    /**
     * 更新扫描状态
     */
    private void updateScanStatus() {
        int count = radarScanView.getServerDotCount();
        if (count > 0) {
            tvScanStatus.setText(getString(R.string.found_servers_count, count));
        } else if (isScanning) {
            tvScanStatus.setText(R.string.scanning_servers_ellipsis);
        } else {
            tvScanStatus.setText(R.string.no_servers_found);
        }
    }
    
    /**
     * 显示服务器发现通知（Snackbar）
     */
    private void showServerFoundSnackbar(ServerInfo server) {
        Snackbar snackbar = Snackbar.make(
                findViewById(android.R.id.content),
                getString(R.string.snackbar_found_server, server.getName()),
                Snackbar.LENGTH_LONG
        );
        
        snackbar.setAction(R.string.connect, v -> {
            // 点击连接按钮，直接进入照片选择页面
            registerDeviceConnection(server);
        });
        
        snackbar.setActionTextColor(getColor(R.color.radar_blue_light));
        snackbar.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        radarScanView.startScanning();
        startBonjour();
        radarScanView.post(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (didAutoJoin) {
                return;
            }
            beginDiscovery();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        radarScanView.stopScanning();
        if (bonjour != null) {
            bonjour.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bonjour != null) {
            bonjour.stop();
        }
        if (mainHandler != null) {
            mainHandler.removeCallbacks(heartbeatTask);
            mainHandler.removeCallbacks(autoJoinTask);
        }
        if (executorService != null) {
            executorService.shutdown();
        }
    }
    
    /**
     * 保存服务器列表到缓存
     */
    private void saveServerCache() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            
            JSONArray jsonArray = new JSONArray();
            
            // 遍历discoveredServers列表
            for (ServerInfo server : discoveredServers) {
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("name", server.getName());
                jsonObject.put("ip", server.getIp());
                jsonObject.put("port", server.getPort());
                jsonArray.put(jsonObject);
            }
            
            editor.putString(KEY_CACHED_SERVERS, jsonArray.toString());
            editor.putLong("last_scan_time", System.currentTimeMillis());
            editor.apply();
            
            lastScanTime = System.currentTimeMillis();
            Log.d(TAG, "服务器列表已缓存: " + jsonArray.length() + " 个");
        } catch (Exception e) {
            Log.e(TAG, "保存服务器缓存失败", e);
        }
    }
    
    /**
     * 从缓存加载服务器列表
     */
    private void loadCachedServers() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String cachedData = prefs.getString(KEY_CACHED_SERVERS, null);
            lastScanTime = prefs.getLong("last_scan_time", 0);
            
            if (cachedData != null && !cachedData.isEmpty()) {
                JSONArray jsonArray = new JSONArray(cachedData);
                
                if (jsonArray.length() > 0) {
                    List<ServerInfo> cachedServers = new ArrayList<>();
                    for (int i = 0; i < jsonArray.length(); i++) {
                        JSONObject jsonObject = jsonArray.getJSONObject(i);
                        String name = jsonObject.getString("name");
                        String ip = jsonObject.getString("ip");
                        int port = jsonObject.getInt("port");
                        cachedServers.add(new ServerInfo(name, ip, port));
                    }
                    
                    // 显示缓存的服务器
                    discoveredServers.clear();
                    discoveredServers.addAll(cachedServers);
                    for (ServerInfo server : cachedServers) {
                        // 在雷达上添加服务器点
                        radarScanView.addServerDot(server);
                    }
                    
                    hasCachedServers = true;
                    
                    // 更新状态文字
                    updateScanStatus();
                    
                    Log.d(TAG, "从缓存加载了 " + cachedServers.size() + " 个服务器");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "加载服务器缓存失败", e);
        }
    }
    
    /**
     * 清除服务器缓存
     */
    private void clearServerCache() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .remove(KEY_CACHED_SERVERS)
                .remove("last_scan_time")
                .apply();
        lastScanTime = 0;
        Log.d(TAG, "服务器缓存已清除");
    }
    
    /**
     * 验证缓存的服务器是否在线
     */
    private void validateCachedServers() {
        if (discoveredServers.isEmpty()) {
            startNetworkScan();
            return;
        }
        
        List<ServerInfo> serversToValidate = new ArrayList<>(discoveredServers);
        
        new Thread(() -> {
            int onlineCount = 0;
            List<ServerInfo> offlineServers = new ArrayList<>();
            
            for (ServerInfo server : serversToValidate) {
                if (probeServer(server.getIp(), server.getPort())) {
                    onlineCount++;
                } else {
                    offlineServers.add(server);
                }
            }
            
            final int finalOnlineCount = onlineCount;
            final List<ServerInfo> finalOfflineServers = offlineServers;
            
            mainHandler.post(() -> {
                // 移除离线的服务器
                for (ServerInfo server : finalOfflineServers) {
                    discoveredServers.remove(server);
                    radarScanView.removeServerDot(server.getIp());
                }
                
                updateScanStatus();
                
                if (finalOnlineCount > 0) {
                    tvScanStatus.setText(R.string.verification_complete);
                    saveServerCache();
                    scheduleAutoJoin();
                } else {
                    tvScanStatus.setText(R.string.servers_offline);
                    // 清除缓存并重新扫描
                    clearServerCache();
                    hasCachedServers = false;
                    startNetworkScan();
                }
            });
        }).start();
    }
    
    private void beginDiscovery() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String lastIp = prefs.getString(KEY_LAST_IP, "");
        int lastPort = prefs.getInt(KEY_LAST_PORT, DEFAULT_PORT);
        if (lastIp != null && !lastIp.isEmpty() && executorService != null) {
            tvScanStatus.setText(R.string.verifying_servers);
            executorService.execute(() -> {
                boolean ok = probeServer(lastIp, lastPort);
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed() || didAutoJoin) {
                        return;
                    }
                    if (ok) {
                        String name = probedName != null && !probedName.isEmpty()
                                ? probedName
                                : prefs.getString(KEY_LAST_NAME, lastIp);
                        if (probedName != null && !probedName.isEmpty()) {
                            prefs.edit().putString(KEY_LAST_NAME, probedName).apply();
                        }
                        ServerInfo server = new ServerInfo(name, lastIp, lastPort);
                        radarScanView.addServerDot(server);
                        if (!discoveredServers.contains(server)) {
                            discoveredServers.add(server);
                        }
                        didAutoJoin = true;
                        tvScanStatus.setText(R.string.auto_connecting);
                        registerDeviceConnection(server);
                    } else {
                        startCachedOrScan();
                    }
                });
            });
            return;
        }
        startCachedOrScan();
    }

    private void startCachedOrScan() {
        long currentTime = System.currentTimeMillis();
        if (hasCachedServers && (currentTime - lastScanTime) < CACHE_VALIDITY_MS) {
            tvScanStatus.setText(R.string.verifying_servers);
            validateCachedServers();
        } else {
            startNetworkScan();
        }
    }

    private final Runnable autoJoinTask = this::maybeAutoJoin;

    private void scheduleAutoJoin() {
        if (didAutoJoin || mainHandler == null) {
            return;
        }
        mainHandler.removeCallbacks(autoJoinTask);
        mainHandler.postDelayed(autoJoinTask, 1600);
    }

    private void maybeAutoJoin() {
        if (didAutoJoin || isFinishing() || isDestroyed()) {
            return;
        }
        ServerInfo pick = pickServerToJoin();
        if (pick == null) {
            return;
        }
        didAutoJoin = true;
        tvScanStatus.setText(R.string.auto_connecting);
        registerDeviceConnection(pick);
    }

    private ServerInfo pickServerToJoin() {
        if (discoveredServers.isEmpty()) {
            return null;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String lastIp = prefs.getString(KEY_LAST_IP, "");
        if (discoveredServers.size() == 1) {
            return discoveredServers.get(0);
        }
        if (lastIp != null && !lastIp.isEmpty()) {
            for (ServerInfo server : discoveredServers) {
                if (lastIp.equals(server.getIp())) {
                    return server;
                }
            }
        }
        return null;
    }

    private void rememberServer(ServerInfo server) {
        if (server == null) {
            return;
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_IP, server.getIp())
                .putInt(KEY_LAST_PORT, server.getPort())
                .putString(KEY_LAST_NAME, server.getName())
                .apply();
    }

    /**
     * 注册设备连接
     */
    private void registerDeviceConnection(ServerInfo server) {
        // 先确保这台电脑的配对令牌就位，否则后面每个请求都会被挡回 401
        ensurePaired(server.getServerUrl(), () -> registerDeviceConnectionInner(server));
    }

    private void registerDeviceConnectionInner(ServerInfo server) {
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        String deviceName = DeviceNameGenerator.getOrGenerateDeviceName(this);
        
        String serverUrl = server.getServerUrl();
        RetrofitClient retrofitClient = RetrofitClient.getInstance(serverUrl);
        ApiService apiService = retrofitClient.getApiService();
        
        // 构建请求
        Map<String, String> request = new HashMap<>();
        request.put("device_id", deviceId);
        request.put("device_name", deviceName);
        
        Log.d(TAG, "注册设备连接: " + deviceName + " -> " + serverUrl);
        
        // 调用连接接口
        apiService.connectDevice(request).enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    Map<String, Object> result = response.body();
                    Boolean success = (Boolean) result.get("success");
                    if (success != null && success) {
                        Log.d(TAG, "设备连接成功");
                        heartbeatServer = server;
                        mainHandler.removeCallbacks(heartbeatTask);
                        mainHandler.postDelayed(heartbeatTask, MAINTAIN_MS);
                        Toast.makeText(MainActivity.this, R.string.connected_to_server, Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Log.w(TAG, "设备连接响应失败: " + response.code());
                }
                
                // 无论连接是否成功，都跳转到照片选择页面
                navigateToPhotoSelection(server);
            }
            
            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                Log.e(TAG, "设备连接请求失败", t);
                // 即使连接请求失败，也跳转到照片选择页面
                navigateToPhotoSelection(server);
            }
        });
    }

    private void sendHeartbeat(ServerInfo server) {
        try {
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            String deviceName = DeviceNameGenerator.getOrGenerateDeviceName(this);
            Map<String, String> request = new HashMap<>();
            request.put("device_id", deviceId);
            request.put("device_name", deviceName);
            RetrofitClient.getInstance(server.getServerUrl()).getApiService()
                    .heartbeat(request)
                    .enqueue(new Callback<Map<String, Object>>() {
                        @Override
                        public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> response) {
                            Log.d(TAG, "heartbeat " + response.code());
                        }
                        @Override
                        public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                            Log.w(TAG, "heartbeat failed", t);
                        }
                    });
        } catch (Exception e) {
            Log.w(TAG, "heartbeat", e);
        }
    }
    
    /**
     * 跳转到照片选择页面
     */
    private void navigateToPhotoSelection(ServerInfo server) {
        rememberServer(server);
        Intent intent = new Intent(MainActivity.this, PhotoSelectionActivity.class);
        intent.putExtra("server_url", server.getServerUrl());
        intent.putExtra("server_name", server.getName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }
    
    /**
     * 打开上传历史记录
     */
    private void openUploadHistory() {
        Intent intent = new Intent(this, UploadHistoryActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }
}
