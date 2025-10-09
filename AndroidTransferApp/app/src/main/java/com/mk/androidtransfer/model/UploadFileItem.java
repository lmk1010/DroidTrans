package com.mk.androidtransfer.model;

/**
 * 上传文件项
 */
public class UploadFileItem {

    public enum Status {
        PENDING,    // 等待中
        UPLOADING,  // 上传中
        COMPLETED,  // 已完成
        FAILED      // 失败
    }

    private String name;      // 文件名
    private String path;      // 文件路径
    private long size;        // 文件大小（字节）
    private Status status;    // 上传状态
    private int progress;     // 上传进度（0-100）
    private long speed;       // 上传速度（字节/秒）
    private String errorMessage; // 错误信息

    public UploadFileItem(String name, String path, long size) {
        this.name = name;
        this.path = path;
        this.size = size;
        this.status = Status.PENDING;
        this.progress = 0;
        this.speed = 0;
    }

    // Getters and Setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = Math.max(0, Math.min(100, progress));
    }

    public long getSpeed() {
        return speed;
    }

    public void setSpeed(long speed) {
        this.speed = speed;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}

