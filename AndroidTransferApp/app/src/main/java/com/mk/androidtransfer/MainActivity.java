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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import android.provider.Settings;

import com.mk.androidtransfer.adapter.ServerAdapter;
import com.mk.androidtransfer.model.ApiResponse;
import com.mk.androidtransfer.model.ServerInfo;
import com.mk.androidtransfer.network.ApiService;
import com.mk.androidtransfer.network.RetrofitClient;
import com.mk.androidtransfer.util.DeviceNameGenerator;
import com.mk.androidtransfer.widget.RadarScanView;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 主界面Activity - 服务器发现界面
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int DEFAULT_PORT = 9500;
    private static final int SCAN_TIMEOUT = 1000; // 1秒超时
    private static final String PREFS_NAME = "ServerCache";
    private static final String KEY_CACHED_SERVERS = "cached_servers";
    private static final long CACHE_VALIDITY_MS = 5 * 60 * 1000; // 5分钟缓存有效期

    // UI组件
    private RadarScanView radarScanView;
    private Chip chipDeviceName;
    private TextView tvScanStatus;
    private TextView tvServerCount;
    private RecyclerView recyclerViewServers;
    private LinearLayout emptyState;
    private ExtendedFloatingActionButton fabManualInput;
    private com.google.android.material.button.MaterialButton btnRefresh;

    // 数据
    private ServerAdapter serverAdapter;
    private List<ServerInfo> discoveredServers = new ArrayList<>();
    private ExecutorService executorService;
    private Handler mainHandler;
    private boolean isScanning = false;
    private boolean hasCachedServers = false;
    private long lastScanTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        setContentView(R.layout.activity_main);
        
        // 设置沉浸式状态栏（必须在setContentView之后）
        setupImmersiveStatusBar();

        initViews();
        setupDeviceName();
        setupRecyclerView();
        setupListeners();

        executorService = Executors.newFixedThreadPool(20); // 用于并发扫描
        mainHandler = new Handler(Looper.getMainLooper());
        
        // 尝试加载缓存的服务器列表
        loadCachedServers();
    }
    
    /**
     * 设置沉浸式状态栏
     */
    private void setupImmersiveStatusBar() {
        // 启用edge-to-edge显示
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11及以上
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                // 状态栏图标使用浅色（因为状态栏背景是深色青绿色）
                controller.setSystemBarsAppearance(0, 
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6.0到10
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
        }
    }

    /**
     * 初始化UI组件
     */
    private void initViews() {
        radarScanView = findViewById(R.id.radarScanView);
        chipDeviceName = findViewById(R.id.chipDeviceName);
        tvScanStatus = findViewById(R.id.tvScanStatus);
        tvServerCount = findViewById(R.id.tvServerCount);
        recyclerViewServers = findViewById(R.id.recyclerViewServers);
        emptyState = findViewById(R.id.emptyState);
        fabManualInput = findViewById(R.id.fabManualInput);
        btnRefresh = findViewById(R.id.btnRefresh);
    }

    /**
     * 设置设备名称
     */
    private void setupDeviceName() {
        String deviceName = DeviceNameGenerator.getOrGenerateDeviceName(this);
        chipDeviceName.setText(deviceName);
    }

    /**
     * 设置RecyclerView
     */
    private void setupRecyclerView() {
        serverAdapter = new ServerAdapter();
        recyclerViewServers.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewServers.setAdapter(serverAdapter);

        serverAdapter.setOnServerClickListener(server -> {
            // 点击服务器，先注册设备连接，然后跳转到照片选择页面
            registerDeviceConnection(server);
        });
    }

    /**
     * 设置监听器
     */
    private void setupListeners() {
        // 手动输入服务器地址
        fabManualInput.setOnClickListener(v -> showManualInputDialog());
        
        // 刷新按钮
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> {
                // 清除缓存并重新扫描
                clearServerCache();
                hasCachedServers = false;
                startNetworkScan();
            });
        }
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
        verifyAndAddServer(ip, port, "手动添加的服务器");
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
                        serverAdapter.addServer(server);
                        updateServerCount();
                        
                        // 添加到discoveredServers列表
                        if (!discoveredServers.contains(server)) {
                            discoveredServers.add(server);
                        }
                        
                        // 保存到缓存
                        saveServerCache();
                        hasCachedServers = true;
                        
                        Toast.makeText(MainActivity.this, "服务器连接成功", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    mainHandler.post(() ->
                        Toast.makeText(MainActivity.this, "服务器连接失败", Toast.LENGTH_SHORT).show()
                    );
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<Object>> call, Throwable t) {
                mainHandler.post(() ->
                    Toast.makeText(MainActivity.this, "服务器连接失败: " + t.getMessage(), Toast.LENGTH_SHORT).show()
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
        serverAdapter.clearServers();
        updateServerCount();

        tvScanStatus.setText(R.string.scanning_servers);
        emptyState.setVisibility(View.VISIBLE);
        recyclerViewServers.setVisibility(View.GONE);

        // 获取本地IP地址前缀
        new Thread(() -> {
            try {
                String localIp = getLocalIpAddress();
                if (localIp != null && !localIp.isEmpty()) {
                    String subnet = localIp.substring(0, localIp.lastIndexOf('.') + 1);
                    Log.d(TAG, "扫描网段: " + subnet + "0/24");

                    // 扫描整个子网 (1-254)
                    for (int i = 1; i <= 254; i++) {
                        final String ip = subnet + i;
                        executorService.execute(() -> scanHost(ip, DEFAULT_PORT));
                    }
                } else {
                    mainHandler.post(() -> {
                        Toast.makeText(MainActivity.this, "无法获取本地IP地址", Toast.LENGTH_SHORT).show();
                        isScanning = false;
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "网络扫描失败", e);
                mainHandler.post(() -> {
                    Toast.makeText(MainActivity.this, "扫描失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    isScanning = false;
                });
            }
        }).start();

        // 15秒后停止扫描
        mainHandler.postDelayed(() -> {
            isScanning = false;
            if (discoveredServers.size() > 0) {
                tvScanStatus.setText("扫描完成");
                // 保存扫描结果到缓存
                saveServerCache();
                hasCachedServers = true;
            } else {
                tvScanStatus.setText("未发现服务器");
            }
        }, 15000);
    }

    /**
     * 扫描单个主机
     */
    private void scanHost(String ip, int port) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            if (address.isReachable(SCAN_TIMEOUT)) {
                // 主机可达，尝试连接服务
                verifyServer(ip, port);
            }
        } catch (Exception e) {
            // 忽略不可达的主机
        }
    }

    /**
     * 验证服务器
     */
    private void verifyServer(String ip, int port) {
        String serverUrl = "http://" + ip + ":" + port + "/";
        try {
            RetrofitClient retrofitClient = RetrofitClient.getInstance(serverUrl);
            ApiService apiService = retrofitClient.getApiService();

            apiService.getWifiInfo(null, null).enqueue(new Callback<ApiResponse<Object>>() {
                @Override
                public void onResponse(Call<ApiResponse<Object>> call, Response<ApiResponse<Object>> response) {
                    if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                        String serverName = "传输服务器 " + ip.substring(ip.lastIndexOf('.') + 1);
                        ServerInfo server = new ServerInfo(serverName, ip, port);

                        mainHandler.post(() -> {
                            serverAdapter.addServer(server);
                            updateServerCount();

                            // 显示服务器列表，隐藏空状态
                            if (recyclerViewServers.getVisibility() != View.VISIBLE) {
                                recyclerViewServers.setVisibility(View.VISIBLE);
                                emptyState.setVisibility(View.GONE);
                            }
                            
                            // 添加到discoveredServers列表
                            if (!discoveredServers.contains(server)) {
                                discoveredServers.add(server);
                            }
                            
                            // 保存到缓存
                            saveServerCache();
                            hasCachedServers = true;
                        });

                        Log.d(TAG, "发现服务器: " + ip);
                    }
                }

                @Override
                public void onFailure(Call<ApiResponse<Object>> call, Throwable t) {
                    // 连接失败，不是我们的服务器
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "验证服务器失败: " + ip, e);
        }
    }

    /**
     * 获取本地IP地址
     */
    private String getLocalIpAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = interfaces.nextElement();
                java.util.Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();

                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address instanceof java.net.Inet4Address) {
                        String ip = address.getHostAddress();
                        if (ip != null && ip.startsWith("192.168.")) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取IP失败", e);
        }
        return null;
    }

    /**
     * 更新服务器数量显示
     */
    private void updateServerCount() {
        int count = serverAdapter.getItemCount();
        tvServerCount.setText(getString(R.string.found_servers_count, count));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 启动雷达扫描动画
        radarScanView.startScanning();

        // 智能扫描：如果有有效缓存就验证，否则重新扫描
        long currentTime = System.currentTimeMillis();
        if (hasCachedServers && (currentTime - lastScanTime) < CACHE_VALIDITY_MS) {
            // 有效缓存存在，只验证服务器是否在线
            tvScanStatus.setText("验证服务器状态...");
            validateCachedServers();
        } else {
            // 缓存过期或不存在，执行完整扫描
            startNetworkScan();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 停止雷达扫描动画
        radarScanView.stopScanning();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
                        serverAdapter.addServer(server);
                    }
                    
                    updateServerCount();
                    hasCachedServers = true;
                    
                    // 显示服务器列表
                    recyclerViewServers.setVisibility(View.VISIBLE);
                    emptyState.setVisibility(View.GONE);
                    tvScanStatus.setText("已加载缓存的服务器");
                    
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
        prefs.edit().clear().apply();
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
                String serverUrl = "http://" + server.getIp() + ":" + server.getPort() + "/";
                try {
                    RetrofitClient retrofitClient = RetrofitClient.getInstance(serverUrl);
                    ApiService apiService = retrofitClient.getApiService();
                    
                    // 同步验证
                    retrofit2.Response<ApiResponse<Object>> response = 
                        apiService.getWifiInfo(null, null).execute();
                    
                    if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                        onlineCount++;
                    } else {
                        offlineServers.add(server);
                    }
                } catch (Exception e) {
                    offlineServers.add(server);
                }
            }
            
            final int finalOnlineCount = onlineCount;
            final List<ServerInfo> finalOfflineServers = offlineServers;
            
            mainHandler.post(() -> {
                // 移除离线的服务器
                for (ServerInfo server : finalOfflineServers) {
                    discoveredServers.remove(server);
                    serverAdapter.removeServer(server);
                }
                
                updateServerCount();
                
                if (finalOnlineCount > 0) {
                    tvScanStatus.setText("验证完成");
                    // 更新缓存
                    saveServerCache();
                } else {
                    tvScanStatus.setText("所有服务器离线");
                    // 清除缓存并重新扫描
                    clearServerCache();
                    hasCachedServers = false;
                    startNetworkScan();
                }
            });
        }).start();
    }
    
    /**
     * 注册设备连接
     */
    private void registerDeviceConnection(ServerInfo server) {
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
                        Toast.makeText(MainActivity.this, "已连接到服务器", Toast.LENGTH_SHORT).show();
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
    
    /**
     * 跳转到照片选择页面
     */
    private void navigateToPhotoSelection(ServerInfo server) {
        Intent intent = new Intent(MainActivity.this, PhotoSelectionActivity.class);
        intent.putExtra("server_url", server.getServerUrl());
        intent.putExtra("server_name", server.getName());
        startActivity(intent);
    }
}
