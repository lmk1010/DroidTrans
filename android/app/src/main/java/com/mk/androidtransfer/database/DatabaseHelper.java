package com.mk.androidtransfer.database;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * 数据库助手类
 */
public class DatabaseHelper extends SQLiteOpenHelper {
    
    private static final String DATABASE_NAME = "android_transfer.db";
    private static final int DATABASE_VERSION = 2;
    
    // 上传记录表
    public static final String TABLE_UPLOAD_RECORDS = "upload_records";
    public static final String COLUMN_ID = "id";
    public static final String COLUMN_SERVER_URL = "server_url";
    public static final String COLUMN_SERVER_NAME = "server_name";
    public static final String COLUMN_TOTAL_COUNT = "total_count";
    public static final String COLUMN_SUCCESS_COUNT = "success_count";
    public static final String COLUMN_FAILED_COUNT = "failed_count";
    public static final String COLUMN_UPLOAD_TIME = "upload_time";
    public static final String COLUMN_FILE_LIST = "file_list";
    public static final String COLUMN_DURATION_SEC = "duration_sec";
    public static final String COLUMN_TOTAL_BYTES = "total_bytes";
    
    // 已上传文件表（用于快速查询）
    public static final String TABLE_UPLOADED_FILES = "uploaded_files";
    public static final String COLUMN_FILE_PATH = "file_path";
    public static final String COLUMN_FILE_NAME = "file_name";
    public static final String COLUMN_FILE_SIZE = "file_size";
    
    private static final String CREATE_UPLOAD_RECORDS_TABLE = 
        "CREATE TABLE " + TABLE_UPLOAD_RECORDS + " (" +
        COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
        COLUMN_SERVER_URL + " TEXT, " +
        COLUMN_SERVER_NAME + " TEXT, " +
        COLUMN_TOTAL_COUNT + " INTEGER, " +
        COLUMN_SUCCESS_COUNT + " INTEGER, " +
        COLUMN_FAILED_COUNT + " INTEGER, " +
        COLUMN_UPLOAD_TIME + " INTEGER, " +
        COLUMN_FILE_LIST + " TEXT, " +
        COLUMN_DURATION_SEC + " INTEGER DEFAULT 0, " +
        COLUMN_TOTAL_BYTES + " INTEGER DEFAULT 0" +
        ")";
    
    private static final String CREATE_UPLOADED_FILES_TABLE = 
        "CREATE TABLE " + TABLE_UPLOADED_FILES + " (" +
        COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
        COLUMN_FILE_PATH + " TEXT UNIQUE, " +
        COLUMN_FILE_NAME + " TEXT, " +
        COLUMN_FILE_SIZE + " INTEGER, " +
        COLUMN_UPLOAD_TIME + " INTEGER" +
        ")";
    
    private static DatabaseHelper instance;
    
    public static synchronized DatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new DatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }
    
    private DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }
    
    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_UPLOAD_RECORDS_TABLE);
        db.execSQL(CREATE_UPLOADED_FILES_TABLE);
    }
    
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE_UPLOAD_RECORDS + " ADD COLUMN " + COLUMN_DURATION_SEC + " INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_UPLOAD_RECORDS + " ADD COLUMN " + COLUMN_TOTAL_BYTES + " INTEGER DEFAULT 0");
        }
    }
}

