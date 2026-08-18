package com.mk.androidtransfer.model;

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

    /**
     * 根据路径智能识别相册名称（简约版，不带emoji）
     */
    public static String getAlbumDisplayName(String path) {
        if (path == null) return "其他";
        
        String lowerPath = path.toLowerCase();
        
        // 相机
        if (lowerPath.contains("/dcim/camera") || lowerPath.contains("/camera")) {
            return "相机";
        }
        // 截图
        if (lowerPath.contains("/screenshots") || lowerPath.contains("screenshot")) {
            return "截图";
        }
        // 微信
        if (lowerPath.contains("/wechat") || lowerPath.contains("/weixin")
                || lowerPath.contains("/micromsg") || lowerPath.contains("/微信")) {
            return "微信";
        }
        // QQ
        if (lowerPath.contains("/qq") || lowerPath.contains("/tencent/qq")
                || lowerPath.contains("/tencent/qqi")) {
            return "QQ";
        }
        // 下载
        if (lowerPath.contains("/download")) {
            return "下载";
        }
        // 蓝牙
        if (lowerPath.contains("/bluetooth")) {
            return "蓝牙";
        }
        // Instagram
        if (lowerPath.contains("/instagram")) {
            return "Instagram";
        }
        // 录屏
        if (lowerPath.contains("/screenrecord") || lowerPath.contains("/screen_record")) {
            return "录屏";
        }
        // Pictures文件夹
        if (lowerPath.contains("/pictures")) {
            return "图片";
        }
        
        // 尝试提取文件夹名称
        String[] parts = path.split("/");
        if (parts.length > 0) {
            String folderName = parts[parts.length - 1];
            if (!folderName.isEmpty()) {
                return folderName;
            }
        }
        
        return "其他";
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

