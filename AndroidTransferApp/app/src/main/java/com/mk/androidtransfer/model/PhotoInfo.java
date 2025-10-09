package com.mk.androidtransfer.model;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

/**
 * 照片信息数据模型
 */
public class PhotoInfo implements Parcelable {
    private String path;           // 照片路径
    private String name;           // 文件名
    private long size;             // 文件大小（字节）
    private double sizeMb;         // 文件大小（MB）
    private long mtime;            // 修改时间戳
    private String date;           // 格式化日期
    private boolean selected;      // 是否被选中
    private String uri;            // Content URI

    public PhotoInfo() {
        this.selected = false;
    }

    public PhotoInfo(String path, String name, long size, long mtime, String date, String uri) {
        this.path = path;
        this.name = name;
        this.size = size;
        this.sizeMb = size / 1024.0 / 1024.0;
        this.mtime = mtime;
        this.date = date;
        this.selected = false;
        this.uri = uri;
    }

    // Getters and Setters
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

    // Parcelable implementation
    protected PhotoInfo(Parcel in) {
        path = in.readString();
        name = in.readString();
        size = in.readLong();
        sizeMb = in.readDouble();
        mtime = in.readLong();
        date = in.readString();
        selected = in.readByte() != 0;
        uri = in.readString();
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
