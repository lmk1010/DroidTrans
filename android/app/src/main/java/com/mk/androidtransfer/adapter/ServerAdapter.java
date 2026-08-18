package com.mk.androidtransfer.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.mk.androidtransfer.R;
import com.mk.androidtransfer.model.ServerInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器列表适配器
 */
public class ServerAdapter extends RecyclerView.Adapter<ServerAdapter.ServerViewHolder> {

    private List<ServerInfo> serverList = new ArrayList<>();
    private OnServerClickListener listener;

    public interface OnServerClickListener {
        void onServerClick(ServerInfo server);
    }

    public void setOnServerClickListener(OnServerClickListener listener) {
        this.listener = listener;
    }

    public void updateData(List<ServerInfo> servers) {
        this.serverList = servers;
        notifyDataSetChanged();
    }

    public void addServer(ServerInfo server) {
        // 检查是否已存在相同IP的服务器
        for (ServerInfo existingServer : serverList) {
            if (existingServer.getIpAddress().equals(server.getIpAddress())) {
                return; // 已存在，不重复添加
            }
        }
        serverList.add(server);
        notifyItemInserted(serverList.size() - 1);
    }

    public void clearServers() {
        serverList.clear();
        notifyDataSetChanged();
    }
    
    public void removeServer(ServerInfo server) {
        int position = serverList.indexOf(server);
        if (position >= 0) {
            serverList.remove(position);
            notifyItemRemoved(position);
        }
    }

    @NonNull
    @Override
    public ServerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_server, parent, false);
        return new ServerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ServerViewHolder holder, int position) {
        ServerInfo server = serverList.get(position);
        holder.bind(server);
    }

    @Override
    public int getItemCount() {
        return serverList.size();
    }

    class ServerViewHolder extends RecyclerView.ViewHolder {
        private MaterialCardView cardView;
        private ImageView ivServerIcon;
        private TextView tvServerName;
        private TextView tvServerIp;
        private Chip chipStatus;
        private ImageView ivChevron;

        public ServerViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = (MaterialCardView) itemView;
            ivServerIcon = itemView.findViewById(R.id.ivServerIcon);
            tvServerName = itemView.findViewById(R.id.tvServerName);
            tvServerIp = itemView.findViewById(R.id.tvServerIp);
            chipStatus = itemView.findViewById(R.id.chipStatus);
            ivChevron = itemView.findViewById(R.id.ivChevron);
        }

        public void bind(ServerInfo server) {
            tvServerName.setText(server.getName());
            tvServerIp.setText(server.getDisplayAddress());

            if (server.isAvailable()) {
                chipStatus.setText(R.string.available);
                chipStatus.setChipBackgroundColorResource(R.color.success);
            } else {
                chipStatus.setText(R.string.unavailable);
                chipStatus.setChipBackgroundColorResource(R.color.error);
            }

            cardView.setOnClickListener(v -> {
                if (listener != null && server.isAvailable()) {
                    listener.onServerClick(server);
                }
            });
        }
    }
}
