package com.mk.androidtransfer.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.mk.androidtransfer.R;
import com.mk.androidtransfer.model.UploadRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * 上传记录Adapter
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
        
        holder.tvServerName.setText(record.getServerName());
        holder.tvUploadTime.setText(record.getUploadTimeStr());
        holder.tvTotalCount.setText(String.format("总数: %d", record.getTotalCount()));
        holder.tvSuccessCount.setText(String.format("成功: %d", record.getSuccessCount()));
        holder.tvFailedCount.setText(String.format("失败: %d", record.getFailedCount()));
        
        // 计算成功率
        float successRate = record.getTotalCount() > 0 
            ? (record.getSuccessCount() * 100.0f / record.getTotalCount()) 
            : 0;
        holder.tvSuccessRate.setText(String.format("成功率: %.1f%%", successRate));
        
        // 设置成功率颜色
        if (successRate == 100) {
            holder.tvSuccessRate.setTextColor(context.getColor(R.color.success));
        } else if (successRate >= 80) {
            holder.tvSuccessRate.setTextColor(context.getColor(R.color.warning));
        } else {
            holder.tvSuccessRate.setTextColor(context.getColor(R.color.error));
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
        TextView tvSuccessRate;
        ImageButton btnDelete;
        
        ViewHolder(View itemView) {
            super(itemView);
            tvServerName = itemView.findViewById(R.id.tvServerName);
            tvUploadTime = itemView.findViewById(R.id.tvUploadTime);
            tvTotalCount = itemView.findViewById(R.id.tvTotalCount);
            tvSuccessCount = itemView.findViewById(R.id.tvSuccessCount);
            tvFailedCount = itemView.findViewById(R.id.tvFailedCount);
            tvSuccessRate = itemView.findViewById(R.id.tvSuccessRate);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }
    }
}

