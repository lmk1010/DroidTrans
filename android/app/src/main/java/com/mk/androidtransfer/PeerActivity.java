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

import com.mk.androidtransfer.network.DirectHotspot;
import com.mk.androidtransfer.network.PeerLink;
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

    /** 常驻监听那边已经同意了对面的连接，这一屏直接进接收状态。 */
    public static final String EXTRA_TAKE_OVER = "take_over";

    private static final int REQ_HOTSPOT_PERM = 4101;

    private DirectHotspot hotspot;
    private String kind = KIND_ANDROID;

    /** 直连热点的名字和密码。开着才有值，它们要一起进二维码。 */
    private String hotspotSsid = "";
    private String hotspotPass = "";

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
    private ImageView recvQr;
    private TextView recvQrHint;
    private TextView recvHotspot;
    private com.google.android.material.button.MaterialButton btnDirect;
    private AnimatorSet receiveAnimator;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** 文件名 → 它在列表里那张卡 */
    private final java.util.Map<String, View> rows = new java.util.LinkedHashMap<>();
    private android.widget.LinearLayout recvList;

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
        recvList = findViewById(R.id.recvList);
        recvQr = findViewById(R.id.recvQr);
        recvQrHint = findViewById(R.id.recvQrHint);
        recvHotspot = findViewById(R.id.recvHotspot);
        btnDirect = findViewById(R.id.btnDirect);
        hotspot = new DirectHotspot(this);
        btnDirect.setVisibility(DirectHotspot.isSupported() ? View.VISIBLE : View.GONE);
        btnDirect.setOnClickListener(v -> toggleHotspot());

        boolean iphone = KIND_IPHONE.equals(kind);
        ((ImageView) findViewById(R.id.roleIcon))
                .setImageResource(iphone ? R.drawable.art_phone : R.drawable.art_android);
        ((TextView) findViewById(R.id.roleTitle))
                .setText(iphone ? R.string.start_iphone : R.string.start_android);

        bindCard(R.id.cardSend, R.drawable.art_link,
                R.string.peer_send, R.string.peer_send_sub,
                v -> {
                    // 用户是从「和手机传」进来的，雷达上就不该再列电脑 ——
                    // 这一屏问的是「跟哪台手机传」，把电脑混在里面，
                    // 点错了才发现连的不是想连的那台。
                    Intent i = new Intent(this, ConnectActivity.class);
                    i.putExtra(ConnectActivity.EXTRA_PHONES_ONLY, true);
                    startActivity(i);
                });

        bindCard(R.id.cardRecv, R.drawable.art_inbox,
                R.string.peer_recv, R.string.peer_recv_sub, v -> startReceiving());

        findViewById(R.id.btnHome).setOnClickListener(v -> finish());
        findViewById(R.id.btnStop).setOnClickListener(v -> stopReceiving());
        findViewById(R.id.btnConnectedStop).setOnClickListener(v -> stopReceiving());
        findViewById(R.id.btnConnectedHome).setOnClickListener(v -> finish());
        findViewById(R.id.btnDoneContinue).setOnClickListener(v -> startReceiving());
        findViewById(R.id.btnDoneHome).setOnClickListener(v -> finish());

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_TAKE_OVER, false)) {
            // 常驻监听已经替用户同意了这次连接：直接进接收界面，
            // 并把它的实时状态接过来 —— 不这么做的话，对面已经在发了，
            // 这一屏还停在「等待连接」上一动不动。
            startReceiving();
            showConnected(getString(R.string.peer_someone));
        }
    }

    // ------------------------------------------------------------------ 接收

    private void startReceiving() {
        rows.clear();
        if (recvList != null) {
            recvList.removeAllViews();
            recvList.setVisibility(View.GONE);
        }
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

        // 不再另起一个服务。
        //
        // 以前这一屏自己 new 一个 PeerServer，而 App 一打开就已经有一个常驻的在
        // 监听同一个端口了 —— 于是连接被常驻那个接下，界面盯着自己那个，
        // 用户在发，这边却一直显示「等待连接」。现在整屏就是常驻监听的界面。
        PeerHost host = PeerHost.get();
        if (host == null) {
            recvError.setText(R.string.peer_need_wifi);
            recvError.setVisibility(View.VISIBLE);
            return;
        }
        host.setUi(hostUi);
        host.resume();

        recvName.setText(deviceName());
        roleGroup.setVisibility(View.GONE);
        connectedGroup.setVisibility(View.GONE);
        recvGroup.setVisibility(View.VISIBLE);
        doneGroup.setVisibility(View.GONE);
        startReceiveAnimation();
        showAddressAndCode();
        // 收东西的时候屏幕别灭 —— 灭屏之后 Wi-Fi 会进省电，传一半可能断
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** 地址、配对码、二维码，全从常驻监听那儿取。 */
    private void showAddressAndCode() {
        PeerHost host = PeerHost.get();
        if (host == null) {
            return;
        }
        recvCode.setText(spaced(host.pairingCode()));
        String ip = host.localIp();
        if (ip != null) {
            recvAddr.setText(ip + ":" + host.boundPort());
        } else {
            recvAddr.setText(R.string.peer_need_wifi);
        }
        refreshQr();
    }

    /** 常驻监听发生的每一件事，都在这儿变成界面上的动静。 */
    private final PeerHost.Ui hostUi = new PeerHost.Ui() {
        @Override
        public void onState(boolean running, String error) {
            if (error != null) {
                recvError.setText(error);
                recvError.setVisibility(View.VISIBLE);
            }
            if (running) {
                showAddressAndCode();
            }
        }

        @Override
        public void onKnock(String who) {
            askApproval(who);
        }

        @Override
        public void onPeerConnected(String name) {
            showConnected(name);
        }

        @Override
        public void onFileProgress(String name, long received, long total) {
            showProgress(name, received, total);
        }

        @Override
        public void onFileReceived(String name, long size, File file) {
            addReceivedRow(name, size);
        }
    };

    // ------------------------------------------------------------------ 二维码

    /**
     * 把「连上这台」需要的一切画成一张码：地址、端口、六位码，
     * 开了直连热点时再加上 SSID 和密码。
     */
    private void refreshQr() {
        PeerHost host = PeerHost.get();
        String ip = host == null ? null : host.localIp();
        if (ip == null || host == null || !host.isRunning()) {
            recvQr.setVisibility(View.GONE);
            recvQrHint.setVisibility(View.GONE);
            return;
        }
        String payload = PeerLink.encode(ip, host.boundPort(),
                host.pairingCode(), deviceName(),
                hotspotSsid.isEmpty() ? null : hotspotSsid,
                hotspotSsid.isEmpty() ? null : hotspotPass);
        try {
            int px = dp(176);
            com.google.zxing.common.BitMatrix m = new com.google.zxing.MultiFormatWriter()
                    .encode(payload, com.google.zxing.BarcodeFormat.QR_CODE, px, px);
            recvQr.setImageBitmap(
                    new com.journeyapps.barcodescanner.BarcodeEncoder().createBitmap(m));
            recvQr.setVisibility(View.VISIBLE);
            recvQrHint.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            // 画不出来就当没有这条路，地址和六位码还在下面摆着
            recvQr.setVisibility(View.GONE);
            recvQrHint.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------ 直连热点

    /** 没有路由器时，这台自己拉一个热点，名字和密码直接进二维码。 */
    private void toggleHotspot() {
        if (hotspot.isRunning()) {
            hotspot.stop();
            hotspotSsid = "";
            hotspotPass = "";
            btnDirect.setText(R.string.peer_direct);
            recvHotspot.setVisibility(View.GONE);
            refreshQr();
            return;
        }
        String need = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? android.Manifest.permission.NEARBY_WIFI_DEVICES
                : android.Manifest.permission.ACCESS_FINE_LOCATION;
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, need)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{need}, REQ_HOTSPOT_PERM);
            return;
        }
        startHotspot();
    }

    private void startHotspot() {
        PeerHost host = PeerHost.get();
        int port = host == null ? PeerServer.PORT : host.boundPort();
        hotspot.start(deviceName(), port, new DirectHotspot.Callback() {
            @Override
            public void onStarted(String ssid, String password) {
                hotspotSsid = ssid;
                hotspotPass = password;
                btnDirect.setText(R.string.peer_direct_stop);
                // 走 Wi-Fi Direct 时对面连扫码都不用，这句话要说清楚
                recvHotspot.setText(getString(hotspot.isDirectGroup()
                        ? R.string.peer_direct_on_p2p : R.string.peer_direct_on, ssid));
                recvHotspot.setVisibility(View.VISIBLE);
                // 热点起来之后本机地址会变成热点网段的那个，码必须重画
                showAddressAndCode();
            }

            @Override
            public void onFailed(String msg) {
                hotspotSsid = "";
                hotspotPass = "";
                btnDirect.setText(R.string.peer_direct);
                recvHotspot.setText(msg);
                recvHotspot.setVisibility(View.VISIBLE);
                refreshQr();
            }

            @Override
            public void onStopped() {
                hotspotSsid = "";
                hotspotPass = "";
                btnDirect.setText(R.string.peer_direct);
                recvHotspot.setVisibility(View.GONE);
                refreshQr();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_HOTSPOT_PERM) {
            return;
        }
        if (grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startHotspot();
        } else {
            recvHotspot.setText(R.string.peer_direct_need_perm);
            recvHotspot.setVisibility(View.VISIBLE);
        }
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
        boolean hadFiles = gotCount > 0;
        stopReceiveAnimation();
        if (PeerHost.get() != null) {
            PeerHost.get().pause();
        }
        // 热点是为这次接收开的，接收停了还留着它，这台手机就一直上不了网
        hotspot.stop();
        hotspotSsid = "";
        hotspotPass = "";
        btnDirect.setText(R.string.peer_direct);
        recvHotspot.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (hadFiles) {
            // 收过东西就先给一张小结，别让用户不知道刚才到底收下了什么
            finishReceiving();
            return;
        }
        connectedGroup.setVisibility(View.GONE);
        recvGroup.setVisibility(View.GONE);
        doneGroup.setVisibility(View.GONE);
        roleGroup.setVisibility(View.VISIBLE);
    }

    /** 用户点了「停止接收」之后的收尾页：这一轮一共收了些什么。 */
    private void finishReceiving() {
        if (gotCount == 0) {
            return;
        }
        stopReceiveAnimation();
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
                .setNegativeButton(R.string.peer_knock_deny, (d, w) -> PeerHost.get().deny())
                .setPositiveButton(R.string.peer_knock_allow, (d, w) -> PeerHost.get().approve())
                .show();
    }

    /**
     * 正在收的那个文件，边收边更新它那张卡。
     *
     * <p>按文件名认卡：同一个文件的进度更新只改自己那一行，
     * 不会把上一个已经收好的挤掉。
     */
    private void showProgress(String name, long received, long total) {
        View row = rowFor(name);
        ((TextView) row.findViewById(R.id.jobStatus)).setText(
                getString(R.string.peer_row_receiving,
                        android.text.format.Formatter.formatShortFileSize(this, received),
                        android.text.format.Formatter.formatShortFileSize(this, total)));
        if (connectedGroup.getVisibility() == View.VISIBLE) {
            connectedGroup.setVisibility(View.GONE);
            recvGroup.setVisibility(View.VISIBLE);
        }
        recvStatus.setText(R.string.peer_receiving);
    }

    /** 这个文件对应的那张卡；没有就新建一张。 */
    private View rowFor(String name) {
        View row = rows.get(name);
        if (row != null) {
            return row;
        }
        row = getLayoutInflater().inflate(R.layout.item_job_row, recvList, false);
        ((ImageView) row.findViewById(R.id.jobIcon)).setImageResource(iconFor(name));
        ((TextView) row.findViewById(R.id.jobName)).setText(name);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        recvList.addView(row, 0, lp);
        recvList.setVisibility(View.VISIBLE);
        rows.put(name, row);
        return row;
    }

    private static int iconFor(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".heic")
                || n.endsWith(".heif") || n.endsWith(".mp4") || n.endsWith(".mov")
                || n.endsWith(".mkv") || n.endsWith(".webm")) {
            return R.drawable.art_photos;
        }
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".json")
                || n.endsWith(".csv")) {
            return R.drawable.art_text;
        }
        return R.drawable.art_files;
    }

    private void addReceivedRow(String name, long size) {
        View row = rowFor(name);
        TextView status = row.findViewById(R.id.jobStatus);
        status.setText(getString(R.string.peer_row_done,
                android.text.format.Formatter.formatShortFileSize(this, size)));
        status.setTextColor(getColor(R.color.ok));

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
        recvBytesLabel.setText(
                android.text.format.Formatter.formatFileSize(this, gotBytes));
        recvBytesLabel.setVisibility(View.VISIBLE);
        recvStatus.setText(R.string.peer_receiving);

        // **不再自作主张地结束。**
        //
        // 原来是「最后一个文件之后闲 1.6 秒就算传完」，然后把 PeerServer 停掉 ——
        // 对面一次选了十张照片，第一张收完那一秒半里第二张还没开始，
        // 这边就把服务关了，剩下九张全部失败。判断「一批传完了」这件事
        // 在协议上根本没有依据，那就别猜：收着，直到用户说停。
    }

    @Override
    protected void onDestroy() {
        if (PeerHost.get() != null) {
            PeerHost.get().clearUi(hostUi);
        }
        stopReceiveAnimation();
        super.onDestroy();
        hotspot.stop();
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
