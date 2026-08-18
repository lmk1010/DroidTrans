package com.mk.androidtransfer.network;

import android.content.Context;

import com.mk.androidtransfer.R;

/**
 * 局域网可用通道。数字越小越优先。
 */
public enum TransferProtocol {
    TCP(1, R.string.protocol_tcp),
    FTP(2, R.string.protocol_ftp),
    HTTP_PUT(3, R.string.protocol_http_put),
    HTTP_MULTIPART(4, R.string.protocol_http_form);

    public final int priority;
    public final int labelRes;
    public final String label;

    TransferProtocol(int priority, int labelRes) {
        this.priority = priority;
        this.labelRes = labelRes;
        this.label = defaultLabel(labelRes);
    }

    public String getLabel(Context context) {
        return context.getString(labelRes);
    }

    private static String defaultLabel(int labelRes) {
        if (labelRes == R.string.protocol_tcp) return "TCP";
        if (labelRes == R.string.protocol_ftp) return "FTP";
        if (labelRes == R.string.protocol_http_put) return "HTTP PUT";
        return "HTTP";
    }
}
