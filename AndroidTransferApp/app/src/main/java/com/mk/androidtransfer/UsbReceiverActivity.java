package com.mk.androidtransfer;

import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.mk.androidtransfer.usb.UsbConnectionManager;
import com.mk.androidtransfer.usb.UsbTransferProtocol;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * USB接收端Activity
 * 作为Accessory模式接收文件
 */
public class UsbReceiverActivity extends AppCompatActivity {
    private static final String TAG = "UsbReceiverActivity";
    private static final int UPDATE_INTERVAL_MS = 100; // 更新间隔100ms
    
    // UI组件
    private MaterialButton btnBack;
    private MaterialButton btnCancel;
    private TextView tvStatus;
    private TextView tvReceiveStatus;
    private TextView tvCurrentFile;
    private TextView tvProgress;
    private TextView tvFileCount;
    private TextView tvSpeed;
    private TextView tvReceived;
    private TextView tvTimeRemaining;
    private ProgressBar progressBar;
    private RecyclerView recyclerViewFiles;
    
    // 数据
    private UsbConnectionManager connectionManager;
    private List<ReceiveFileItem> receiveFiles;
    private ReceiveFileAdapter adapter;
    
    // 接收状态
    private long totalSize = 0;
    private long receivedSize = 0;
    private long startTime = 0;
    private int currentFileIndex = 0;
    private boolean isReceiving = false;
    private boolean isConnected = false;
    
    private Handler mainHandler;
    private Runnable updateRunnable;
    private File receiveDirectory;
    
