package com.mk.androidtransfer.network;

/**
 * 局域网可用通道。数字越小越优先。
 */
public enum TransferProtocol {
    TCP(1, "TCP 直传"),
    FTP(2, "FTP"),
    HTTP_PUT(3, "HTTP PUT"),
    HTTP_MULTIPART(4, "HTTP 表单");

    public final int priority;
    public final String label;

    TransferProtocol(int priority, String label) {
        this.priority = priority;
        this.label = label;
    }
}
