package com.mk.androidtransfer.model;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;

/**
 * 照片/视频信息
 */
public class PhotoInfo implements Parcelable {
    private String path;
    private String name;
    private long size;
    private double sizeMb;
    private long mtime;
    private String date;
    private boolean selected;
    private String uri;
    private String mimeType;
    private boolean video;
    private String relativePath;
    private String bucketId;
    private String bucketName;
    private long durationMs;

    public PhotoInfo() {
        this.selected = false;
    }

    public PhotoInfo(String path, String name, long size, long mtime, String date, String uri) {
        this(path, name, size, mtime, date, uri, null, false, null, null, null, 0);
    }

    public PhotoInfo(String path, String name, long size, long mtime, String date, String uri,
                     String mimeType, boolean video, String relativePath, String bucketId,
                     String bucketName, long durationMs) {
        this.path = path;
        this.name = name;
        this.size = size;
        this.sizeMb = size / 1024.0 / 1024.0;
        this.mtime = mtime;
        this.date = date;
        this.selected = false;
        this.uri = uri;
        this.mimeType = mimeType;
        this.video = video;
        this.relativePath = relativePath;
        this.bucketId = bucketId;
        this.bucketName = bucketName;
        this.durationMs = durationMs;
    }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public long getSize() { return size; }
    public void setSize(long size) {
        this.size = size;
        this.sizeMb = size / 1024.0 / 1024.0;
    }

    public double getSizeMb() { return sizeMb; }
    public void setSizeMb(double sizeMb) { this.sizeMb = sizeMb; }

    public long getMtime() { return mtime; }
    public void setMtime(long mtime) { this.mtime = mtime; }

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }

    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public boolean isVideo() { return video; }
    public void setVideo(boolean video) { this.video = video; }

    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String relativePath) { this.relativePath = relativePath; }

    public String getBucketId() { return bucketId; }
    public void setBucketId(String bucketId) { this.bucketId = bucketId; }

    public String getBucketName() { return bucketName; }
    public void setBucketName(String bucketName) { this.bucketName = bucketName; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public String getLoadUri() {
        return !TextUtils.isEmpty(uri) ? uri : path;
    }

    public String getStablePath() {
        if (!TextUtils.isEmpty(path)) return path;
        if (!TextUtils.isEmpty(relativePath) && !TextUtils.isEmpty(name)) {
            String rel = relativePath.endsWith("/") ? relativePath : relativePath + "/";
            return "/storage/emulated/0/" + rel + name;
        }
        return name;
    }

    public String getUploadRelativePath() {
        if (!TextUtils.isEmpty(relativePath) && !TextUtils.isEmpty(name)) {
            String rel = relativePath.endsWith("/") ? relativePath : relativePath + "/";
            return rel + name;
        }
        if (!TextUtils.isEmpty(path)) {
            return path.replaceFirst("^/storage/emulated/0/", "")
                    .replaceFirst("^/sdcard/", "");
        }
        return name != null ? name : "unknown";
    }

    protected PhotoInfo(Parcel in) {
        path = in.readString();
        name = in.readString();
        size = in.readLong();
        sizeMb = in.readDouble();
        mtime = in.readLong();
        date = in.readString();
        selected = in.readByte() != 0;
        uri = in.readString();
        mimeType = in.readString();
        video = in.readByte() != 0;
        relativePath = in.readString();
        bucketId = in.readString();
        bucketName = in.readString();
        durationMs = in.readLong();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(path);
        dest.writeString(name);
        dest.writeLong(size);
        dest.writeDouble(sizeMb);
        dest.writeLong(mtime);
        dest.writeString(date);
        dest.writeByte((byte) (selected ? 1 : 0));
        dest.writeString(uri);
        dest.writeString(mimeType);
        dest.writeByte((byte) (video ? 1 : 0));
        dest.writeString(relativePath);
        dest.writeString(bucketId);
        dest.writeString(bucketName);
        dest.writeLong(durationMs);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<PhotoInfo> CREATOR = new Creator<PhotoInfo>() {
        @Override
        public PhotoInfo createFromParcel(Parcel in) {
            return new PhotoInfo(in);
        }

        @Override
        public PhotoInfo[] newArray(int size) {
            return new PhotoInfo[size];
        }
    };
}
