package com.mk.androidtransfer;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.mk.androidtransfer.view.UsbDebugAnimationView;
import com.mk.androidtransfer.util.ThemeBars;

/**
 * USB直连引导页面
 * 教学用户如何开启开发者模式和USB调试
 */
public class UsbGuideActivity extends AppCompatActivity {

    private UsbDebugAnimationView animationView;
    private TextView tvDeviceModel;
    private TextView tvDeviceBrand;
    private TextView tvDebugStatus;
    private View btnRefreshStatus;
    private View cardGenericSteps;
    private View cardBrandSpecific;
    
    private String deviceBrand;
    private String deviceModel;
    private boolean isDeveloperModeEnabled;
    private boolean isUsbDebuggingEnabled;
    private boolean isUsbConnected = false; // USB物理连接状态
    
    // 定时刷新状态
    private Handler statusCheckHandler;
    private Runnable statusCheckRunnable;
    
    // USB状态变化监听器
    private BroadcastReceiver usbReceiver;
    private BroadcastReceiver debugSettingsReceiver;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usb_guide);

        setupImmersiveStatusBar();
        initViews();
        detectDevice();
        setupClickListeners();
    }

    private void setupImmersiveStatusBar() {
        ThemeBars.apply(this);
    }

    private void initViews() {
        animationView = findViewById(R.id.usbDebugAnimation);
        tvDeviceModel = findViewById(R.id.tvDeviceModel);
        tvDeviceBrand = findViewById(R.id.tvDeviceBrand);
        tvDebugStatus = findViewById(R.id.tvDebugStatus);
        btnRefreshStatus = findViewById(R.id.btnRefreshStatus);
        cardGenericSteps = findViewById(R.id.cardGenericSteps);
        cardBrandSpecific = findViewById(R.id.cardBrandSpecific);
        
        // 初始化定时检测
        statusCheckHandler = new Handler(Looper.getMainLooper());
        statusCheckRunnable = new Runnable() {
            @Override
            public void run() {
                checkDebugStatus();
                // 每2秒检测一次
                statusCheckHandler.postDelayed(this, 2000);
            }
        };
        
        // 初始化USB状态监听器
        setupUsbStateReceiver();
    }

    private void detectDevice() {
        // 获取设备品牌和型号
        deviceBrand = Build.BRAND.toLowerCase();
        deviceModel = Build.MODEL;
        String manufacturer = Build.MANUFACTURER.toLowerCase();

        // 显示设备信息
        tvDeviceModel.setText(getString(R.string.usb_device_model, deviceModel));
        tvDeviceBrand.setText(getString(R.string.usb_device_brand, formatBrandName(deviceBrand)));

        // 检测开发者模式状态
        checkDebugStatus();
        
        // 根据品牌显示特定步骤
        updateStepsForBrand();
    }

    private String formatBrandName(String brand) {
        // 格式化品牌名称
        switch (brand) {
            case "xiaomi":
            case "redmi":
                return "小米 / Redmi";
            case "huawei":
                return "华为";
            case "honor":
                return "荣耀";
            case "oppo":
                return "OPPO";
            case "vivo":
                return "vivo";
            case "oneplus":
                return "OnePlus";
            case "samsung":
                return "三星";
            case "meizu":
                return "魅族";
            case "realme":
                return "realme";
            default:
                return brand.substring(0, 1).toUpperCase() + brand.substring(1);
        }
    }

    private void checkDebugStatus() {
        try {
            // 检查开发者模式是否开启
            isDeveloperModeEnabled = Settings.Secure.getInt(
                    getContentResolver(),
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                    0
            ) == 1;

            // 检查USB调试是否开启
            isUsbDebuggingEnabled = Settings.Secure.getInt(
                    getContentResolver(),
                    Settings.Global.ADB_ENABLED,
                    0
            ) == 1;

            // 检查USB物理连接状态
            checkUsbConnection();

            updateDebugStatus();
        } catch (Exception e) {
            e.printStackTrace();
            tvDebugStatus.setText(R.string.usb_status_unknown);
        }
    }
    
    /**
     * 检查USB物理连接状态
     */
    private void checkUsbConnection() {
        try {
            Intent intent = registerReceiver(null, new IntentFilter("android.hardware.usb.action.USB_STATE"));
            if (intent != null) {
                isUsbConnected = intent.getBooleanExtra("connected", false);
            }
        } catch (Exception e) {
            e.printStackTrace();
            // 如果无法检测，默认为未连接
            isUsbConnected = false;
        }
    }

    private void updateDebugStatus() {
        // 只有USB调试开启 AND USB线已连接时，才显示已连接
        boolean isFullyConnected = isUsbDebuggingEnabled && isUsbConnected;
        
        if (animationView != null) {
            animationView.setConnectedState(isFullyConnected);
        }
        
        if (isUsbDebuggingEnabled && isUsbConnected) {
            tvDebugStatus.setText(R.string.usb_status_enabled);
            tvDebugStatus.setTextColor(getColor(R.color.success));
        } else if (isUsbDebuggingEnabled && !isUsbConnected) {
            tvDebugStatus.setText("等待连接");
            tvDebugStatus.setTextColor(getColor(R.color.primary));
        } else if (isDeveloperModeEnabled) {
            tvDebugStatus.setText(R.string.usb_status_developer_only);
            tvDebugStatus.setTextColor(getColor(R.color.warning));
        } else {
            tvDebugStatus.setText(R.string.usb_status_disabled);
            tvDebugStatus.setTextColor(getColor(R.color.error));
        }
    }

    private void updateStepsForBrand() {
        // 根据品牌显示特定的开启步骤
        TextView tvBrandSteps = findViewById(R.id.tvBrandSpecificSteps);
        
        String brandSteps = getBrandSpecificSteps();
        if (brandSteps != null && !brandSteps.isEmpty()) {
            cardBrandSpecific.setVisibility(View.VISIBLE);
            tvBrandSteps.setText(brandSteps);
        } else {
            cardBrandSpecific.setVisibility(View.GONE);
        }
    }

    private String getBrandSpecificSteps() {
        switch (deviceBrand) {
            case "xiaomi":
            case "redmi":
                return getString(R.string.usb_steps_xiaomi);
            case "huawei":
                return getString(R.string.usb_steps_huawei);
            case "honor":
                return getString(R.string.usb_steps_honor);
            case "oppo":
                return getString(R.string.usb_steps_oppo);
            case "vivo":
                return getString(R.string.usb_steps_vivo);
            case "oneplus":
                return getString(R.string.usb_steps_oneplus);
            case "samsung":
                return getString(R.string.usb_steps_samsung);
            case "meizu":
                return getString(R.string.usb_steps_meizu);
            case "realme":
                return getString(R.string.usb_steps_realme);
            default:
                return null;
        }
    }

    private void setupClickListeners() {
        // 返回按钮
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // 刷新状态按钮
        btnRefreshStatus.setOnClickListener(v -> {
            checkDebugStatus();
            Toast.makeText(this, R.string.usb_status_refreshed, Toast.LENGTH_SHORT).show();
        });

        // 打开开发者选项
        findViewById(R.id.btnOpenDevSettings).setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(
                        Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                ));
            } catch (Exception e) {
                Toast.makeText(this, R.string.usb_cannot_open_dev_settings, Toast.LENGTH_SHORT).show();
            }
        });

        // 复制版本号点击位置提示
        findViewById(R.id.btnCopyBuildNumber).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Build Number Path", 
                    getString(R.string.usb_build_number_path));
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, R.string.usb_path_copied, Toast.LENGTH_SHORT).show();
        });
    }
    
    /**
     * 设置USB状态监听器 - 实时监听USB插拔和设置变化
     */
    private void setupUsbStateReceiver() {
        // USB连接状态监听器
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if ("android.hardware.usb.action.USB_STATE".equals(action)) {
                    // 立即获取USB连接状态
                    isUsbConnected = intent.getBooleanExtra("connected", false);
                    // 立即更新显示
                    checkDebugStatus();
                } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    // USB设备连接
                    isUsbConnected = true;
                    statusCheckHandler.postDelayed(() -> checkDebugStatus(), 200);
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    // USB设备断开
                    isUsbConnected = false;
                    checkDebugStatus();
                }
            }
        };
        
        // 开发者设置变化监听器
        debugSettingsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // 设置变化时立即检测
                checkDebugStatus();
            }
        };
    }
    
    /**
     * 注册广播接收器
     */
    private void registerReceivers() {
        // 注册USB状态监听
        IntentFilter usbFilter = new IntentFilter();
        usbFilter.addAction("android.hardware.usb.action.USB_STATE");
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, usbFilter);
        
        // 注册设置变化监听
        IntentFilter settingsFilter = new IntentFilter();
        settingsFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        registerReceiver(debugSettingsReceiver, settingsFilter);
    }
    
    /**
     * 注销广播接收器
     */
    private void unregisterReceivers() {
        try {
            if (usbReceiver != null) {
                unregisterReceiver(usbReceiver);
            }
            if (debugSettingsReceiver != null) {
                unregisterReceiver(debugSettingsReceiver);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (animationView != null) {
            animationView.startAnimation();
        }
        // 重新检查状态
        checkDebugStatus();
        // 启动定时检测
        if (statusCheckHandler != null && statusCheckRunnable != null) {
            statusCheckHandler.postDelayed(statusCheckRunnable, 2000);
        }
        // 注册广播接收器
        registerReceivers();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (animationView != null) {
            animationView.stopAnimation();
        }
        // 停止定时检测
        if (statusCheckHandler != null && statusCheckRunnable != null) {
            statusCheckHandler.removeCallbacks(statusCheckRunnable);
        }
        // 注销广播接收器
        unregisterReceivers();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 清理Handler
        if (statusCheckHandler != null && statusCheckRunnable != null) {
            statusCheckHandler.removeCallbacks(statusCheckRunnable);
        }
        // 确保注销广播接收器
        unregisterReceivers();
    }
}

