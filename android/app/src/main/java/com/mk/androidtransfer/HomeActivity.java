package com.mk.androidtransfer;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.mk.androidtransfer.model.UploadFileItem;
import com.mk.androidtransfer.network.FastTransferClient;
import com.mk.androidtransfer.network.Pairing;
import com.mk.androidtransfer.network.PeerServer;
import com.mk.androidtransfer.network.ProtocolSelector;
import com.mk.androidtransfer.network.RetrofitClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 连上电脑之后的主界面。
 *
 * <p>和 iOS 端 HomeScreen 一一对应：一屏放下所有事 ——
 * 连着哪台电脑、要发什么、正在传的进度。
 *
 * <p>原来这些散在 DashboardActivity / PhotoSelectionActivity /
 * UploadProgressActivity 三屏里，用户想发一张照片要走两次页面切换。
 * 传文件本来就是几秒钟的事，不该为它设计一套需要导航的界面。
 *
 * <p>选文件不再自己写相册：系统的照片选择器（Android 13+ 是隐私友好的
 * PickVisualMedia）比自己实现的那一千行更快、更眼熟，也不用申请存储权限。
 */
public class HomeActivity extends AppCompatActivity {

    public static final String EXTRA_BASE_URL = "base_url";
    public static final String EXTRA_NAME = "name";
    /** 对面是什么（go / android / swift）。图标据此选电脑还是手机。 */
    public static final String EXTRA_ENGINE = "engine";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            // 传一个几 GB 的视频要好几分钟，读超时不能按常规接口来
            .readTimeout(60, TimeUnit.MINUTES)
            .writeTimeout(60, TimeUnit.MINUTES)
            // 每个请求都要带配对令牌。少了它，电脑一律回 403，
            // 而用户在这一屏看到的只是「pairing required」——他明明已经配过对了。
            .addInterceptor(chain -> {
                String t = com.mk.androidtransfer.network.RetrofitClient.getToken();
                if (t == null || t.isEmpty()) {
                    return chain.proceed(chain.request());
                }
                return chain.proceed(chain.request().newBuilder()
                        .header(Pairing.header(), t)
                        .build());
            })
            .build();

    private String baseUrl;
    /** host:port，只用于界面显示和按机器存令牌，别拿去发请求。 */
    private String host;
    private String name;

    private LinearLayout jobs;
    private TextView jobsLabel;

    private final androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest> photos =
            registerForActivityResult(new ActivityResultContracts.PickMultipleVisualMedia(30),
                    uris -> send(uris));

