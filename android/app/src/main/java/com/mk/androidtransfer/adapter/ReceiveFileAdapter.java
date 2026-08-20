package com.mk.androidtransfer.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.mk.androidtransfer.R;
import com.mk.androidtransfer.model.ReceiveItem;
import com.mk.androidtransfer.util.TransferFormat;

import java.util.List;

/** 电脑发来的文件清单。 */
public class ReceiveFileAdapter extends RecyclerView.Adapter<ReceiveFileAdapter.VH> {

    private final List<ReceiveItem> items;

    public ReceiveFileAdapter(List<ReceiveItem> items) {
        this.items = items;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_receive_file, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        ReceiveItem item = items.get(position);
        holder.name.setText(item.rel == null || item.rel.isEmpty() ? item.name : item.rel);
        holder.size.setText(item.size < 0 ? "" : TransferFormat.bytes(item.size));

        int stateRes;
        int colorRes;
        switch (item.state) {
            case DOWNLOADING:
                stateRes = R.string.receive_state_downloading;
                colorRes = R.color.primary;
                break;
            case DONE:
                stateRes = R.string.receive_state_done;
                colorRes = R.color.success;
                break;
            case FAILED:
                stateRes = R.string.receive_state_failed;
                colorRes = R.color.error;
                break;
            default:
                stateRes = R.string.receive_state_pending;
                colorRes = R.color.text_medium_emphasis;
                break;
        }
        holder.state.setText(stateRes);
        holder.state.setTextColor(holder.itemView.getContext().getColor(colorRes));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView size;
        final TextView state;

        VH(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.tvReceiveName);
            size = itemView.findViewById(R.id.tvReceiveSize);
            state = itemView.findViewById(R.id.tvReceiveState);
        }
    }
}
