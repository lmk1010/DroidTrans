package com.mk.androidtransfer.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.mk.androidtransfer.model.UploadRecord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 上传记录数据访问对象
 */
public class UploadRecordDao {

    private static final String TAG = "UploadRecordDao";
    private final DatabaseHelper dbHelper;

    public UploadRecordDao(Context context) {
        this.dbHelper = DatabaseHelper.getInstance(context);
    }

    /**
     * 插入上传记录和成功文件索引。
     */
    public long insertUploadRecord(UploadRecord record) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String serverUrl = normalizeServerUrl(record.getServerUrl());

        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put(DatabaseHelper.COLUMN_SERVER_URL, serverUrl);
            values.put(DatabaseHelper.COLUMN_SERVER_NAME, record.getServerName());
            values.put(DatabaseHelper.COLUMN_TOTAL_COUNT, record.getTotalCount());
            values.put(DatabaseHelper.COLUMN_SUCCESS_COUNT, record.getSuccessCount());
            values.put(DatabaseHelper.COLUMN_FAILED_COUNT, record.getFailedCount());
            values.put(DatabaseHelper.COLUMN_UPLOAD_TIME, record.getUploadTime());
            values.put(DatabaseHelper.COLUMN_FILE_LIST, record.getFileList());
            values.put(DatabaseHelper.COLUMN_DURATION_SEC, record.getDurationSec());
            values.put(DatabaseHelper.COLUMN_TOTAL_BYTES, record.getTotalBytes());

            long id = db.insert(DatabaseHelper.TABLE_UPLOAD_RECORDS, null, values);
            Log.d(TAG, "插入上传记录，ID: " + id);
            if (id == -1) {
                return -1;
            }

