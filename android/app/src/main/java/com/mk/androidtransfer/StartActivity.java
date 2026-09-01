package com.mk.androidtransfer;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 主界面：先选跟谁传，再进对应的连接流程。
 *
 * <p>以前 App 一打开就是 DashboardActivity —— 那一屏只管「和电脑传」，
 * 直接开始扫局域网。可这个 App 要做的是三件事：和电脑传、和 iPhone 传、和安卓机传。
 * 把三件事摆在一屏上，选哪件是用户的事。
 *
 * <p>这一屏还是整个 App 的家。各处的返回键都能回到这里。
 */
public class StartActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 深色底一直铺到屏幕边缘，中间夹一条灰色系统栏会把整屏切成两段
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        setContentView(R.layout.activity_start);
        applyInsets();

        bindCard(R.id.cardDesktop, R.drawable.art_laptop,
                R.string.start_desktop, R.string.start_desktop_sub,
                v -> startActivity(new Intent(this, ConnectActivity.class)));

        bindCard(R.id.cardIphone, R.drawable.art_phone,
                R.string.start_iphone, R.string.start_iphone_sub,
                v -> openPeer(PeerActivity.KIND_IPHONE));

        bindCard(R.id.cardAndroid, R.drawable.art_android,
                R.string.start_android, R.string.start_android_sub,
                v -> openPeer(PeerActivity.KIND_ANDROID));

        findViewById(R.id.btnSettings).setOnClickListener(
                v -> startActivity(new Intent(this, UploadHistoryActivity.class)));
    }

    private void openPeer(String kind) {
        Intent i = new Intent(this, PeerActivity.class);
        i.putExtra(PeerActivity.EXTRA_KIND, kind);
        startActivity(i);
    }

    /** 三张卡长得一样，用同一份 include 布局，这里只填内容和点击。 */
    private void bindCard(int rootId, int iconRes, int titleRes, int subRes,
                          View.OnClickListener onClick) {
        View card = findViewById(rootId);
        ((ImageView) card.findViewById(R.id.cardIcon)).setImageResource(iconRes);
        ((TextView) card.findViewById(R.id.cardTitle)).setText(titleRes);
        ((TextView) card.findViewById(R.id.cardSub)).setText(subRes);
        card.setOnClickListener(onClick);
    }

    /**
     * 内容自己躲开刘海和手势条。
     * 不躲的话标题会被状态栏压住，最下面那张卡会被手势条挡掉一截。
     */
    private void applyInsets() {
        View content = findViewById(R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            androidx.core.graphics.Insets bars =
                    insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
    }
}
