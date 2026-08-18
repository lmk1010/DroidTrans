package com.mk.androidtransfer.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.mk.androidtransfer.R;
import com.mk.androidtransfer.model.AlbumInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 相册列表适配器
 */
public class AlbumListAdapter extends RecyclerView.Adapter<AlbumListAdapter.AlbumViewHolder> {

    private Context context;
    private List<AlbumInfo> albumList;
    private OnAlbumClickListener listener;
    private OnAlbumLongClickListener longClickListener;
    private OnSelectionChangedListener selectionChangedListener;
    private boolean isSelectionMode = false;
    private Set<String> selectedAlbumPaths = new HashSet<>();

    public interface OnAlbumClickListener {
        void onAlbumClick(AlbumInfo album, int position);
    }
    
    public interface OnAlbumLongClickListener {
        void onAlbumLongClick(AlbumInfo album, int position);
    }
    
    public interface OnSelectionChangedListener {
        void onSelectionChanged(int selectedCount);
    }

    public AlbumListAdapter(Context context) {
        this.context = context;
        this.albumList = new ArrayList<>();
    }

    public void setOnAlbumClickListener(OnAlbumClickListener listener) {
        this.listener = listener;
    }
    
    public void setOnAlbumLongClickListener(OnAlbumLongClickListener listener) {
        this.longClickListener = listener;
    }
    
    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.selectionChangedListener = listener;
    }

    public void setAlbumList(List<AlbumInfo> albumList) {
        this.albumList = albumList;
        notifyDataSetChanged();
    }
    
    /**
     * 设置选择模式
     */
    public void setSelectionMode(boolean isSelectionMode) {
        this.isSelectionMode = isSelectionMode;
        if (!isSelectionMode) {
            selectedAlbumPaths.clear();
        }
        notifyDataSetChanged();
    }
    
    /**
     * 切换相册选中状态
     */
    public void toggleAlbumSelection(String albumPath) {
        if (selectedAlbumPaths.contains(albumPath)) {
            selectedAlbumPaths.remove(albumPath);
        } else {
            selectedAlbumPaths.add(albumPath);
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }
    
    /**
     * 全选相册
     */
    public void selectAll() {
        selectedAlbumPaths.clear();
        for (AlbumInfo album : albumList) {
            selectedAlbumPaths.add(album.getAlbumPath());
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }
    
    /**
     * 取消全选
     */
    public void deselectAll() {
        selectedAlbumPaths.clear();
        notifyDataSetChanged();
        notifySelectionChanged();
    }
    
    /**
     * 通知选择状态改变
     */
    private void notifySelectionChanged() {
        if (selectionChangedListener != null) {
            selectionChangedListener.onSelectionChanged(selectedAlbumPaths.size());
        }
    }
    
    /**
     * 获取选中的相册
     */
    public List<AlbumInfo> getSelectedAlbums() {
        List<AlbumInfo> selected = new ArrayList<>();
        for (AlbumInfo album : albumList) {
            if (selectedAlbumPaths.contains(album.getAlbumPath())) {
                selected.add(album);
            }
        }
        return selected;
    }
    
    /**
     * 获取选中的相册数量
     */
    public int getSelectedCount() {
        return selectedAlbumPaths.size();
    }

    @NonNull
    @Override
    public AlbumViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_album, parent, false);
        return new AlbumViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AlbumViewHolder holder, int position) {
        AlbumInfo album = albumList.get(position);
        boolean isSelected = selectedAlbumPaths.contains(album.getAlbumPath());
        
        // 设置相册名称
        holder.tvAlbumName.setText(album.getAlbumName());
        
        // 设置照片数量（在Chip中显示）
        holder.chipPhotoCount.setText(String.valueOf(album.getPhotoCount()));
        
        // 设置相册图标
        holder.ivAlbumIcon.setImageResource(album.getIconResId());
        
        // 设置选择状态
        holder.checkboxContainer.setVisibility(isSelectionMode ? View.VISIBLE : View.GONE);
        if (isSelected) {
            holder.checkboxBackground.setBackgroundResource(R.drawable.checkbox_background_checked);
            holder.checkboxIcon.setVisibility(View.VISIBLE);
        } else {
            holder.checkboxBackground.setBackgroundResource(R.drawable.checkbox_background);
            holder.checkboxIcon.setVisibility(View.GONE);
        }
        holder.viewOverlay.setVisibility(isSelected ? View.VISIBLE : View.GONE);
        
        // 加载封面图片
        if (album.getCoverPhotoPath() != null) {
            Glide.with(context)
                    .load(album.getCoverPhotoPath())
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_image_placeholder)
                    .into(holder.ivCover);
        } else {
            holder.ivCover.setImageResource(R.drawable.ic_image_placeholder);
        }
        
        // 点击事件
        holder.itemView.setOnClickListener(v -> {
            if (isSelectionMode) {
                // 选择模式：切换选中状态
                toggleAlbumSelection(album.getAlbumPath());
            } else {
                // 普通模式：打开相册
                if (listener != null) {
                    listener.onAlbumClick(album, position);
                }
            }
        });
        
        // 长按事件：进入选择模式
        holder.itemView.setOnLongClickListener(v -> {
            if (!isSelectionMode && longClickListener != null) {
                longClickListener.onAlbumLongClick(album, position);
                return true;
            }
            return false;
        });
        
        // 复选框点击
        holder.checkboxContainer.setOnClickListener(v -> {
            toggleAlbumSelection(album.getAlbumPath());
        });
    }

    @Override
    public int getItemCount() {
        return albumList.size();
    }

    static class AlbumViewHolder extends RecyclerView.ViewHolder {
        ImageView ivCover;
        ImageView ivAlbumIcon;
        TextView tvAlbumName;
        com.google.android.material.chip.Chip chipPhotoCount;
        View checkboxContainer;
        View checkboxBackground;
        ImageView checkboxIcon;
        View viewOverlay;

        public AlbumViewHolder(@NonNull View itemView) {
            super(itemView);
            ivCover = itemView.findViewById(R.id.ivAlbumCover);
            ivAlbumIcon = itemView.findViewById(R.id.ivAlbumIcon);
            tvAlbumName = itemView.findViewById(R.id.tvAlbumName);
            chipPhotoCount = itemView.findViewById(R.id.chipPhotoCount);
            checkboxContainer = itemView.findViewById(R.id.checkboxContainer);
            checkboxBackground = itemView.findViewById(R.id.checkboxBackground);
            checkboxIcon = itemView.findViewById(R.id.checkboxIcon);
            viewOverlay = itemView.findViewById(R.id.viewSelectedOverlay);
        }
    }
}

