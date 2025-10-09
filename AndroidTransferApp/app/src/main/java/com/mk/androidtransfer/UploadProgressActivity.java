package com.mk.androidtransfer;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowInsetsController;
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
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.mk.androidtransfer.adapter.UploadFileAdapter;
import com.mk.androidtransfer.model.PhotoInfo;
import com.mk.androidtransfer.model.UploadFileItem;
import com.mk.androidtransfer.network.ApiService;
import com.mk.androidtransfer.network.RetrofitClient;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 上传进度Activity
 */
public class UploadProgressActivity extends AppCompatActivity {

    private static final String TAG = "UploadProgressActivity";

    // UI组件
    private MaterialToolbar toolbar;
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
    private String deviceId;
    private ApiService apiService;

    // 状态
    private boolean isUploading = false;
    private boolean isCancelled = false;
    private int currentIndex = 0;
    private int completedCount = 0;
    private int failedCount = 0;

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
        ArrayList<PhotoInfo> selectedPhotos = getIntent().getParcelableArrayListExtra("selected_photos");

        if (serverUrl == null || selectedPhotos == null || selectedPhotos.isEmpty()) {
            Toast.makeText(this, "参数错误", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 获取设备ID
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        // 初始化网络
        apiService = RetrofitClient.getInstance(serverUrl).getApiService();

        // 初始化文件列表
        initFileList(selectedPhotos);

        // 初始化上传会话
        initUploadSession();

        // 开始上传
        startUpload();
    }

    private void setupImmersiveStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                );
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            );
        }
        
        getWindow().setStatusBarColor(ContextCompat.getColor(this, android.R.color.transparent));
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
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

        toolbar.setNavigationOnClickListener(v -> onBackPressed());

        // 设置RecyclerView
        adapter = new UploadFileAdapter(this);
        recyclerViewFiles.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewFiles.setAdapter(adapter);

        // 按钮点击事件
        btnCancel.setOnClickListener(v -> cancelUpload());
        btnDone.setOnClickListener(v -> finish());
    }

    private void initFileList(ArrayList<PhotoInfo> photos) {
        for (PhotoInfo photo : photos) {
            UploadFileItem item = new UploadFileItem(
                photo.getName(),
                photo.getPath(),
                photo.getSize()
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
        currentIndex = 0;
        uploadNextFile();
    }

    private void uploadNextFile() {
        if (isCancelled) {
            return;
        }

        if (currentIndex >= fileList.size()) {
            // 所有文件上传完成
            onUploadComplete();
            return;
        }

        UploadFileItem item = fileList.get(currentIndex);
        item.setStatus(UploadFileItem.Status.UPLOADING);
        adapter.updateItem(currentIndex);
        updateProgress();

        // 滚动到当前上传的文件
        recyclerViewFiles.smoothScrollToPosition(currentIndex);

        // 更新服务器状态为uploading
        updateFileStatus(currentIndex, "uploading");

        // 上传文件
        uploadFile(item, currentIndex);
    }

    private void uploadFile(UploadFileItem item, int index) {
        try {
            File file = new File(item.getPath());
            if (!file.exists()) {
                onFileUploadFailed(index, "文件不存在");
                return;
            }

            RequestBody requestFile = RequestBody.create(MediaType.parse("image/*"), file);
            MultipartBody.Part body = MultipartBody.Part.createFormData("file", file.getName(), requestFile);
            
            RequestBody deviceIdBody = RequestBody.create(MediaType.parse("text/plain"), deviceId);
            RequestBody relativePathBody = RequestBody.create(MediaType.parse("text/plain"), file.getName());

            Call<Map<String, Object>> call = apiService.uploadPhotoMultipart(body, deviceIdBody, relativePathBody);

            // 记录开始时间用于计算速度
            long startTime = System.currentTimeMillis();
            long startBytes = 0;

            call.enqueue(new Callback<Map<String, Object>>() {
                @Override
                public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> response) {
                    if (response.isSuccessful()) {
                        onFileUploadSuccess(index);
                    } else {
                        onFileUploadFailed(index, "上传失败: " + response.code());
                    }
                }

                @Override
                public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                    onFileUploadFailed(index, t.getMessage());
                }
            });

            // 模拟进度更新（实际应该使用OkHttp的ProgressRequestBody）
            simulateProgress(item, index);

        } catch (Exception e) {
            onFileUploadFailed(index, e.getMessage());
        }
    }

    private void simulateProgress(UploadFileItem item, int index) {
        // 简单的模拟进度（实际应该使用ProgressRequestBody）
        new Thread(() -> {
            try {
                for (int progress = 0; progress <= 100; progress += 10) {
                    if (isCancelled || item.getStatus() != UploadFileItem.Status.UPLOADING) {
                        break;
                    }
                    
                    final int currentProgress = progress;
                    mainHandler.post(() -> {
                        item.setProgress(currentProgress);
                        adapter.updateItem(index);
                    });
                    
                    Thread.sleep(100); // 模拟上传时间
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void onFileUploadSuccess(int index) {
        UploadFileItem item = fileList.get(index);
        item.setStatus(UploadFileItem.Status.COMPLETED);
        item.setProgress(100);
        adapter.updateItem(index);

        completedCount++;
        tvCompletedFiles.setText(String.valueOf(completedCount));

        // 更新服务器状态为completed
        updateFileStatus(index, "completed");

        // 继续下一个文件
        currentIndex++;
        mainHandler.postDelayed(this::uploadNextFile, 300);
        
        updateProgress();
    }

    private void onFileUploadFailed(int index, String error) {
        UploadFileItem item = fileList.get(index);
        item.setStatus(UploadFileItem.Status.FAILED);
        item.setErrorMessage(error);
        adapter.updateItem(index);

        failedCount++;
        tvFailedFiles.setText(String.valueOf(failedCount));

        Log.e(TAG, "文件上传失败: " + item.getName() + ", 错误: " + error);

        // 更新服务器状态为failed
        updateFileStatus(index, "failed");

        // 继续下一个文件
        currentIndex++;
        mainHandler.postDelayed(this::uploadNextFile, 300);
        
        updateProgress();
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

    private void updateProgress() {
        int total = fileList.size();
        int completed = completedCount + failedCount;
        int percent = total > 0 ? (completed * 100 / total) : 0;

        progressBar.setProgress(percent);
        tvProgressPercent.setText(percent + "%");
        tvProgressText.setText(String.format("正在上传第 %d / %d 个文件", currentIndex + 1, total));
    }

    private void onUploadComplete() {
        isUploading = false;
        btnCancel.setVisibility(View.GONE);
        btnDone.setVisibility(View.VISIBLE);

        if (failedCount == 0) {
            tvProgressText.setText("全部上传完成！");
            Toast.makeText(this, "所有照片上传成功", Toast.LENGTH_LONG).show();
        } else {
            tvProgressText.setText(String.format("部分上传完成（失败 %d 个）", failedCount));
            Toast.makeText(this, String.format("上传完成，失败 %d 个文件", failedCount), Toast.LENGTH_LONG).show();
        }
    }

    private void cancelUpload() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("取消上传")
            .setMessage("确定要取消上传吗？已上传的文件不会被删除。")
            .setPositiveButton("确定", (dialog, which) -> {
                isCancelled = true;
                isUploading = false;

                // 更新未上传的文件状态
                for (int i = currentIndex; i < fileList.size(); i++) {
                    if (fileList.get(i).getStatus() == UploadFileItem.Status.PENDING) {
                        fileList.get(i).setStatus(UploadFileItem.Status.FAILED);
                        fileList.get(i).setErrorMessage("已取消");
                        failedCount++;
                    }
                }
                adapter.notifyDataSetChanged();

                tvProgressText.setText("已取消上传");
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
            .setNegativeButton("继续上传", null)
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
            Toast.makeText(this, "上传进行中，请先取消上传", Toast.LENGTH_SHORT).show();
        } else {
            super.onBackPressed();
        }
    }
}

