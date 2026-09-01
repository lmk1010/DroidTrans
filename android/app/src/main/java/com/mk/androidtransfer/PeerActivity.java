package com.mk.androidtransfer;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.mk.androidtransfer.network.PeerServer;

import java.io.File;
import java.util.Locale;

/**
 * 手机互传。
 *
 * <p>现有协议是 HTTP：一侧当服务端，另一侧当客户端。两台手机之间没有天然的服务端，
 * 所以这一屏第一件事就是问清楚「这次你是发还是收」——
 * 收的那台跑起 PeerServer 并广播 Bonjour，发的那台走原来那套扫描流程。
 *
 * <p>发送方一行都不用改：接收方通告的是同一个 _droidtrans._tcp、说的是同一套 HTTP 口，
 * 扫描列表把它当成一台「电脑」照常连。
 */
public class PeerActivity extends AppCompatActivity {

    public static final String EXTRA_KIND = "kind";
    public static final String KIND_IPHONE = "iphone";
    public static final String KIND_ANDROID = "android";

    private PeerServer server;
    private String kind = KIND_ANDROID;

    private View roleGroup;
    private View recvGroup;
    private TextView recvCode;
    private TextView recvAddr;
    private TextView recvName;
    private TextView recvError;
    private TextView recvGotLabel;
    private LinearLayout recvList;

    private int gotCount;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        setContentView(R.layout.activity_peer);
        applyInsets();

        if (getIntent() != null && getIntent().getStringExtra(EXTRA_KIND) != null) {
            kind = getIntent().getStringExtra(EXTRA_KIND);
        }
        server = new PeerServer(this);

        roleGroup = findViewById(R.id.roleGroup);
        recvGroup = findViewById(R.id.recvGroup);
        recvCode = findViewById(R.id.recvCode);
        recvAddr = findViewById(R.id.recvAddr);
        recvName = findViewById(R.id.recvName);
        recvError = findViewById(R.id.recvError);
        recvGotLabel = findViewById(R.id.recvGotLabel);
        recvList = findViewById(R.id.recvList);

        boolean iphone = KIND_IPHONE.equals(kind);
        ((ImageView) findViewById(R.id.roleIcon))
                .setImageResource(iphone ? R.drawable.art_phone : R.drawable.art_android);
        ((TextView) findViewById(R.id.roleTitle))
                .setText(iphone ? R.string.start_iphone : R.string.start_android);

        bindCard(R.id.cardSend, R.drawable.art_link,
                R.string.peer_send, R.string.peer_send_sub,
                v -> startActivity(new Intent(this, ConnectActivity.class)));

        bindCard(R.id.cardRecv, R.drawable.art_inbox,
                R.string.peer_recv, R.string.peer_recv_sub, v -> startReceiving());

        findViewById(R.id.btnHome).setOnClickListener(v -> finish());
        findViewById(R.id.btnStop).setOnClickListener(v -> stopReceiving());
    }

    // ------------------------------------------------------------------ 接收

    private void startReceiving() {
        gotCount = 0;
        recvList.removeAllViews();
        recvGotLabel.setVisibility(View.GONE);
        recvError.setVisibility(View.GONE);

        server.start(deviceName(), new PeerServer.Listener() {
            @Override
            public void onStateChanged(boolean running, String error) {
                if (error != null) {
                    recvError.setText(error);
                    recvError.setVisibility(View.VISIBLE);
                }
                if (running) {
                    recvCode.setText(spaced(server.getPairingCode()));
                    String ip = server.localIp();
                    if (ip != null) {
                        recvAddr.setText(ip + ":" + server.getBoundPort());
                    } else {
                        recvAddr.setText(R.string.peer_need_wifi);
                    }
                }
            }

            @Override
            public void onFileReceived(String name, long size, File file) {
                addReceivedRow(name);
            }

            @Override
            public void onKnock(String who) {
                askApproval(who);
            }
        });

        recvName.setText(deviceName());
        roleGroup.setVisibility(View.GONE);
        recvGroup.setVisibility(View.VISIBLE);
        // 收东西的时候屏幕别灭 —— 灭屏之后 Wi-Fi 会进省电，传一半可能断
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void stopReceiving() {
        server.stop();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        recvGroup.setVisibility(View.GONE);
        roleGroup.setVisibility(View.VISIBLE);
    }

    /**
     * 有人在敲门。把关就在这一下 ——
     * 设备名是对面自己报的，只当提示看，真正决定开不开门的是这个人。
     *
     * <p>不可点外部取消：手一滑点到旁边就等于拒绝，对面那台会莫名其妙地失败。
     */
    private void askApproval(String who) {
        View body = getLayoutInflater().inflate(R.layout.dialog_knock, null, false);
        ((TextView) body.findViewById(R.id.knockName)).setText(who);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setView(body)
                .setCancelable(false)
                .setNegativeButton(R.string.peer_knock_deny, (d, w) -> server.deny())
                .setPositiveButton(R.string.peer_knock_allow, (d, w) -> server.approve())
                .show();
    }

    private void addReceivedRow(String name) {
        gotCount++;
        recvGotLabel.setText(getString(R.string.peer_got) + " " + gotCount);
        recvGotLabel.setVisibility(View.VISIBLE);

        TextView row = new TextView(this);
        row.setText("✓  " + name);
        row.setTextColor(getColor(R.color.ok));
        row.setTextSize(13f);
        row.setGravity(Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        recvList.addView(row, lp);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        server.stop();
    }

    // ------------------------------------------------------------------ 小工具

    private void bindCard(int rootId, int iconRes, int titleRes, int subRes,
                          View.OnClickListener onClick) {
        View card = findViewById(rootId);
        ((ImageView) card.findViewById(R.id.cardIcon)).setImageResource(iconRes);
        ((TextView) card.findViewById(R.id.cardTitle)).setText(titleRes);
        ((TextView) card.findViewById(R.id.cardSub)).setText(subRes);
        card.setOnClickListener(onClick);
    }

    private String deviceName() {
        String n = Build.MODEL;
        return n == null || n.isEmpty() ? "Android" : n;
    }

    /** 六位码分成两组三位，念给对面听的时候不容易串行 */
    private String spaced(String s) {
        if (s == null || s.length() != 6) {
            return s == null ? "" : s;
        }
        return String.format(Locale.US, "%s %s", s.substring(0, 3), s.substring(3));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

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
