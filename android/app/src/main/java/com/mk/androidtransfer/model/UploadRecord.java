package com.mk.androidtransfer.model;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 上传记录数据模型
 */
public class UploadRecord {
    private long id;
    private String serverUrl;
    private String serverName;
    private int totalCount;           // 总数量
    private int successCount;         // 成功数量
    private int failedCount;          // 失败数量
    private long uploadTime;          // 上传时间戳
    private String uploadTimeStr;     // 格式化的时间字符串
    private String fileList;          // 文件列表（JSON格式）
    private long durationSec;
    private long totalBytes;
    
    public UploadRecord() {
    }
    
    public UploadRecord(String serverUrl, String serverName, int totalCount, int successCount, int failedCount, long uploadTime, String fileList) {
        this(serverUrl, serverName, totalCount, successCount, failedCount, uploadTime, fileList, 0, 0);
    }

    public UploadRecord(String serverUrl, String serverName, int totalCount, int successCount, int failedCount, long uploadTime, String fileList, long durationSec, long totalBytes) {
        this.serverUrl = serverUrl;
        this.serverName = serverName;
        this.totalCount = totalCount;
        this.successCount = successCount;
        this.failedCount = failedCount;
        this.uploadTime = uploadTime;
        this.fileList = fileList;
        this.durationSec = durationSec;
        this.totalBytes = totalBytes;
        this.uploadTimeStr = formatTime(uploadTime);
    }
    
    private String formatTime(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }
    
    // Getters and Setters
    public long getId() {
        return id;
    }
    
    public void setId(long id) {
        this.id = id;
    }
    
    public String getServerUrl() {
        return serverUrl;
    }
    
    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }
    
    public String getServerName() {
        return serverName;
    }
    
    public void setServerName(String serverName) {
        this.serverName = serverName;
    }
    
    public int getTotalCount() {
        return totalCount;
    }
    
    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }
    
    public int getSuccessCount() {
        return successCount;
    }
    
    public void setSuccessCount(int successCount) {
        this.successCount = successCount;
    }
    
    public int getFailedCount() {
        return failedCount;
    }
    
    public void setFailedCount(int failedCount) {
        this.failedCount = failedCount;
    }
    
    public long getUploadTime() {
        return uploadTime;
    }
    
    public void setUploadTime(long uploadTime) {
        this.uploadTime = uploadTime;
        this.uploadTimeStr = formatTime(uploadTime);
    }
    
    public String getUploadTimeStr() {
        return uploadTimeStr;
    }
    
    public void setUploadTimeStr(String uploadTimeStr) {
        this.uploadTimeStr = uploadTimeStr;
    }
    
    public String getFileList() {
        return fileList;
    }
    
    public void setFileList(String fileList) {
        this.fileList = fileList;
    }

    public long getDurationSec() {
        return durationSec;
    }

    public void setDurationSec(long durationSec) {
        this.durationSec = durationSec;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }
}

