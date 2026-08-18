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
import android.widget.ImageButton;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.mk.androidtransfer.adapter.AlbumListAdapter;
import com.mk.androidtransfer.adapter.PhotoGridAdapter;
import com.mk.androidtransfer.database.UploadRecordDao;
import com.mk.androidtransfer.model.AlbumInfo;
import com.mk.androidtransfer.model.ApiResponse;
import com.mk.androidtransfer.model.PhotoInfo;
import com.mk.androidtransfer.model.PhotoListRequest;
import com.mk.androidtransfer.network.ApiService;
import com.mk.androidtransfer.network.RetrofitClient;
import com.mk.androidtransfer.utils.PhotoScanner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    private TextView tvPhotoTitle;
    private TextView tvPhotoSubtitle;
    private com.google.android.material.chip.Chip chipSelectionCount;
    private ImageButton btnBack;
    private ImageButton btnViewMode;  // 视图切换按钮
    private MaterialButton btnSelectAll;
    private MaterialButton btnDeselectAll;
    private MaterialButton btnSort;
    private MaterialButton btnFilterUploaded;
    private RecyclerView recyclerViewPhotos;
    private RecyclerView recyclerViewAlbums;  // 相册列表
    private FrameLayout loadingContainer;
    private LinearLayout emptyState;
    private ExtendedFloatingActionButton fabUpload;

    // 数据
    private PhotoGridAdapter photoAdapter;
    private AlbumListAdapter albumAdapter;
    private List<PhotoInfo> photoList = new ArrayList<>();
    private List<PhotoInfo> allPhotoList = new ArrayList<>(); // 保存所有照片
    private List<AlbumInfo> albumList = new ArrayList<>();    // 相册列表
    private AlbumInfo currentAlbum = null;  // 当前选中的相册
    private boolean isAlbumView = true;     // 是否为相册视图
    private boolean isAlbumSelectionMode = false;  // 相册选择模式
    private String serverUrl;
    private String serverName;
    private ApiService apiService;
    private UploadRecordDao uploadRecordDao;
    private boolean isFilteringUploaded = false;
    private int currentSort = 0; // 0 时间 1 大小 2 名称

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
        
        // 初始化数据库
        uploadRecordDao = new UploadRecordDao(this);

        initViews();
        setupListeners();
        setupRecyclerView();
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
            // Android 11及以上，使用浅色图标（配合白色/浅色背景）
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                );
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6.0到10，使用浅色图标
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            );
        }
    }

    /**
     * 初始化UI组件
     */
    private void initViews() {
        tvPhotoTitle = findViewById(R.id.tvPhotoTitle);
        tvPhotoSubtitle = findViewById(R.id.tvPhotoSubtitle);
        chipSelectionCount = findViewById(R.id.chipSelectionCount);
        btnBack = findViewById(R.id.btnBack);
        btnViewMode = findViewById(R.id.btnViewMode);
        btnSelectAll = findViewById(R.id.btnSelectAll);
        btnDeselectAll = findViewById(R.id.btnDeselectAll);
        btnSort = findViewById(R.id.btnSort);
        btnFilterUploaded = findViewById(R.id.btnFilterUploaded);
        recyclerViewPhotos = findViewById(R.id.recyclerViewPhotos);
        recyclerViewAlbums = findViewById(R.id.recyclerViewAlbums);
        loadingContainer = findViewById(R.id.loadingContainer);
        emptyState = findViewById(R.id.emptyState);
        fabUpload = findViewById(R.id.fabUpload);
        
        // 设置服务器名称
        if (serverName != null && !serverName.isEmpty()) {
            tvPhotoSubtitle.setText("从 " + serverName + " 选择照片");
        }
    }

    /**
     * 设置监听器
     */
    private void setupListeners() {
        // 返回按钮
        btnBack.setOnClickListener(v -> handleBackPress());
        
        // 视图切换按钮
        btnViewMode.setOnClickListener(v -> {
            if (isAlbumView) {
                switchToPhotoView();
            } else {
                switchToAlbumView();
            }
        });
        
        // 全选
        btnSelectAll.setOnClickListener(v -> {
            if (isAlbumView && !isAlbumSelectionMode) {
                // 相册视图且未进入选择模式：自动进入选择模式并全选
                enterAlbumSelectionMode();
                albumAdapter.selectAll();
                updateAlbumSelectionCount();
            } else if (isAlbumSelectionMode) {
                // 相册选择模式：全选相册
                albumAdapter.selectAll();
                updateAlbumSelectionCount();
            } else if (!isAlbumView) {
                // 照片视图：全选照片
                photoAdapter.selectAll();
                // updateSelectionCount 会在 photoAdapter.selectAll() 中通过回调自动触发
            }
        });

        // 取消全选
        btnDeselectAll.setOnClickListener(v -> {
            if (isAlbumView && !isAlbumSelectionMode) {
                // 相册视图且未进入选择模式：提示用户先选择相册
                Toast.makeText(this, "请先长按相册进入选择模式，或点击全选", Toast.LENGTH_SHORT).show();
            } else if (isAlbumSelectionMode) {
                // 相册选择模式：取消全选相册
                albumAdapter.deselectAll();
                updateAlbumSelectionCount();
            } else if (!isAlbumView) {
                // 照片视图：取消全选照片
                photoAdapter.deselectAll();
                // updateSelectionCount 会在 photoAdapter.deselectAll() 中通过回调自动触发
            }
        });

        // 排序
        btnSort.setOnClickListener(v -> {
            showSortDialog();
        });
        
        // 过滤已上传照片
        btnFilterUploaded.setOnClickListener(v -> {
            toggleFilterUploaded();
        });

        // 上传照片
        fabUpload.setOnClickListener(v -> {
            if (isAlbumSelectionMode) {
                uploadSelectedAlbums();
            } else {
                uploadSelectedPhotos();
            }
        });
    }
    
    /**
     * 处理返回按钮点击
     */
    private void handleBackPress() {
        if (isAlbumSelectionMode) {
            // 相册选择模式，退出选择模式
            exitAlbumSelectionMode();
        } else if (isAlbumView) {
            // 在相册视图，直接退出
            finish();
        } else {
            // 在照片视图，返回相册视图
            switchToAlbumView();
        }
    }
    
    /**
     * 重写返回键处理
     */
    @Override
    public void onBackPressed() {
        if (isAlbumSelectionMode) {
            // 相册选择模式，退出选择模式
            exitAlbumSelectionMode();
        } else if (isAlbumView) {
            // 在相册视图，调用默认返回
            super.onBackPressed();
        } else {
            // 在照片视图，返回相册视图
            switchToAlbumView();
        }
    }

    /**
     * 设置RecyclerView
     */
    private void setupRecyclerView() {
        // 设置照片网格适配器
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
        
        // 设置相册列表适配器（2列网格）
        albumAdapter = new AlbumListAdapter(this);
        GridLayoutManager albumLayoutManager = new GridLayoutManager(this, 2);
        recyclerViewAlbums.setLayoutManager(albumLayoutManager);
        recyclerViewAlbums.setAdapter(albumAdapter);
        
        albumAdapter.setOnAlbumClickListener((album, position) -> {
            if (!isAlbumSelectionMode) {
                currentAlbum = album;
                switchToPhotoView();
                showPhotosInAlbum(album);
            }
        });
        
        albumAdapter.setOnAlbumLongClickListener((album, position) -> {
            // 长按进入相册选择模式
            enterAlbumSelectionMode();
            albumAdapter.toggleAlbumSelection(album.getAlbumPath());
        });
        
        // 设置相册选择状态改变监听器
        albumAdapter.setOnSelectionChangedListener(selectedCount -> {
            // 当相册选择状态改变时，更新显示
            if (isAlbumSelectionMode) {
                updateAlbumSelectionCount();
            }
        });
    }

    /**
     * 初始化权限请求
     */
    private void initPermissions() {
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    if (hasAnyMediaPermission()) {
                        loadPhotos();
                    } else {
                        Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show();
                        finish();
                    }
                }
        );
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAnyMediaPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean selected = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    && hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
            return hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    || hasPermission(Manifest.permission.READ_MEDIA_VIDEO)
                    || selected;
        }
        return hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    /**
     * 请求权限并加载照片
     */
    private void requestPermissionsAndLoadPhotos() {
        List<String> toRequest = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.READ_MEDIA_IMAGES)) {
                toRequest.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (!hasPermission(Manifest.permission.READ_MEDIA_VIDEO)) {
                toRequest.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
        } else if (!hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            toRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        if (toRequest.isEmpty()) {
            loadPhotos();
        } else {
            permissionLauncher.launch(toRequest.toArray(new String[0]));
        }
    }

    /**
     * 加载照片
     */
    private void loadPhotos() {
        showLoading(true);

        // 在后台线程加载照片
        new Thread(() -> {
            try {
                PhotoScanner.ScanResult result = PhotoScanner.scan(this);
                List<PhotoInfo> photos = result.photos;
                List<AlbumInfo> albums = result.albums;

                // 回到主线程更新UI
                mainHandler.post(() -> {
                    showLoading(false);
                    allPhotoList = photos;
                    photoList = new ArrayList<>(photos);
                    albumList = albums;
                    
                    Log.d(TAG, "加载完成 - 媒体总数: " + photos.size() + ", 相册数: " + albums.size());
                    
                    // 显示相册列表
                    albumAdapter.setAlbumList(albumList);
                    
                    if (albumList.isEmpty()) {
                        Log.d(TAG, "相册列表为空");
                        emptyState.setVisibility(View.VISIBLE);
                        recyclerViewAlbums.setVisibility(View.GONE);
                        fabUpload.setVisibility(View.GONE);
                    } else {
                        Log.d(TAG, "显示相册列表，相册数: " + albumList.size());
                        emptyState.setVisibility(View.GONE);
                        recyclerViewAlbums.setVisibility(View.VISIBLE);
                        fabUpload.setVisibility(View.VISIBLE);
                    }

                    updateSelectionCount(0);
                    
                    // 更新按钮显示已上传数量
                    updateFilterButtonText();
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
     * 切换到相册视图
     */
    private void switchToAlbumView() {
        isAlbumView = true;
        currentAlbum = null;
        
        // 更新UI
        recyclerViewAlbums.setVisibility(View.VISIBLE);
        recyclerViewPhotos.setVisibility(View.GONE);
        btnViewMode.setImageResource(R.drawable.ic_view_grid);
        tvPhotoTitle.setText("照片相册");
        
        // 取消所有照片选中
        photoAdapter.deselectAll();
        
        // 在相册视图下，始终显示操作按钮（用于相册选择）
        findViewById(R.id.actionButtonsScroll).setVisibility(View.VISIBLE);
        
        // 显示全选和取消全选按钮
        btnSelectAll.setVisibility(View.VISIBLE);
        btnDeselectAll.setVisibility(View.VISIBLE);
        
        // 隐藏排序和过滤按钮（相册视图不需要）
        btnSort.setVisibility(View.GONE);
        btnFilterUploaded.setVisibility(View.GONE);
        
        // 如果在选择模式，显示选择数量
        if (isAlbumSelectionMode) {
            chipSelectionCount.setVisibility(View.VISIBLE);
            updateAlbumSelectionCount();
        } else {
            chipSelectionCount.setVisibility(View.GONE);
        }
    }
    
    /**
     * 切换到照片视图
     */
    private void switchToPhotoView() {
        isAlbumView = false;
        
        // 更新UI
        recyclerViewAlbums.setVisibility(View.GONE);
        recyclerViewPhotos.setVisibility(View.VISIBLE);
        btnViewMode.setImageResource(R.drawable.ic_view_list);
        
        // 显示操作按钮
        findViewById(R.id.actionButtonsScroll).setVisibility(View.VISIBLE);
        chipSelectionCount.setVisibility(View.VISIBLE);
        
        // 显示全选和取消全选按钮
        btnSelectAll.setVisibility(View.VISIBLE);
        btnDeselectAll.setVisibility(View.VISIBLE);
        
        // 显示排序和过滤按钮
        btnSort.setVisibility(View.VISIBLE);
        btnFilterUploaded.setVisibility(View.VISIBLE);
        
        // 如果选中了相册，显示相册名称
        if (currentAlbum != null) {
            tvPhotoTitle.setText(currentAlbum.getAlbumName());
        } else {
            tvPhotoTitle.setText("所有照片");
        }
        
        // 更新选择数量显示
        updateSelectionCount(photoAdapter.getSelectedCount());
    }
    
    /**
     * 显示相册中的照片
     */
    private void showPhotosInAlbum(AlbumInfo album) {
        applyPhotoList(album.getPhotos());
        photoAdapter.deselectAll();
        updateSelectionCount(0);
    }

    private List<PhotoInfo> currentSourcePhotos() {
        if (currentAlbum != null) {
            return currentAlbum.getPhotos();
        }
        return allPhotoList;
    }

    private void applyPhotoList(List<PhotoInfo> source) {
        List<PhotoInfo> next = new ArrayList<>(source);
        if (isFilteringUploaded) {
            try {
                Set<String> uploadedPaths = uploadRecordDao.getUploadedFilePaths();
                List<PhotoInfo> filtered = new ArrayList<>();
                for (PhotoInfo photo : next) {
                    if (!uploadedPaths.contains(photo.getStablePath())
                            && !uploadedPaths.contains(photo.getPath())) {
                        filtered.add(photo);
                    }
                }
                next = filtered;
            } catch (Exception e) {
                Log.e(TAG, "过滤已上传照片失败", e);
            }
        }
        sortPhotoList(next);
        photoList = next;
        photoAdapter.updateData(photoList);
    }

    private void sortPhotoList(List<PhotoInfo> photos) {
        Comparator<PhotoInfo> comparator;
        if (currentSort == 1) {
            comparator = (a, b) -> Long.compare(b.getSize(), a.getSize());
        } else if (currentSort == 2) {
            comparator = (a, b) -> {
                String na = a.getName() != null ? a.getName() : "";
                String nb = b.getName() != null ? b.getName() : "";
                return na.compareToIgnoreCase(nb);
            };
        } else {
            comparator = (a, b) -> Long.compare(b.getMtime(), a.getMtime());
        }
        Collections.sort(photos, comparator);
    }
    
    /**
     * 进入相册选择模式
     */
    private void enterAlbumSelectionMode() {
        isAlbumSelectionMode = true;
        albumAdapter.setSelectionMode(true);
        
        // 更新UI
        tvPhotoTitle.setText("选择相册");
        btnViewMode.setVisibility(View.GONE);
        
        // 确保操作按钮容器可见
        findViewById(R.id.actionButtonsScroll).setVisibility(View.VISIBLE);
        
        // 确保选择按钮可见
        btnSelectAll.setVisibility(View.VISIBLE);
        btnDeselectAll.setVisibility(View.VISIBLE);
        
        // 隐藏其他按钮
        btnSort.setVisibility(View.GONE);
        btnFilterUploaded.setVisibility(View.GONE);
        
        // 显示选择数量
        chipSelectionCount.setVisibility(View.VISIBLE);
        
        // 更新选择数量显示
        updateAlbumSelectionCount();
        
        // 修改返回按钮行为
        btnBack.setImageResource(R.drawable.ic_close);
    }
    
    /**
     * 退出相册选择模式
     */
    private void exitAlbumSelectionMode() {
        isAlbumSelectionMode = false;
        albumAdapter.setSelectionMode(false);
        
        // 更新UI
        tvPhotoTitle.setText("照片相册");
        btnViewMode.setVisibility(View.VISIBLE);
        
        // 保持操作按钮容器可见（在相册视图下用于快速选择）
        findViewById(R.id.actionButtonsScroll).setVisibility(View.VISIBLE);
        
        // 保持选择按钮可见（方便用户再次快速选择）
        btnSelectAll.setVisibility(View.VISIBLE);
        btnDeselectAll.setVisibility(View.VISIBLE);
        
        // 隐藏其他按钮（相册视图不需要排序和过滤）
        btnSort.setVisibility(View.GONE);
        btnFilterUploaded.setVisibility(View.GONE);
        
        // 隐藏选择数量（退出选择模式后不显示）
        chipSelectionCount.setVisibility(View.GONE);
        
        // 恢复返回按钮
        btnBack.setImageResource(R.drawable.ic_arrow_back);
    }
    
    /**
     * 更新相册选择数量显示
     */
    private void updateAlbumSelectionCount() {
        int count = albumAdapter.getSelectedCount();
        if (count == 0) {
            chipSelectionCount.setText("已选 0 个相册");
            // 在相册选择模式下，即使为0也要显示
            chipSelectionCount.setVisibility(View.VISIBLE);
        } else {
            chipSelectionCount.setVisibility(View.VISIBLE);
            chipSelectionCount.setText("已选 " + count + " 个相册");
        }
        
        // 更新FAB文本
        if (count > 0) {
            fabUpload.setText("上传相册");
            fabUpload.setEnabled(true);
        } else {
            fabUpload.setText("上传照片");
            fabUpload.setEnabled(false);
        }
    }
    
    /**
     * 上传选中的相册
     */
    private void uploadSelectedAlbums() {
        List<AlbumInfo> selectedAlbums = albumAdapter.getSelectedAlbums();
        if (selectedAlbums.isEmpty()) {
            Toast.makeText(this, "请至少选择一个相册", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 收集所有选中相册的照片
        List<PhotoInfo> photosToUpload = new ArrayList<>();
        for (AlbumInfo album : selectedAlbums) {
            photosToUpload.addAll(album.getPhotos());
        }
        
        // 显示确认对话框
        int albumCount = selectedAlbums.size();
        int photoCount = photosToUpload.size();
        new AlertDialog.Builder(this)
                .setTitle("确认上传")
                .setMessage("将上传 " + albumCount + " 个相册共 " + photoCount + " 个文件")
                .setPositiveButton("确定", (dialog, which) -> {
                    startUpload(photosToUpload);
                    exitAlbumSelectionMode();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 更新选择数量显示
     */
    private void updateSelectionCount(int count) {
        if (count == 0) {
            chipSelectionCount.setText("已选 0");
            chipSelectionCount.setVisibility(isAlbumView ? View.GONE : View.VISIBLE);
        } else {
            chipSelectionCount.setVisibility(View.VISIBLE);
            chipSelectionCount.setText("已选 " + count);
        }
    }

    /**
     * 显示排序对话框
     */
    private void showSortDialog() {
        String[] sortOptions = {"按时间排序", "按大小排序", "按名称排序"};
        new AlertDialog.Builder(this)
                .setTitle("排序方式")
                .setSingleChoiceItems(sortOptions, currentSort, (dialog, which) -> {
                    currentSort = which;
                    applyPhotoList(currentSourcePhotos());
                    dialog.dismiss();
                })
                .show();
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
                .setMessage("确定要上传 " + selectedPhotos.size() + " 个文件吗？")
                .setPositiveButton("上传", (dialog, which) -> {
                    // 跳转到上传进度页面
                    Intent intent = new Intent(PhotoSelectionActivity.this, UploadProgressActivity.class);
                    intent.putExtra("server_url", serverUrl);
                    intent.putExtra("server_name", serverName);
                    intent.putParcelableArrayListExtra("selected_photos", new ArrayList<>(selectedPhotos));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    startActivity(intent);
                    overridePendingTransition(0, 0);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 开始上传 - 跳转到上传进度页面
     */
    private void startUpload(List<PhotoInfo> selectedPhotos) {
        // 使用新的上传进度页面，提供更好的用户体验和上传记录保存
        Intent intent = new Intent(this, UploadProgressActivity.class);
        intent.putExtra("server_url", serverUrl);
        intent.putExtra("server_name", serverName != null ? serverName : "未知服务器");
        intent.putParcelableArrayListExtra("selected_photos", new ArrayList<>(selectedPhotos));
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(intent);
        overridePendingTransition(0, 0);
        
        // 上传开始后关闭选择页面
        finish();
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
    
    /**
     * 切换过滤已上传照片
     */
    private void toggleFilterUploaded() {
        isFilteringUploaded = !isFilteringUploaded;
        showLoading(true);
        new Thread(() -> {
            List<PhotoInfo> source = currentSourcePhotos();
            int excluded = 0;
            try {
                if (isFilteringUploaded) {
                    Set<String> uploadedPaths = uploadRecordDao.getUploadedFilePaths();
                    for (PhotoInfo photo : source) {
                        if (uploadedPaths.contains(photo.getStablePath())
                                || uploadedPaths.contains(photo.getPath())) {
                            excluded++;
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "过滤已上传照片失败", e);
            }
            final int finalExcluded = excluded;
            mainHandler.post(() -> {
                showLoading(false);
                applyPhotoList(currentSourcePhotos());
                btnFilterUploaded.setText(isFilteringUploaded ? "显示全部" : "排除已上传");
                if (isFilteringUploaded) {
                    if (finalExcluded > 0) {
                        Toast.makeText(this, "已排除 " + finalExcluded + " 个已上传文件", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "暂无已上传文件", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(this, "显示全部文件", Toast.LENGTH_SHORT).show();
                    updateFilterButtonText();
                }
                if (photoList.isEmpty()) {
                    emptyState.setVisibility(View.VISIBLE);
                    recyclerViewPhotos.setVisibility(View.GONE);
                } else {
                    emptyState.setVisibility(View.GONE);
                    recyclerViewPhotos.setVisibility(View.VISIBLE);
                }
                updateSelectionCount(0);
            });
        }).start();
    }
    
    /**
     * 更新过滤按钮文字
     */
    private void updateFilterButtonText() {
        new Thread(() -> {
            try {
                Set<String> uploadedPaths = uploadRecordDao.getUploadedFilePaths();
                int uploadedCount = 0;
                
                for (PhotoInfo photo : allPhotoList) {
                    if (uploadedPaths.contains(photo.getPath())) {
                        uploadedCount++;
                    }
                }
                
                final int finalUploadedCount = uploadedCount;
                
                mainHandler.post(() -> {
                    if (finalUploadedCount > 0) {
                        btnFilterUploaded.setText("排除已上传 (" + finalUploadedCount + ")");
                    } else {
                        btnFilterUploaded.setText("排除已上传");
                    }
                });
                
            } catch (Exception e) {
                Log.e(TAG, "更新过滤按钮失败", e);
            }
        }).start();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // 返回此页面时更新过滤按钮文字（可能刚上传了新照片）
        if (allPhotoList != null && !allPhotoList.isEmpty()) {
            updateFilterButtonText();
        }
    }
}
