package com.mk.androidtransfer;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import com.mk.androidtransfer.model.PhotoInfo;
import com.mk.androidtransfer.network.Pairing;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 系统分享菜单的入口：在任何 App 里「分享 → 卓传」，东西直接进电脑。
 *
 * <p>分享文字/链接时，电脑那边会顺手放进剪贴板——从手机甩个链接过去，
 * 多半就是想在电脑上马上打开。分享文件时走原来的传输页。
 */
// 这个页面没有自己的界面（只弹 Toast 然后退出），用透明主题时不能继承
// AppCompatActivity：AppCompat 会去要 Theme.AppCompat，直接崩。
public class ShareReceiverActivity extends android.app.Activity {

    private static final String TAG = "ShareReceiver";
    private static final String PREFS_NAME = "ServerCache";
    private static final String KEY_LAST_IP = "last_ip";
    private static final String KEY_LAST_PORT = "last_port";
    private static final String KEY_LAST_NAME = "last_name";
    private static final int DEFAULT_PORT = 9500;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String ip = prefs.getString(KEY_LAST_IP, "");
        int port = prefs.getInt(KEY_LAST_PORT, DEFAULT_PORT);
        if (ip.isEmpty()) {
            Toast.makeText(this, R.string.share_no_server, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        String baseUrl = ip + ":" + port;
        String serverName = prefs.getString(KEY_LAST_NAME, ip);

        Intent intent = getIntent();
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            ArrayList<Uri> uris = collectUris(intent);
            if (uris.isEmpty() && text != null && !text.trim().isEmpty()) {
                sendText(baseUrl, serverName, text);
                return;
            }
            if (!uris.isEmpty()) {
                sendFiles(baseUrl, serverName, uris);
                return;
            }
        }
        Toast.makeText(this, R.string.share_nothing, Toast.LENGTH_SHORT).show();
        finish();
    }

    private ArrayList<Uri> collectUris(Intent intent) {
        ArrayList<Uri> out = new ArrayList<>();
        Uri single = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (single != null) {
            out.add(single);
        }
        ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (many != null) {
            out.addAll(many);
        }
        return out;
    }

    private void sendText(String baseUrl, String serverName, String text) {
        Toast.makeText(this, getString(R.string.share_sending_text, serverName), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = postText(baseUrl, text);
            ui.post(() -> {
                Toast.makeText(this,
                        ok ? R.string.share_text_ok : R.string.share_failed,
                        Toast.LENGTH_LONG).show();
                finish();
            });
        }).start();
    }

    private boolean postText(String baseUrl, String text) {
        try {
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("text", text);
            body.put("device_id", android.provider.Settings.Secure.getString(
                    getContentResolver(), android.provider.Settings.Secure.ANDROID_ID));
            body.put("device_name", com.mk.androidtransfer.util.DeviceNameGenerator
                    .getOrGenerateDeviceName(this));

            Request.Builder req = new Request.Builder()
                    .url("http://" + baseUrl + "/api/inbox/text")
                    .post(RequestBody.create(body.toString(),
                            MediaType.parse("application/json; charset=utf-8")));
            String token = Pairing.token(this, baseUrl);
            if (token != null && !token.isEmpty()) {
                req.header(Pairing.header(), token);
            }
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .build();
            try (Response res = client.newCall(req.build()).execute()) {
                return res.isSuccessful();
            }
        } catch (Exception e) {
            Log.w(TAG, "发送文字失败", e);
            return false;
        }
    }

    private void sendFiles(String baseUrl, String serverName, ArrayList<Uri> uris) {
        ArrayList<PhotoInfo> files = new ArrayList<>();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        for (Uri uri : uris) {
            String name = null;
            long size = 0;
            try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int si = c.getColumnIndex(OpenableColumns.SIZE);
                    if (ni >= 0) name = c.getString(ni);
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
                }
            } catch (Exception e) {
                Log.w(TAG, "读不到分享来的文件信息", e);
            }
            if (name == null || name.isEmpty()) {
                name = uri.getLastPathSegment() == null ? "file" : uri.getLastPathSegment();
            }
            long now = System.currentTimeMillis();
            files.add(new PhotoInfo(null, name, size, now / 1000, fmt.format(new java.util.Date(now)),
                    uri.toString()));
        }
        Intent go = new Intent(this, UploadProgressActivity.class);
        // 把读取授权一并交给传输页，否则本页 finish 之后 content:// 可能读不了
        go.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!uris.isEmpty()) {
            android.content.ClipData clip =
                    android.content.ClipData.newUri(getContentResolver(), "shared", uris.get(0));
            for (int i = 1; i < uris.size(); i++) {
                clip.addItem(new android.content.ClipData.Item(uris.get(i)));
            }
            go.setClipData(clip);
        }
        go.putExtra("server_url", "http://" + baseUrl + "/");
        go.putExtra("server_name", serverName);
        go.putParcelableArrayListExtra("selected_photos", files);
        go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(go);
        finish();
    }
}
