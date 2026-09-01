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
import com.mk.androidtransfer.network.ProtocolSelector;

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

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            // 传一个几 GB 的视频要好几分钟，读超时不能按常规接口来
            .readTimeout(60, TimeUnit.MINUTES)
            .writeTimeout(60, TimeUnit.MINUTES)
            .build();

    private String baseUrl;
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

        baseUrl = Pairing.normalize(getIntent().getStringExtra(EXTRA_BASE_URL));
        name = getIntent().getStringExtra(EXTRA_NAME);

        ((TextView) findViewById(R.id.connName)).setText(name == null ? baseUrl : name);
        ((TextView) findViewById(R.id.connAddr)).setText(baseUrl);

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

        TextView row = addJobRow(display);
        io.execute(() -> {
            try {
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
                                main.post(() -> row.setText(display + "   " + pct + "%"));
                            }
                        });
                main.post(() -> {
                    row.setText("✓  " + display);
                    row.setTextColor(getColor(R.color.ok));
                });
            } catch (IOException e) {
                main.post(() -> {
                    row.setText("✗  " + display + "   " + e.getMessage());
                    row.setTextColor(getColor(R.color.danger));
                });
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

    private TextView addJobRow(String label) {
        jobsLabel.setVisibility(View.VISIBLE);
        TextView row = new TextView(this);
        row.setText(label);
        row.setTextColor(getColor(R.color.ink2));
        row.setTextSize(13f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(6 * getResources().getDisplayMetrics().density);
        jobs.addView(row, lp);
        return row;
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
