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
import com.mk.androidtransfer.util.TransferFormat;

import java.util.ArrayList;
import java.util.List;

/**
 * 上传记录 Adapter：列表行，无卡片。
 */
public class UploadRecordAdapter extends RecyclerView.Adapter<UploadRecordAdapter.ViewHolder> {

    private final Context context;
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
        String time = formatTime(record.getUploadTimeStr());
        String dur = TransferFormat.duration(context, record.getDurationSec());
        if (!dur.isEmpty()) {
            holder.tvUploadTime.setText(context.getString(R.string.history_time_duration, time, dur));
        } else {
            holder.tvUploadTime.setText(time);
        }

        holder.tvSuccessCount.setText(String.valueOf(record.getSuccessCount()));
        holder.tvTotalCount.setText(String.valueOf(record.getTotalCount()));

        if (record.getFailedCount() > 0) {
            holder.tvFailedCount.setVisibility(View.VISIBLE);
            holder.tvFailedCount.setText(context.getString(R.string.failed_count, record.getFailedCount()));
        } else {
            holder.tvFailedCount.setVisibility(View.GONE);
        }

        StringBuilder meta = new StringBuilder();
        String size = TransferFormat.bytes(record.getTotalBytes());
        if (!size.isEmpty()) {
            meta.append(size);
        }
        if (record.getDurationSec() > 0 && record.getTotalBytes() > 0) {
            long bps = record.getTotalBytes() / Math.max(1, record.getDurationSec());
            String avg = TransferFormat.speed(bps);
            if (!avg.isEmpty()) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(avg);
            }
        }
        float successRate = record.getTotalCount() > 0
                ? (record.getSuccessCount() * 100.0f / record.getTotalCount())
                : 0;
        if (meta.length() > 0) meta.append(" · ");
        meta.append(String.format("%.0f%%", successRate));
        holder.tvSuccessRate.setText(meta.toString());

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

    private String formatTime(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) {
            return "";
        }
        if (timeStr.length() > 16) {
            return timeStr.substring(0, 16);
        }
        return timeStr;
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
