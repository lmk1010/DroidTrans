package com.mk.androidtransfer;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.mk.androidtransfer.adapter.UploadFileAdapter;
import com.mk.androidtransfer.util.ThemeBars;
import com.mk.androidtransfer.database.UploadRecordDao;
import com.mk.androidtransfer.model.PhotoInfo;
import com.mk.androidtransfer.model.UploadFileItem;
import com.mk.androidtransfer.model.UploadRecord;
import com.mk.androidtransfer.network.ApiService;
import com.mk.androidtransfer.network.FastTransferClient;
import com.mk.androidtransfer.network.ProtocolSelector;
import com.mk.androidtransfer.network.RetrofitClient;
import com.mk.androidtransfer.network.TransferProtocol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.Okio;
import okio.Sink;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 上传进度Activity
 */
public class UploadProgressActivity extends AppCompatActivity {

    private static final String TAG = "UploadProgressActivity";

    // UI组件
    private com.mk.androidtransfer.view.DataTransferAnimationView dataTransferAnimation;
    private TextView tvTotalFiles;
    private TextView tvCompletedFiles;
    private TextView tvFailedFiles;
    private LinearProgressIndicator progressBar;
    private TextView tvProgressText;
    private TextView tvProgressPercent;
    private RecyclerView recyclerViewFiles;
    private LinearLayout emptyState;
    private MaterialButton btnCancel;
    private MaterialButton btnDone;

    // 数据
    private List<UploadFileItem> fileList = new ArrayList<>();
    private UploadFileAdapter adapter;
    private String serverUrl;
    private String serverName;
    private String deviceId;
    private ApiService apiService;
    private OkHttpClient okHttpClient;
    private UploadRecordDao uploadRecordDao;
    private ProtocolSelector.Choice protocolChoice;

    // 状态
    private boolean isUploading = false;
    private volatile boolean isCancelled = false;
    private AtomicInteger completedCount = new AtomicInteger(0);
    private AtomicInteger failedCount = new AtomicInteger(0);
    private AtomicInteger uploadingCount = new AtomicInteger(0);
    
    // 多线程上传
    private static final int THREAD_POOL_SIZE = 6; // 并发上传线程数（可调整：4-8）
    private ExecutorService executorService;
    private ConcurrentHashMap<Integer, Boolean> uploadingFiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Call<?>> activeCalls = new ConcurrentHashMap<>();