    private final androidx.activity.result.ActivityResultLauncher<String[]> files =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(),
                    uris -> send(uris));

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        setContentView(R.layout.activity_home);
        applyInsets();

        // normalize 出来的是「host:port」，没有 http:// —— 那是给存令牌当 key 用的，
        // 不能直接拿去请求：Uri.parse("192.168.1.5:9500") 解不出 host，
        // ProtocolSelector 会一路退化到 127.0.0.1，用户看到的是
        // 「Failed to connect to /127.0.0.1:9500」，而他连的明明是另一台机器。
        host = Pairing.normalize(getIntent().getStringExtra(EXTRA_BASE_URL));
        baseUrl = "http://" + host;

        // 令牌要在这里装上：ATF3 快传是从 RetrofitClient 这个静态字段取令牌的，
        // 而这一屏原来从没设过它 —— 于是从雷达连上电脑之后，发任何文件都被
        // 电脑判 403，界面上写着「pairing required」，可用户明明已经配过对。
        RetrofitClient.setToken(Pairing.token(this, host));
        name = getIntent().getStringExtra(EXTRA_NAME);

        // 连的是手机就别画一台笔记本。这一屏最上面那张卡是「我现在连着谁」，
        // 图标画错，用户第一眼得到的就是错的信息。
        int port = 9500;
        int colon = host.lastIndexOf(':');
        if (colon > 0) {
            try {
                port = Integer.parseInt(host.substring(colon + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        String engine = getIntent().getStringExtra(EXTRA_ENGINE);
        boolean phone = port == PeerServer.PORT
                || "android".equalsIgnoreCase(engine) || "swift".equalsIgnoreCase(engine);
        ((ImageView) findViewById(R.id.connIcon)).setImageResource(
                phone ? R.drawable.art_phone : R.drawable.art_laptop);

        ((TextView) findViewById(R.id.connName)).setText(name == null ? host : name);
        ((TextView) findViewById(R.id.connAddr)).setText(host);

        jobs = findViewById(R.id.jobs);
        jobsLabel = findViewById(R.id.jobsLabel);

        findViewById(R.id.btnHome).setOnClickListener(v -> finish());
        findViewById(R.id.btnSwitch).setOnClickListener(v -> finish());

        bindCard(R.id.sendPhotos, R.drawable.art_photos,
                R.string.home_photos, R.string.home_photos_sub,
                v -> photos.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE)
                        .build()));

        bindCard(R.id.sendFiles, R.drawable.art_files,
                R.string.home_files, R.string.home_files_sub,
                v -> files.launch(new String[]{"*/*"}));

        bindCard(R.id.sendText, R.drawable.art_text,
                R.string.home_text, R.string.home_text_sub,
                v -> askText());
    }

    // ------------------------------------------------------------------ 发送

    private void send(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }
        for (Uri uri : uris) {
            sendOne(uri);
        }
    }

    private void sendOne(Uri uri) {
        String display = queryName(uri);
        long size = querySize(uri);

        View row = addJobRow(display);
        io.execute(() -> {
            try {
                // 每发一个都按当前这台重新取令牌。
                //
                // 令牌存在 RetrofitClient 那个静态字段里，别的界面（连电脑、
                // 上传进度页）也会往里写 —— 只在 onCreate 设一次的话，
                // 中途被覆盖成另一台的令牌，对面就回 403，
                // 而界面上显示的还是「已连接」。
                String tok = Pairing.token(this, host);
                if (tok == null || tok.isEmpty()) {
                    throw new IOException(getString(R.string.home_need_repair));
                }
                RetrofitClient.setToken(tok);

                ProtocolSelector.Choice choice = ProtocolSelector.select(baseUrl);
                // path 传 null：内容 URI 拿不到真实路径，FastTransferClient 会走
                // uri 那条分支用 ContentResolver 打开
                UploadFileItem item = new UploadFileItem(
                        display, null, size, uri.toString(), display);
                FastTransferClient.send(this, choice, item, deviceId(), http,
                        new FastTransferClient.ProgressListener() {
                            @Override
                            public boolean isCancelled() {
                                return isFinishing();
                            }

                            @Override
                            public void onBytes(long sent, long total) {
                                int pct = total > 0 ? (int) (sent * 100 / total) : 0;
                                main.post(() -> setJob(row,
                                        getString(R.string.home_job_sending, pct),
                                        R.color.ink3));
                            }
                        });
                main.post(() -> setJob(row, getString(R.string.home_job_done), R.color.ok));
            } catch (IOException e) {
                // 403 = 对面不认这个令牌。对用户来说「HTTP PUT 403」什么都不是，
                // 而他能做的事很具体：重新连一次。
                final String why = String.valueOf(e.getMessage()).contains("403")
                        ? getString(R.string.home_need_repair)
                        : e.getMessage();
                main.post(() -> setJob(row, why, R.color.danger));
            }
        });
    }

    private void askText() {
        View body = getLayoutInflater().inflate(R.layout.dialog_input, null, false);
        EditText input = body.findViewById(R.id.input);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        ((TextView) body.findViewById(R.id.inputHint)).setText(R.string.home_text_sub);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.home_text)
                .setView(body)
                .setPositiveButton(R.string.common_send, (d, w) -> {
                    String t = input.getText().toString();
                    if (!t.isEmpty()) {
                        postText(t);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 一段文字直接进电脑的剪贴板，不落成文件 —— 那是这个功能的全部意义。 */
    private void postText(String text) {
        io.execute(() -> {
            try {
                String body = "{\"text\":" + org.json.JSONObject.quote(text) + "}";
                Request req = new Request.Builder()
                        .url(baseUrl + "/api/inbox/text")
                        .addHeader(Pairing.header(), Pairing.token(this, baseUrl))
                        .post(RequestBody.create(body,
                                MediaType.parse("application/json; charset=utf-8")))
                        .build();
                try (Response r = http.newCall(req).execute()) {
                    boolean ok = r.isSuccessful();
                    main.post(() -> toast(getString(
                            ok ? R.string.home_text_sent : R.string.home_send_failed)));
                }
            } catch (IOException e) {
                main.post(() -> toast(getString(R.string.home_send_failed)));
            }
        });
    }

    // ------------------------------------------------------------------ 进度

    /**
     * 「正在传」里的一行。
     *
     * <p>做成卡片而不是一行小字：一次发好几个的时候，一堆灰字挤在一起
     * 根本看不出谁传到哪儿了，而这一屏最需要回答的就是这个问题。
     */
    private View addJobRow(String label) {
        jobsLabel.setVisibility(View.VISIBLE);
        View row = getLayoutInflater().inflate(R.layout.item_job_row, jobs, false);
        ((ImageView) row.findViewById(R.id.jobIcon)).setImageResource(iconFor(label));
        ((TextView) row.findViewById(R.id.jobName)).setText(label);
        ((TextView) row.findViewById(R.id.jobStatus)).setText(R.string.home_job_waiting);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(8 * getResources().getDisplayMetrics().density);
        jobs.addView(row, lp);
        return row;
    }

    /** 按扩展名挑图标。认不出来就用通用的文件图标。 */
    private static int iconFor(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".heic")
                || n.endsWith(".heif") || n.endsWith(".bmp") || n.endsWith(".mp4")
                || n.endsWith(".mov") || n.endsWith(".mkv") || n.endsWith(".webm")) {
            return R.drawable.art_photos;
        }
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".json")
                || n.endsWith(".csv")) {
            return R.drawable.art_text;
        }
        return R.drawable.art_files;
    }

    private void setJob(View row, String status, int color) {
        TextView t = row.findViewById(R.id.jobStatus);
        t.setText(status);
        t.setTextColor(getColor(color));
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

    /** 内容 URI 拿不到真实路径，只能问它要个显示名。 */
    private String queryName(Uri uri) {
        try (android.database.Cursor c = getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    return c.getString(i);
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "file" : last;
    }

    private long querySize(Uri uri) {
        try (android.database.Cursor c = getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) {
                    return c.getLong(i);
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private String deviceId() {
        return android.provider.Settings.Secure.getString(
                getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
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
