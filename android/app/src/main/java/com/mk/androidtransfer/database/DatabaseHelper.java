package com.mk.androidtransfer.database;

import android.content.Context;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 数据库助手类
 */
public class DatabaseHelper extends SQLiteOpenHelper {
    
    private static final String DATABASE_NAME = "android_transfer.db";
    private static final int DATABASE_VERSION = 3;
    
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
    public static final String COLUMN_UPLOADED_SERVER_URL = "server_url";
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
        COLUMN_UPLOADED_SERVER_URL + " TEXT NOT NULL DEFAULT '', " +
        COLUMN_FILE_PATH + " TEXT NOT NULL, " +
        COLUMN_FILE_NAME + " TEXT, " +
        COLUMN_FILE_SIZE + " INTEGER, " +
        COLUMN_UPLOAD_TIME + " INTEGER" +
        ")";

    private static final String UPLOADED_FILES_SERVER_PATH_INDEX =
        "idx_uploaded_files_server_path";
    
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
        createUploadedFilesIndex(db);
    }
    
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE_UPLOAD_RECORDS + " ADD COLUMN " + COLUMN_DURATION_SEC + " INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_UPLOAD_RECORDS + " ADD COLUMN " + COLUMN_TOTAL_BYTES + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 3) {
            migrateUploadedFiles(db);
        }
    }

    private void createUploadedFilesIndex(SQLiteDatabase db) {
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS " + UPLOADED_FILES_SERVER_PATH_INDEX
                + " ON " + TABLE_UPLOADED_FILES + " ("
                + COLUMN_UPLOADED_SERVER_URL + ", " + COLUMN_FILE_PATH + ")");
    }

    private void migrateUploadedFiles(SQLiteDatabase db) {
        final String legacyTable = TABLE_UPLOADED_FILES + "_legacy";
        db.execSQL("ALTER TABLE " + TABLE_UPLOADED_FILES + " RENAME TO " + legacyTable);
        db.execSQL(CREATE_UPLOADED_FILES_TABLE);

        Map<String, Set<String>> serversByPath = new HashMap<>();
        try (android.database.Cursor records = db.query(
                TABLE_UPLOAD_RECORDS,
                new String[]{COLUMN_SERVER_URL, COLUMN_FILE_LIST},
                null,
                null,
                null,
                null,
                null
        )) {
            while (records.moveToNext()) {
                String serverUrl = normalizeServerUrl(records.getString(0));
                if (!serverUrl.isEmpty()) {
                    for (String path : UploadRecordFiles.successfulPaths(records.getString(1))) {
                        serversByPath.computeIfAbsent(path, ignored -> new HashSet<>()).add(serverUrl);
                    }
                }
            }
        }

        try (android.database.Cursor files = db.query(
                legacyTable,
                new String[]{COLUMN_FILE_PATH, COLUMN_FILE_NAME, COLUMN_FILE_SIZE, COLUMN_UPLOAD_TIME},
                null,
                null,
                null,
                null,
                null
        )) {
            while (files.moveToNext()) {
                String path = files.getString(0);
                if (path == null || path.isEmpty()) {
                    continue;
                }
                Set<String> servers = serversByPath.get(path);
                if (servers == null || servers.isEmpty()) {
                    insertMigratedFile(db, "", path, files.getString(1), files.getLong(2), files.getLong(3));
                } else {
                    for (String serverUrl : servers) {
                        insertMigratedFile(db, serverUrl, path, files.getString(1), files.getLong(2), files.getLong(3));
                    }
                }
            }
        }

        db.execSQL("DROP TABLE " + legacyTable);
        createUploadedFilesIndex(db);
    }

    private void insertMigratedFile(
            SQLiteDatabase db,
            String serverUrl,
            String path,
            String name,
            long size,
            long uploadTime
    ) {
        ContentValues values = new ContentValues();
        values.put(COLUMN_UPLOADED_SERVER_URL, serverUrl);
        values.put(COLUMN_FILE_PATH, path);
        values.put(COLUMN_FILE_NAME, name);
        values.put(COLUMN_FILE_SIZE, size);
        values.put(COLUMN_UPLOAD_TIME, uploadTime);
        db.insertWithOnConflict(
                TABLE_UPLOADED_FILES,
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
        );
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
