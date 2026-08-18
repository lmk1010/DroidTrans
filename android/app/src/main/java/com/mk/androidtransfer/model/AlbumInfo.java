package com.mk.androidtransfer.model;

import android.content.Context;

import com.mk.androidtransfer.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 相册分组信息
 */
public class AlbumInfo {
    private String albumName;       // 相册名称
    private String albumPath;       // 相册路径
    private int photoCount;         // 照片数量
    private String coverPhotoPath;  // 封面照片路径
    private List<PhotoInfo> photos; // 照片列表
    private int iconResId;          // 相册图标资源ID

    public AlbumInfo(String albumName, String albumPath) {
        this(albumName, albumPath, albumPath);
    }

    public AlbumInfo(String albumName, String albumPath, String iconHintPath) {
        this.albumName = albumName;
        this.albumPath = albumPath;
        this.photos = new ArrayList<>();
        this.photoCount = 0;
        this.iconResId = getAlbumIcon(iconHintPath != null ? iconHintPath : albumPath);
    }

    public String getAlbumName() {
        return albumName;
    }

    public void setAlbumName(String albumName) {
        this.albumName = albumName;
    }

    public String getAlbumPath() {
        return albumPath;
    }

    public void setAlbumPath(String albumPath) {
        this.albumPath = albumPath;
    }

    public int getPhotoCount() {
        return photoCount;
    }

    public void setPhotoCount(int photoCount) {
        this.photoCount = photoCount;
    }

    public String getCoverPhotoPath() {
        return coverPhotoPath;
    }

    public void setCoverPhotoPath(String coverPhotoPath) {
        this.coverPhotoPath = coverPhotoPath;
    }

    public List<PhotoInfo> getPhotos() {
        return photos;
    }

    public void setPhotos(List<PhotoInfo> photos) {
        this.photos = photos;
        this.photoCount = photos.size();
        if (!photos.isEmpty()) {
            this.coverPhotoPath = photos.get(0).getPath();
        }
    }

    public void addPhoto(PhotoInfo photo) {
        this.photos.add(photo);
        this.photoCount = photos.size();
        if (coverPhotoPath == null) {
            this.coverPhotoPath = photo.getPath();
        }
    }
    
    public int getIconResId() {
        return iconResId;
    }

    public static int getAlbumNameRes(String path) {
        if (path == null) return R.string.album_other;

        String lowerPath = path.toLowerCase();
        if (lowerPath.contains("/dcim/camera") || lowerPath.contains("/camera")) {
            return R.string.album_camera;
        }
        if (lowerPath.contains("/screenshots") || lowerPath.contains("screenshot")) {
            return R.string.album_screenshots;
        }
        if (lowerPath.contains("/wechat") || lowerPath.contains("/weixin")
                || lowerPath.contains("/micromsg") || lowerPath.contains("/微信")) {
            return R.string.album_wechat;
        }
        if (lowerPath.contains("/qq") || lowerPath.contains("/tencent/qq")
                || lowerPath.contains("/tencent/qqi")) {
            return R.string.album_qq;
        }
        if (lowerPath.contains("/download")) {
            return R.string.album_download;
        }
        if (lowerPath.contains("/bluetooth")) {
            return R.string.album_bluetooth;
        }
        if (lowerPath.contains("/instagram")) {
            return R.string.album_instagram;
        }
        if (lowerPath.contains("/screenrecord") || lowerPath.contains("/screen_record")) {
            return R.string.album_screen_record;
        }
        if (lowerPath.contains("/pictures")) {
            return R.string.album_pictures;
        }
        return 0;
    }

    public static String getAlbumDisplayName(Context context, String path) {
        int resId = getAlbumNameRes(path);
        if (resId != 0) return context.getString(resId);
        if (path != null) {
            String[] parts = path.split("/");
            if (parts.length > 0) {
                String folderName = parts[parts.length - 1];
                if (!folderName.isEmpty()) {
                    return folderName;
                }
            }
        }
        return context.getString(R.string.album_other);
    }
    
    /**
     * 根据路径获取相册图标
     */
    private static int getAlbumIcon(String path) {
        if (path == null) return R.drawable.ic_album_folder;
        
        String lowerPath = path.toLowerCase();
        
        // 相机
        if (lowerPath.contains("/dcim/camera") || lowerPath.contains("/camera")) {
            return R.drawable.ic_album_camera;
        }
        // 截图
        if (lowerPath.contains("/screenshots") || lowerPath.contains("screenshot")) {
            return R.drawable.ic_album_screenshot;
        }
        // 微信
        if (lowerPath.contains("/wechat") || lowerPath.contains("/weixin")
                || lowerPath.contains("/micromsg") || lowerPath.contains("/微信")) {
            return R.drawable.ic_album_wechat;
        }
        // QQ
        if (lowerPath.contains("/qq") || lowerPath.contains("/tencent/qq")
                || lowerPath.contains("/tencent/qqi")) {
            return R.drawable.ic_album_qq;
        }
        // 下载
        if (lowerPath.contains("/download")) {
            return R.drawable.ic_album_download;
        }
        // Pictures文件夹
        if (lowerPath.contains("/pictures")) {
            return R.drawable.ic_album_image;
        }
        // Instagram等其他应用
        if (lowerPath.contains("/instagram") || lowerPath.contains("/bluetooth")) {
            return R.drawable.ic_album_android;
        }
        
        return R.drawable.ic_album_folder;
    }
}

