package com.mk.androidtransfer.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import com.mk.androidtransfer.model.PhotoInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 照片扫描工具类
 */
public class PhotoScanner {

    /**
     * 扫描手机中的所有照片和视频
     */
    public static List<PhotoInfo> scanPhotos(Context context) {
        List<PhotoInfo> photos = new ArrayList<>();

        // 扫描图片
        photos.addAll(scanImages(context));

        // 扫描视频
        photos.addAll(scanVideos(context));

        return photos;
    }

    /**
     * 扫描图片
     */
    private static List<PhotoInfo> scanImages(Context context) {
        List<PhotoInfo> photos = new ArrayList<>();
        ContentResolver contentResolver = context.getContentResolver();

        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATA
        };

        String sortOrder = MediaStore.Images.Media.DATE_MODIFIED + " DESC";

        try (Cursor cursor = contentResolver.query(
                collection,
                projection,
                null,
                null,
                sortOrder
        )) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
                int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED);
                int dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);

                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    String name = cursor.getString(nameColumn);
                    long size = cursor.getLong(sizeColumn);
                    long dateModified = cursor.getLong(dateColumn);
                    String path = cursor.getString(dataColumn);

                    // 构建Content URI
                    Uri contentUri = Uri.withAppendedPath(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            String.valueOf(id)
                    );

                    String date = dateFormat.format(new Date(dateModified * 1000));

                    PhotoInfo photo = new PhotoInfo(
                            path,
                            name,
                            size,
                            dateModified,
                            date,
                            contentUri.toString()
                    );

                    photos.add(photo);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return photos;
    }

    /**
     * 扫描视频
     */
    private static List<PhotoInfo> scanVideos(Context context) {
        List<PhotoInfo> videos = new ArrayList<>();
        ContentResolver contentResolver = context.getContentResolver();

        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATA
        };

        String sortOrder = MediaStore.Video.Media.DATE_MODIFIED + " DESC";

        try (Cursor cursor = contentResolver.query(
                collection,
                projection,
                null,
                null,
                sortOrder
        )) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
                int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED);
                int dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA);

                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    String name = cursor.getString(nameColumn);
                    long size = cursor.getLong(sizeColumn);
                    long dateModified = cursor.getLong(dateColumn);
                    String path = cursor.getString(dataColumn);

                    // 构建Content URI
                    Uri contentUri = Uri.withAppendedPath(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            String.valueOf(id)
                    );

                    String date = dateFormat.format(new Date(dateModified * 1000));

                    PhotoInfo video = new PhotoInfo(
                            path,
                            name,
                            size,
                            dateModified,
                            date,
                            contentUri.toString()
                    );

                    videos.add(video);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return videos;
    }
}
