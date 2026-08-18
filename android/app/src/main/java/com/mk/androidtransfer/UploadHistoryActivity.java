package com.mk.androidtransfer;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.mk.androidtransfer.adapter.UploadRecordAdapter;
import com.mk.androidtransfer.util.ThemeBars;
import com.mk.androidtransfer.database.UploadRecordDao;
import com.mk.androidtransfer.model.UploadRecord;
import com.mk.androidtransfer.util.TransferFormat;

import java.util.ArrayList;
import java.util.List;

/**
 * 上传历史记录Activity
 */
public class UploadHistoryActivity extends AppCompatActivity {
    
    private static final String TAG = "UploadHistoryActivity";
    
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
        ThemeBars.apply(this);
    }
    
    private void initViews() {
        ImageButton btnBack = findViewById(R.id.btnBack);
        View btnClearAll = findViewById(R.id.btnClearAll);
        recyclerView = findViewById(R.id.recyclerViewHistory);
        emptyState = findViewById(R.id.emptyState);
        loadingContainer = findViewById(R.id.loadingContainer);
        
        btnBack.setOnClickListener(v -> finish());
        btnClearAll.setOnClickListener(v -> showClearAllDialog());
    }
    
    private void setupToolbar() {
        // 已在initViews中处理，此方法可移除或保留为空
    }
    
    private void setupRecyclerView() {
        adapter = new UploadRecordAdapter(this);
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemAnimator(null);
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
                    Toast.makeText(this, getString(R.string.load_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
    
    private void showRecordDetailsDialog(UploadRecord record) {
        String size = TransferFormat.bytes(record.getTotalBytes());
        if (size.isEmpty()) size = getString(R.string.unknown_value);
        String dur = TransferFormat.duration(this, record.getDurationSec());
        if (dur.isEmpty()) dur = getString(R.string.unknown_value);
        String avg = getString(R.string.unknown_value);
        if (record.getDurationSec() > 0 && record.getTotalBytes() > 0) {
            String speed = TransferFormat.speed(record.getTotalBytes() / Math.max(1, record.getDurationSec()));
            if (!speed.isEmpty()) avg = speed;
        }
        String message = getString(
            R.string.upload_detail_body,
            record.getServerName(),
            record.getUploadTimeStr(),
            record.getTotalCount(),
            record.getSuccessCount(),
            record.getFailedCount(),
            size,
            dur,
            avg
        );
        
        new AlertDialog.Builder(this)
            .setTitle(R.string.upload_details)
            .setMessage(message)
            .setPositiveButton(R.string.confirm, null)
            .show();
    }
    
    private void showDeleteDialog(UploadRecord record) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.delete_record)
            .setMessage(R.string.delete_record_message)
            .setPositiveButton(R.string.delete, (dialog, which) -> {
                deleteRecord(record);
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }
    
    private void showClearAllDialog() {
        if (recordList.isEmpty()) {
            Toast.makeText(this, R.string.no_upload_records, Toast.LENGTH_SHORT).show();
            return;
        }
        
        new AlertDialog.Builder(this)
            .setTitle(R.string.clear_all_records)
            .setMessage(R.string.clear_all_message)
            .setPositiveButton(R.string.clear, (dialog, which) -> {
                clearAllRecords();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }
    
    private void deleteRecord(UploadRecord record) {
        new Thread(() -> {
            try {
                uploadRecordDao.deleteUploadRecord(record.getId());
                
                mainHandler.post(() -> {
                    Toast.makeText(this, R.string.record_deleted, Toast.LENGTH_SHORT).show();
                    loadUploadRecords();
                });
                
            } catch (Exception e) {
                Log.e(TAG, "删除记录失败", e);
                mainHandler.post(() -> {
                    Toast.makeText(this, getString(R.string.delete_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
    
    private void clearAllRecords() {
        new Thread(() -> {
            try {
                uploadRecordDao.clearAllRecords();
                
                mainHandler.post(() -> {
                    Toast.makeText(this, R.string.records_cleared, Toast.LENGTH_SHORT).show();
                    loadUploadRecords();
                });
                
            } catch (Exception e) {
                Log.e(TAG, "清空记录失败", e);
                mainHandler.post(() -> {
                    Toast.makeText(this, getString(R.string.clear_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
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

