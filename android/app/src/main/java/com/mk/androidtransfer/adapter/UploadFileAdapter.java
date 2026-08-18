package com.mk.androidtransfer.adapter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.mk.androidtransfer.R;
import com.mk.androidtransfer.model.UploadFileItem;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 上传文件列表适配器
 */
public class UploadFileAdapter extends RecyclerView.Adapter<UploadFileAdapter.ViewHolder> {

    private Context context;
    private List<UploadFileItem> fileList = new ArrayList<>();

    public UploadFileAdapter(Context context) {
        this.context = context;
    }

    public void setFileList(List<UploadFileItem> fileList) {
        this.fileList = fileList != null ? fileList : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void updateItem(int position) {
        if (position >= 0 && position < fileList.size()) {
            notifyItemChanged(position);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_upload_file, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        UploadFileItem item = fileList.get(position);
        holder.bind(item);
    }

    @Override
    public int getItemCount() {
        return fileList.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {

        ImageView ivThumbnail;
        ImageView ivStatusIcon;
        TextView tvFileName;
        TextView tvFileSize;
        TextView tvStatus;
        View progressContainer;
        LinearProgressIndicator progressBar;
        TextView tvProgressText;
        TextView tvSpeed;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivThumbnail = itemView.findViewById(R.id.ivThumbnail);
            ivStatusIcon = itemView.findViewById(R.id.ivStatusIcon);
            tvFileName = itemView.findViewById(R.id.tvFileName);
            tvFileSize = itemView.findViewById(R.id.tvFileSize);
            tvStatus = itemView.findViewById(R.id.tvStatus);
            progressContainer = itemView.findViewById(R.id.progressContainer);
            progressBar = itemView.findViewById(R.id.progressBar);
            tvProgressText = itemView.findViewById(R.id.tvProgressText);
            tvSpeed = itemView.findViewById(R.id.tvSpeed);
        }

        void bind(UploadFileItem item) {
            // 设置文件名
            tvFileName.setText(item.getName());

            // 设置文件大小
            tvFileSize.setText(formatFileSize(item.getSize()));

            // 加载缩略图
            loadThumbnail(item.getPath());

            // 根据状态更新UI
            switch (item.getStatus()) {
                case PENDING:
                    tvStatus.setText(R.string.status_waiting);
                    tvStatus.setTextColor(context.getColor(R.color.on_surface_variant));
                    progressContainer.setVisibility(View.GONE);
                    ivStatusIcon.setVisibility(View.GONE);
                    ivThumbnail.setAlpha(1.0f);
                    break;

                case UPLOADING:
                    tvStatus.setText(R.string.status_uploading);
                    tvStatus.setTextColor(context.getColor(R.color.primary));
                    progressContainer.setVisibility(View.VISIBLE);
                    ivStatusIcon.setVisibility(View.GONE);
                    ivThumbnail.setAlpha(0.7f);
                    
                    // 更新进度
                    int progress = item.getProgress();
                    progressBar.setProgress(progress);
                    tvProgressText.setText(progress + "%");
                    
                    // 更新速度
                    if (item.getSpeed() > 0) {
                        tvSpeed.setText(formatSpeed(item.getSpeed()));
                    } else {
                        tvSpeed.setText("");
                    }
                    break;

                case COMPLETED:
                    tvStatus.setText(R.string.status_completed);
                    tvStatus.setTextColor(context.getColor(R.color.primary));
                    progressContainer.setVisibility(View.GONE);
                    ivStatusIcon.setVisibility(View.VISIBLE);
                    ivStatusIcon.setImageResource(R.drawable.ic_check_all);
                    ivStatusIcon.setColorFilter(context.getColor(R.color.primary));
                    ivThumbnail.setAlpha(1.0f);
                    break;

                case FAILED:
                    tvStatus.setText(R.string.failed);
                    tvStatus.setTextColor(context.getColor(R.color.error));
                    progressContainer.setVisibility(View.GONE);
                    ivStatusIcon.setVisibility(View.VISIBLE);
                    ivStatusIcon.setImageResource(android.R.drawable.ic_dialog_alert);
                    ivStatusIcon.setColorFilter(context.getColor(R.color.error));
                    ivThumbnail.setAlpha(0.5f);
                    break;
            }
        }

        private void loadThumbnail(String path) {
            try {
                File file = new File(path);
                if (file.exists()) {
                    // 加载缩略图
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 4; // 缩小4倍
                    options.inJustDecodeBounds = false;
                    
                    Bitmap bitmap = BitmapFactory.decodeFile(path, options);
                    if (bitmap != null) {
                        ivThumbnail.setImageBitmap(bitmap);
                    } else {
                        ivThumbnail.setImageResource(R.drawable.ic_no_photos);
                    }
                } else {
                    ivThumbnail.setImageResource(R.drawable.ic_no_photos);
                }
            } catch (Exception e) {
                e.printStackTrace();
                ivThumbnail.setImageResource(R.drawable.ic_no_photos);
            }
        }

        private String formatFileSize(long size) {
            if (size < 1024) {
                return size + " B";
            } else if (size < 1024 * 1024) {
                return String.format("%.1f KB", size / 1024.0);
            } else {
                return String.format("%.2f MB", size / (1024.0 * 1024.0));
            }
        }

        private String formatSpeed(long bytesPerSecond) {
            if (bytesPerSecond < 1024) {
                return bytesPerSecond + " B/s";
            } else if (bytesPerSecond < 1024 * 1024) {
                return String.format("%.1f KB/s", bytesPerSecond / 1024.0);
            } else {
                return String.format("%.2f MB/s", bytesPerSecond / (1024.0 * 1024.0));
            }
        }
    }
}

