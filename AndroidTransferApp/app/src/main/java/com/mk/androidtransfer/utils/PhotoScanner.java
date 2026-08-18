package com.mk.androidtransfer.utils;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import com.mk.androidtransfer.model.AlbumInfo;
import com.mk.androidtransfer.model.PhotoInfo;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 统一扫描 MediaStore 中的图片和视频，并按系统相册（BUCKET）分组。
 */
public class PhotoScanner {
    private static final String TAG = "PhotoScanner";

    public static class ScanResult {
        public final List<PhotoInfo> photos;
        public final List<AlbumInfo> albums;

        public ScanResult(List<PhotoInfo> photos, List<AlbumInfo> albums) {
            this.photos = photos;
            this.albums = albums;
        }
    }

    public static ScanResult scan(Context context) {
        List<PhotoInfo> photos = new ArrayList<>();
        photos.addAll(queryCollection(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false));
        photos.addAll(queryCollection(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true));

        Collections.sort(photos, (a, b) -> Long.compare(b.getMtime(), a.getMtime()));

        Map<String, AlbumInfo> albumMap = new LinkedHashMap<>();
        for (PhotoInfo photo : photos) {
            String hintPath = albumHintPath(photo);
            String albumKey = !TextUtils.isEmpty(photo.getBucketId())
                    ? photo.getBucketId()
                    : hintPath;
            AlbumInfo album = albumMap.get(albumKey);
            if (album == null) {
                String displayName = AlbumInfo.getAlbumDisplayName(hintPath);
                if ("其他".equals(displayName) && !TextUtils.isEmpty(photo.getBucketName())) {
                    displayName = photo.getBucketName();
                }
                album = new AlbumInfo(displayName, albumKey, hintPath);
                albumMap.put(albumKey, album);
            }
            album.addPhoto(photo);
            if (album.getCoverPhotoPath() == null || photo.getUri() != null) {
                if (album.getCoverPhotoPath() == null) {
                    album.setCoverPhotoPath(photo.getLoadUri());
                }
            }
        }

        // 封面优先用 content URI，避免 DATA 为空时 Glide 加载失败
        for (AlbumInfo album : albumMap.values()) {
            if (!album.getPhotos().isEmpty()) {
                album.setCoverPhotoPath(album.getPhotos().get(0).getLoadUri());
            }
        }

        List<AlbumInfo> albums = new ArrayList<>(albumMap.values());
        Collections.sort(albums, (a, b) -> {
            int p = Integer.compare(albumPriority(a), albumPriority(b));
            if (p != 0) return p;
            return Integer.compare(b.getPhotoCount(), a.getPhotoCount());
        });

        Log.d(TAG, "扫描完成: " + photos.size() + " 个媒体, " + albums.size() + " 个相册");
        return new ScanResult(photos, albums);
    }

    public static List<PhotoInfo> scanPhotos(Context context) {
        return scan(context).photos;
    }

    private static List<PhotoInfo> queryCollection(Context context, Uri collection, boolean video) {
        List<PhotoInfo> photos = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();

        ArrayList<String> projection = new ArrayList<>();
        projection.add(MediaStore.MediaColumns._ID);
        projection.add(MediaStore.MediaColumns.DISPLAY_NAME);
        projection.add(MediaStore.MediaColumns.SIZE);
        projection.add(MediaStore.MediaColumns.DATE_MODIFIED);
        projection.add(MediaStore.MediaColumns.MIME_TYPE);
        projection.add(MediaStore.Images.Media.BUCKET_ID);
        projection.add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
        projection.add(MediaStore.MediaColumns.DATA);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.MediaColumns.RELATIVE_PATH);
        }
        if (video) {
            projection.add(MediaStore.Video.Media.DURATION);
        }

        String sortOrder = MediaStore.MediaColumns.DATE_MODIFIED + " DESC";

        try (Cursor cursor = resolver.query(
                collection,
                projection.toArray(new String[0]),
                null,
                null,
                sortOrder
        )) {
            if (cursor == null) return photos;

            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            int mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);
            int relCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
            int bucketIdCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID);
            int bucketNameCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME);
            int dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
            int durationCol = video ? cursor.getColumnIndex(MediaStore.Video.Media.DURATION) : -1;

            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                String name = cursor.getString(nameCol);
                long size = cursor.getLong(sizeCol);
                if (size <= 0) continue;

                long dateModified = cursor.getLong(dateCol);
                String mime = mimeCol >= 0 ? cursor.getString(mimeCol) : null;
                String relativePath = relCol >= 0 ? cursor.getString(relCol) : null;
                String bucketId = bucketIdCol >= 0 ? cursor.getString(bucketIdCol) : null;
                String bucketName = bucketNameCol >= 0 ? cursor.getString(bucketNameCol) : null;
                String dataPath = dataCol >= 0 ? cursor.getString(dataCol) : null;
                long duration = durationCol >= 0 ? cursor.getLong(durationCol) : 0;

                if (TextUtils.isEmpty(name)) {
                    name = video ? ("VID_" + id) : ("IMG_" + id);
                }

                Uri contentUri = ContentUris.withAppendedId(collection, id);
                String date = dateFormat.format(new Date(dateModified * 1000L));

                photos.add(new PhotoInfo(
                        dataPath,
                        name,
                        size,
                        dateModified,
                        date,
                        contentUri.toString(),
                        mime,
                        video || (mime != null && mime.startsWith("video/")),
                        relativePath,
                        bucketId,
                        bucketName,
                        duration
                ));
            }
        } catch (SecurityException e) {
            Log.w(TAG, "没有媒体权限，跳过 " + collection, e);
        } catch (Exception e) {
            Log.e(TAG, "扫描失败: " + collection, e);
        }

        return photos;
    }

    private static String albumHintPath(PhotoInfo photo) {
        if (!TextUtils.isEmpty(photo.getRelativePath())) {
            String rel = photo.getRelativePath();
            return rel.startsWith("/") ? rel : "/" + rel;
        }
        if (!TextUtils.isEmpty(photo.getPath())) {
            File parent = new File(photo.getPath()).getParentFile();
            if (parent != null) return parent.getAbsolutePath();
        }
        if (!TextUtils.isEmpty(photo.getBucketName())) {
            return "/" + photo.getBucketName();
        }
        return "其他";
    }

    private static int albumPriority(AlbumInfo album) {
        String name = album.getAlbumName();
        if ("相机".equals(name)) return 0;
        if ("截图".equals(name)) return 1;
        if ("微信".equals(name)) return 2;
        if ("QQ".equals(name)) return 3;
        if ("录屏".equals(name)) return 4;
        if ("下载".equals(name)) return 5;
        if ("图片".equals(name)) return 6;
        return 10;
    }
}
