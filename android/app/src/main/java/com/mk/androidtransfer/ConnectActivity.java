package com.mk.androidtransfer;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.mk.androidtransfer.model.ServerInfo;
import com.mk.androidtransfer.network.BonjourBrowser;
import com.mk.androidtransfer.network.Pairing;
import com.mk.androidtransfer.network.PeerLink;
import com.mk.androidtransfer.network.PeerServer;
import com.mk.androidtransfer.network.WifiJoiner;
import com.mk.androidtransfer.widget.RadarScanView;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 找电脑。App 里「和电脑传」那条路的第一屏。
 *
 * <p>和 iOS 端 ConnectView 一一对应，三条路能同时用：
 * <ol>
 *   <li>Bonjour —— 电脑通告 _droidtrans._tcp，用户什么都不用做</li>
 *   <li>扫码 —— 电脑上的二维码就是 http://ip:9500/?c=六位码</li>
 *   <li>手输 IP —— 前两条都不通时的兜底</li>
 * </ol>
 *
 * <p>第 2、3 条必须一直留着：组播被路由器拦掉的情况不少见，
 * 那时候雷达永远是空的，而且不会报错。
 *
 * <p>这一屏取代了原来的 DashboardActivity。
 */
public class ConnectActivity extends AppCompatActivity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    /** 已经出现在雷达上的电脑，按 ip:port 去重 */
    private final Map<String, ServerInfo> found = new LinkedHashMap<>();
    /** Bonjour 服务名 → ip:port。服务消失时只给得到服务名，得靠它找回那个点。 */
    private final Map<String, String> byService = new LinkedHashMap<>();

    private BonjourBrowser browser;
    private RadarScanView radar;
    private TextView status;
    private TextView hint;

    private final androidx.activity.result.ActivityResultLauncher<ScanOptions> scanner =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() != null) {
                    handleScan(result.getContents());
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        setContentView(R.layout.activity_connect);
        applyInsets();

        radar = findViewById(R.id.radar);
        status = findViewById(R.id.status);
        hint = findViewById(R.id.hint);

        findViewById(R.id.btnHome).setOnClickListener(v -> finish());
        ((MaterialButton) findViewById(R.id.btnScan)).setOnClickListener(v -> {
            ScanOptions o = new ScanOptions();
            o.setPrompt(getString(R.string.connect_scan));
            o.setBeepEnabled(false);
            o.setOrientationLocked(false);
            scanner.launch(o);
        });
        ((MaterialButton) findViewById(R.id.btnManual)).setOnClickListener(v -> askAddress());

        radar.setOnServerDotClickListener(dot -> connect(
                "http://" + dot.ip + ":" + dot.port, dot.serverName));
    }

    @Override
    protected void onStart() {
        super.onStart();
        radar.startScanning();
        browser = new BonjourBrowser(this);
        browser.start(new BonjourBrowser.Listener() {
            @Override
            public void onFound(String name, String host, int port) {
                main.post(() -> ConnectActivity.this.onFound(name, host, port));
            }

            @Override
            public void onLost(String name) {
                main.post(() -> ConnectActivity.this.onLost(name));
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        radar.stopScanning();
        if (browser != null) {
            browser.stop();
            browser = null;
        }
    }

    // ------------------------------------------------------------------ 发现

    private void onFound(String name, String host, int port) {
        String key = host + ":" + port;
        byService.put(name, key);
        if (found.containsKey(key)) {
            return;
        }
        ServerInfo info = new ServerInfo(name, host, port);
        found.put(key, info);
        radar.addServerDot(info);
        refreshStatus();

        // Bonjour 只有地址信息。补一次 health，才能把 Android/iPhone
        // 从电脑节点里区分出来，并在雷达上换成对应素材。
        io.execute(() -> {
            String engine = Pairing.engine(info.getServerUrl());
            if (engine.isEmpty()) {
                return;
            }
            main.post(() -> {
                ServerInfo current = found.get(key);
                if (current == null) {
                    return;
                }
                current.setEngine(engine);
                radar.updateServerDot(current);
            });
        });
    }

    /**
     * 对面不再广播了 —— 停了接收、退出了 App、或者离开了这个网络。
     *
     * <p>留在雷达上的话，用户点下去只会等到一个超时错误，
     * 而他看到的是「明明在列表里，就是连不上」。
     */
    private void onLost(String serviceName) {
        String key = byService.remove(serviceName);
        if (key == null) {
            return;
        }
        ServerInfo gone = found.remove(key);
        if (gone == null) {
            return;
        }
        radar.removeServerDot(gone.getIp());
        refreshStatus();
    }

    private void refreshStatus() {
        int n = found.size();
        if (n == 0) {
            status.setText(R.string.connect_status_scanning);
            hint.setVisibility(View.VISIBLE);
        } else {
            status.setText(n == 1 ? R.string.connect_status_one : R.string.connect_status_many);
            // 已经找到了就别再教用户怎么排查，那段话这时候只是噪音
            hint.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------ 连接

    /**
     * 连一台电脑。
     *
     * <p>要不要配对是电脑说了算，所以先问它一句，再决定是直接进主界面
     * 还是先去输六位码 —— 不能默认「没令牌就一定要配对」，
     * 有些电脑压根没开配对要求。
     */
    private void connect(String baseUrl, String name) {
        io.execute(() -> {
            String url = resolvePort(Pairing.normalize(baseUrl));
            boolean needsPair;
            String mode;
            try {
                needsPair = Pairing.requiresPairing(url)
                        && Pairing.token(this, url).isEmpty();
                mode = needsPair ? Pairing.pairingMode(url) : "";
            } catch (Exception e) {
                main.post(() -> toast(getString(R.string.connect_failed)));
                return;
            }

            // 对面是台手机的话，别让用户去抄六位码 —— 敲一下门，等它点「同意」。
            // 这一步会挂在那儿等人点击，所以先把「正在等对方确认」显示出来。
            if (needsPair && "approve".equals(mode)) {
                main.post(() -> toast(getString(R.string.approval_waiting)));
                boolean ok = Pairing.knock(this, url);
                main.post(() -> {
                    if (ok) {
                        Intent i = new Intent(this, HomeActivity.class);
                        i.putExtra(HomeActivity.EXTRA_BASE_URL, url);
                        i.putExtra(HomeActivity.EXTRA_NAME, name);
                        startActivity(i);
                    } else {
                        toast(getString(R.string.approval_refused));
                    }
                });
                return;
            }

            final boolean pair = needsPair;
            main.post(() -> {
                Intent i = pair
                        ? new Intent(this, PairActivity.class)
                        : new Intent(this, HomeActivity.class);
                i.putExtra(HomeActivity.EXTRA_BASE_URL, url);
                i.putExtra(HomeActivity.EXTRA_NAME, name);
                startActivity(i);
            });
        });
    }

    /**
     * 手输的地址没带端口时，替用户试出来。
     *
     * <p>不补的话请求会打到 80 端口 —— 那儿什么都没有，用户看到的是
     * 「连不上」，而他输的 IP 完全正确。电脑在 9500，手机接收端在 9600，
     * 两个都试一下，谁应声就是谁。
     */
    private String resolvePort(String hostPort) {
        if (hostPort.contains(":")) {
            return hostPort;
        }
        for (int port : new int[]{9500, PeerServer.PORT}) {
            String candidate = hostPort + ":" + port;
            if (!Pairing.engine("http://" + candidate).isEmpty()) {
                return candidate;
            }
        }
        // 都没应声：按电脑那个端口报错，错误信息里带的地址才是用户认得的
        return hostPort + ":9500";
    }

    /**
     * 扫到一个码。
     *
     * <p>码里可能有三样东西：地址、六位配对码，以及（对面开了直连热点时）
     * 那个热点的 SSID 和密码。带网络信息的话先把网连上 —— 不然「扫一下就能传」
     * 只是嘴上说说：用户还得退出 App、去设置里翻热点、手输一串随机密码。
     */
    private void handleScan(String payload) {
        PeerLink link = PeerLink.parse(payload);
        if (link == null) {
            toast(getString(R.string.connect_failed));
            return;
        }
        if (link.hasHotspot()) {
            toast(getString(R.string.peer_scan_joining));
            WifiJoiner.get(this).join(link.ssid, link.password, new WifiJoiner.Callback() {
                @Override
                public void onJoined() {
                    toast(getString(R.string.peer_scan_joined));
                    afterScan(link);
                }

                @Override
                public void onFailed(String msg) {
                    // 没连上也照样往下走：用户可能本来就在同一个网里，
                    // 这时候码里的地址依然是通的，不该在这儿把人拦下
                    toast(msg);
                    afterScan(link);
                }
            });
            return;
        }
        afterScan(link);
    }

    private void afterScan(PeerLink link) {
        String url = link.baseUrl();
        // 对面是手机（接收端口，或码里报了设备名）：走「点一下同意」那条路，
        // 六位码在那边只是兜底，不该把用户推到一个要抄码的界面上去
        if (link.port == PeerServer.PORT || !link.name.isEmpty()) {
            connect(url, link.name.isEmpty() ? link.host : link.name);
            return;
        }
        Intent i = new Intent(this, PairActivity.class);
        i.putExtra(HomeActivity.EXTRA_BASE_URL, Pairing.normalize(url));
        i.putExtra(PairActivity.EXTRA_CODE, link.code.isEmpty() ? null : link.code);
        startActivity(i);
    }

    private void askAddress() {
        View body = getLayoutInflater().inflate(R.layout.dialog_input, null, false);
        EditText input = body.findViewById(R.id.input);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("192.168.1.5");
        ((TextView) body.findViewById(R.id.inputHint)).setText(R.string.connect_manual_hint);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.connect_manual)
                .setView(body)
                .setPositiveButton(R.string.common_connect, (d, w) -> {
                    String s = input.getText().toString().trim();
                    if (!s.isEmpty()) {
                        connect(s, s);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
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
