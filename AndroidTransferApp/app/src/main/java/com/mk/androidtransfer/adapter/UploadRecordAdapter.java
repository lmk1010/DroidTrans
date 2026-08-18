package com.mk.androidtransfer.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.mk.androidtransfer.R;
import com.mk.androidtransfer.model.UploadRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * 上传记录Adapter - 扁平化蓝色风格
 */
public class UploadRecordAdapter extends RecyclerView.Adapter<UploadRecordAdapter.ViewHolder> {
    
    private Context context;
    private List<UploadRecord> records = new ArrayList<>();
    private OnItemClickListener listener;
    
    public interface OnItemClickListener {
        void onItemClick(UploadRecord record);
        void onDeleteClick(UploadRecord record);
    }
    
    public UploadRecordAdapter(Context context) {
        this.context = context;
    }
    
    public void setRecords(List<UploadRecord> records) {
        this.records = records;
        notifyDataSetChanged();
    }
    
    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }
    
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_upload_record, parent, false);
        return new ViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        UploadRecord record = records.get(position);
        
        // 服务器名称和时间
        holder.tvServerName.setText(record.getServerName());
        holder.tvUploadTime.setText(formatTime(record.getUploadTimeStr()));
        
        // 数量显示 - 简洁格式
        holder.tvSuccessCount.setText(String.valueOf(record.getSuccessCount()));
        holder.tvTotalCount.setText(String.valueOf(record.getTotalCount()));
        
        // 失败数量（如果有）
        if (record.getFailedCount() > 0) {
            holder.layoutFailed.setVisibility(View.VISIBLE);
            holder.tvFailedCount.setText(String.valueOf(record.getFailedCount()));
        } else {
            holder.layoutFailed.setVisibility(View.GONE);
        }
        
        // 耗时（模拟计算，实际应该从数据库读取）
        holder.tvDuration.setText(formatDuration(estimateDuration(record)));
        
        // 计算成功率
        float successRate = record.getTotalCount() > 0 
            ? (record.getSuccessCount() * 100.0f / record.getTotalCount()) 
            : 0;
        holder.tvSuccessRate.setText(String.format("%.0f%%", successRate));
        
        // 设置进度条
        if (holder.progressSuccess != null) {
            holder.progressSuccess.setProgress((int) successRate);
        }
        
        // 点击事件
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(record);
            }
        });
        
        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDeleteClick(record);
            }
        });
    }
    
    /**
     * 格式化时间 - 简化显示
     */
    private String formatTime(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) {
            return "";
        }
        // 如果是 "2025-11-17 16:30:25" 格式，截取为 "2025-11-17 16:30"
        if (timeStr.length() > 16) {
            return timeStr.substring(0, 16);
        }
        return timeStr;
    }
    
    /**
     * 估算耗时（根据文件数量）
     */
    private int estimateDuration(UploadRecord record) {
        // 每个文件平均3秒，失败的算1秒
        int successTime = record.getSuccessCount() * 3;
        int failedTime = record.getFailedCount() * 1;
        return successTime + failedTime;
    }
    
    /**
     * 格式化耗时
     */
    private String formatDuration(int seconds) {
        if (seconds < 60) {
            return seconds + "秒";
        } else if (seconds < 3600) {
            int minutes = seconds / 60;
            int secs = seconds % 60;
            if (secs == 0) {
                return minutes + "分钟";
            }
            return String.format("%d分%d秒", minutes, secs);
        } else {
            int hours = seconds / 3600;
            int minutes = (seconds % 3600) / 60;
            if (minutes == 0) {
                return hours + "小时";
            }
            return String.format("%d小时%d分", hours, minutes);
        }
    }
    
    @Override
    public int getItemCount() {
        return records.size();
    }
    
    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvServerName;
        TextView tvUploadTime;
        TextView tvTotalCount;
        TextView tvSuccessCount;
        TextView tvFailedCount;
        TextView tvDuration;
        TextView tvSuccessRate;
        LinearLayout layoutFailed;
        android.widget.ProgressBar progressSuccess;
        com.google.android.material.button.MaterialButton btnDelete;
        
        ViewHolder(View itemView) {
            super(itemView);
            tvServerName = itemView.findViewById(R.id.tvServerName);
            tvUploadTime = itemView.findViewById(R.id.tvUploadTime);
            tvTotalCount = itemView.findViewById(R.id.tvTotalCount);
            tvSuccessCount = itemView.findViewById(R.id.tvSuccessCount);
            tvFailedCount = itemView.findViewById(R.id.tvFailedCount);
            tvDuration = itemView.findViewById(R.id.tvDuration);
            tvSuccessRate = itemView.findViewById(R.id.tvSuccessRate);
            layoutFailed = itemView.findViewById(R.id.layoutFailed);
            progressSuccess = itemView.findViewById(R.id.progressSuccess);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }
    }
}
