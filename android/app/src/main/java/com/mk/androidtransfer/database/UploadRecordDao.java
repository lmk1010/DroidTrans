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
    private DatabaseHelper dbHelper;
    
    public UploadRecordDao(Context context) {
        this.dbHelper = DatabaseHelper.getInstance(context);
    }
    
    /**
     * 插入上传记录
     */
    public long insertUploadRecord(UploadRecord record) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COLUMN_SERVER_URL, record.getServerUrl());
        values.put(DatabaseHelper.COLUMN_SERVER_NAME, record.getServerName());
        values.put(DatabaseHelper.COLUMN_TOTAL_COUNT, record.getTotalCount());
        values.put(DatabaseHelper.COLUMN_SUCCESS_COUNT, record.getSuccessCount());
        values.put(DatabaseHelper.COLUMN_FAILED_COUNT, record.getFailedCount());
        values.put(DatabaseHelper.COLUMN_UPLOAD_TIME, record.getUploadTime());
        values.put(DatabaseHelper.COLUMN_FILE_LIST, record.getFileList());
        
        long id = db.insert(DatabaseHelper.TABLE_UPLOAD_RECORDS, null, values);
        Log.d(TAG, "插入上传记录，ID: " + id);
        
        // 同时插入到已上传文件表
        try {
            JSONArray fileArray = new JSONArray(record.getFileList());
            for (int i = 0; i < fileArray.length(); i++) {
                JSONObject file = fileArray.getJSONObject(i);
                if (file.optBoolean("success", false)) {
                    insertUploadedFile(
                        file.getString("path"),
                        file.getString("name"),
                        file.getLong("size"),
                        record.getUploadTime()
                    );
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "解析文件列表失败", e);
        }
        
        return id;
    }
    
    /**
     * 插入已上传文件
     */
    private void insertUploadedFile(String path, String name, long size, long uploadTime) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COLUMN_FILE_PATH, path);
        values.put(DatabaseHelper.COLUMN_FILE_NAME, name);
        values.put(DatabaseHelper.COLUMN_FILE_SIZE, size);
        values.put(DatabaseHelper.COLUMN_UPLOAD_TIME, uploadTime);
        
        // 使用 INSERT OR REPLACE 避免重复
        db.insertWithOnConflict(DatabaseHelper.TABLE_UPLOADED_FILES, null, values, 
            SQLiteDatabase.CONFLICT_REPLACE);
    }
    
    /**
     * 获取所有上传记录
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
                
                records.add(record);
            }
            cursor.close();
        }
        
        return records;
    }
    
    /**
     * 获取已上传的文件路径集合
     */
    public Set<String> getUploadedFilePaths() {
        Set<String> paths = new HashSet<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        
        Cursor cursor = db.query(
            DatabaseHelper.TABLE_UPLOADED_FILES,
            new String[]{DatabaseHelper.COLUMN_FILE_PATH},
            null,
            null,
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
        
        Log.d(TAG, "已上传文件数量: " + paths.size());
        return paths;
    }
    
    /**
     * 检查文件是否已上传
     */
    public boolean isFileUploaded(String filePath) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        
        Cursor cursor = db.query(
            DatabaseHelper.TABLE_UPLOADED_FILES,
            new String[]{DatabaseHelper.COLUMN_ID},
            DatabaseHelper.COLUMN_FILE_PATH + " = ?",
            new String[]{filePath},
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
     * 删除上传记录
     */
    public int deleteUploadRecord(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return db.delete(DatabaseHelper.TABLE_UPLOAD_RECORDS, 
            DatabaseHelper.COLUMN_ID + " = ?", 
            new String[]{String.valueOf(id)});
    }
    
    /**
     * 清空所有上传记录
     */
    public void clearAllRecords() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(DatabaseHelper.TABLE_UPLOAD_RECORDS, null, null);
        db.delete(DatabaseHelper.TABLE_UPLOADED_FILES, null, null);
        Log.d(TAG, "已清空所有上传记录");
    }
    
    /**
     * 获取上传记录统计
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
}

