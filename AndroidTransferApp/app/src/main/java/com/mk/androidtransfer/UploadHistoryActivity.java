package com.mk.androidtransfer;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.mk.androidtransfer.adapter.UploadRecordAdapter;
import com.mk.androidtransfer.database.UploadRecordDao;
import com.mk.androidtransfer.model.UploadRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * 上传历史记录Activity
 */
public class UploadHistoryActivity extends AppCompatActivity {
    
    private static final String TAG = "UploadHistoryActivity";
    
    private MaterialToolbar toolbar;
    private RecyclerView recyclerView;
    private LinearLayout emptyState;
    private LinearLayout loadingContainer;
    
    private UploadRecordAdapter adapter;
    private UploadRecordDao uploadRecordDao;
    private List<UploadRecord> recordList = new ArrayList<>();
    
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_upload_history);
        
        setupImmersiveStatusBar();
        initViews();
        setupToolbar();
        setupRecyclerView();
        
        uploadRecordDao = new UploadRecordDao(this);
        
        loadUploadRecords();
    }
    
    private void setupImmersiveStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(0, 
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
        }
    }
    
    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
        recyclerView = findViewById(R.id.recyclerViewHistory);
        emptyState = findViewById(R.id.emptyState);
        loadingContainer = findViewById(R.id.loadingContainer);
    }
    
    private void setupToolbar() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("上传记录");
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        
        // 添加清空记录菜单项
        toolbar.inflateMenu(R.menu.menu_upload_history);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_clear_all) {
                showClearAllDialog();
                return true;
            }
            return false;
        });
    }
    
    private void setupRecyclerView() {
        adapter = new UploadRecordAdapter(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        
        adapter.setOnItemClickListener(new UploadRecordAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(UploadRecord record) {
                showRecordDetailsDialog(record);
            }
            
            @Override
            public void onDeleteClick(UploadRecord record) {
                showDeleteDialog(record);
            }
        });
    }
    
    private void loadUploadRecords() {
        showLoading(true);
        
        new Thread(() -> {
            try {
                List<UploadRecord> records = uploadRecordDao.getAllUploadRecords();
                
                mainHandler.post(() -> {
                    showLoading(false);
                    recordList = records;
                    adapter.setRecords(records);
                    updateEmptyState();
                });
                
            } catch (Exception e) {
                Log.e(TAG, "加载上传记录失败", e);
                mainHandler.post(() -> {
                    showLoading(false);
                    Toast.makeText(this, "加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
    
    private void showRecordDetailsDialog(UploadRecord record) {
        String message = String.format(
            "服务器: %s\n\n" +
            "上传时间: %s\n" +
            "总数量: %d 张\n" +
            "成功: %d 张\n" +
            "失败: %d 张\n\n" +
            "成功率: %.1f%%",
            record.getServerName(),
            record.getUploadTimeStr(),
            record.getTotalCount(),
            record.getSuccessCount(),
            record.getFailedCount(),
            record.getTotalCount() > 0 ? (record.getSuccessCount() * 100.0 / record.getTotalCount()) : 0
        );
        
        new AlertDialog.Builder(this)
            .setTitle("上传详情")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show();
    }
    
    private void showDeleteDialog(UploadRecord record) {
        new AlertDialog.Builder(this)
            .setTitle("删除记录")
            .setMessage("确定要删除这条上传记录吗？")
            .setPositiveButton("删除", (dialog, which) -> {
                deleteRecord(record);
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    private void showClearAllDialog() {
        if (recordList.isEmpty()) {
            Toast.makeText(this, "暂无上传记录", Toast.LENGTH_SHORT).show();
            return;
        }
        
        new AlertDialog.Builder(this)
            .setTitle("清空所有记录")
            .setMessage("确定要清空所有上传记录吗？此操作不可恢复。")
            .setPositiveButton("清空", (dialog, which) -> {
                clearAllRecords();
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    private void deleteRecord(UploadRecord record) {
        new Thread(() -> {
            try {
                uploadRecordDao.deleteUploadRecord(record.getId());
                
                mainHandler.post(() -> {
                    Toast.makeText(this, "已删除记录", Toast.LENGTH_SHORT).show();
                    loadUploadRecords();
                });
                
            } catch (Exception e) {
                Log.e(TAG, "删除记录失败", e);
                mainHandler.post(() -> {
                    Toast.makeText(this, "删除失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
    
    private void clearAllRecords() {
        new Thread(() -> {
            try {
                uploadRecordDao.clearAllRecords();
                
                mainHandler.post(() -> {
                    Toast.makeText(this, "已清空所有记录", Toast.LENGTH_SHORT).show();
                    loadUploadRecords();
                });
                
            } catch (Exception e) {
                Log.e(TAG, "清空记录失败", e);
                mainHandler.post(() -> {
                    Toast.makeText(this, "清空失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
    
    private void showLoading(boolean show) {
        loadingContainer.setVisibility(show ? View.VISIBLE : View.GONE);
    }
    
    private void updateEmptyState() {
        if (recordList.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }
}

