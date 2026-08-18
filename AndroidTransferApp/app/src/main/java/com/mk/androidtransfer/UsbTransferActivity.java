package com.mk.androidtransfer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.mk.androidtransfer.usb.UsbConnectionManager;
import com.mk.androidtransfer.usb.UsbTransferProtocol;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * USB传输Activity
 * 处理实际的USB数据传输
 */
public class UsbTransferActivity extends AppCompatActivity {

    private static final String TAG = "UsbTransferActivity";
    private static final int UPDATE_INTERVAL_MS = 100; // 更新间隔100ms

    // UI组件
    private MaterialButton btnBack;
    private TextView tvStatus;
    private TextView tvTransferStatus;
    private TextView tvCurrentFile;
    private TextView tvProgress;
    private TextView tvFileCount;
    private TextView tvSpeed;
    private TextView tvTransferred;
    private TextView tvTimeRemaining;
    private TextView tvUsbVersion;
    private TextView tvMaxSpeed;
    private ProgressBar progressBar;
    private MaterialCardView cardFileList;
    private RecyclerView recyclerViewFiles;

    // 数据
    private String usbVersion;
    private float maxSpeed;
    private int fileCount;
    private String fileType;
    private List<Uri> fileUris;
    private List<TransferFileItem> transferFiles;
    private TransferFileAdapter adapter;

    // 传输状态
    private long totalSize = 0;
    private long transferredSize = 0;
    private long startTime = 0;
    private int currentFileIndex = 0;
    private boolean isTransferring = false;

    private Handler mainHandler;
    private Runnable updateRunnable;
    
    // USB设备信息
    private UsbManager usbManager;
    private UsbDevice targetDevice;
    private File transferDirectory;
    
