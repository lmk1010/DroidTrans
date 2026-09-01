package com.mk.androidtransfer;

import android.content.Intent;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
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
    private View connectedGroup;
    private View recvGroup;
    private View doneGroup;
    private TextView recvCode;
    private TextView recvAddr;
    private TextView recvName;
    private TextView recvStatus;
    private TextView connectedName;
    private TextView recvError;
    private TextView recvGotLabel;
    private TextView recvBytesLabel;
    private TextView doneSummary;
    private TextView doneImages;
    private TextView doneVideos;
    private TextView doneFiles;
    private TextView doneText;
    private ImageView receiveArt;
    private AnimatorSet receiveAnimator;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable finishAfterIdle = this::finishReceiving;

    private int gotCount;
    private long gotBytes;
    private int imageCount;
    private int videoCount;
    private int fileCount;
    private int textCount;

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
        connectedGroup = findViewById(R.id.connectedGroup);
        recvGroup = findViewById(R.id.recvGroup);
        doneGroup = findViewById(R.id.doneGroup);
        recvCode = findViewById(R.id.recvCode);
        recvAddr = findViewById(R.id.recvAddr);
        recvName = findViewById(R.id.recvName);
        recvStatus = findViewById(R.id.recvStatus);
        connectedName = findViewById(R.id.connectedName);
        recvError = findViewById(R.id.recvError);
        recvGotLabel = findViewById(R.id.recvGotLabel);
        recvBytesLabel = findViewById(R.id.recvBytesLabel);
        doneSummary = findViewById(R.id.doneSummary);
        doneImages = findViewById(R.id.doneImages);
        doneVideos = findViewById(R.id.doneVideos);
        doneFiles = findViewById(R.id.doneFiles);
        doneText = findViewById(R.id.doneText);
        receiveArt = findViewById(R.id.receiveArt);

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
        findViewById(R.id.btnConnectedStop).setOnClickListener(v -> stopReceiving());
        findViewById(R.id.btnConnectedHome).setOnClickListener(v -> finish());
        findViewById(R.id.btnDoneContinue).setOnClickListener(v -> startReceiving());
        findViewById(R.id.btnDoneHome).setOnClickListener(v -> finish());
    }

    // ------------------------------------------------------------------ 接收

    private void startReceiving() {
        main.removeCallbacks(finishAfterIdle);
        stopReceiveAnimation();
        gotCount = 0;
        gotBytes = 0;
        imageCount = 0;
        videoCount = 0;
        fileCount = 0;
        textCount = 0;
        recvGotLabel.setVisibility(View.GONE);
        recvBytesLabel.setVisibility(View.GONE);
        recvError.setVisibility(View.GONE);
        recvStatus.setText(R.string.peer_receiving_hint);

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
                addReceivedRow(name, size);
            }

            @Override
            public void onPeerConnected(String name) {
                showConnected(name);
            }

            @Override
            public void onKnock(String who) {
                // 连接请求一到，先给接收端一个明确的连接状态；
                // 是否放行仍由弹窗里的「同意」决定。
                showConnected(who);
                askApproval(who);
            }
        });

        recvName.setText(deviceName());
        roleGroup.setVisibility(View.GONE);
        connectedGroup.setVisibility(View.GONE);
        recvGroup.setVisibility(View.VISIBLE);
        doneGroup.setVisibility(View.GONE);
        startReceiveAnimation();
        // 收东西的时候屏幕别灭 —— 灭屏之后 Wi-Fi 会进省电，传一半可能断
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void showConnected(String name) {
        connectedName.setText(name);
        roleGroup.setVisibility(View.GONE);
        recvGroup.setVisibility(View.GONE);
        doneGroup.setVisibility(View.GONE);
        connectedGroup.setVisibility(View.VISIBLE);
        stopReceiveAnimation();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void stopReceiving() {
        main.removeCallbacks(finishAfterIdle);
        stopReceiveAnimation();
        server.stop();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        connectedGroup.setVisibility(View.GONE);
        recvGroup.setVisibility(View.GONE);
        doneGroup.setVisibility(View.GONE);
        roleGroup.setVisibility(View.VISIBLE);
    }

    /**
     * PUT 没有单独的“批次结束”请求，所以用最后一个文件后的短暂空闲判断传输完成。
     * 大文件只会在完整落盘后回调，不会在传输中误切页面。
     */
    private void finishReceiving() {
        if (gotCount == 0 || !server.isRunning()) {
            return;
        }
        server.stop();
        stopReceiveAnimation();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        recvGroup.setVisibility(View.GONE);
        connectedGroup.setVisibility(View.GONE);
        doneGroup.setVisibility(View.VISIBLE);
        doneSummary.setText(getString(R.string.peer_done_summary, gotCount,
                android.text.format.Formatter.formatFileSize(this, gotBytes)));
        doneImages.setText(getString(R.string.peer_category_images, imageCount));
        doneVideos.setText(getString(R.string.peer_category_videos, videoCount));
        doneFiles.setText(getString(R.string.peer_category_files, fileCount));
        doneText.setText(getString(R.string.peer_category_text, textCount));
    }

    private void startReceiveAnimation() {
        receiveArt.setVisibility(View.VISIBLE);
        receiveAnimator = new AnimatorSet();
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(
                receiveArt, View.SCALE_X, 0.96f, 1.04f, 0.96f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(
                receiveArt, View.SCALE_Y, 0.96f, 1.04f, 0.96f);
        ObjectAnimator floatY = ObjectAnimator.ofFloat(
                receiveArt, View.TRANSLATION_Y, 0f, -8f, 0f);
        scaleX.setRepeatCount(ObjectAnimator.INFINITE);
        scaleY.setRepeatCount(ObjectAnimator.INFINITE);
        floatY.setRepeatCount(ObjectAnimator.INFINITE);
        receiveAnimator.playTogether(scaleX, scaleY, floatY);
        receiveAnimator.setDuration(1800);
        receiveAnimator.setInterpolator(new LinearInterpolator());
        receiveAnimator.setStartDelay(120);
        receiveAnimator.start();
    }

    private void stopReceiveAnimation() {
        if (receiveAnimator != null) {
            receiveAnimator.cancel();
            receiveAnimator = null;
        }
        if (receiveArt != null) {
            receiveArt.setScaleX(1f);
            receiveArt.setScaleY(1f);
            receiveArt.setTranslationY(0f);
        }
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

    private void addReceivedRow(String name, long size) {
        if (connectedGroup.getVisibility() == View.VISIBLE) {
            connectedGroup.setVisibility(View.GONE);
            recvGroup.setVisibility(View.VISIBLE);
        }
        gotCount++;
        gotBytes += Math.max(0, size);
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        if (lower.startsWith("text-") || lower.endsWith(".txt")
                || lower.endsWith(".md") || lower.endsWith(".json")
                || lower.endsWith(".csv")) {
            textCount++;
        } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".gif")
                || lower.endsWith(".webp") || lower.endsWith(".heic")
                || lower.endsWith(".heif") || lower.endsWith(".bmp")) {
            imageCount++;
        } else if (lower.endsWith(".mp4") || lower.endsWith(".mov")
                || lower.endsWith(".mkv") || lower.endsWith(".avi")
                || lower.endsWith(".webm") || lower.endsWith(".3gp")) {
            videoCount++;
        } else {
            fileCount++;
        }
        recvGotLabel.setText(getString(R.string.peer_got) + " " + gotCount);
        recvGotLabel.setVisibility(View.VISIBLE);
        recvBytesLabel.setText(android.text.format.Formatter.formatFileSize(this, gotBytes));
        recvBytesLabel.setVisibility(View.VISIBLE);
        recvStatus.setText(R.string.peer_receiving);

        main.removeCallbacks(finishAfterIdle);
        main.postDelayed(finishAfterIdle, 1600);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(finishAfterIdle);
        stopReceiveAnimation();
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
