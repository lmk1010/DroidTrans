package com.mk.androidtransfer.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * 照片列表上传请求数据模型
 */
public class PhotoListRequest {
    @SerializedName("device_id")
    private String deviceId;
    private List<PhotoInfo> photos;

    public PhotoListRequest(String deviceId, List<PhotoInfo> photos) {
        this.deviceId = deviceId;
        this.photos = photos;
    }

    // Getters and Setters
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public List<PhotoInfo> getPhotos() { return photos; }
    public void setPhotos(List<PhotoInfo> photos) { this.photos = photos; }
}
