package com.mk.androidtransfer;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.mk.androidtransfer.util.ThemeBars;
import com.mk.androidtransfer.view.DataTransferAnimationView;

/**
 * 首页仪表盘 - 三个入口卡片（USB / Wi-Fi / 手机互传）
 */
public class DashboardActivity extends AppCompatActivity {

    private View cardUsb;
    private View cardWifi;
    private View cardPhoneToPhone;
    private DataTransferAnimationView dataTransferAnimation;
    private TextView tvUsbStatus;
    private TextView tvWifiStatus;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        setupImmersiveStatusBar();
        initViews();
        setupClickListeners();
    }

    private void setupImmersiveStatusBar() {
        ThemeBars.apply(this);
    }

    private void initViews() {
        cardUsb = findViewById(R.id.cardUsb);
        cardWifi = findViewById(R.id.cardWifi);
        cardPhoneToPhone = findViewById(R.id.cardPhoneToPhone);
        dataTransferAnimation = findViewById(R.id.dataTransferAnimation);
        tvUsbStatus = findViewById(R.id.tvUsbStatus);
        tvWifiStatus = findViewById(R.id.tvWifiStatus);
    }

    private void setupClickListeners() {
        // USB 直连：进入USB直连引导页面
        cardUsb.setOnClickListener(v -> {
            v.setEnabled(false); // 防止重复点击
            startActivitySafely(UsbGuideActivity.class, v);
        });

        // Wi-Fi 传输：进入现有 Wi-Fi 扫描主界面
        cardWifi.setOnClickListener(v -> {
            v.setEnabled(false);
            startActivitySafely(MainActivity.class, v);
        });
        cardWifi.setOnLongClickListener(v -> {
            startActivity(new Intent(this, SpeedTestActivity.class));
            return true;
        });

        // 手机互传：进入手机互传选择页面
        cardPhoneToPhone.setOnClickListener(v -> {
            v.setEnabled(false); // 防止重复点击
            startActivitySafely(PhoneTransferActivity.class, v);
        });
    }
    
    private void startActivitySafely(Class<?> activityClass, View clickedView) {
        try {
            startActivity(new Intent(this, activityClass));
        } catch (Exception e) {
            clickedView.setEnabled(true);
            Toast.makeText(this, getString(R.string.cannot_open_page, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        
        // 重新启用所有卡片
        if (cardUsb != null) cardUsb.setEnabled(true);
        if (cardWifi != null) cardWifi.setEnabled(true);
        if (cardPhoneToPhone != null) cardPhoneToPhone.setEnabled(true);
        refreshEntryStatus();
        
        // 延迟启动动画，避免与Activity转场冲突
        if (dataTransferAnimation != null) {
            dataTransferAnimation.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    dataTransferAnimation.startAnimation();
                }
            }, 200);
        }
    }

    private void refreshEntryStatus() {
        if (tvUsbStatus != null) {
            boolean adbOn = Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
            tvUsbStatus.setText(adbOn ? R.string.usb_debug_on : R.string.usb_debug_off);
        }
        if (tvWifiStatus != null) {
            SharedPreferences prefs = getSharedPreferences("ServerCache", MODE_PRIVATE);
            String last = prefs.getString("last_name", "");
            if (last == null || last.isEmpty()) {
                last = prefs.getString("last_ip", "");
            }
            if (last != null && !last.isEmpty()) {
                tvWifiStatus.setText(getString(R.string.wifi_last, last));
            } else {
                tvWifiStatus.setText(R.string.wifi_will_search);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (dataTransferAnimation != null) {
            dataTransferAnimation.stopAnimation();
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (dataTransferAnimation != null) {
            dataTransferAnimation.stopAnimation();
        }
    }
}
