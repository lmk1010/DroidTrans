package com.mk.androidtransfer;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mk.androidtransfer.adapter.ReceiveFileAdapter;
import com.mk.androidtransfer.model.ReceiveItem;
import com.mk.androidtransfer.util.TransferFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 从电脑接收文件。
 *
 * <p>电脑端把要发的东西放进「发到手机」队列（拖进窗口或选择文件），
 * 这里把清单拉下来，一键全部收下。文件按电脑上的相对路径落到
 * Download/DroidTrans/ 下，文件夹结构原样保留。
 */
public class ReceiveActivity extends AppCompatActivity {

    private static final String TAG = "ReceiveActivity";
    // 与 MainActivity 用同一份缓存，别自己另起一套键名
    private static final String PREFS_NAME = "ServerCache";
    private static final String KEY_LAST_IP = "last_ip";
    private static final String KEY_LAST_PORT = "last_port";
    private static final String KEY_LAST_NAME = "last_name";
    private static final int DEFAULT_PORT = 9500;
    private static final String SUBDIR = "DroidTrans";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.MINUTES)
            .build();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<ReceiveItem> items = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ReceiveFileAdapter adapter;
    private TextView tvSubtitle;
    private TextView tvStatus;
    private MaterialButton btnGetAll;
    private LinearProgressIndicator progress;
    private View emptyState;

    private String baseUrl;
    private String serverName;

    public static void open(Context ctx) {
        ctx.startActivity(new Intent(ctx, ReceiveActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receive);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String ip = prefs.getString(KEY_LAST_IP, "");
        int port = prefs.getInt(KEY_LAST_PORT, DEFAULT_PORT);
        serverName = prefs.getString(KEY_LAST_NAME, ip);
        baseUrl = ip.isEmpty() ? null : "http://" + ip + ":" + port;

        tvSubtitle = findViewById(R.id.tvReceiveSubtitle);
        tvStatus = findViewById(R.id.tvReceiveStatus);
        btnGetAll = findViewById(R.id.btnGetAll);
        progress = findViewById(R.id.receiveProgress);
        emptyState = findViewById(R.id.receiveEmpty);

        RecyclerView list = findViewById(R.id.recyclerReceive);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ReceiveFileAdapter(items);
        list.setAdapter(adapter);

        findViewById(R.id.btnReceiveBack).setOnClickListener(v -> finish());
        btnGetAll.setOnClickListener(v -> startDownloadAll());

        tvSubtitle.setText(baseUrl == null
                ? getString(R.string.receive_no_server)
                : getString(R.string.receive_from, serverName));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!running.get()) {
            loadList();
        }
    }

    private void loadList() {
        if (baseUrl == null) {
            showEmpty(true);
            return;
        }
        new Thread(() -> {
            List<ReceiveItem> fetched = new ArrayList<>();
            String err = null;
            Request req = new Request.Builder().url(baseUrl + "/api/outbox").get().build();
            try (Response res = client.newCall(req).execute()) {
                ResponseBody body = res.body();
                if (res.isSuccessful() && body != null) {
                    JsonObject obj = JsonParser.parseString(body.string()).getAsJsonObject();
                    JsonArray arr = obj.getAsJsonArray("items");
                    for (int i = 0; arr != null && i < arr.size(); i++) {
                        JsonObject it = arr.get(i).getAsJsonObject();
                        fetched.add(new ReceiveItem(
                                it.get("id").getAsString(),
                                it.get("name").getAsString(),
                                it.has("rel") ? it.get("rel").getAsString() : it.get("name").getAsString(),
                                it.get("size").getAsLong()));
                    }
                } else {
                    err = getString(R.string.receive_list_failed);
                }
            } catch (Exception e) {
                Log.w(TAG, "拉取清单失败", e);
                err = getString(R.string.receive_offline);
            }
            final String error = err;
            ui.post(() -> {
                items.clear();
                items.addAll(fetched);
                adapter.notifyDataSetChanged();
                showEmpty(items.isEmpty());
                if (error != null) {
                    tvStatus.setText(error);
                    tvStatus.setVisibility(View.VISIBLE);
                } else if (!items.isEmpty()) {
                    long total = 0;
                    for (ReceiveItem it : items) {
                        total += Math.max(0, it.size);
                    }
                    tvStatus.setVisibility(View.VISIBLE);
                    tvStatus.setText(getString(R.string.receive_pending, items.size(), TransferFormat.bytes(total)));
                } else {
                    tvStatus.setVisibility(View.GONE);
                }
            });
        }).start();
    }

    private void showEmpty(boolean empty) {
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        btnGetAll.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void startDownloadAll() {
        if (baseUrl == null || items.isEmpty() || !running.compareAndSet(false, true)) {
            return;
        }
        btnGetAll.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setProgressCompat(0, false);
        UploadService.start(this, items.size());

        new Thread(() -> {
            int done = 0;
            int failed = 0;
            for (int i = 0; i < items.size(); i++) {
                ReceiveItem item = items.get(i);
                final int index = i;
                ui.post(() -> {
                    item.state = ReceiveItem.State.DOWNLOADING;
                    adapter.notifyItemChanged(index);
                });
                boolean ok = downloadOne(item);
                if (ok) {
                    done++;
                } else {
                    failed++;
                }
                final int d = done;
                final boolean success = ok;
                ui.post(() -> {
                    item.state = success ? ReceiveItem.State.DONE : ReceiveItem.State.FAILED;
                    adapter.notifyItemChanged(index);
                    progress.setProgressCompat((int) (d * 100f / Math.max(1, items.size())), true);
                    tvStatus.setText(getString(R.string.receive_progress, d, items.size()));
                });
                UploadService.update(this, done, items.size(),
                        getString(R.string.receive_progress, done, items.size()));
            }
            final int okCount = done;
            final int failCount = failed;
            ui.post(() -> {
                running.set(false);
                btnGetAll.setEnabled(true);
                progress.setVisibility(View.GONE);
                String msg = failCount == 0
                        ? getString(R.string.receive_done, okCount)
                        : getString(R.string.receive_done_partial, okCount, failCount);
                tvStatus.setText(msg);
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                UploadService.finish(this, msg);
            });
        }).start();
    }

    /** 下载一个文件，按相对路径落到 Download/DroidTrans/ 下。 */
    private boolean downloadOne(ReceiveItem item) {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/outbox/file/" + item.id)
                .get()
                .build();
        try (Response res = client.newCall(req).execute()) {
            ResponseBody body = res.body();
            if (!res.isSuccessful() || body == null) {
                return false;
            }
            try (InputStream in = body.byteStream()) {
                return writeToDownloads(item, in);
            }
        } catch (Exception e) {
            Log.w(TAG, "下载失败 " + item.name, e);
            return false;
        }
    }

    private boolean writeToDownloads(ReceiveItem item, InputStream in) throws IOException {
        String rel = item.rel == null || item.rel.isEmpty() ? item.name : item.rel;
        String sub = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/')) : "";
        String fileName = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String dir = Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR + (sub.isEmpty() ? "" : "/" + sub);
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, dir);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            Uri uri = getContentResolver().insert(collection, values);
            if (uri == null) {
                return false;
            }
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    return false;
                }
                copy(in, out);
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            item.savedTo = dir + "/" + fileName;
            return true;
        }

        File base = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                SUBDIR + (sub.isEmpty() ? "" : "/" + sub));
        if (!base.exists() && !base.mkdirs()) {
            return false;
        }
        File out = new File(base, fileName);
        try (OutputStream os = new FileOutputStream(out)) {
            copy(in, os);
        }
        item.savedTo = out.getAbsolutePath();
        return true;
    }

    private static void copy(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
        byte[] buf = new byte[256 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.flush();
    }
}
