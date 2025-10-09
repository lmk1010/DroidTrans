package com.mk.androidtransfer;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.mk.androidtransfer.adapter.PhotoGridAdapter;
import com.mk.androidtransfer.model.ApiResponse;
import com.mk.androidtransfer.model.PhotoInfo;
import com.mk.androidtransfer.model.PhotoListRequest;
import com.mk.androidtransfer.network.ApiService;
import com.mk.androidtransfer.network.RetrofitClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 照片选择Activity
 */
public class PhotoSelectionActivity extends AppCompatActivity {

    private static final String TAG = "PhotoSelectionActivity";

    // UI组件
    private TextView tvSelectionCount;
    private MaterialButton btnSelectAll;
    private MaterialButton btnDeselectAll;
    private MaterialButton btnSort;
    private RecyclerView recyclerViewPhotos;
    private FrameLayout loadingContainer;
    private LinearLayout emptyState;
    private ExtendedFloatingActionButton fabUpload;

    // 数据
    private PhotoGridAdapter photoAdapter;
    private List<PhotoInfo> photoList = new ArrayList<>();
    private String serverUrl;
    private String serverName;
    private ApiService apiService;

    // 权限请求
    private ActivityResultLauncher<String[]> permissionLauncher;

    // Handler for UI updates
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        setContentView(R.layout.activity_photo_selection);
        
        // 设置沉浸式状态栏（必须在setContentView之后）
        setupImmersiveStatusBar();

        // 获取传递的服务器信息
        serverUrl = getIntent().getStringExtra("server_url");
        serverName = getIntent().getStringExtra("server_name");

