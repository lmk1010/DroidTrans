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
 * 照片网格适配器
 */
public class PhotoGridAdapter extends RecyclerView.Adapter<PhotoGridAdapter.PhotoViewHolder> {

    private Context context;
    private List<PhotoInfo> photoList = new ArrayList<>();
    private OnPhotoClickListener listener;

    public interface OnPhotoClickListener {
        void onPhotoClick(PhotoInfo photo, int position);
        void onSelectionChanged(int selectedCount);
    }

    public PhotoGridAdapter(Context context) {
        this.context = context;
    }

    public void setOnPhotoClickListener(OnPhotoClickListener listener) {
        this.listener = listener;
    }

    public void updateData(List<PhotoInfo> photos) {
        this.photoList = photos;
        notifyDataSetChanged();
    }

    public void selectAll() {
        for (PhotoInfo photo : photoList) {
            photo.setSelected(true);
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void deselectAll() {
        for (PhotoInfo photo : photoList) {
            photo.setSelected(false);
        }
        notifyDataSetChanged();
        notifySelectionChanged();
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

    public List<PhotoInfo> getSelectedPhotos() {
        List<PhotoInfo> selected = new ArrayList<>();
        for (PhotoInfo photo : photoList) {
            if (photo.isSelected()) {
                selected.add(photo);
            }
        }
        return selected;
    }

    private void notifySelectionChanged() {
        if (listener != null) {
            listener.onSelectionChanged(getSelectedCount());
        }
    }

    @NonNull
    @Override
    public PhotoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_photo_grid, parent, false);
        return new PhotoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PhotoViewHolder holder, int position) {
        PhotoInfo photo = photoList.get(position);
        holder.bind(photo, position);
    }

    @Override
    public int getItemCount() {
        return photoList.size();
    }

    class PhotoViewHolder extends RecyclerView.ViewHolder {
        private MaterialCardView cardView;
        private ImageView ivPhoto;
        private View selectionBorder;
        private View selectionOverlay;
        private MaterialCheckBox checkbox;
        private TextView tvPhotoName;
        private TextView tvPhotoSize;

        public PhotoViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = (MaterialCardView) itemView;
            ivPhoto = itemView.findViewById(R.id.ivPhoto);
            selectionBorder = itemView.findViewById(R.id.selectionBorder);
            selectionOverlay = itemView.findViewById(R.id.selectionOverlay);
            checkbox = itemView.findViewById(R.id.checkbox);
            tvPhotoName = itemView.findViewById(R.id.tvPhotoName);
            tvPhotoSize = itemView.findViewById(R.id.tvPhotoSize);
        }

        public void bind(PhotoInfo photo, int position) {
            // 加载图片
            Uri uri = Uri.parse(photo.getUri());
            Glide.with(context)
                    .load(uri)
                    .centerCrop()
                    .placeholder(R.drawable.ic_no_photos)
                    .into(ivPhoto);

            // 设置照片名称和大小
            tvPhotoName.setText(photo.getName());
            if (photo.getSizeMb() < 1) {
                tvPhotoSize.setText(String.format("%.0f KB", photo.getSizeMb() * 1024));
            } else {
                tvPhotoSize.setText(String.format("%.1f MB", photo.getSizeMb()));
            }

            // 设置选中状态
            boolean isSelected = photo.isSelected();
            checkbox.setChecked(isSelected);
            cardView.setChecked(isSelected);
            selectionBorder.setSelected(isSelected);
            selectionOverlay.setVisibility(isSelected ? View.VISIBLE : View.GONE);

            // 点击事件
            cardView.setOnClickListener(v -> {
                photo.setSelected(!photo.isSelected());
                notifyItemChanged(position);
                notifySelectionChanged();

                if (listener != null) {
                    listener.onPhotoClick(photo, position);
                }
            });
        }
    }
}
