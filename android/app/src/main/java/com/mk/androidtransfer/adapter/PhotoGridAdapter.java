package com.mk.androidtransfer.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.mk.androidtransfer.R;
import com.mk.androidtransfer.model.PhotoInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 照片网格适配器
 */
public class PhotoGridAdapter extends RecyclerView.Adapter<PhotoGridAdapter.PhotoViewHolder> {

    private final Context context;
    private final int thumbSize;
    private List<PhotoInfo> photoList = new ArrayList<>();
    private OnPhotoClickListener listener;

    public interface OnPhotoClickListener {
        void onPhotoClick(PhotoInfo photo, int position);
        void onSelectionChanged(int selectedCount);
    }

    public PhotoGridAdapter(Context context) {
        this.context = context;
        int width = context.getResources().getDisplayMetrics().widthPixels;
        this.thumbSize = Math.max(240, width / 3);
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
        private final ImageView ivPhoto;
        private final View selectionOverlay;
        private final ImageView checkbox;
        private final ImageView ivVideoBadge;

        public PhotoViewHolder(@NonNull View itemView) {
            super(itemView);
            ivPhoto = itemView.findViewById(R.id.ivPhoto);
            selectionOverlay = itemView.findViewById(R.id.selectionOverlay);
            checkbox = itemView.findViewById(R.id.checkbox);
            ivVideoBadge = itemView.findViewById(R.id.ivVideoBadge);
        }

        public void bind(PhotoInfo photo, int position) {
            Glide.with(context)
                    .load(photo.getLoadUri())
                    .centerCrop()
                    .override(thumbSize, thumbSize)
                    .dontAnimate()
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .placeholder(R.drawable.ic_no_photos)
                    .into(ivPhoto);

            ivVideoBadge.setVisibility(photo.isVideo() ? View.VISIBLE : View.GONE);
            bindSelection(photo);

            itemView.setOnClickListener(v -> {
                photo.setSelected(!photo.isSelected());
                bindSelection(photo);
                notifySelectionChanged();
                if (listener != null) {
                    listener.onPhotoClick(photo, position);
                }
            });
        }

        private void bindSelection(PhotoInfo photo) {
            boolean selected = photo.isSelected();
            checkbox.setSelected(selected);
            selectionOverlay.setVisibility(selected ? View.VISIBLE : View.GONE);
        }
    }
}