    // Handler
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_upload_progress);

        // 设置沉浸式状态栏
        setupImmersiveStatusBar();

        // 初始化视图
        initViews();

        // 获取传递的数据
        serverUrl = getIntent().getStringExtra("server_url");
        serverName = getIntent().getStringExtra("server_name");
        ArrayList<PhotoInfo> selectedPhotos = getIntent().getParcelableArrayListExtra("selected_photos");

        if (serverUrl == null || selectedPhotos == null || selectedPhotos.isEmpty()) {
            Toast.makeText(this, R.string.invalid_params, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 获取设备ID
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        // 初始化网络
        RetrofitClient retrofitClient = RetrofitClient.getInstance(serverUrl);
        apiService = retrofitClient.getApiService();
        okHttpClient = retrofitClient.getOkHttpClient();
        
        // 初始化数据库
        uploadRecordDao = new UploadRecordDao(this);

        // 初始化文件列表
        initFileList(selectedPhotos);

        // 初始化上传会话
        initUploadSession();

        // 开始上传
        startUpload();
    }

    private void setupImmersiveStatusBar() {
        ThemeBars.apply(this);
    }

    private void initViews() {
        ImageButton btnBack = findViewById(R.id.btnBack);
        dataTransferAnimation = findViewById(R.id.dataTransferAnimation);
        tvTotalFiles = findViewById(R.id.tvTotalFiles);
        tvCompletedFiles = findViewById(R.id.tvCompletedFiles);
        tvFailedFiles = findViewById(R.id.tvFailedFiles);
        progressBar = findViewById(R.id.progressBar);
        tvProgressText = findViewById(R.id.tvProgressText);
        tvProgressPercent = findViewById(R.id.tvProgressPercent);
        recyclerViewFiles = findViewById(R.id.recyclerViewFiles);
        emptyState = findViewById(R.id.emptyState);
        btnCancel = findViewById(R.id.btnCancel);
        btnDone = findViewById(R.id.btnDone);

        btnBack.setOnClickListener(v -> onBackPressed());

        // 设置RecyclerView
        adapter = new UploadFileAdapter(this);
        recyclerViewFiles.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewFiles.setAdapter(adapter);

        // 按钮点击事件
        btnCancel.setOnClickListener(v -> cancelUpload());
        btnDone.setOnClickListener(v -> finish());
        
        // 启用滑动
        recyclerViewFiles.setHasFixedSize(true);
        recyclerViewFiles.setItemAnimator(null);
        recyclerViewFiles.setNestedScrollingEnabled(true);
    }

    private void initFileList(ArrayList<PhotoInfo> photos) {
        for (PhotoInfo photo : photos) {
            UploadFileItem item = new UploadFileItem(
                photo.getName(),
                photo.getStablePath(),
                photo.getSize(),
                photo.getUri(),
                photo.getUploadRelativePath()
            );
            fileList.add(item);
        }

        adapter.setFileList(fileList);
        tvTotalFiles.setText(String.valueOf(fileList.size()));
        updateEmptyState();
    }

    private void initUploadSession() {
        // 构建文件列表
        List<Map<String, Object>> files = new ArrayList<>();
        for (UploadFileItem item : fileList) {
            Map<String, Object> file = new HashMap<>();
            file.put("name", item.getName());
            file.put("size", item.getSize());
            file.put("path", item.getPath());
            files.add(file);
        }

        // 构建请求
        Map<String, Object> request = new HashMap<>();
        request.put("device_id", deviceId);
        request.put("files", files);

        // 发送初始化请求
        apiService.initUpload(request).enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> response) {
                if (response.isSuccessful()) {
                    Log.d(TAG, "上传会话初始化成功");
                } else {
                    Log.e(TAG, "上传会话初始化失败: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                Log.e(TAG, "上传会话初始化失败", t);
            }
        });
    }

    private void startUpload() {
        if (isUploading || fileList.isEmpty()) {
            return;
        }

        isUploading = true;

        if (dataTransferAnimation != null) {
            dataTransferAnimation.startAnimation();
        }

        tvProgressText.setText(R.string.selecting_channel);

        new Thread(() -> {
            protocolChoice = ProtocolSelector.select(serverUrl);
            mainHandler.post(() -> tvProgressText.setText(getString(R.string.channel_label, protocolChoice.protocol.getLabel(UploadProgressActivity.this))));

            executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
            for (int i = 0; i < fileList.size(); i++) {
                final int index = i;
                executorService.submit(() -> uploadFile(fileList.get(index), index));
            }
            executorService.shutdown();
            Log.d(TAG, "已启动上传 protocol=" + protocolChoice.protocol);
        }).start();
    }

    private void uploadFile(UploadFileItem item, int index) {
        // 检查是否被取消
        if (isCancelled) {
            return;
        }
        
        // 防止重复上传
        if (uploadingFiles.putIfAbsent(index, true) != null) {
            Log.w(TAG, "文件 " + index + " 已在上传中，跳过");
            return;
        }
        
        try {
            // 更新UI状态为上传中
            mainHandler.post(() -> {
                item.setStatus(UploadFileItem.Status.UPLOADING);
                adapter.updateItem(index);
                uploadingCount.incrementAndGet();
                updateProgress();
            });
            
            // 更新服务器状态为uploading
            updateFileStatus(index, "uploading");

            if (protocolChoice != null && protocolChoice.protocol != TransferProtocol.HTTP_MULTIPART) {
                FastTransferClient.send(this, protocolChoice, item, deviceId, okHttpClient,
                        new FastTransferClient.ProgressListener() {
                            @Override
                            public boolean isCancelled() {
                                return UploadProgressActivity.this.isCancelled;
                            }

                            @Override
                            public void onBytes(long sent, long total) {
                                int percent = total > 0 ? (int) Math.min(99, sent * 100 / total) : 0;
                                mainHandler.post(() -> {
                                    if (item.getStatus() == UploadFileItem.Status.UPLOADING) {
                                        item.setProgress(percent);
                                        adapter.updateItem(index);
                                    }
                                });
                            }
                        });
                uploadingFiles.remove(index);
                uploadingCount.decrementAndGet();
                if (isCancelled) {
                    return;
                }
                onFileUploadSuccess(index);
                return;
            }

            RequestBody requestFile = wrapWithProgress(createUploadBody(item), item, index);
            if (requestFile == null) {
                onFileUploadFailed(index, getString(R.string.cannot_read_file));
                return;
            }
            MultipartBody.Part body = MultipartBody.Part.createFormData("file", item.getName(), requestFile);

            RequestBody deviceIdBody = RequestBody.create(MediaType.parse("text/plain"), deviceId);
            String relative = !TextUtils.isEmpty(item.getRelativePath()) ? item.getRelativePath() : item.getName();
            RequestBody relativePathBody = RequestBody.create(MediaType.parse("text/plain"), relative);
            RequestBody fileSizeBody = RequestBody.create(MediaType.parse("text/plain"), String.valueOf(item.getSize()));

            Call<Map<String, Object>> call = apiService.uploadPhotoMultipart(body, deviceIdBody, relativePathBody, fileSizeBody);
            activeCalls.put(index, call);

            call.enqueue(new Callback<Map<String, Object>>() {
                @Override
                public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> response) {
                    activeCalls.remove(index);
                    uploadingFiles.remove(index);
                    uploadingCount.decrementAndGet();

                    if (isCancelled || call.isCanceled()) {
                        return;
                    }

                    if (response.isSuccessful()) {
                        Map<String, Object> result = response.body();
                        boolean skipped = result != null && Boolean.TRUE.equals(result.get("skipped"));

                        if (skipped) {
                            Log.d(TAG, "⏭️ 文件已存在，跳过: " + item.getName());
                        }

                        onFileUploadSuccess(index);
                    } else {
                        onFileUploadFailed(index, getString(R.string.upload_failed_code, response.code()));
                    }
                }

                @Override
                public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                    activeCalls.remove(index);
                    uploadingFiles.remove(index);
                    uploadingCount.decrementAndGet();
                    if (isCancelled || call.isCanceled()) {
                        return;
                    }
                    onFileUploadFailed(index, t.getMessage());
                }
            });

        } catch (Exception e) {
            uploadingFiles.remove(index);
            uploadingCount.decrementAndGet();
            onFileUploadFailed(index, e.getMessage());
        }
    }

    private RequestBody wrapWithProgress(RequestBody delegate, UploadFileItem item, int index) {
        if (delegate == null) {
            return null;
        }
        return new RequestBody() {
            @Override
            public MediaType contentType() {
                return delegate.contentType();
            }

            @Override
            public long contentLength() throws IOException {
                return delegate.contentLength();
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                final long total = contentLength();
                final long startedAt = System.currentTimeMillis();
                Sink forwarding = new ForwardingSink(sink) {
                    long written = 0;
                    long lastUiAt = 0;

                    @Override
                    public void write(Buffer source, long byteCount) throws IOException {
                        if (isCancelled) {
                            throw new IOException("cancelled");
                        }
                        super.write(source, byteCount);
                        written += byteCount;
                        long now = System.currentTimeMillis();
                        if (now - lastUiAt < 200 && written < total) {
                            return;
                        }
                        lastUiAt = now;
                        int percent = total > 0 ? (int) Math.min(99, written * 100 / total) : 0;
                        long elapsed = Math.max(1, now - startedAt);
                        long speed = written * 1000 / elapsed;
                        mainHandler.post(() -> {
                            if (item.getStatus() == UploadFileItem.Status.UPLOADING) {
                                item.setProgress(percent);
                                item.setSpeed(speed);
                                adapter.updateItem(index);
                            }
                        });
                    }
                };
                BufferedSink buffered = Okio.buffer(forwarding);
                delegate.writeTo(buffered);
                buffered.flush();
            }
        };
    }

    private RequestBody createUploadBody(UploadFileItem item) {
        File file = item.getPath() != null ? new File(item.getPath()) : null;
        if (file != null && file.exists() && file.canRead()) {
            return RequestBody.create(MediaType.parse("application/octet-stream"), file);
        }

        if (TextUtils.isEmpty(item.getUri())) {
            return null;
        }

        final Uri uri = Uri.parse(item.getUri());
        final long size = item.getSize();
        return new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.parse("application/octet-stream");
            }

            @Override
            public long contentLength() {
                return size > 0 ? size : -1;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
                    if (inputStream == null) {
                        throw new IOException(getString(R.string.cannot_open_file, uri));
                    }
                    sink.writeAll(Okio.source(inputStream));
                }
            }
        };
    }

    private void onFileUploadSuccess(int index) {
        UploadFileItem item = fileList.get(index);
        
        mainHandler.post(() -> {
            item.setStatus(UploadFileItem.Status.COMPLETED);
            item.setProgress(100);
            adapter.updateItem(index);

            int completed = completedCount.incrementAndGet();
            tvCompletedFiles.setText(String.valueOf(completed));
            
            updateProgress();
            
            // 检查是否全部完成
            checkUploadComplete();
        });

        // 更新服务器状态为completed
        updateFileStatus(index, "completed");
    }

    private void onFileUploadFailed(int index, String error) {
        UploadFileItem item = fileList.get(index);
        
        mainHandler.post(() -> {
            item.setStatus(UploadFileItem.Status.FAILED);
            item.setErrorMessage(error);
            adapter.updateItem(index);

            int failed = failedCount.incrementAndGet();
            updateFailedCount(failed);
            
            Log.e(TAG, "文件上传失败: " + item.getName() + ", 错误: " + error);
            
            updateProgress();
            
            // 检查是否全部完成
            checkUploadComplete();
        });

        // 更新服务器状态为failed
        updateFileStatus(index, "failed");
    }
    
    private void checkUploadComplete() {
        int total = fileList.size();
        int completed = completedCount.get();
        int failed = failedCount.get();
        
        if (completed + failed >= total) {
            // 所有文件处理完成
            onUploadComplete();
        }
    }

    private void updateFileStatus(int fileIndex, String status) {
        Map<String, Object> request = new HashMap<>();
        request.put("device_id", deviceId);
        request.put("file_index", fileIndex);
        request.put("status", status);

        apiService.updateUploadProgress(request).enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> response) {
                if (response.isSuccessful()) {
                    Log.d(TAG, "文件状态更新成功: " + fileIndex + " -> " + status);
                }
            }

            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                Log.e(TAG, "文件状态更新失败", t);
            }
        });
    }

    private void updateFailedCount(int failed) {
        if (failed > 0) {
            tvFailedFiles.setVisibility(View.VISIBLE);
            tvFailedFiles.setText(getString(R.string.failed_count, failed));
        } else {
            tvFailedFiles.setVisibility(View.GONE);
        }
    }

    private void updateProgress() {
        int total = fileList.size();
        int completed = completedCount.get() + failedCount.get();
        int uploading = uploadingCount.get();
        int percent = total > 0 ? (completed * 100 / total) : 0;

        progressBar.setProgressCompat(percent, true);
        tvProgressPercent.setText(percent + "%");
        tvProgressText.setText(getString(R.string.uploading_progress, uploading, completed, total));
    }

    private void onUploadComplete() {
        isUploading = false;
        
        // 停止传输动画
        if (dataTransferAnimation != null) {
            dataTransferAnimation.stopAnimation();
        }
        
        btnCancel.setVisibility(View.GONE);
        btnDone.setVisibility(View.VISIBLE);

        int failed = failedCount.get();
        int completed = completedCount.get();
        int total = fileList.size();
        
        // 保存上传记录到数据库
        saveUploadRecord(total, completed, failed);
        
        if (failed == 0) {
            tvProgressText.setText(R.string.all_upload_complete);
            Toast.makeText(this, R.string.all_photos_uploaded, Toast.LENGTH_LONG).show();
        } else {
            tvProgressText.setText(getString(R.string.partial_upload, completed, failed));
            Toast.makeText(this, getString(R.string.upload_done_with_failures, failed), Toast.LENGTH_LONG).show();
        }
    }
    
    /**
     * 保存上传记录到数据库
     */
    private void saveUploadRecord(int total, int success, int failed) {
        new Thread(() -> {
            try {
                // 构建文件列表JSON
                JSONArray fileArray = new JSONArray();
                for (UploadFileItem item : fileList) {
                    JSONObject fileObj = new JSONObject();
                    fileObj.put("name", item.getName());
                    fileObj.put("path", item.getPath());
                    fileObj.put("size", item.getSize());
                    fileObj.put("success", item.getStatus() == UploadFileItem.Status.COMPLETED);
                    fileArray.put(fileObj);
                }
                
                // 创建上传记录
                UploadRecord record = new UploadRecord(
                    serverUrl,
                    serverName != null ? serverName : "Unknown Server",
                    total,
                    success,
                    failed,
                    System.currentTimeMillis(),
                    fileArray.toString()
                );
                
                // 保存到数据库
                long recordId = uploadRecordDao.insertUploadRecord(record);
                
                Log.d(TAG, "上传记录已保存，ID: " + recordId + ", 成功: " + success + ", 失败: " + failed);
                
                mainHandler.post(() -> {
                    Toast.makeText(this, R.string.record_saved, Toast.LENGTH_SHORT).show();
                });
                
            } catch (Exception e) {
                Log.e(TAG, "保存上传记录失败", e);
                mainHandler.post(() -> {
                    Toast.makeText(this, getString(R.string.save_record_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void cancelUpload() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.cancel_upload)
            .setMessage(R.string.cancel_upload_message)
            .setPositiveButton(R.string.confirm, (dialog, which) -> {
                isCancelled = true;
                isUploading = false;

                for (Call<?> call : activeCalls.values()) {
                    call.cancel();
                }
                activeCalls.clear();

                if (executorService != null && !executorService.isShutdown()) {
                    executorService.shutdownNow();
                }

                int cancelCount = 0;
                for (int i = 0; i < fileList.size(); i++) {
                    UploadFileItem item = fileList.get(i);
                    if (item.getStatus() == UploadFileItem.Status.PENDING
                            || item.getStatus() == UploadFileItem.Status.UPLOADING) {
                        item.setStatus(UploadFileItem.Status.FAILED);
                        item.setErrorMessage(getString(R.string.cancelled));
                        cancelCount++;
                    }
                }
                
                if (cancelCount > 0) {
                    failedCount.addAndGet(cancelCount);
                    updateFailedCount(failedCount.get());
                }
                
                adapter.notifyDataSetChanged();

                tvProgressText.setText(R.string.upload_cancelled);
                btnCancel.setVisibility(View.GONE);
                btnDone.setVisibility(View.VISIBLE);

                // 通知服务器取消
                apiService.cancelUpload(deviceId).enqueue(new Callback<Map<String, Object>>() {
                    @Override
                    public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> response) {
                        Log.d(TAG, "已通知服务器取消上传");
                    }

                    @Override
                    public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                        Log.e(TAG, "通知服务器取消失败", t);
                    }
                });
            })
            .setNegativeButton(R.string.continue_upload, null)
            .show();
    }

    private void updateEmptyState() {
        if (fileList.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            recyclerViewFiles.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            recyclerViewFiles.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onBackPressed() {
        if (isUploading) {
            Toast.makeText(this, R.string.cancel_first, Toast.LENGTH_SHORT).show();
        } else {
            super.onBackPressed();
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // 清理线程池资源
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
            Log.d(TAG, "线程池已关闭");
        }
        for (Call<?> call : activeCalls.values()) {
            call.cancel();
        }
        activeCalls.clear();
        
        // 清理Handler消息
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
    }
}


