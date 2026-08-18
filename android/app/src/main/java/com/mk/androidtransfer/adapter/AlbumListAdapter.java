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

    private final Context context;
    private final int thumbSize;
    private List<AlbumInfo> albumList;
    private OnAlbumClickListener listener;
    private OnAlbumLongClickListener longClickListener;
    private OnSelectionChangedListener selectionChangedListener;
    private boolean isSelectionMode = false;
    private final Set<String> selectedAlbumPaths = new HashSet<>();

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
        int width = context.getResources().getDisplayMetrics().widthPixels;
        this.thumbSize = Math.max(320, width / 2);
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

    public void setSelectionMode(boolean isSelectionMode) {
        this.isSelectionMode = isSelectionMode;
        if (!isSelectionMode) {
            selectedAlbumPaths.clear();
        }
        notifyDataSetChanged();
    }

    public void toggleAlbumSelection(String albumPath) {
        if (selectedAlbumPaths.contains(albumPath)) {
            selectedAlbumPaths.remove(albumPath);
        } else {
            selectedAlbumPaths.add(albumPath);
        }
        for (int i = 0; i < albumList.size(); i++) {
            if (albumPath.equals(albumList.get(i).getAlbumPath())) {
                notifyItemChanged(i);
                break;
            }
        }
        notifySelectionChanged();
    }

    public void selectAll() {
        selectedAlbumPaths.clear();
        for (AlbumInfo album : albumList) {
            selectedAlbumPaths.add(album.getAlbumPath());
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void deselectAll() {
        selectedAlbumPaths.clear();
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    private void notifySelectionChanged() {
        if (selectionChangedListener != null) {
            selectionChangedListener.onSelectionChanged(selectedAlbumPaths.size());
        }
    }

    public List<AlbumInfo> getSelectedAlbums() {
        List<AlbumInfo> selected = new ArrayList<>();
        for (AlbumInfo album : albumList) {
            if (selectedAlbumPaths.contains(album.getAlbumPath())) {
                selected.add(album);
            }
        }
        return selected;
    }

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

        holder.tvAlbumName.setText(AlbumInfo.getAlbumDisplayName(context, album.getAlbumPath()));
        holder.chipPhotoCount.setText(String.valueOf(album.getPhotoCount()));
        holder.ivAlbumIcon.setImageResource(album.getIconResId());

        holder.checkboxContainer.setVisibility(isSelectionMode ? View.VISIBLE : View.GONE);
        holder.checkboxContainer.setImageResource(
                isSelected ? R.drawable.checkbox_background_checked : R.drawable.checkbox_background
        );
        holder.viewOverlay.setVisibility(isSelected ? View.VISIBLE : View.GONE);

        if (album.getCoverPhotoPath() != null) {
            Glide.with(context)
                    .load(album.getCoverPhotoPath())
                    .centerCrop()
                    .override(thumbSize, thumbSize)
                    .dontAnimate()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_image_placeholder)
                    .into(holder.ivCover);
        } else {
            holder.ivCover.setImageResource(R.drawable.ic_image_placeholder);
        }

        holder.itemView.setOnClickListener(v -> {
            if (isSelectionMode) {
                toggleAlbumSelection(album.getAlbumPath());
            } else if (listener != null) {
                listener.onAlbumClick(album, position);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (!isSelectionMode && longClickListener != null) {
                longClickListener.onAlbumLongClick(album, position);
                return true;
            }
            return false;
        });

        holder.checkboxContainer.setOnClickListener(v -> toggleAlbumSelection(album.getAlbumPath()));
    }

    @Override
    public int getItemCount() {
        return albumList.size();
    }

    static class AlbumViewHolder extends RecyclerView.ViewHolder {
        ImageView ivCover;
        ImageView ivAlbumIcon;
        TextView tvAlbumName;
        TextView chipPhotoCount;
        ImageView checkboxContainer;
        View viewOverlay;

        public AlbumViewHolder(@NonNull View itemView) {
            super(itemView);
            ivCover = itemView.findViewById(R.id.ivAlbumCover);
            ivAlbumIcon = itemView.findViewById(R.id.ivAlbumIcon);
            tvAlbumName = itemView.findViewById(R.id.tvAlbumName);
            chipPhotoCount = itemView.findViewById(R.id.chipPhotoCount);
            checkboxContainer = itemView.findViewById(R.id.checkboxContainer);
            viewOverlay = itemView.findViewById(R.id.viewSelectedOverlay);
        }
    }
}