    // 真实USB传输
    private boolean isRealUsbTransfer = false;
    private UsbConnectionManager connectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usb_transfer);

        // 设置沉浸式状态栏
        setupImmersiveStatusBar();

        mainHandler = new Handler(Looper.getMainLooper());
        
        // 初始化USB管理器
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // 获取传递的参数
        Intent intent = getIntent();
        usbVersion = intent.getStringExtra("usb_version");
        maxSpeed = intent.getFloatExtra("max_speed", 0f);
        fileCount = intent.getIntExtra("file_count", 0);
        fileType = intent.getStringExtra("file_type");
        isRealUsbTransfer = intent.getBooleanExtra("is_real_usb", false);
        
        ArrayList<String> uriStrings = intent.getStringArrayListExtra("file_uris");
        fileUris = new ArrayList<>();
        if (uriStrings != null) {
            for (String uriString : uriStrings) {
                fileUris.add(Uri.parse(uriString));
            }
        }
        
        // 初始化USB连接管理器（用于真实USB传输）
        if (isRealUsbTransfer) {
            connectionManager = new UsbConnectionManager(this);
            Log.d(TAG, "使用真实USB传输模式");
        } else {
            Log.d(TAG, "使用模拟传输模式");
        }

        initViews();
        setupListeners();
        
        // 初始化传输目录
        if (initTransferDirectory()) {
            prepareTransfer();
            startTransfer();
        } else {
            showError("无法创建传输目录");
        }
    }

    /**
     * 设置沉浸式状态栏
     */
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
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    /**
     * 初始化视图
     */
    private void initViews() {
        btnBack = findViewById(R.id.btnBack);
        tvStatus = findViewById(R.id.tvStatus);
        tvTransferStatus = findViewById(R.id.tvTransferStatus);
        tvCurrentFile = findViewById(R.id.tvCurrentFile);
        tvProgress = findViewById(R.id.tvProgress);
        tvFileCount = findViewById(R.id.tvFileCount);
        tvSpeed = findViewById(R.id.tvSpeed);
        tvTransferred = findViewById(R.id.tvTransferred);
        tvTimeRemaining = findViewById(R.id.tvTimeRemaining);
        tvUsbVersion = findViewById(R.id.tvUsbVersion);
        tvMaxSpeed = findViewById(R.id.tvMaxSpeed);
        progressBar = findViewById(R.id.progressBar);
        cardFileList = findViewById(R.id.cardFileList);
        recyclerViewFiles = findViewById(R.id.recyclerViewFiles);
        
        // 设置RecyclerView
        transferFiles = new ArrayList<>();
        adapter = new TransferFileAdapter(transferFiles);
        recyclerViewFiles.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewFiles.setAdapter(adapter);
    }

    /**
     * 设置监听器
     */
    private void setupListeners() {
        btnBack.setOnClickListener(v -> {
            if (isTransferring) {
                // TODO: 显示确认对话框
                stopTransfer();
            }
            finish();
        });
    }

    /**
     * 准备传输
     */
    private void prepareTransfer() {
        tvStatus.setText("准备传输...");
        tvUsbVersion.setText(usbVersion != null ? usbVersion : "USB 2.0");
        tvMaxSpeed.setText(String.format("最大: %.1f MB/s", maxSpeed));
        tvFileCount.setText(String.format("0/%d 文件", fileCount));
        
        // 准备文件列表
        if (fileUris != null && !fileUris.isEmpty()) {
            for (Uri uri : fileUris) {
                TransferFileItem item = new TransferFileItem();
                item.uri = uri;
                item.name = getFileName(uri);
                item.size = getFileSize(uri);
                item.status = TransferFileStatus.PENDING;
                transferFiles.add(item);
                totalSize += item.size;
            }
            adapter.notifyDataSetChanged();
        }
        
        Log.d(TAG, String.format("准备传输 %d 个文件，总大小: %s", fileCount, formatFileSize(totalSize)));
    }

    /**
     * 开始传输
     */
    private void startTransfer() {
        if (transferFiles.isEmpty()) {
            tvStatus.setText("没有文件需要传输");
            return;
        }

        isTransferring = true;
        startTime = System.currentTimeMillis();
        tvStatus.setText("传输中...");
        tvTransferStatus.setText("正在传输...");
        
        // 创建更新UI的Runnable
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                updateUI();
                if (isTransferring) {
                    mainHandler.postDelayed(this, UPDATE_INTERVAL_MS);
                }
            }
        };
        
        // 在后台线程开始传输
        new Thread(() -> {
            try {
                for (int i = 0; i < transferFiles.size(); i++) {
                    if (!isTransferring) break;
                    
                    currentFileIndex = i;
                    TransferFileItem item = transferFiles.get(i);
                    transferFile(item);
                }
                
                // 传输完成
                mainHandler.post(() -> onTransferComplete());
                
            } catch (Exception e) {
                Log.e(TAG, "传输失败", e);
                mainHandler.post(() -> onTransferError(e));
            }
        }).start();
        
        // 开始UI更新
        mainHandler.post(updateRunnable);
    }

    /**
     * 传输单个文件
     */
    private void transferFile(TransferFileItem item) throws Exception {
        // 更新状态为传输中
        item.status = TransferFileStatus.TRANSFERRING;
        item.transferredSize = 0;
        mainHandler.post(() -> {
            tvCurrentFile.setText("当前文件: " + item.name);
            adapter.notifyDataSetChanged();
        });
        
        if (isRealUsbTransfer) {
            // ============ 真实USB传输 ============
            transferFileViaUsb(item);
        } else {
            // ============ 模拟传输（本地复制） ============
            transferFileLocally(item);
        }
    }
    
    /**
     * 通过USB传输文件（真实传输）
     */
    private void transferFileViaUsb(TransferFileItem item) throws Exception {
        if (connectionManager == null || !connectionManager.isConnected()) {
            throw new Exception("USB未连接");
        }
        
        try {
            // 1. 发送文件信息
            Log.d(TAG, String.format("发送文件信息: %s, 大小: %d", item.name, item.size));
            connectionManager.sendFileInfo(
                item.name, 
                item.size, 
                currentFileIndex, 
                transferFiles.size()
            );
            
            // 2. 发送文件数据
            ContentResolver resolver = getContentResolver();
            try (InputStream inputStream = resolver.openInputStream(item.uri)) {
                if (inputStream == null) {
                    throw new Exception("无法打开文件: " + item.name);
                }
                
                // 获取最大数据块大小
                int maxDataSize = connectionManager.getMaxDataSize();
                byte[] buffer = new byte[maxDataSize];
                int bytesRead;
                long fileTransferred = 0;
                
                Log.d(TAG, "开始传输文件数据，数据块大小: " + maxDataSize);
                
                while ((bytesRead = inputStream.read(buffer)) != -1 && isTransferring) {
                    // 发送数据块
                    connectionManager.sendFileData(buffer, 0, bytesRead);
                    
                    fileTransferred += bytesRead;
                    transferredSize += bytesRead;
                    item.transferredSize = fileTransferred;
                    
                    // 每传输1MB显示一次日志
                    if (fileTransferred % (1024 * 1024) == 0) {
                        Log.d(TAG, String.format("已传输: %d/%d bytes (%.1f%%)", 
                            fileTransferred, item.size, 
                            (fileTransferred * 100.0 / item.size)));
                    }
                }
            }
            
            // 3. 发送文件结束标记
            Log.d(TAG, "文件传输完成，发送结束标记");
            connectionManager.sendFileEnd(item.size, item.transferredSize);
            
            // 标记为完成
            item.status = TransferFileStatus.COMPLETED;
            mainHandler.post(() -> adapter.notifyDataSetChanged());
            
            Log.d(TAG, String.format("文件传输成功: %s, 大小: %d bytes", 
                item.name, item.transferredSize));
            
        } catch (Exception e) {
            item.status = TransferFileStatus.FAILED;
            mainHandler.post(() -> adapter.notifyDataSetChanged());
            Log.e(TAG, "文件传输失败: " + item.name, e);
            throw e;
        }
    }
    
    /**
     * 本地传输文件（模拟传输）
     */
    private void transferFileLocally(TransferFileItem item) throws Exception {
        // 读取文件并实际传输到目标目录
        ContentResolver resolver = getContentResolver();
        try (InputStream inputStream = resolver.openInputStream(item.uri)) {
            if (inputStream == null) {
                throw new Exception("无法打开文件: " + item.name);
            }
            
            // 创建目标文件
            File targetFile = new File(transferDirectory, item.name);
            
            // 如果文件已存在，添加时间戳避免覆盖
            if (targetFile.exists()) {
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String nameWithoutExt = item.name.substring(0, item.name.lastIndexOf('.'));
                String ext = item.name.substring(item.name.lastIndexOf('.'));
                targetFile = new File(transferDirectory, nameWithoutExt + "_" + timestamp + ext);
            }
            
            // 写入文件到目标目录
            try (FileOutputStream outputStream = new FileOutputStream(targetFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                long fileTransferred = 0;
                
                while ((bytesRead = inputStream.read(buffer)) != -1 && isTransferring) {
                    // 实际写入文件
                    outputStream.write(buffer, 0, bytesRead);
                    outputStream.flush();
                    
                    // 模拟传输延迟（根据USB速率）
                    if (maxSpeed > 0) {
                        long delayMs = (long) ((bytesRead / (maxSpeed * 1024 * 1024)) * 1000);
                        Thread.sleep(Math.min(delayMs, 50)); // 最多延迟50ms
                    }
                    
                    fileTransferred += bytesRead;
                    transferredSize += bytesRead;
                    item.transferredSize = fileTransferred;
                }
            }
            
            // 标记为完成
            item.status = TransferFileStatus.COMPLETED;
            item.targetPath = targetFile.getAbsolutePath();
            mainHandler.post(() -> adapter.notifyDataSetChanged());
            
            Log.d(TAG, String.format("文件传输成功: %s -> %s", item.name, targetFile.getAbsolutePath()));
            
        } catch (Exception e) {
            item.status = TransferFileStatus.FAILED;
            mainHandler.post(() -> adapter.notifyDataSetChanged());
            Log.e(TAG, "文件传输失败: " + item.name, e);
            throw e;
        }
    }
    
    /**
     * 初始化传输目录
     * 优先使用Download目录（最可靠），其次尝试USB存储设备
     */
    private boolean initTransferDirectory() {
        // 优先使用Download目录（Android 10+推荐方式）
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        transferDirectory = new File(downloadDir, "AndroidTransfer_USB");
        
        if (!transferDirectory.exists()) {
            if (!transferDirectory.mkdirs()) {
                Log.e(TAG, "无法创建传输目录: " + transferDirectory.getAbsolutePath());
                
                // 尝试备选目录
                transferDirectory = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "AndroidTransfer_USB");
                if (!transferDirectory.exists() && !transferDirectory.mkdirs()) {
                    Log.e(TAG, "无法创建备选传输目录");
                    return false;
                }
            }
        }
        
        // 检查目录是否可写
        if (!transferDirectory.canWrite()) {
            Log.e(TAG, "传输目录不可写: " + transferDirectory.getAbsolutePath());
            return false;
        }
        
        Log.d(TAG, "传输目录: " + transferDirectory.getAbsolutePath());
        
        // 尝试查找USB存储设备（作为额外信息）
        File usbStorage = findUsbStorage();
        String storageInfo;
        if (usbStorage != null && usbStorage.exists() && usbStorage.canWrite()) {
            storageInfo = String.format("文件将保存到:\n主目录: %s\n\n提示: 检测到USB存储设备: %s\n" +
                "传输完成后，您可以手动将文件移动到USB设备", 
                transferDirectory.getAbsolutePath(),
                usbStorage.getAbsolutePath());
        } else {
            storageInfo = String.format("文件将保存到:\n%s\n\n可在文件管理器的Download文件夹中查看", 
                transferDirectory.getAbsolutePath());
        }
        
        // 在UI上显示传输目录
        final String finalStorageInfo = storageInfo;
        mainHandler.post(() -> {
            Toast.makeText(UsbTransferActivity.this, finalStorageInfo, Toast.LENGTH_LONG).show();
        });
        
        return true;
    }
    
    /**
     * 查找USB存储设备
     */
    private File findUsbStorage() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                StorageManager storageManager = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
                List<StorageVolume> volumes = storageManager.getStorageVolumes();
                
                for (StorageVolume volume : volumes) {
                    // 查找可移除的存储设备（通常是USB OTG设备）
                    if (volume.isRemovable() && volume.getState().equals(Environment.MEDIA_MOUNTED)) {
                        // Android 9.0+ 可以获取目录
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            File directory = volume.getDirectory();
                            if (directory != null && directory.exists()) {
                                Log.d(TAG, "找到USB存储设备: " + directory.getAbsolutePath());
                                return directory;
                            }
                        }
                    }
                }
            }
            
            // 备选方案：检查常见的USB挂载点
            String[] possiblePaths = {
                "/storage/usbotg",
                "/storage/usb",
                "/mnt/usb",
                "/mnt/usbotg",
                "/mnt/media_rw/usbotg",
                "/mnt/media_rw/usb"
            };
            
            for (String path : possiblePaths) {
                File f = new File(path);
                if (f.exists() && f.canWrite()) {
                    Log.d(TAG, "找到USB存储设备（备选路径）: " + path);
                    return f;
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "查找USB存储设备失败", e);
        }
        
        return null;
    }
    
    /**
     * 显示错误信息
     */
    private void showError(String message) {
        tvStatus.setText("错误");
        tvTransferStatus.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        Log.e(TAG, message);
    }

    /**
     * 更新UI
     */
    private void updateUI() {
        // 计算进度
        int progress = totalSize > 0 ? (int) ((transferredSize * 100) / totalSize) : 0;
        progressBar.setProgress(progress);
        tvProgress.setText(progress + "%");
        
        // 更新文件计数
        int completedFiles = 0;
        for (TransferFileItem item : transferFiles) {
            if (item.status == TransferFileStatus.COMPLETED) {
                completedFiles++;
            }
        }
        tvFileCount.setText(String.format("%d/%d 文件", completedFiles, fileCount));
        
        // 计算速率
        long elapsedTime = System.currentTimeMillis() - startTime;
        if (elapsedTime > 0) {
            double speedMBps = (transferredSize / (1024.0 * 1024.0)) / (elapsedTime / 1000.0);
            tvSpeed.setText(String.format(Locale.getDefault(), "%.2f MB/s", speedMBps));
            
            // 计算剩余时间
            long remainingBytes = totalSize - transferredSize;
            if (speedMBps > 0) {
                long remainingSeconds = (long) (remainingBytes / (speedMBps * 1024 * 1024));
                tvTimeRemaining.setText(formatTime(remainingSeconds));
            }
        }
        
        // 更新已传输大小
        tvTransferred.setText(String.format("%s / %s", 
            formatFileSize(transferredSize), 
            formatFileSize(totalSize)));
    }

    /**
     * 停止传输
     */
    private void stopTransfer() {
        isTransferring = false;
        if (updateRunnable != null) {
            mainHandler.removeCallbacks(updateRunnable);
        }
    }

    /**
     * 传输完成
     */
    private void onTransferComplete() {
        stopTransfer();
        tvStatus.setText("传输完成");
        tvTransferStatus.setText("所有文件传输完成");
        tvTimeRemaining.setText("0秒");
        
        long elapsedTime = System.currentTimeMillis() - startTime;
        double avgSpeed = (totalSize / (1024.0 * 1024.0)) / (elapsedTime / 1000.0);
        
        // 统计传输结果
        int successCount = 0;
        int failedCount = 0;
        for (TransferFileItem item : transferFiles) {
            if (item.status == TransferFileStatus.COMPLETED) {
                successCount++;
            } else if (item.status == TransferFileStatus.FAILED) {
                failedCount++;
            }
        }
        
        // 显示传输结果
        String resultMessage = String.format(
            "传输完成！\n成功: %d 个文件\n失败: %d 个文件\n总大小: %s\n平均速率: %.2f MB/s\n传输目录: %s",
            successCount,
            failedCount,
            formatFileSize(totalSize),
            avgSpeed,
            transferDirectory.getAbsolutePath()
        );
        
        Toast.makeText(this, resultMessage, Toast.LENGTH_LONG).show();
        
        Log.d(TAG, String.format("传输完成！总大小: %s, 耗时: %s, 平均速率: %.2f MB/s, 成功: %d, 失败: %d, 目录: %s",
            formatFileSize(totalSize),
            formatTime(elapsedTime / 1000),
            avgSpeed,
            successCount,
            failedCount,
            transferDirectory.getAbsolutePath()));
    }

    /**
     * 传输错误
     */
    private void onTransferError(Exception e) {
        stopTransfer();
        tvStatus.setText("传输失败");
        tvTransferStatus.setText("传输失败: " + e.getMessage());
        Log.e(TAG, "传输失败", e);
    }

    /**
     * 获取文件名
     */
    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        result = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "获取文件名失败", e);
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result != null ? result.lastIndexOf('/') : -1;
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result != null ? result : "未知文件";
    }

    /**
     * 获取文件大小
     */
    private long getFileSize(Uri uri) {
        long size = 0;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (sizeIndex >= 0) {
                        size = cursor.getLong(sizeIndex);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "获取文件大小失败", e);
            }
        }
        return size;
    }

    /**
     * 格式化文件大小
     */
    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.2f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.2f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format(Locale.getDefault(), "%.2f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
     * 格式化时间
     */
    private String formatTime(long seconds) {
        if (seconds < 60) {
            return seconds + "秒";
        } else if (seconds < 3600) {
            return String.format(Locale.getDefault(), "%d分%d秒", seconds / 60, seconds % 60);
        } else {
            return String.format(Locale.getDefault(), "%d小时%d分", seconds / 3600, (seconds % 3600) / 60);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopTransfer();
    }

    /**
     * 传输文件状态枚举
     */
    enum TransferFileStatus {
        PENDING,      // 等待中
        TRANSFERRING, // 传输中
        COMPLETED,    // 已完成
        FAILED        // 失败
    }

    /**
     * 传输文件项
     */
    static class TransferFileItem {
        Uri uri;
        String name;
        long size;
        long transferredSize;
        TransferFileStatus status;
        String targetPath; // 传输后的目标路径
    }

    /**
     * 文件列表适配器
     */
    class TransferFileAdapter extends RecyclerView.Adapter<TransferFileAdapter.ViewHolder> {
        private List<TransferFileItem> items;

        TransferFileAdapter(List<TransferFileItem> items) {
            this.items = items;
        }

        @Override
        public ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.item_transfer_file, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            TransferFileItem item = items.get(position);
            holder.tvFileName.setText(item.name);
            holder.tvFileSize.setText(formatFileSize(item.size));
            
            // 根据状态更新UI
            switch (item.status) {
                case PENDING:
                    holder.tvFileStatus.setText("等待中");
                    holder.tvFileStatus.setTextColor(getColor(R.color.text_medium_emphasis));
                    holder.progressBarFile.setVisibility(View.GONE);
                    break;
                case TRANSFERRING:
                    holder.tvFileStatus.setText("传输中");
                    holder.tvFileStatus.setTextColor(getColor(R.color.radar_blue_primary));
                    holder.progressBarFile.setVisibility(View.VISIBLE);
                    int progress = item.size > 0 ? (int) ((item.transferredSize * 100) / item.size) : 0;
                    holder.progressBarFile.setProgress(progress);
                    break;
                case COMPLETED:
                    holder.tvFileStatus.setText("已完成");
                    holder.tvFileStatus.setTextColor(getColor(R.color.success));
                    holder.progressBarFile.setVisibility(View.GONE);
                    break;
                case FAILED:
                    holder.tvFileStatus.setText("失败");
                    holder.tvFileStatus.setTextColor(getColor(R.color.error));
                    holder.progressBarFile.setVisibility(View.GONE);
                    break;
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivFileIcon;
            TextView tvFileName;
            TextView tvFileSize;
            TextView tvFileStatus;
            ProgressBar progressBarFile;

            ViewHolder(View view) {
                super(view);
                ivFileIcon = view.findViewById(R.id.ivFileIcon);
                tvFileName = view.findViewById(R.id.tvFileName);
                tvFileSize = view.findViewById(R.id.tvFileSize);
                tvFileStatus = view.findViewById(R.id.tvFileStatus);
                progressBarFile = view.findViewById(R.id.progressBarFile);
            }
        }
    }
}
