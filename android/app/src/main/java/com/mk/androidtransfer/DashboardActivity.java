package com.mk.androidtransfer;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.mk.androidtransfer.view.DataTransferAnimationView;

/**
 * 首页仪表盘 - 三个入口卡片（USB / Wi-Fi / 手机互传）
 */
public class DashboardActivity extends AppCompatActivity {

    private View cardUsb;
    private View cardWifi;
    private View cardPhoneToPhone;
    private DataTransferAnimationView dataTransferAnimation;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        setupImmersiveStatusBar();
        initViews();
        setupClickListeners();
    }

    private void setupImmersiveStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                // Dashboard 使用浅色背景，需要深色状态栏图标
                controller.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                );
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void initViews() {
        cardUsb = findViewById(R.id.cardUsb);
        cardWifi = findViewById(R.id.cardWifi);
        cardPhoneToPhone = findViewById(R.id.cardPhoneToPhone);
        dataTransferAnimation = findViewById(R.id.dataTransferAnimation);
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
        
        // 延迟启动动画，避免与Activity转场冲突
        if (dataTransferAnimation != null) {
            dataTransferAnimation.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    dataTransferAnimation.startAnimation();
                }
            }, 200);
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