        if (serverUrl == null || serverUrl.isEmpty()) {
            Toast.makeText(this, "服务器地址无效", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 初始化API服务
        RetrofitClient retrofitClient = RetrofitClient.getInstance(serverUrl);
        apiService = retrofitClient.getApiService();

        initViews();
        setupToolbar();
        setupRecyclerView();
        setupListeners();
        initPermissions();

        // 请求权限并加载照片
        requestPermissionsAndLoadPhotos();
    }
    
    /**
     * 设置沉浸式状态栏
     */
    private void setupImmersiveStatusBar() {
        // 启用edge-to-edge显示
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11及以上
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                // 状态栏图标使用浅色（因为状态栏背景是深色青绿色）
                controller.setSystemBarsAppearance(0, 
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6.0到10
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
        }
    }

    /**
     * 初始化UI组件
     */
    private void initViews() {
        tvSelectionCount = findViewById(R.id.tvSelectionCount);
        btnSelectAll = findViewById(R.id.btnSelectAll);
        btnDeselectAll = findViewById(R.id.btnDeselectAll);
        btnSort = findViewById(R.id.btnSort);
        recyclerViewPhotos = findViewById(R.id.recyclerViewPhotos);
        loadingContainer = findViewById(R.id.loadingContainer);
        emptyState = findViewById(R.id.emptyState);
        fabUpload = findViewById(R.id.fabUpload);
    }

    /**
     * 设置Toolbar
     */
    private void setupToolbar() {
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.select_photos);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    /**
     * 设置RecyclerView
     */
    private void setupRecyclerView() {
        photoAdapter = new PhotoGridAdapter(this);
        GridLayoutManager layoutManager = new GridLayoutManager(this, 3);
        recyclerViewPhotos.setLayoutManager(layoutManager);
        recyclerViewPhotos.setAdapter(photoAdapter);

        photoAdapter.setOnPhotoClickListener(new PhotoGridAdapter.OnPhotoClickListener() {
            @Override
            public void onPhotoClick(PhotoInfo photo, int position) {
                // 点击照片时的处理已在adapter中完成
            }

            @Override
            public void onSelectionChanged(int selectedCount) {
                updateSelectionCount(selectedCount);
            }
        });
    }

    /**
     * 设置监听器
     */
    private void setupListeners() {
        // 全选
        btnSelectAll.setOnClickListener(v -> {
            photoAdapter.selectAll();
        });

        // 取消全选
        btnDeselectAll.setOnClickListener(v -> {
            photoAdapter.deselectAll();
        });

        // 排序（暂未实现）
        btnSort.setOnClickListener(v -> {
            Toast.makeText(this, "排序功能开发中", Toast.LENGTH_SHORT).show();
        });

        // 上传照片
        fabUpload.setOnClickListener(v -> {
            uploadSelectedPhotos();
        });
    }

    /**
     * 初始化权限请求
     */
    private void initPermissions() {
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean allGranted = true;
                    for (Boolean granted : result.values()) {
                        if (!granted) {
                            allGranted = false;
                            break;
                        }
                    }

                    if (allGranted) {
                        loadPhotos();
                    } else {
                        Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show();
                        finish();
                    }
                }
        );
    }

    /**
     * 请求权限并加载照片
     */
    private void requestPermissionsAndLoadPhotos() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(new String[]{
                        Manifest.permission.READ_MEDIA_IMAGES
                });
            } else {
                loadPhotos();
            }
        } else {
            // Android 12及以下
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE
                });
            } else {
                loadPhotos();
            }
        }
    }

    /**
     * 加载照片（仅图片，不包括视频）
     */
    private void loadPhotos() {
        showLoading(true);

        // 在后台线程加载照片
        new Thread(() -> {
            try {
                List<PhotoInfo> photos = scanImages();

                // 回到主线程更新UI
                mainHandler.post(() -> {
                    showLoading(false);
                    photoList = photos;
                    photoAdapter.updateData(photoList);

                    if (photoList.isEmpty()) {
                        emptyState.setVisibility(View.VISIBLE);
                        recyclerViewPhotos.setVisibility(View.GONE);
                        fabUpload.setVisibility(View.GONE);
                    } else {
                        emptyState.setVisibility(View.GONE);
                        recyclerViewPhotos.setVisibility(View.VISIBLE);
                        fabUpload.setVisibility(View.VISIBLE);
                    }

                    updateSelectionCount(0);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    showLoading(false);
                    Toast.makeText(this, "加载照片失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    Log.e(TAG, "加载照片失败", e);
                });
            }
        }).start();
    }

    /**
     * 扫描图片（仅图片）
     */
    private List<PhotoInfo> scanImages() {
        List<PhotoInfo> photos = new ArrayList<>();
        ContentResolver contentResolver = getContentResolver();

        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.DATA
        };

        String sortOrder = MediaStore.Images.Media.DATE_MODIFIED + " DESC";

        try (Cursor cursor = contentResolver.query(
                collection,
                projection,
                null,
                null,
                sortOrder
        )) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
                int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED);
                int dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);

                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    String name = cursor.getString(nameColumn);
                    long size = cursor.getLong(sizeColumn);
                    long dateModified = cursor.getLong(dateColumn);
                    String path = cursor.getString(dataColumn);

                    // 构建Content URI
                    Uri contentUri = Uri.withAppendedPath(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            String.valueOf(id)
                    );

                    String date = dateFormat.format(new Date(dateModified * 1000));

                    PhotoInfo photo = new PhotoInfo(
                            path,
                            name,
                            size,
                            dateModified,
                            date,
                            contentUri.toString()
                    );

                    photos.add(photo);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "扫描图片失败", e);
        }

        return photos;
    }

    /**
     * 更新选中数量显示
     */
    private void updateSelectionCount(int count) {
        if (count == 0) {
            tvSelectionCount.setText(R.string.selected_count_zero);
        } else {
            tvSelectionCount.setText(getString(R.string.selected_count, count));
        }
    }

    /**
     * 显示/隐藏加载状态
     */
    private void showLoading(boolean show) {
        loadingContainer.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /**
     * 上传选中的照片
     */
    private void uploadSelectedPhotos() {
        List<PhotoInfo> selectedPhotos = photoAdapter.getSelectedPhotos();
        if (selectedPhotos.isEmpty()) {
            Toast.makeText(this, "请先选择照片", Toast.LENGTH_SHORT).show();
            return;
        }

        // 显示确认对话框
        new AlertDialog.Builder(this)
                .setTitle("确认上传")
                .setMessage("确定要上传 " + selectedPhotos.size() + " 张照片吗？")
                .setPositiveButton("上传", (dialog, which) -> {
                    // 跳转到上传进度页面
                    Intent intent = new Intent(PhotoSelectionActivity.this, UploadProgressActivity.class);
                    intent.putExtra("server_url", serverUrl);
                    intent.putParcelableArrayListExtra("selected_photos", new ArrayList<>(selectedPhotos));
                    startActivity(intent);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 开始上传
     */
    private void startUpload(List<PhotoInfo> selectedPhotos) {
        showLoading(true);
        fabUpload.setEnabled(false);

        // 第一步：上传照片列表信息
        String deviceId = getAndroidDeviceId();
        PhotoListRequest request = new PhotoListRequest(deviceId, selectedPhotos);

        apiService.uploadPhotoList(request).enqueue(new Callback<ApiResponse<Object>>() {
            @Override
            public void onResponse(Call<ApiResponse<Object>> call, Response<ApiResponse<Object>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    // 照片列表上传成功，开始上传文件
                    uploadPhotoFiles(selectedPhotos, 0, selectedPhotos.size());
                } else {
                    showLoading(false);
                    fabUpload.setEnabled(true);
                    Toast.makeText(PhotoSelectionActivity.this, "上传照片列表失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<Object>> call, Throwable t) {
                showLoading(false);
                fabUpload.setEnabled(true);
                Toast.makeText(PhotoSelectionActivity.this, "上传失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                Log.e(TAG, "上传照片列表失败", t);
            }
        });
    }

    /**
     * 递归上传照片文件
     */
    private void uploadPhotoFiles(List<PhotoInfo> photos, int index, int total) {
        if (index >= photos.size()) {
            // 所有照片上传完成
            showLoading(false);
            fabUpload.setEnabled(true);
            Toast.makeText(this, "上传完成！", Toast.LENGTH_LONG).show();

            // 上传成功后返回
            finish();
            return;
        }

        PhotoInfo photo = photos.get(index);
        Log.d(TAG, "上传照片 " + (index + 1) + "/" + total + ": " + photo.getName());

        try {
            // 从URI读取文件
            Uri uri = Uri.parse(photo.getUri());
            ContentResolver resolver = getContentResolver();
            InputStream inputStream = resolver.openInputStream(uri);

            if (inputStream == null) {
                // 跳过此文件，继续下一个
                uploadPhotoFiles(photos, index + 1, total);
                return;
            }

            // 创建临时文件
            File tempFile = new File(getCacheDir(), photo.getName());
            FileOutputStream outputStream = new FileOutputStream(tempFile);

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            inputStream.close();
            outputStream.close();

            // 创建Multipart请求
            RequestBody fileBody = RequestBody.create(
                    MediaType.parse("application/octet-stream"),
                    tempFile
            );
            MultipartBody.Part filePart = MultipartBody.Part.createFormData(
                    "file",
                    photo.getName(),
                    fileBody
            );

            String relativePath = photo.getPath().replaceFirst("^/storage/emulated/0/", "")
                    .replaceFirst("^/sdcard/", "");
            RequestBody relativePathBody = RequestBody.create(
                    MediaType.parse("text/plain"),
                    relativePath
            );
            RequestBody outputDirBody = RequestBody.create(
                    MediaType.parse("text/plain"),
                    "./photos_output"
            );

            // 上传文件
            apiService.uploadPhoto(filePart, relativePathBody, outputDirBody)
                    .enqueue(new Callback<ApiResponse<Object>>() {
                        @Override
                        public void onResponse(Call<ApiResponse<Object>> call, Response<ApiResponse<Object>> response) {
                            tempFile.delete(); // 删除临时文件

                            if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                                Log.d(TAG, "上传成功: " + photo.getName());
                            } else {
                                Log.e(TAG, "上传失败: " + photo.getName());
                            }

                            // 继续上传下一个文件
                            uploadPhotoFiles(photos, index + 1, total);
                        }

                        @Override
                        public void onFailure(Call<ApiResponse<Object>> call, Throwable t) {
                            tempFile.delete(); // 删除临时文件
                            Log.e(TAG, "上传失败: " + photo.getName(), t);

                            // 继续上传下一个文件
                            uploadPhotoFiles(photos, index + 1, total);
                        }
                    });

        } catch (Exception e) {
            Log.e(TAG, "准备文件失败: " + photo.getName(), e);
            // 继续上传下一个文件
            uploadPhotoFiles(photos, index + 1, total);
        }
    }

    /**
     * 获取设备ID
     */
    private String getAndroidDeviceId() {
        return Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
    }
}
