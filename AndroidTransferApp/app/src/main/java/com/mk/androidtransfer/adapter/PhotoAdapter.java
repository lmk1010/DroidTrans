package com.mk.androidtransfer.adapter;

import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.mk.androidtransfer.R;
import com.mk.androidtransfer.model.PhotoInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 照片列表适配器
 */
public class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder> {

    private Context context;
    private List<PhotoInfo> photoList;
    private OnPhotoClickListener listener;

    public interface OnPhotoClickListener {
        void onPhotoClick(PhotoInfo photo, int position);
        void onPhotoSelectionChanged(PhotoInfo photo, int position, boolean isSelected);
    }

    public PhotoAdapter(Context context, List<PhotoInfo> photoList) {
        this.context = context;
        this.photoList = photoList != null ? photoList : new ArrayList<>();
    }

    public void setOnPhotoClickListener(OnPhotoClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public PhotoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_photo, parent, false);
        return new PhotoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PhotoViewHolder holder, int position) {
        PhotoInfo photo = photoList.get(position);

        holder.tvPhotoName.setText(photo.getName());
        holder.tvPhotoSize.setText(String.format("%.2f MB", photo.getSizeMb()));
        holder.tvPhotoDate.setText(photo.getDate());
        holder.cbSelected.setChecked(photo.isSelected());

        // 加载缩略图
        if (photo.getUri() != null && !photo.getUri().isEmpty()) {
            Glide.with(context)
                    .load(Uri.parse(photo.getUri()))
                    .centerCrop()
                    .placeholder(R.mipmap.ic_launcher)
                    .error(R.mipmap.ic_launcher)
                    .into(holder.ivThumbnail);
        }

        // 卡片点击事件
        holder.cardPhoto.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPhotoClick(photo, position);
            }
        });

        // 复选框点击事件
        holder.cbSelected.setOnCheckedChangeListener((buttonView, isChecked) -> {
            photo.setSelected(isChecked);
            if (listener != null) {
                listener.onPhotoSelectionChanged(photo, position, isChecked);
            }
        });
    }

    @Override
    public int getItemCount() {
        return photoList.size();
    }

    public void updateData(List<PhotoInfo> newPhotoList) {
        this.photoList = newPhotoList != null ? newPhotoList : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void selectAll() {
        for (PhotoInfo photo : photoList) {
            photo.setSelected(true);
        }
        notifyDataSetChanged();
    }

    public void deselectAll() {
        for (PhotoInfo photo : photoList) {
            photo.setSelected(false);
        }
        notifyDataSetChanged();
    }

    public List<PhotoInfo> getSelectedPhotos() {
        List<PhotoInfo> selectedPhotos = new ArrayList<>();
        for (PhotoInfo photo : photoList) {
            if (photo.isSelected()) {
                selectedPhotos.add(photo);
            }
        }
        return selectedPhotos;
    }

    public int getSelectedCount() {
        int count = 0;
        for (PhotoInfo photo : photoList) {
            if (photo.isSelected()) {
                count++;
            }
        }
        return count;
    }

    static class PhotoViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView cardPhoto;
        ImageView ivThumbnail;
        TextView tvPhotoName;
        TextView tvPhotoSize;
        TextView tvPhotoDate;
        MaterialCheckBox cbSelected;

        public PhotoViewHolder(@NonNull View itemView) {
            super(itemView);
            cardPhoto = itemView.findViewById(R.id.cardPhoto);
            ivThumbnail = itemView.findViewById(R.id.ivThumbnail);
            tvPhotoName = itemView.findViewById(R.id.tvPhotoName);
            tvPhotoSize = itemView.findViewById(R.id.tvPhotoSize);
            tvPhotoDate = itemView.findViewById(R.id.tvPhotoDate);
            cbSelected = itemView.findViewById(R.id.cbSelected);
        }
    }
}
