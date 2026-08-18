package com.mk.androidtransfer;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.mk.androidtransfer.usb.UsbCapabilityDetector;
import com.mk.androidtransfer.util.ThemeBars;
import com.mk.androidtransfer.usb.UsbRndisManager;
import com.mk.androidtransfer.usb.UsbRndisNetworkManager;
import com.mk.androidtransfer.usb.UsbRndisTransferManager;

import java.util.ArrayList;
import java.util.List;

/**
 * USB点对点传输Activity - 角色选择
 * 用户手动选择作为发送端或接收端
 */
public class UsbP2PActivity extends AppCompatActivity {

    private static final String TAG = "UsbP2PActivity";
    private static final int REQUEST_CODE_STORAGE_PERMISSION = 1002;
    private static final String ACTION_USB_PERMISSION = "com.mk.androidtransfer.USB_PERMISSION";

    // UI组件
    private MaterialButton btnBack;
    private MaterialCardView cardRoleSelection;
    private MaterialCardView cardSender;
    private MaterialCardView cardReceiver;
    private TextView tvSenderTitle;
    private TextView tvSenderDesc;
    private TextView tvReceiverTitle;
    private TextView tvReceiverDesc;
    private MaterialButton btnSelectPhotos;
    private MaterialButton btnSelectVideos;
    private MaterialButton btnSelectFiles;
    private MaterialButton btnStartTransfer;
    private MaterialCardView cardFileSelection;
    private TextView tvFileSelectionStatus;
    private TextView tvFileSelectionDetails;
    
    // 日志显示
    private MaterialCardView cardDebugLog;
    private TextView tvDebugLog;
    private TextView tvLogStatus;
    private  ImageView ivLogToggle;
    private LinearLayout logHeader;
    private MaterialButton btnClearLog;
    private MaterialButton btnDisableOtgCharging;
    private MaterialButton btnCheckRndis;
    private ScrollView scrollViewLog;
    private StringBuilder logBuffer = new StringBuilder();
    private boolean isLogExpanded = true; // 日志是否展开
    
    // 角色选择
    private boolean isSenderMode = false;
    private boolean isReceiverMode = false;
    private boolean roleSelected = false;

    // 文件选择相关
    private List<Uri> selectedFiles = new ArrayList<>();
    private String selectedFileType = "";
    
    // ActivityResultLauncher for file selection
    private ActivityResultLauncher<String> selectPhotosLauncher;
    private ActivityResultLauncher<String> selectVideosLauncher;
    private ActivityResultLauncher<String[]> selectFilesLauncher;

    private Handler mainHandler;
    
    // USB RNDIS管理器
    private UsbRndisManager rndisManager;
    private UsbRndisNetworkManager networkManager;
    private UsbRndisTransferManager transferManager;
    private UsbManager usbManager;
    private PendingIntent permissionIntent;
    
    // 广播接收器
    private BroadcastReceiver usbReceiver;
    
