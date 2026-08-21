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
    private View cardReceive;
    private android.widget.TextView tvReceiveCardStatus;
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
        cardReceive = findViewById(R.id.cardReceive);
        tvReceiveCardStatus = findViewById(R.id.tvReceiveCardStatus);
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
        if (cardReceive != null) {
            cardReceive.setOnClickListener(v -> ReceiveActivity.open(this));
        }

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

    /**
     * 问一次电脑「有没有东西等着我取」。
     *
     * <p>以前电脑那边把文件放进队列后，手机上没有任何迹象，
     * 得自己进「电脑发来的」才知道。这里在打开 App 时顺手问一句，
     * 首页那行就能直接写出待取数量——不引入后台轮询。
     */
    private void refreshPendingCount() {
        if (tvReceiveCardStatus == null) {
            return;
        }
        android.content.SharedPreferences prefs =
                getSharedPreferences("ServerCache", MODE_PRIVATE);
        String ip = prefs.getString("last_ip", "");
        int port = prefs.getInt("last_port", 9500);
        if (ip.isEmpty()) {
            return;
        }
        final String url = "http://" + ip + ":" + port + "/api/wifi/info";
        new Thread(() -> {
            int count = -1;
            try {
                okhttp3.OkHttpClient c = new okhttp3.OkHttpClient.Builder()
                        .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                        .build();
                okhttp3.Request req = new okhttp3.Request.Builder().url(url).get().build();
                try (okhttp3.Response res = c.newCall(req).execute()) {
                    okhttp3.ResponseBody body = res.body();
                    if (res.isSuccessful() && body != null) {
                        count = new org.json.JSONObject(body.string()).optInt("outbox_count", 0);
                    }
                }
            } catch (Exception ignored) {
                // 连不上就保持原样，不打扰
            }
            final int n = count;
            runOnUiThread(() -> {
                if (n > 0) {
                    tvReceiveCardStatus.setText(getString(R.string.card_receive_pending, n));
                    tvReceiveCardStatus.setTextColor(getColor(R.color.primary));
                } else if (n == 0) {
                    tvReceiveCardStatus.setText(R.string.card_receive_subtitle);
                    tvReceiveCardStatus.setTextColor(getColor(R.color.text_medium_emphasis));
                }
            });
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPendingCount();
        
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