    // 当前接收的文件
    private FileOutputStream currentFileOutputStream;
    private String currentFileName;
    private long currentFileSize;
    private long currentFileReceived;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usb_receiver);
        
        // 设置沉浸式状态栏
        setupImmersiveStatusBar();
        
        mainHandler = new Handler(Looper.getMainLooper());
        connectionManager = new UsbConnectionManager(this);
        
        initViews();
        setupListeners();
        
        // 初始化接收目录
        if (initReceiveDirectory()) {
            // 开始接收
            startReceiving();
        } else {
            showError("无法创建接收目录");
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
        btnCancel = findViewById(R.id.btnCancel);
        tvStatus = findViewById(R.id.tvStatus);
        tvReceiveStatus = findViewById(R.id.tvReceiveStatus);
        tvCurrentFile = findViewById(R.id.tvCurrentFile);
        tvProgress = findViewById(R.id.tvProgress);
        tvFileCount = findViewById(R.id.tvFileCount);
        tvSpeed = findViewById(R.id.tvSpeed);
        tvReceived = findViewById(R.id.tvReceived);
        tvTimeRemaining = findViewById(R.id.tvTimeRemaining);
        progressBar = findViewById(R.id.progressBar);
        recyclerViewFiles = findViewById(R.id.recyclerViewFiles);
        
        // 设置RecyclerView
        receiveFiles = new ArrayList<>();
        adapter = new ReceiveFileAdapter(receiveFiles);
        recyclerViewFiles.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewFiles.setAdapter(adapter);
    }
    
    /**
     * 设置监听器
     */
    private void setupListeners() {
        btnBack.setOnClickListener(v -> {
            if (isReceiving) {
                Toast.makeText(this, "正在接收文件，无法返回", Toast.LENGTH_SHORT).show();
            } else {
                finish();
            }
        });
        
        btnCancel.setOnClickListener(v -> {
            if (isReceiving) {
                stopReceiving();
                Toast.makeText(this, "已取消接收", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }
    
    /**
     * 初始化接收目录
     */
    private boolean initReceiveDirectory() {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        receiveDirectory = new File(downloadDir, "AndroidTransfer_Received");
        
        if (!receiveDirectory.exists()) {
            if (!receiveDirectory.mkdirs()) {
                Log.e(TAG, "无法创建接收目录: " + receiveDirectory.getAbsolutePath());
                
                // 尝试备选目录
                receiveDirectory = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "AndroidTransfer_Received");
                if (!receiveDirectory.exists() && !receiveDirectory.mkdirs()) {
                    Log.e(TAG, "无法创建备选接收目录");
                    return false;
                }
            }
        }
        
        Log.d(TAG, "接收目录: " + receiveDirectory.getAbsolutePath());
        Toast.makeText(this, "文件将保存到: " + receiveDirectory.getAbsolutePath(), Toast.LENGTH_LONG).show();
        return true;
    }
    
    /**
     * 开始接收
     */
    private void startReceiving() {
        tvStatus.setText("等待连接...");
        tvReceiveStatus.setText("正在建立USB连接");
        
        // 在后台线程处理接收
        new Thread(() -> {
            try {
                // 1. 检查连接（应该已经在UsbP2PActivity中建立了）
                if (!connectionManager.isConnected()) {
                    Log.d(TAG, "USB未连接，尝试连接...");
                    Boolean mode = connectionManager.detectAndConnect();
                    if (mode == null || mode == true) {
                        throw new Exception("无法作为接收端连接");
                    }
                }
                
                isConnected = true;
                mainHandler.post(() -> {
                    tvStatus.setText("已连接");
                    tvReceiveStatus.setText("等待握手...");
                });
                
                // 2. 执行握手
                Log.d(TAG, "执行握手...");
                boolean handshakeSuccess = connectionManager.performHandshake();
                
                if (!handshakeSuccess) {
                    throw new Exception("握手失败");
                }
                
                mainHandler.post(() -> {
                    tvStatus.setText("接收中...");
                    tvReceiveStatus.setText("正在接收文件...");
                });
                
                isReceiving = true;
                startTime = System.currentTimeMillis();
                
                // 3. 开始UI更新
                updateRunnable = new Runnable() {
                    @Override
                    public void run() {
                        updateUI();
                        if (isReceiving) {
                            mainHandler.postDelayed(this, UPDATE_INTERVAL_MS);
                        }
                    }
                };
                mainHandler.post(updateRunnable);
                
                // 4. 接收循环
                receiveLoop();
                
                // 5. 接收完成
                mainHandler.post(() -> onReceiveComplete());
                
            } catch (Exception e) {
                Log.e(TAG, "接收失败", e);
                mainHandler.post(() -> onReceiveError(e));
            }
        }).start();
    }
    
    /**
     * 接收循环
     */
    private void receiveLoop() throws Exception {
        while (isReceiving) {
            // 接收数据包
            UsbTransferProtocol.Packet packet = connectionManager.receivePacket();
            
            switch (packet.type) {
                case UsbTransferProtocol.PACKET_TYPE_FILE_INFO:
                    // 文件信息
                    handleFileInfo(packet);
                    break;
                    
                case UsbTransferProtocol.PACKET_TYPE_FILE_DATA:
                    // 文件数据
                    handleFileData(packet);
                    break;
                    
                case UsbTransferProtocol.PACKET_TYPE_FILE_END:
                    // 文件结束
                    handleFileEnd(packet);
                    break;
                    
                case UsbTransferProtocol.PACKET_TYPE_TRANSFER_COMPLETE:
                    // 传输完成
                    handleTransferComplete(packet);
                    return;
                    
                case UsbTransferProtocol.PACKET_TYPE_ERROR:
                    // 错误
                    String error = packet.data != null ? new String(packet.data) : "Unknown error";
                    throw new Exception("收到错误: " + error);
                    
                default:
                    Log.w(TAG, "收到未知类型的数据包: " + 
                        UsbTransferProtocol.getPacketTypeName(packet.type));
            }
        }
    }
    
    /**
     * 处理文件信息
     */
    private void handleFileInfo(UsbTransferProtocol.Packet packet) throws Exception {
        UsbTransferProtocol.FileInfo fileInfo = UsbTransferProtocol.parseFileInfoPacket(packet);
        
        Log.d(TAG, String.format("收到文件信息: %s, 大小=%d, 索引=%d/%d",
            fileInfo.fileName, fileInfo.fileSize, fileInfo.fileIndex, fileInfo.totalFiles));
        
        // 创建接收文件项
        ReceiveFileItem item = new ReceiveFileItem();
        item.name = fileInfo.fileName;
        item.size = fileInfo.fileSize;
        item.index = fileInfo.fileIndex;
        item.totalFiles = fileInfo.totalFiles;
        item.status = ReceiveFileStatus.RECEIVING;
        
        mainHandler.post(() -> {
            receiveFiles.add(item);
            adapter.notifyDataSetChanged();
            tvCurrentFile.setText("当前文件: " + fileInfo.fileName);
            tvFileCount.setText(String.format("%d/%d 文件", fileInfo.fileIndex + 1, fileInfo.totalFiles));
        });
        
        totalSize += fileInfo.fileSize;
        currentFileIndex = fileInfo.fileIndex;
        
        // 创建输出文件
        File targetFile = new File(receiveDirectory, fileInfo.fileName);
        
        // 如果文件已存在，添加时间戳避免覆盖
        if (targetFile.exists()) {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String nameWithoutExt = fileInfo.fileName.substring(0, fileInfo.fileName.lastIndexOf('.'));
            String ext = fileInfo.fileName.substring(fileInfo.fileName.lastIndexOf('.'));
            targetFile = new File(receiveDirectory, nameWithoutExt + "_" + timestamp + ext);
        }
        
        currentFileOutputStream = new FileOutputStream(targetFile);
        currentFileName = fileInfo.fileName;
        currentFileSize = fileInfo.fileSize;
        currentFileReceived = 0;
        
        item.targetPath = targetFile.getAbsolutePath();
        
        Log.d(TAG, "准备接收文件到: " + targetFile.getAbsolutePath());
    }
    
    /**
     * 处理文件数据
     */
    private void handleFileData(UsbTransferProtocol.Packet packet) throws Exception {
        if (currentFileOutputStream == null) {
            throw new Exception("收到文件数据但没有打开的文件");
        }
        
        // 写入数据
        currentFileOutputStream.write(packet.data);
        currentFileOutputStream.flush();
        
        currentFileReceived += packet.data.length;
        receivedSize += packet.data.length;
        
        // 更新当前文件项的进度
        if (currentFileIndex < receiveFiles.size()) {
            ReceiveFileItem item = receiveFiles.get(currentFileIndex);
            item.receivedSize = currentFileReceived;
            
            // 每接收1MB显示一次日志
            if (currentFileReceived % (1024 * 1024) == 0) {
                Log.d(TAG, String.format("已接收: %d/%d bytes (%.1f%%)",
                    currentFileReceived, currentFileSize,
                    (currentFileReceived * 100.0 / currentFileSize)));
            }
        }
    }
    
    /**
     * 处理文件结束
     */
    private void handleFileEnd(UsbTransferProtocol.Packet packet) throws Exception {
        if (currentFileOutputStream != null) {
            currentFileOutputStream.close();
            currentFileOutputStream = null;
        }
        
        // 更新文件状态
        if (currentFileIndex < receiveFiles.size()) {
            ReceiveFileItem item = receiveFiles.get(currentFileIndex);
            item.status = ReceiveFileStatus.COMPLETED;
            
            mainHandler.post(() -> adapter.notifyDataSetChanged());
        }
        
        Log.d(TAG, String.format("文件接收完成: %s, 大小: %d bytes", 
            currentFileName, currentFileReceived));
    }
    
    /**
     * 处理传输完成
     */
    private void handleTransferComplete(UsbTransferProtocol.Packet packet) throws Exception {
        UsbTransferProtocol.TransferResult result = 
            UsbTransferProtocol.parseTransferCompletePacket(packet);
        
        Log.d(TAG, String.format("传输完成: total=%d, success=%d, failed=%d",
            result.totalFiles, result.successFiles, result.failedFiles));
        
        isReceiving = false;
    }
    
    /**
     * 更新UI
     */
    private void updateUI() {
        // 计算进度
        int progress = totalSize > 0 ? (int) ((receivedSize * 100) / totalSize) : 0;
        progressBar.setProgress(progress);
        tvProgress.setText(progress + "%");
        
        // 更新文件计数
        int completedFiles = 0;
        for (ReceiveFileItem item : receiveFiles) {
            if (item.status == ReceiveFileStatus.COMPLETED) {
                completedFiles++;
            }
        }
        if (!receiveFiles.isEmpty()) {
            tvFileCount.setText(String.format("%d/%d 文件", completedFiles, receiveFiles.get(0).totalFiles));
        }
        
        // 计算速率
        long elapsedTime = System.currentTimeMillis() - startTime;
        if (elapsedTime > 0) {
            double speedMBps = (receivedSize / (1024.0 * 1024.0)) / (elapsedTime / 1000.0);
            tvSpeed.setText(String.format(Locale.getDefault(), "%.2f MB/s", speedMBps));
            
            // 计算剩余时间
            long remainingBytes = totalSize - receivedSize;
            if (speedMBps > 0) {
                long remainingSeconds = (long) (remainingBytes / (speedMBps * 1024 * 1024));
                tvTimeRemaining.setText(formatTime(remainingSeconds));
            }
        }
        
        // 更新已接收大小
        tvReceived.setText(String.format("%s / %s",
            formatFileSize(receivedSize),
            formatFileSize(totalSize)));
    }
    
    /**
     * 停止接收
     */
    private void stopReceiving() {
        isReceiving = false;
        
        try {
            if (currentFileOutputStream != null) {
                currentFileOutputStream.close();
                currentFileOutputStream = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "关闭文件流失败", e);
        }
        
        if (updateRunnable != null) {
            mainHandler.removeCallbacks(updateRunnable);
        }
        
        if (connectionManager != null) {
            connectionManager.disconnect();
        }
    }
    
    /**
     * 接收完成
     */
    private void onReceiveComplete() {
        stopReceiving();
        tvStatus.setText("接收完成");
        tvReceiveStatus.setText("所有文件接收完成");
        tvTimeRemaining.setText("0秒");
        
        long elapsedTime = System.currentTimeMillis() - startTime;
        double avgSpeed = (totalSize / (1024.0 * 1024.0)) / (elapsedTime / 1000.0);
        
        // 统计接收结果
        int successCount = 0;
        int failedCount = 0;
        for (ReceiveFileItem item : receiveFiles) {
            if (item.status == ReceiveFileStatus.COMPLETED) {
                successCount++;
            } else if (item.status == ReceiveFileStatus.FAILED) {
                failedCount++;
            }
        }
        
        // 显示接收结果
        String resultMessage = String.format(
            "接收完成！\n成功: %d 个文件\n失败: %d 个文件\n总大小: %s\n平均速率: %.2f MB/s\n保存位置: %s",
            successCount,
            failedCount,
            formatFileSize(totalSize),
            avgSpeed,
            receiveDirectory.getAbsolutePath()
        );
        
        Toast.makeText(this, resultMessage, Toast.LENGTH_LONG).show();
        
        Log.d(TAG, String.format("接收完成！总大小: %s, 耗时: %s, 平均速率: %.2f MB/s, 成功: %d, 失败: %d",
            formatFileSize(totalSize),
            formatTime(elapsedTime / 1000),
            avgSpeed,
            successCount,
            failedCount));
    }
    
    /**
     * 接收错误
     */
    private void onReceiveError(Exception e) {
        stopReceiving();
        tvStatus.setText("接收失败");
        tvReceiveStatus.setText("接收失败: " + e.getMessage());
        Toast.makeText(this, "接收失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        Log.e(TAG, "接收失败", e);
    }
    
    /**
     * 显示错误信息
     */
    private void showError(String message) {
        tvStatus.setText("错误");
        tvReceiveStatus.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        Log.e(TAG, message);
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
        stopReceiving();
    }
    
    /**
     * 接收文件状态枚举
     */
    enum ReceiveFileStatus {
        RECEIVING,    // 接收中
        COMPLETED,    // 已完成
        FAILED        // 失败
    }
    
    /**
     * 接收文件项
     */
    static class ReceiveFileItem {
        String name;
        long size;
        long receivedSize;
        int index;
        int totalFiles;
        ReceiveFileStatus status;
        String targetPath;
    }
    
    /**
     * 文件列表适配器（简化版）
     */
    class ReceiveFileAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<ReceiveFileItem> items;
        
        ReceiveFileAdapter(List<ReceiveFileItem> items) {
            this.items = items;
        }
        
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            // 这里应该inflate实际的item布局，暂时返回空
            return null;
        }
        
        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            // 绑定数据到视图
        }
        
        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}