    // 连接状态
    private volatile boolean isConnecting = false;
    private volatile boolean isUsbPermissionRequested = false;
    private int connectionRetryCount = 0;
    private static final int MAX_RETRY_COUNT = 5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usb_p2p);

        // 设置沉浸式状态栏
        setupImmersiveStatusBar();

        mainHandler = new Handler(Looper.getMainLooper());
        
        // 初始化USB管理器
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        
        // 创建权限Intent
        // Android 14+ 要求隐式Intent必须使用FLAG_IMMUTABLE
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ?
                PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
        permissionIntent = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION), flags);
        
        // 初始化USB RNDIS管理器
        rndisManager = new UsbRndisManager(this);
        networkManager = new UsbRndisNetworkManager(this);
        transferManager = new UsbRndisTransferManager();
        
        // 注册USB广播接收器
        registerUsbReceiver();

        // 初始化文件选择器
        initFileSelectionLaunchers();
        
        initViews();
        setupListeners();
        
        // 检查并请求存储权限
        checkStoragePermission();
        
        // 检查当前USB连接状态
        checkExistingUsbConnection();
    }
    
    /**
     * 注册USB广播接收器
     */
    private void registerUsbReceiver() {
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.d(TAG, "USB广播: " + action);

                if (ACTION_USB_PERMISSION.equals(action)) {
                    // USB权限响应
                    synchronized (this) {
                        boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                        Log.d(TAG, "USB权限" + (granted ? "已授予" : "被拒绝"));
                        addLog(granted ? "✅ USB权限已授予" : "❌ USB权限被拒绝");

                        if (granted) {
                            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                            UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                            
                            if (device != null) {
                                Log.d(TAG, "USB设备权限已授予，尝试连接");
                                addLog("🔄 USB设备权限已授予，开始连接...");
                                mainHandler.post(() -> attemptConnection());
                            } else if (accessory != null) {
                                Log.d(TAG, "USB Accessory权限已授予，尝试连接");
                                addLog("🔄 USB Accessory权限已授予，开始连接...");
                                mainHandler.post(() -> attemptConnection());
                            }
                        } else {
                            addLog("💡 请重新插拔USB数据线重试");
                            mainHandler.post(() -> {
                                Toast.makeText(UsbP2PActivity.this,
                                        "需要USB权限才能建立连接\n请重新插拔USB数据线", Toast.LENGTH_LONG).show();
                            });
                            // 不重置角色，继续尝试
                        }
                    }
                } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    // USB设备连接
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (device != null) {
                        Log.d(TAG, "检测到USB设备连接: " + device.getProductName());
                        addLog("🔌 检测到USB设备连接: " + device.getProductName());
                        mainHandler.post(() -> {
                            Toast.makeText(UsbP2PActivity.this,
                                    "检测到USB设备连接", Toast.LENGTH_SHORT).show();
                            if (roleSelected && !isConnecting) {
                                addLog("🔄 自动开始连接...");
                                attemptConnection();
                            } else if (!roleSelected) {
                                addLog("💡 请先选择角色(发送端/接收端)");
                            }
                        });
                    }
                } else if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action)) {
                    // USB Accessory连接
                    UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                    if (accessory != null) {
                        Log.d(TAG, "检测到USB Accessory连接: " + accessory.getModel());
                        addLog("🔌 检测到USB Accessory连接: " + accessory.getModel());
                        mainHandler.post(() -> {
                            Toast.makeText(UsbP2PActivity.this,
                                    "检测到USB Accessory连接", Toast.LENGTH_SHORT).show();
                            if (roleSelected && !isConnecting) {
                                addLog("🔄 自动开始连接...");
                                attemptConnection();
                            } else if (!roleSelected) {
                                addLog("💡 请先选择角色(发送端/接收端)");
                            }
                        });
                    }
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    // USB设备断开
                    Log.d(TAG, "USB设备断开");
                    addLog("❌ USB设备已断开");
                    mainHandler.post(() -> {
                        Toast.makeText(UsbP2PActivity.this,
                                "USB连接已断开", Toast.LENGTH_SHORT).show();
                        if (roleSelected) {
                            addLog("💡 角色保持不变，请重新连接USB");
                        }
                    });
                    // 不重置角色，保持选择状态
                    isConnecting = false;
                } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                    // USB Accessory断开
                    Log.d(TAG, "USB Accessory断开");
                    addLog("❌ USB Accessory已断开");
                    mainHandler.post(() -> {
                        Toast.makeText(UsbP2PActivity.this,
                                "USB连接已断开", Toast.LENGTH_SHORT).show();
                        if (roleSelected) {
                            addLog("💡 角色保持不变，请重新连接USB");
                        }
                    });
                    // 不重置角色，保持选择状态
                    isConnecting = false;
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
        
        Log.d(TAG, "USB广播接收器已注册");
    }
    
    /**
     * 检查现有USB连接
     */
    private void checkExistingUsbConnection() {
        mainHandler.postDelayed(() -> {
            if (usbManager == null) return;
            
            // 检查USB设备
            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            if (deviceList.size() > 0) {
                Log.d(TAG, "发现现有USB设备连接: " + deviceList.size() + " 个设备");
                addLog("✅ 检测到 " + deviceList.size() + " 个USB设备");
                Toast.makeText(this, "检测到USB设备，请选择角色后开始连接", Toast.LENGTH_SHORT).show();
            } else {
                // 没有检测到USB设备
                Log.d(TAG, "未检测到USB设备");
            }
            
            // 检查USB Accessory
            UsbAccessory[] accessories = usbManager.getAccessoryList();
            if (accessories != null && accessories.length > 0) {
                Log.d(TAG, "发现现有USB Accessory连接: " + accessories.length + " 个");
                addLog("✅ 检测到 " + accessories.length + " 个USB Accessory");
                Toast.makeText(this, "检测到USB Accessory，请选择角色后开始连接", Toast.LENGTH_SHORT).show();
            }
            
            // 如果既没有设备也没有Accessory，可能处于充电模式
            if (deviceList.size() == 0 && (accessories == null || accessories.length == 0)) {
                Log.d(TAG, "未检测到任何USB连接，可能处于充电模式");
                addLog("⚠️ 未检测到USB OTG连接");
                addLog("💡 如果已连接USB线，可能的原因:");
                addLog("  • 手机处于充电模式（需要关闭反向充电）");
                addLog("  • 使用的是仅充电线");
                addLog("  • 对方手机未开启本APP");
                addLog("  • 需要等待几秒让系统识别");
                
                // 延迟显示提示，给系统一些时间识别设备
                mainHandler.postDelayed(() -> {
                    // 再次检查
                    HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
                    UsbAccessory[] accs = usbManager.getAccessoryList();
                    
                    if (devices.size() == 0 && (accs == null || accs.length == 0)) {
                        addLog("❗ 持续未检测到USB设备");
                        addLog("🔧 建议操作:");
                        addLog("  1. 点击下方【去关闭反向充电】按钮");
                        addLog("  2. 在对方手机上也打开本APP");
                        addLog("  3. 重新插拔USB数据线");
                    }
                }, 3000); // 3秒后再次检查
            }
        }, 500);
    }
    
    /**
     * 请求USB权限
     */
    private boolean requestUsbPermission() {
        if (usbManager == null) {
            Log.e(TAG, "UsbManager未初始化");
            addLog("❌ UsbManager未初始化");
            return false;
        }
        
        addLog("🔍 检查USB权限...");
        
        // 检查Host模式设备
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        addLog(String.format("📱 发现 %d 个USB设备", deviceList.size()));
        
        for (UsbDevice device : deviceList.values()) {
            addLog(String.format("📱 设备: %s (VID: 0x%04X, PID: 0x%04X)", 
                device.getProductName() != null ? device.getProductName() : "Unknown",
                device.getVendorId(), 
                device.getProductId()));
                
            if (!usbManager.hasPermission(device)) {
                Log.d(TAG, "请求USB设备权限: " + device.getProductName());
                addLog("🔐 请求USB设备访问权限");
                addLog("💡 请在弹出的对话框中点击【允许】");
                addLog("⚠️ 这不是照片权限，是USB硬件访问权限！");
                
                // 在主线程显示更清晰的Toast提示
                mainHandler.post(() -> {
                    Toast.makeText(UsbP2PActivity.this, 
                        "即将弹出USB设备权限对话框\n请点击【允许】\n(这是USB硬件访问权限，不是照片权限)", 
                        Toast.LENGTH_LONG).show();
                });
                
                // 等待一下让Toast显示
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                usbManager.requestPermission(device, permissionIntent);
                isUsbPermissionRequested = true;
                return false; // 等待权限回调
            } else {
                addLog("✅ USB设备权限已授予");
            }
        }
        
        // 检查Accessory模式
        UsbAccessory[] accessories = usbManager.getAccessoryList();
        if (accessories != null && accessories.length > 0) {
            addLog(String.format("📱 发现 %d 个USB Accessory", accessories.length));
            UsbAccessory accessory = accessories[0];
            addLog(String.format("📱 Accessory: %s - %s", 
                accessory.getManufacturer(), 
                accessory.getModel()));
                
            if (!usbManager.hasPermission(accessory)) {
                Log.d(TAG, "请求USB Accessory权限: " + accessory.getModel());
                addLog("🔐 请求USB Accessory访问权限");
                addLog("💡 请在弹出的对话框中点击【允许】");
                addLog("⚠️ 这不是照片权限，是USB硬件访问权限！");
                
                // 在主线程显示更清晰的Toast提示
                mainHandler.post(() -> {
                    Toast.makeText(UsbP2PActivity.this, 
                        "即将弹出USB Accessory权限对话框\n请点击【允许】\n(这是USB硬件访问权限，不是照片权限)", 
                        Toast.LENGTH_LONG).show();
                });
                
                // 等待一下让Toast显示
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                usbManager.requestPermission(accessory, permissionIntent);
                isUsbPermissionRequested = true;
                return false; // 等待权限回调
            } else {
                addLog("✅ USB Accessory权限已授予");
            }
        }
        
        if (deviceList.size() == 0 && (accessories == null || accessories.length == 0)) {
            addLog("⚠️ 未检测到USB连接");
            addLog("❗ 可能原因:");
            addLog("  1. USB数据线未连接");
            addLog("  2. 手机处于充电模式（需要关闭反向充电）");
            addLog("  3. 使用的是仅充电线（需要OTG数据线）");
            addLog("  4. 对方手机未选择角色");
            addLog("💡 解决方法:");
            addLog("  ✓ 请确保已关闭【反向充电】功能");
            addLog("  ✓ 使用支持OTG的数据线");
            addLog("  ✓ 确保对方已在APP中选择角色");
            
            mainHandler.post(() -> {
                Toast.makeText(UsbP2PActivity.this,
                    "⚠️ 未检测到USB设备\n\n请确保:\n" +
                    "1. 已关闭反向充电功能\n" +
                    "2. 使用支持OTG的数据线\n" +
                    "3. 对方已选择角色", 
                    Toast.LENGTH_LONG).show();
            });
            return false;
        }
        
        return true; // 已有权限
    }
    
    /**
     * 尝试连接（统一的连接入口）
     */
    private void attemptConnection() {
        if (isConnecting) {
            Log.d(TAG, "正在连接中，跳过重复尝试");
            return;
        }
        
        if (!roleSelected) {
            Log.d(TAG, "角色未选择，跳过连接尝试");
            return;
        }
        
        if (isSenderMode) {
            connectAsSender();
        } else if (isReceiverMode) {
            connectAsReceiver();
        }
    }
    
    /**
     * 初始化文件选择器
     */
    private void initFileSelectionLaunchers() {
        // 照片选择器 (支持多选)
        selectPhotosLauncher = registerForActivityResult(
            new ActivityResultContracts.GetMultipleContents(),
            uris -> {
                if (uris != null && !uris.isEmpty()) {
                    selectedFiles.clear();
                    selectedFiles.addAll(uris);
                    selectedFileType = "照片";
                    updateFileSelectionUI();
                }
            }
        );
        
        // 视频选择器 (支持多选)
        selectVideosLauncher = registerForActivityResult(
            new ActivityResultContracts.GetMultipleContents(),
            uris -> {
                if (uris != null && !uris.isEmpty()) {
                    selectedFiles.clear();
                    selectedFiles.addAll(uris);
                    selectedFileType = "视频";
                    updateFileSelectionUI();
                }
            }
        );
        
        // 文件选择器 (支持多选多种类型)
        selectFilesLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(),
            uris -> {
                if (uris != null && !uris.isEmpty()) {
                    selectedFiles.clear();
                    selectedFiles.addAll(uris);
                    selectedFileType = "文件";
                    updateFileSelectionUI();
                }
            }
        );
    }

    /**
     * 设置沉浸式状态栏
     */
    private void setupImmersiveStatusBar() {
        ThemeBars.apply(this);
    }

    /**
     * 初始化视图
     */
    private void initViews() {
        btnBack = findViewById(R.id.btnBack);
        
        // 角色选择卡片
        cardRoleSelection = findViewById(R.id.cardRoleSelection);
        cardSender = findViewById(R.id.cardSender);
        cardReceiver = findViewById(R.id.cardReceiver);
        tvSenderTitle = findViewById(R.id.tvSenderTitle);
        tvSenderDesc = findViewById(R.id.tvSenderDesc);
        tvReceiverTitle = findViewById(R.id.tvReceiverTitle);
        tvReceiverDesc = findViewById(R.id.tvReceiverDesc);
        
        // 文件选择卡片
        cardFileSelection = findViewById(R.id.cardFileSelection);
        tvFileSelectionStatus = findViewById(R.id.tvFileSelectionStatus);
        tvFileSelectionDetails = findViewById(R.id.tvFileSelectionDetails);
        btnSelectPhotos = findViewById(R.id.btnSelectPhotos);
        btnSelectVideos = findViewById(R.id.btnSelectVideos);
        btnSelectFiles = findViewById(R.id.btnSelectFiles);
        btnStartTransfer = findViewById(R.id.btnStartTransfer);
        
        // 日志显示
        cardDebugLog = findViewById(R.id.cardDebugLog);
        tvDebugLog = findViewById(R.id.tvDebugLog);
        tvLogStatus = findViewById(R.id.tvLogStatus);
        ivLogToggle = findViewById(R.id.ivLogToggle);
        logHeader = findViewById(R.id.logHeader);
        scrollViewLog = findViewById(R.id.scrollViewLog);
        btnClearLog = findViewById(R.id.btnClearLog);
        btnDisableOtgCharging = findViewById(R.id.btnDisableOtgCharging);
        btnCheckRndis = findViewById(R.id.btnCheckRndis);
        
        // 初始状态：显示角色选择，隐藏文件选择
        cardRoleSelection.setVisibility(View.VISIBLE);
        cardFileSelection.setVisibility(View.GONE);
        
        // 初始化日志
        addLog("📱 USB点对点传输已启动");
        addLog("💡 请先选择角色(发送端/接收端)");
    }

    /**
     * 设置监听器
     */
    private void setupListeners() {
        btnBack.setOnClickListener(v -> {
            finish();
        });
        
        // 日志标题栏点击 - 折叠/展开
        logHeader.setOnClickListener(v -> {
            toggleLogPanel();
        });
        
        // 清空日志按钮
        btnClearLog.setOnClickListener(v -> {
            logBuffer.setLength(0);
            tvDebugLog.setText("日志已清空\n");
            addLog("📝 日志已清空");
        });
        
        // 关闭反向充电按钮
        btnDisableOtgCharging.setOnClickListener(v -> {
            showDisableOtgChargingDialog();
        });
        
        // 检测RNDIS支持按钮
        btnCheckRndis.setOnClickListener(v -> {
            checkAllUsbCapabilities();
        });
        
        // 发送端卡片点击
        cardSender.setOnClickListener(v -> {
            selectSenderRole();
        });
        
        // 接收端卡片点击
        cardReceiver.setOnClickListener(v -> {
            selectReceiverRole();
        });
        
        // 照片选择按钮
        btnSelectPhotos.setOnClickListener(v -> {
            selectPhotosLauncher.launch("image/*");
        });
        
        // 视频选择按钮
        btnSelectVideos.setOnClickListener(v -> {
            selectVideosLauncher.launch("video/*");
        });
        
        // 文件选择按钮
        btnSelectFiles.setOnClickListener(v -> {
            selectFilesLauncher.launch(new String[]{"*/*"});
        });
        
        // 开始传输按钮
        btnStartTransfer.setOnClickListener(v -> {
            if (!selectedFiles.isEmpty()) {
                startTransfer();
            } else {
                Toast.makeText(this, "请先选择要传输的文件", Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    /**
     * 选择发送端角色
     */
    private void selectSenderRole() {
        if (roleSelected) {
            addLog("⚠️ 已选择角色，无法更改");
            return;
        }
        
        roleSelected = true;
        isSenderMode = true;
        isReceiverMode = false;
        
        Log.d(TAG, "用户选择：发送端模式");
        addLog("✅ 已选择角色：发送端");
        addLog("💡 请在对方手机选择【接收端】");
        addLog("🔌 然后连接USB数据线");
        
        Toast.makeText(this, "已选择：作为发送端\n请在对方手机选择【接收端】后，再连接USB数据线", 
            Toast.LENGTH_LONG).show();
        
        // 更新UI - 高亮选中的卡片
        cardSender.setCardBackgroundColor(getColor(R.color.radar_blue_primary));
        tvSenderTitle.setTextColor(getColor(android.R.color.white));
        tvSenderDesc.setTextColor(getColor(android.R.color.white));
        
        cardReceiver.setAlpha(0.5f);
        cardReceiver.setClickable(false);
        
        // 隐藏文件选择界面，等待连接成功后再显示
        cardFileSelection.setVisibility(View.GONE);
        
        // 延迟1秒后开始连接流程
        mainHandler.postDelayed(() -> {
            connectAsSender();
        }, 1000);
    }
    
    /**
     * 选择接收端角色
     */
    private void selectReceiverRole() {
        if (roleSelected) {
            addLog("⚠️ 已选择角色，无法更改");
            return;
        }
        
        roleSelected = true;
        isSenderMode = false;
        isReceiverMode = true;
        
        Log.d(TAG, "用户选择：接收端模式");
        addLog("✅ 已选择角色：接收端");
        addLog("💡 请在对方手机选择【发送端】");
        addLog("🔌 然后连接USB数据线");
        addLog("⏳ 等待发送端连接...");
        
        Toast.makeText(this, "已选择：作为接收端\n请在对方手机选择【发送端】后，再连接USB数据线", 
            Toast.LENGTH_LONG).show();
        
        // 更新UI - 高亮选中的卡片
        cardReceiver.setCardBackgroundColor(getColor(R.color.success));
        tvReceiverTitle.setTextColor(getColor(android.R.color.white));
        tvReceiverDesc.setTextColor(getColor(android.R.color.white));
        
        cardSender.setAlpha(0.5f);
        cardSender.setClickable(false);
        
        // 延迟1秒后开始连接流程
        mainHandler.postDelayed(() -> {
            connectAsReceiver();
        }, 1000);
    }
    
    /**
     * 作为发送端连接 (RNDIS模式)
     */
    private void connectAsSender() {
        if (isConnecting) {
            Log.d(TAG, "正在连接中，跳过");
            addLog("⏳ 正在连接中，请稍候...");
            return;
        }
        
        isConnecting = true;
        connectionRetryCount = 0;
        
        addLog("🌐 [发送端] 启动USB RNDIS网络连接...");
        Toast.makeText(this, "准备USB网络连接...\n请确保对方已选择【接收端】", Toast.LENGTH_LONG).show();
        
        // 在后台线程建立连接
        new Thread(() -> {
            try {
                // 1. 检测并建立网络连接
                addLog("🔍 检测USB网络...");
                int result = networkManager.establishConnection(true);
                
                if (result == -1) {
                    // 需要启用USB网络共享
                    mainHandler.post(() -> {
                        showEnableUsbNetworkDialog();
                    });
                    isConnecting = false;
                    return;
                } else if (result == 0) {
                    throw new Exception("USB网络连接失败");
                }
                
                addLog("✅ USB网络已建立");
                addLog("📡 本地IP: " + networkManager.getLocalIp());
                addLog("📡 对端IP: " + networkManager.getRemoteIp());
                
                // 3. 建立TCP连接
                addLog("🔌 建立TCP传输通道...");
                boolean transferOk = transferManager.connectAsClient(
                    networkManager.getRemoteIp(), 
                    networkManager.getTransferPort()
                );
                
                if (!transferOk) {
                    throw new Exception("TCP连接失败");
                }
                
                addLog("✅ TCP连接已建立");
                
                // 4. 执行握手
                addLog("🤝 开始握手...");
                boolean handshakeOk = transferManager.performHandshake(
                    android.os.Build.MODEL, true);
                
                if (!handshakeOk) {
                    throw new Exception("握手失败");
                }
                
                addLog("✅ 握手成功！");
                addLog("🎉 连接建立完成！");
                isConnecting = false;
                
                mainHandler.post(() -> {
                    Toast.makeText(UsbP2PActivity.this, 
                        "✓ USB网络连接成功！\n请选择要发送的文件", Toast.LENGTH_LONG).show();
                    cardFileSelection.setVisibility(View.VISIBLE);
                });
                
            } catch (Exception e) {
                Log.e(TAG, "发送端连接失败", e);
                addLog("❌ 连接失败: " + e.getMessage());
                isConnecting = false;
                
                mainHandler.post(() -> {
                    Toast.makeText(UsbP2PActivity.this, 
                        "连接失败: " + e.getMessage() + "\n将在3秒后自动重试", Toast.LENGTH_LONG).show();
                });
                
                // 自动重试
                mainHandler.postDelayed(() -> {
                    if (isSenderMode && roleSelected) {
                        addLog("🔄 自动重试连接...");
                        connectAsSender();
                    }
                }, 3000);
            }
        }).start();
    }
    
    /**
     * 作为接收端连接 (RNDIS模式)
     */
    private void connectAsReceiver() {
        if (isConnecting) {
            Log.d(TAG, "正在连接中，跳过");
            addLog("⏳ 正在连接中，请稍候...");
            return;
        }
        
        isConnecting = true;
        connectionRetryCount = 0;
        
        addLog("🌐 [接收端] 启动USB RNDIS网络连接...");
        Toast.makeText(this, "准备USB网络连接...\n请确保对方已选择【发送端】", Toast.LENGTH_LONG).show();
        
        // 在后台线程建立连接
        new Thread(() -> {
            try {
                // 1. 检测并建立网络连接
                addLog("🔍 检测USB网络...");
                int result = networkManager.establishConnection(false);
                
                if (result == -1) {
                    // 需要启用USB网络共享
                    mainHandler.post(() -> {
                        showEnableUsbNetworkDialog();
                    });
                    isConnecting = false;
                    return;
                } else if (result == 0) {
                    throw new Exception("USB网络连接失败");
                }
                
                addLog("✅ USB网络已建立");
                addLog("📡 本地IP: " + networkManager.getLocalIp());
                addLog("📡 对端IP: " + networkManager.getRemoteIp());
                
                // 3. 启动TCP服务器
                addLog("🔌 启动TCP传输服务器...");
                boolean serverOk = transferManager.startServer(networkManager.getTransferPort());
                
                if (!serverOk) {
                    throw new Exception("TCP服务器启动失败");
                }
                
                addLog("✅ TCP服务器已启动，等待客户端连接...");
                
                // 4. 执行握手
                addLog("🤝 开始握手...");
                boolean handshakeOk = transferManager.performHandshake(
                    android.os.Build.MODEL, false);
                
                if (!handshakeOk) {
                    throw new Exception("握手失败");
                }
                
                addLog("✅ 握手成功！");
                addLog("🎉 连接建立完成！");
                addLog("📥 启动接收界面...");
                isConnecting = false;
                
                mainHandler.post(() -> {
                    Toast.makeText(UsbP2PActivity.this, 
                        "✓ USB网络连接成功！\n启动接收界面...", Toast.LENGTH_SHORT).show();
                    startReceiverActivity();
                });
                
            } catch (Exception e) {
                Log.e(TAG, "接收端连接失败", e);
                addLog("❌ 连接失败: " + e.getMessage());
                isConnecting = false;
                
                mainHandler.post(() -> {
                    Toast.makeText(UsbP2PActivity.this, 
                        "连接失败: " + e.getMessage() + "\n将在3秒后自动重试", Toast.LENGTH_LONG).show();
                });
                
                // 自动重试
                mainHandler.postDelayed(() -> {
                    if (isReceiverMode && roleSelected) {
                        addLog("🔄 自动重试连接...");
                        connectAsReceiver();
                    }
                }, 3000);
            }
        }).start();
    }
    
    /**
     * 重置角色选择
     */
    private void resetRoleSelection() {
        roleSelected = false;
        isSenderMode = false;
        isReceiverMode = false;
        isConnecting = false;
        connectionRetryCount = 0;
        isUsbPermissionRequested = false;
        
        // 恢复卡片状态 - 需要检查是否已渲染成渐变背景
        // 如果是渐变背景，需要重新应用
        cardSender.setCardBackgroundColor(getColor(android.R.color.transparent));
        tvSenderTitle.setTextColor(getColor(android.R.color.white));
        tvSenderDesc.setTextColor(getColor(android.R.color.white));
        cardSender.setAlpha(1.0f);
        cardSender.setClickable(true);
        
        cardReceiver.setCardBackgroundColor(getColor(android.R.color.transparent));
        tvReceiverTitle.setTextColor(getColor(android.R.color.white));
        tvReceiverDesc.setTextColor(getColor(android.R.color.white));
        cardReceiver.setAlpha(1.0f);
        cardReceiver.setClickable(true);
        
        // 隐藏文件选择
        cardFileSelection.setVisibility(View.GONE);
        
        // 断开连接
        if (transferManager != null) {
            transferManager.disconnect();
        }
    }
    
    /**
     * 启动接收端Activity
     */
    private void startReceiverActivity() {
        Intent intent = new Intent(this, UsbReceiverActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }
    
    /**
     * 更新文件选择UI
     */
    private void updateFileSelectionUI() {
        if (!selectedFiles.isEmpty()) {
            tvFileSelectionStatus.setText("已选择 " + selectedFiles.size() + " 个" + selectedFileType);
            tvFileSelectionDetails.setText(
                String.format("已选择 %d 个%s，点击下方按钮开始传输", selectedFiles.size(), selectedFileType)
            );
            btnStartTransfer.setEnabled(true);
            Log.d(TAG, "已选择 " + selectedFiles.size() + " 个" + selectedFileType);
        } else {
            tvFileSelectionStatus.setText("未选择文件");
            tvFileSelectionDetails.setText("请选择要传输的照片、视频或文件");
            btnStartTransfer.setEnabled(false);
        }
    }
    
    /**
     * 开始传输
     */
    private void startTransfer() {
        if (!isSenderMode || !transferManager.isConnected()) {
            Toast.makeText(this, "请先建立USB连接", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, "请先选择文件", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 启动传输Activity
        Intent intent = new Intent(this, UsbTransferActivity.class);
        intent.putExtra("is_real_usb", true);
        intent.putExtra("file_count", selectedFiles.size());
        intent.putExtra("file_type", selectedFileType);
        
        // 传递文件URI列表
        ArrayList<String> fileUris = new ArrayList<>();
        for (Uri uri : selectedFiles) {
            fileUris.add(uri.toString());
        }
        intent.putStringArrayListExtra("file_uris", fileUris);
        
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }
    
    /**
     * 检查并请求存储权限
     */
    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> permissionsNeeded = new ArrayList<>();
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ 使用新的媒体权限
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) 
                        != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(Manifest.permission.READ_MEDIA_IMAGES);
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) 
                        != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(Manifest.permission.READ_MEDIA_VIDEO);
                }
            } else {
                // Android 12 及以下使用旧的权限
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                        != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                        != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                }
            }
            
            if (!permissionsNeeded.isEmpty()) {
                ActivityCompat.requestPermissions(this, 
                    permissionsNeeded.toArray(new String[0]), 
                    REQUEST_CODE_STORAGE_PERMISSION);
            }
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == REQUEST_CODE_STORAGE_PERMISSION) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (!allGranted) {
                Toast.makeText(this, "需要存储权限才能传输文件", Toast.LENGTH_LONG).show();
            }
        }
    }
    
    /**
     * 添加日志
     */
    private void addLog(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String logLine = "[" + timestamp + "] " + message + "\n";
        
        mainHandler.post(() -> {
            logBuffer.append(logLine);
            
            // 保留最近200行日志
            String[] lines = logBuffer.toString().split("\n");
            if (lines.length > 200) {
                logBuffer.setLength(0);
                for (int i = lines.length - 200; i < lines.length; i++) {
                    logBuffer.append(lines[i]).append("\n");
                }
            }
            
            tvDebugLog.setText(logBuffer.toString());
            
            // 自动滚动到底部
            tvDebugLog.post(() -> {
                if (cardDebugLog.getParent() instanceof ScrollView) {
                    ((ScrollView) cardDebugLog.getParent()).fullScroll(View.FOCUS_DOWN);
                }
            });
        });
        
        // 同时输出到logcat
        Log.d(TAG, message);
    }
    
    /**
     * 折叠/展开日志面板
     */
    private void toggleLogPanel() {
        if (isLogExpanded) {
            // 折叠
            scrollViewLog.setVisibility(View.GONE);
            ivLogToggle.setImageResource(R.drawable.ic_arrow_down);
            tvLogStatus.setText("点击展开");
            isLogExpanded = false;
        } else {
            // 展开
            scrollViewLog.setVisibility(View.VISIBLE);
            ivLogToggle.setImageResource(R.drawable.ic_arrow_up);
            tvLogStatus.setText("点击折叠");
            isLogExpanded = true;
        }
    }
    
    /**
     * 检测所有USB能力
     */
    private void checkAllUsbCapabilities() {
        addLog("🔍 开始全面检测USB能力...");
        Toast.makeText(this, "正在检测USB能力，请稍候...", Toast.LENGTH_SHORT).show();
        
        // 在后台线程执行检测
        new Thread(() -> {
            try {
                UsbCapabilityDetector detector = new UsbCapabilityDetector(this);
                UsbCapabilityDetector.DetectionResult result = detector.detectAllCapabilities();
                
                // 在主线程更新UI
                mainHandler.post(() -> {
                    showCapabilityDetectionResult(result);
                });
                
            } catch (Exception e) {
                Log.e(TAG, "USB能力检测失败", e);
                mainHandler.post(() -> {
                    addLog("❌ 检测失败: " + e.getMessage());
                    Toast.makeText(UsbP2PActivity.this, 
                        "检测失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
    
    /**
     * 显示能力检测结果
     */
    private void showCapabilityDetectionResult(UsbCapabilityDetector.DetectionResult result) {
        // 添加到日志
        addLog("=== USB能力检测完成 ===");
        addLog("");
        
        // 构建报告
        StringBuilder report = new StringBuilder();
        report.append("📱 设备信息\n");
        report.append("━━━━━━━━━━━━━━━━━━━━\n");
        report.append("厂商: ").append(Build.MANUFACTURER).append("\n");
        report.append("型号: ").append(Build.MODEL).append("\n");
        report.append("Android: ").append(Build.VERSION.RELEASE).append("\n\n");
        
        report.append("🔍 检测结果\n");
        report.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        // USB Host
        report.append("USB Host/OTG: ");
        report.append(result.usbHost.supported ? "✓ 支持" : "✗ 不支持").append("\n");
        if (result.usbHost.supported) {
            report.append("  - 硬件特性: ").append(result.usbHost.hasHostFeature ? "✓" : "✗").append("\n");
            report.append("  - 内核支持: ").append(result.usbHost.kernelOtgSupport ? "✓" : "✗").append("\n");
            report.append("  - 已连接设备: ").append(result.usbHost.connectedDevices).append("\n");
        }
        report.append("\n");
        
        // USB Accessory
        report.append("USB Accessory: ");
        report.append(result.usbAccessory.supported ? "✓ 支持" : "✗ 不支持").append("\n");
        if (result.usbAccessory.deprecated) {
            report.append("  ⚠️  已废弃 (Android 12+)\n");
        }
        if (result.usbAccessory.supported) {
            report.append("  - AOA协议: ").append(result.usbAccessory.aoaSupport ? "✓" : "✗").append("\n");
        }
        report.append("\n");
        
        // USB RNDIS
        report.append("USB RNDIS网络: ");
        report.append(result.usbRndis.supported ? "✓ 支持" : "✗ 不支持").append("\n");
        report.append("  ⚠️  仅支持连接电脑\n");
        report.append("  ✗ 手机对手机不可用\n");
        if (result.usbRndis.hasUsbInterface) {
            report.append("  - 网络接口: ").append(result.usbRndis.interfaceName).append("\n");
        }
        report.append("\n");
        
        // USB Serial
        report.append("USB串口: ");
        report.append(result.usbSerial.supported ? "✓ 支持" : "✗ 不支持").append("\n");
        if (result.usbSerial.supported) {
            report.append("  ⚠️  需要Root权限\n");
            report.append("  - 串口设备: ").append(result.usbSerial.serialDevices.size()).append("\n");
        }
        report.append("\n");
        
        // Root
        report.append("Root权限: ");
        report.append(result.hasRoot ? "✓ 已Root" : "✗ 未Root").append("\n\n");
        
        // 推荐方案
        report.append(result.recommendedSolution);
        
        // 显示对话框
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("USB能力检测报告")
            .setMessage(report.toString())
            .setPositiveButton("知道了", null)
            .setNeutralButton("使用WiFi Direct", (dialog, which) -> {
                Toast.makeText(this, "WiFi Direct功能开发中...", Toast.LENGTH_SHORT).show();
                // TODO: 跳转到WiFi Direct
            })
            .show();
        
        // 添加到日志
        addLog(report.toString());
    }
    
    /**
     * 检测RNDIS支持
     */
    private void checkRndisSupport() {
        addLog("🔍 开始检测USB RNDIS支持...");
        Toast.makeText(this, "正在检测RNDIS支持，请稍候...", Toast.LENGTH_SHORT).show();
        
        // 在后台线程执行检测
        new Thread(() -> {
            try {
                UsbRndisManager.RndisCheckResult result = rndisManager.checkRndisSupport();
                
                // 在主线程更新UI
                mainHandler.post(() -> {
                    showRndisCheckResult(result);
                });
                
            } catch (Exception e) {
                Log.e(TAG, "RNDIS检测失败", e);
                mainHandler.post(() -> {
                    addLog("❌ RNDIS检测失败: " + e.getMessage());
                    Toast.makeText(UsbP2PActivity.this, 
                        "检测失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
    
    /**
     * 显示RNDIS检测结果
     */
    private void showRndisCheckResult(UsbRndisManager.RndisCheckResult result) {
        // 添加到日志
        addLog("=== RNDIS检测完成 ===");
        addLog(result.getSummary());
        addLog("");
        
        // 显示详细报告对话框
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("USB RNDIS 支持检测")
            .setMessage(result.getDetailedReport())
            .setPositiveButton("知道了", null)
            .setNeutralButton(result.supported ? "使用RNDIS模式" : "使用旧模式", (dialog, which) -> {
                if (result.supported) {
                    // TODO: 切换到RNDIS模式
                    Toast.makeText(UsbP2PActivity.this, 
                        "RNDIS模式开发中，敬请期待！", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(UsbP2PActivity.this, 
                        "继续使用当前模式", Toast.LENGTH_SHORT).show();
                }
            })
            .show();
        
        // 显示Toast总结
        if (result.supported) {
            Toast.makeText(this, 
                "✓ 您的设备支持USB RNDIS网络\n级别: " + result.supportLevel + 
                "\n可以使用高速USB网络传输！", 
                Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, 
                "✗ 您的设备不支持USB RNDIS\n" + result.errorMessage, 
                Toast.LENGTH_LONG).show();
        }
    }
    
    /**
     * 显示启用USB网络共享的对话框
     */
    private void showEnableUsbNetworkDialog() {
        String guide = networkManager.getUsbTetheringGuide();
        
        addLog("⚠️ 需要启用USB网络共享");
        addLog("📱 " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚠️ 请先启用USB网络共享")
            .setMessage("检测到USB已连接，但未开启USB网络共享功能。\n\n" + 
                       "请按以下步骤操作：\n\n" + guide + "\n\n" +
                       "开启后，点击【已开启】重新连接")
            .setPositiveButton("已开启", (dialog, which) -> {
                addLog("🔄 用户已开启USB网络共享，重试连接...");
                if (isSenderMode) {
                    connectAsSender();
                } else if (isReceiverMode) {
                    connectAsReceiver();
                }
            })
            .setNegativeButton("查看详情", (dialog, which) -> {
                showDetailedUsbNetworkGuide();
            })
            .setNeutralButton("取消", null)
            .setCancelable(false)
            .show();
    }
    
    /**
     * 显示详细的USB网络共享指南
     */
    private void showDetailedUsbNetworkGuide() {
        String detailedGuide = "📱 如何开启USB网络共享\n\n" +
                              "=== 小米/Redmi ===\n" +
                              "设置 → 连接与共享 → USB网络共享\n\n" +
                              "=== vivo/iQOO ===\n" +
                              "设置 → 其他网络与连接 → 个人热点 → USB网络共享\n\n" +
                              "=== OPPO/一加/真我 ===\n" +
                              "设置 → 连接与共享 → USB网络共享\n\n" +
                              "=== 华为/荣耀 ===\n" +
                              "设置 → 移动网络 → 个人热点 → USB共享网络\n\n" +
                              "=== 三星 ===\n" +
                              "设置 → 连接 → 移动热点和网络共享 → USB网络共享\n\n" +
                              "⚠️ 重要提示：\n" +
                              "1. 确保USB数据线已连接\n" +
                              "2. 两台手机都需要开启USB网络共享\n" +
                              "3. 开启后会看到一个USB图标在状态栏\n" +
                              "4. 如找不到选项，在设置中搜索\"USB网络\"";
        
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("详细操作指南")
            .setMessage(detailedGuide)
            .setPositiveButton("我知道了", (dialog, which) -> {
                showEnableUsbNetworkDialog();
            })
            .show();
    }
    
    /**
     * 显示关闭反向充电的对话框
     */
    private void showDisableOtgChargingDialog() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        String brand = Build.BRAND.toLowerCase();
        
        addLog("📱 检测到手机品牌: " + Build.MANUFACTURER + " " + Build.BRAND);
        
        String title;
        String message;
        String settingPath;
        
        // 根据不同品牌提供不同的说明
        if (manufacturer.contains("xiaomi") || brand.contains("xiaomi") || 
            manufacturer.contains("redmi") || brand.contains("redmi")) {
            title = "小米/Redmi 手机";
            message = "请按以下步骤关闭反向充电：\n\n" +
                     "1. 打开【设置】\n" +
                     "2. 进入【电池】\n" +
                     "3. 找到【反向充电】或【OTG充电】\n" +
                     "4. 关闭该功能\n\n" +
                     "点击【去设置】将跳转到电池设置页面";
            settingPath = "battery";
        } else if (manufacturer.contains("vivo") || brand.contains("vivo") ||
                   manufacturer.contains("iqoo") || brand.contains("iqoo")) {
            title = "vivo/iQOO 手机";
            message = "请按以下步骤关闭反向充电：\n\n" +
                     "1. 打开【设置】\n" +
                     "2. 进入【电池】\n" +
                     "3. 找到【OTG】或【反向充电】\n" +
                     "4. 关闭该功能\n\n" +
                     "点击【去设置】将跳转到电池设置页面";
            settingPath = "battery";
        } else if (manufacturer.contains("oppo") || brand.contains("oppo") ||
                   manufacturer.contains("oneplus") || brand.contains("oneplus")) {
            title = "OPPO/一加 手机";
            message = "请按以下步骤关闭反向充电：\n\n" +
                     "1. 打开【设置】\n" +
                     "2. 进入【电池】\n" +
                     "3. 找到【OTG连接】\n" +
                     "4. 关闭该功能\n\n" +
                     "点击【去设置】将跳转到电池设置页面";
            settingPath = "battery";
        } else if (manufacturer.contains("huawei") || brand.contains("huawei") ||
                   manufacturer.contains("honor") || brand.contains("honor")) {
            title = "华为/荣耀 手机";
            message = "请按以下步骤关闭反向充电：\n\n" +
                     "1. 打开【设置】\n" +
                     "2. 进入【电池】\n" +
                     "3. 找到【无线反向充电】或【OTG】\n" +
                     "4. 关闭该功能\n\n" +
                     "点击【去设置】将跳转到电池设置页面";
            settingPath = "battery";
        } else if (manufacturer.contains("samsung") || brand.contains("samsung")) {
            title = "三星 手机";
            message = "请按以下步骤关闭反向充电：\n\n" +
                     "1. 打开【设置】\n" +
                     "2. 进入【电池和设备维护】\n" +
                     "3. 进入【电池】\n" +
                     "4. 找到【无线电源共享】\n" +
                     "5. 关闭该功能\n\n" +
                     "点击【去设置】将跳转到电池设置页面";
            settingPath = "battery";
        } else {
            title = "关闭反向充电";
            message = "请按以下步骤关闭反向充电：\n\n" +
                     "1. 打开【设置】\n" +
                     "2. 进入【电池】设置\n" +
                     "3. 查找【反向充电】、【OTG充电】或类似选项\n" +
                     "4. 关闭该功能\n\n" +
                     "点击【去设置】将跳转到设置页面";
            settingPath = "settings";
        }
        
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("去设置", (dialog, which) -> {
                openBatterySettings(settingPath);
            })
            .setNegativeButton("取消", null)
            .setNeutralButton("查看详细说明", (dialog, which) -> {
                showDetailedInstructions();
            })
            .show();
    }
    
    /**
     * 打开电池设置页面
     */
    private void openBatterySettings(String type) {
        try {
            Intent intent;
            if ("battery".equals(type)) {
                // 尝试打开电池设置
                intent = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
            } else {
                // 打开通用设置
                intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
            }
            startActivity(intent);
            addLog("✅ 已跳转到设置页面");
            addLog("💡 请在设置中关闭反向充电功能");
            Toast.makeText(this, "请在电池设置中关闭反向充电功能", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "无法打开设置页面", e);
            addLog("❌ 无法打开设置: " + e.getMessage());
            
            // 备用方案：打开通用设置
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
                startActivity(intent);
                Toast.makeText(this, "请手动进入电池设置关闭反向充电", Toast.LENGTH_LONG).show();
            } catch (Exception e2) {
                Toast.makeText(this, "请手动打开设置 → 电池 → 关闭反向充电", Toast.LENGTH_LONG).show();
            }
        }
    }
    
    /**
     * 显示详细说明
     */
    private void showDetailedInstructions() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        String brand = Build.BRAND.toLowerCase();
        
        String instructions = "📱 各品牌手机关闭反向充电步骤：\n\n" +
                            "【小米/Redmi】\n" +
                            "设置 → 电池 → 反向充电 → 关闭\n\n" +
                            "【vivo/iQOO】\n" +
                            "设置 → 电池 → OTG/反向充电 → 关闭\n\n" +
                            "【OPPO/一加】\n" +
                            "设置 → 电池 → OTG连接 → 关闭\n\n" +
                            "【华为/荣耀】\n" +
                            "设置 → 电池 → 无线反向充电/OTG → 关闭\n\n" +
                            "【三星】\n" +
                            "设置 → 电池和设备维护 → 电池 → 无线电源共享 → 关闭\n\n" +
                            "⚠️ 注意事项：\n" +
                            "1. 必须在两台手机都关闭反向充电\n" +
                            "2. 关闭后重新连接USB数据线\n" +
                            "3. 使用支持数据传输的OTG线";
        
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("详细说明")
            .setMessage(instructions)
            .setPositiveButton("我知道了", null)
            .show();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // 注销广播接收器
        if (usbReceiver != null) {
            try {
                unregisterReceiver(usbReceiver);
                Log.d(TAG, "USB广播接收器已注销");
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "广播接收器未注册");
            }
        }
        
        // 断开USB连接
        if (transferManager != null) {
            transferManager.disconnect();
        }
        
        // 取消所有pending的Handler任务
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        
        // 重置连接状态
        isConnecting = false;
        connectionRetryCount = 0;
    }
}
