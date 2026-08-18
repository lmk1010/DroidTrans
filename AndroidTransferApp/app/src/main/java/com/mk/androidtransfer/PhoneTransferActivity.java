package com.mk.androidtransfer;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsetsController;
import android.view.animation.DecelerateInterpolator;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.mk.androidtransfer.view.SimpleLineAnimationView;

/**
 * 手机互传选择页面
 * 提供USB点对点传输和无线点对点传输两种模式
 */
public class PhoneTransferActivity extends AppCompatActivity {

    private MaterialCardView cardUsb;
    private MaterialCardView cardWifi;
    private MaterialButton btnBack;
    private SimpleLineAnimationView lineAnimation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_phone_transfer);

        // 设置沉浸式状态栏
        setupImmersiveStatusBar();

        initViews();
        setupListeners();
        startAnimations();
    }

    /**
     * 设置沉浸式状态栏
     */
    private void setupImmersiveStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
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

    /**
     * 初始化视图
     */
    private void initViews() {
        cardUsb = findViewById(R.id.cardUsb);
        cardWifi = findViewById(R.id.cardWifi);
        btnBack = findViewById(R.id.btnBack);
        lineAnimation = findViewById(R.id.lineAnimation);
    }

    /**
     * 设置监听器
     */
    private void setupListeners() {
        // 返回按钮
        btnBack.setOnClickListener(v -> {
            finish();
            // 主题中已设置动画，不需要再调用
        });

        // USB点对点传输卡片
        cardUsb.setOnClickListener(v -> {
            // 禁用按钮避免重复点击
            cardUsb.setEnabled(false);
            
            // 停止动画
            if (lineAnimation != null) {
                lineAnimation.stopAnimation();
            }
            
            // 延迟启动新Activity，确保当前动画状态稳定
            cardUsb.postDelayed(() -> {
                try {
                    Intent intent = new Intent(PhoneTransferActivity.this, UsbP2PActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    startActivity(intent);
                    overridePendingTransition(0, 0);
                } catch (Exception e) {
                    e.printStackTrace();
                    cardUsb.setEnabled(true); // 发生错误时重新启用
                }
            }, 100);
        });

        // 无线点对点传输卡片
        cardWifi.setOnClickListener(v -> {
            // TODO: 实现无线点对点传输页面
            // Intent intent = new Intent(PhoneTransferActivity.this, WifiP2PActivity.class);
            // startActivity(intent);
        });
    }

    /**
     * 启动动画效果
     */
    private void startAnimations() {
        // 卡片从下方滑入并渐显
        cardUsb.setAlpha(0f);
        cardUsb.setTranslationY(100f);
        cardWifi.setAlpha(0f);
        cardWifi.setTranslationY(100f);

        // USB卡片动画
        ObjectAnimator usbAlpha = ObjectAnimator.ofFloat(cardUsb, "alpha", 0f, 1f);
        ObjectAnimator usbTranslation = ObjectAnimator.ofFloat(cardUsb, "translationY", 100f, 0f);
        AnimatorSet usbSet = new AnimatorSet();
        usbSet.playTogether(usbAlpha, usbTranslation);
        usbSet.setDuration(600);
        usbSet.setInterpolator(new DecelerateInterpolator());
        usbSet.setStartDelay(200);
        usbSet.start();

        // WiFi卡片动画
        ObjectAnimator wifiAlpha = ObjectAnimator.ofFloat(cardWifi, "alpha", 0f, 0.6f);
        ObjectAnimator wifiTranslation = ObjectAnimator.ofFloat(cardWifi, "translationY", 100f, 0f);
        AnimatorSet wifiSet = new AnimatorSet();
        wifiSet.playTogether(wifiAlpha, wifiTranslation);
        wifiSet.setDuration(600);
        wifiSet.setInterpolator(new DecelerateInterpolator());
        wifiSet.setStartDelay(300);
        wifiSet.start();

        // 线条动画自动启动
        if (lineAnimation != null) {
            lineAnimation.startAnimation();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 重新启用卡片点击
        if (cardUsb != null) {
            cardUsb.setEnabled(true);
        }
        if (cardWifi != null) {
            cardWifi.setEnabled(true);
        }
        
        // 延迟启动动画，避免与Activity转场冲突
        if (lineAnimation != null) {
            lineAnimation.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    lineAnimation.startAnimation();
                }
            }, 200);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (lineAnimation != null) {
            lineAnimation.stopAnimation();
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 清理动画资源
        if (lineAnimation != null) {
            lineAnimation.stopAnimation();
        }
    }
}