            for (String path : UploadRecordFiles.successfulPaths(record.getFileList())) {
                insertUploadedFile(
                        serverUrl,
                        path,
                        findFileName(record.getFileList(), path),
                        findFileSize(record.getFileList(), path),
                        record.getUploadTime()
                );
            }
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    private void insertUploadedFile(
            String serverUrl,
            String path,
            String name,
            long size,
            long uploadTime
    ) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COLUMN_UPLOADED_SERVER_URL, serverUrl);
        values.put(DatabaseHelper.COLUMN_FILE_PATH, path);
        values.put(DatabaseHelper.COLUMN_FILE_NAME, name);
        values.put(DatabaseHelper.COLUMN_FILE_SIZE, size);
        values.put(DatabaseHelper.COLUMN_UPLOAD_TIME, uploadTime);

        // 同一服务器上的同一路径只保留一条去重记录。
        db.insertWithOnConflict(
                DatabaseHelper.TABLE_UPLOADED_FILES,
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
        );
    }

    /**
     * 获取所有上传记录。
     */
    public List<UploadRecord> getAllUploadRecords() {
        List<UploadRecord> records = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        Cursor cursor = db.query(
                DatabaseHelper.TABLE_UPLOAD_RECORDS,
                null,
                null,
                null,
                null,
                null,
                DatabaseHelper.COLUMN_UPLOAD_TIME + " DESC"
        );

        if (cursor != null) {
            while (cursor.moveToNext()) {
                UploadRecord record = new UploadRecord();
                record.setId(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_ID)));
                record.setServerUrl(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_SERVER_URL)));
                record.setServerName(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_SERVER_NAME)));
                record.setTotalCount(cursor.getInt(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_TOTAL_COUNT)));
                record.setSuccessCount(cursor.getInt(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_SUCCESS_COUNT)));
                record.setFailedCount(cursor.getInt(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_FAILED_COUNT)));
                record.setUploadTime(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_UPLOAD_TIME)));
                record.setFileList(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_FILE_LIST)));
                int durCol = cursor.getColumnIndex(DatabaseHelper.COLUMN_DURATION_SEC);
                int bytesCol = cursor.getColumnIndex(DatabaseHelper.COLUMN_TOTAL_BYTES);
                if (durCol >= 0) {
                    record.setDurationSec(cursor.getLong(durCol));
                }
                if (bytesCol >= 0) {
                    record.setTotalBytes(cursor.getLong(bytesCol));
                }
                if (record.getTotalBytes() <= 0) {
                    record.setTotalBytes(sumFileListBytes(record.getFileList()));
                }

                records.add(record);
            }
            cursor.close();
        }

        return records;
    }

    /**
     * 获取指定目标服务器已上传的文件路径集合。
     */
    public Set<String> getUploadedFilePaths(String serverUrl) {
        Set<String> paths = new HashSet<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String normalizedServerUrl = normalizeServerUrl(serverUrl);

        Cursor cursor = db.query(
                DatabaseHelper.TABLE_UPLOADED_FILES,
                new String[]{DatabaseHelper.COLUMN_FILE_PATH},
                DatabaseHelper.COLUMN_UPLOADED_SERVER_URL + " IN (?, ?)",
                new String[]{normalizedServerUrl, ""},
                null,
                null,
                null
        );

        if (cursor != null) {
            while (cursor.moveToNext()) {
                paths.add(cursor.getString(0));
            }
            cursor.close();
        }

        Log.d(TAG, "目标服务器已上传文件数量: " + paths.size());
        return paths;
    }

    /**
     * 保留给旧调用方的兼容方法。
     */
    public Set<String> getUploadedFilePaths() {
        return getUploadedFilePaths("");
    }

    /**
     * 检查指定目标服务器上的文件是否已上传。
     */
    public boolean isFileUploaded(String serverUrl, String filePath) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String normalizedServerUrl = normalizeServerUrl(serverUrl);

        Cursor cursor = db.query(
                DatabaseHelper.TABLE_UPLOADED_FILES,
                new String[]{DatabaseHelper.COLUMN_ID},
                DatabaseHelper.COLUMN_FILE_PATH + " = ? AND "
                        + DatabaseHelper.COLUMN_UPLOADED_SERVER_URL + " IN (?, ?)",
                new String[]{filePath, normalizedServerUrl, ""},
                null,
                null,
                null
        );

        boolean exists = cursor != null && cursor.getCount() > 0;
        if (cursor != null) {
            cursor.close();
        }
        return exists;
    }

    /**
     * 保留给旧调用方的兼容方法。
     */
    public boolean isFileUploaded(String filePath) {
        return isFileUploaded("", filePath);
    }

    /**
     * 删除上传记录，并清理不再被同一服务器其他记录引用的文件索引。
     */
    public int deleteUploadRecord(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            String serverUrl = "";
            try (Cursor cursor = db.query(
                    DatabaseHelper.TABLE_UPLOAD_RECORDS,
                    new String[]{DatabaseHelper.COLUMN_SERVER_URL},
                    DatabaseHelper.COLUMN_ID + " = ?",
                    new String[]{String.valueOf(id)},
                    null,
                    null,
                    null
            )) {
                if (cursor.moveToFirst()) {
                    serverUrl = normalizeServerUrl(cursor.getString(0));
                }
            }

            int deleted = db.delete(
                    DatabaseHelper.TABLE_UPLOAD_RECORDS,
                    DatabaseHelper.COLUMN_ID + " = ?",
                    new String[]{String.valueOf(id)}
            );
            if (deleted > 0) {
                pruneUploadedFiles(serverUrl);
            }
            db.setTransactionSuccessful();
            return deleted;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 清空所有上传记录和文件索引。
     */
    public void clearAllRecords() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(DatabaseHelper.TABLE_UPLOAD_RECORDS, null, null);
            db.delete(DatabaseHelper.TABLE_UPLOADED_FILES, null, null);
            db.setTransactionSuccessful();
            Log.d(TAG, "已清空所有上传记录");
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 获取所有目标服务器的去重文件数量。
     */
    public int getTotalUploadedCount() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM " + DatabaseHelper.TABLE_UPLOADED_FILES,
                null
        );

        int count = 0;
        if (cursor != null && cursor.moveToFirst()) {
            count = cursor.getInt(0);
            cursor.close();
        }
        return count;
    }

    private void pruneUploadedFiles(String serverUrl) {
        String normalizedServerUrl = normalizeServerUrl(serverUrl);
        Set<String> referencedPaths = new HashSet<>();

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        try (Cursor cursor = db.query(
                DatabaseHelper.TABLE_UPLOAD_RECORDS,
                new String[]{DatabaseHelper.COLUMN_FILE_LIST},
                DatabaseHelper.COLUMN_SERVER_URL + " = ?",
                new String[]{normalizedServerUrl},
                null,
                null,
                null
        )) {
            while (cursor.moveToNext()) {
                referencedPaths.addAll(UploadRecordFiles.successfulPaths(cursor.getString(0)));
            }
        }

        List<Long> staleIds = new ArrayList<>();
        try (Cursor cursor = db.query(
                DatabaseHelper.TABLE_UPLOADED_FILES,
                new String[]{DatabaseHelper.COLUMN_ID, DatabaseHelper.COLUMN_FILE_PATH},
                DatabaseHelper.COLUMN_UPLOADED_SERVER_URL + " = ?",
                new String[]{normalizedServerUrl},
                null,
                null,
                null
        )) {
            while (cursor.moveToNext()) {
                if (!referencedPaths.contains(cursor.getString(1))) {
                    staleIds.add(cursor.getLong(0));
                }
            }
        }
        for (Long staleId : staleIds) {
            db.delete(
                    DatabaseHelper.TABLE_UPLOADED_FILES,
                    DatabaseHelper.COLUMN_ID + " = ?",
                    new String[]{String.valueOf(staleId)}
            );
        }
    }

    private long sumFileListBytes(String json) {
        if (json == null || json.isEmpty()) {
            return 0;
        }
        try {
            JSONArray fileArray = new JSONArray(json);
            long total = 0;
            for (int i = 0; i < fileArray.length(); i++) {
                JSONObject file = fileArray.getJSONObject(i);
                if (file.optBoolean("success", true)) {
                    total += file.optLong("size", 0);
                }
            }
            return total;
        } catch (Exception e) {
            return 0;
        }
    }

    private String findFileName(String json, String path) {
        return findFileField(json, path, "name", "");
    }

    private long findFileSize(String json, String path) {
        try {
            return Long.parseLong(findFileField(json, path, "size", "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String findFileField(String json, String path, String field, String fallback) {
        try {
            JSONArray fileArray = new JSONArray(json);
            for (int i = 0; i < fileArray.length(); i++) {
                JSONObject file = fileArray.getJSONObject(i);
                if (path.equals(file.optString("path"))) {
                    return file.optString(field, fallback);
                }
            }
        } catch (Exception ignored) {
            // 字段缺失时使用默认值。
        }
        return fallback;
    }

    private String normalizeServerUrl(String serverUrl) {
        if (serverUrl == null) {
            return "";
        }
        String normalized = serverUrl.trim();
        while (normalized.endsWith("/") && !normalized.isEmpty()) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
