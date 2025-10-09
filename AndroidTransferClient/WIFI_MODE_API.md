# WiFi模式 API 文档

## 概述

WiFi模式允许Android手机通过局域网WiFi连接直接向PC传输照片，无需USB连接和ADB调试。

## 服务器信息

- **默认端口**: 9500
- **协议**: HTTP
- **服务器地址**: http://[PC的IP地址]:9500

## API 接口列表

### 1. 获取服务器信息

**接口**: `GET /api/wifi/info`

**描述**: 获取服务器的IP地址、端口等信息

**响应示例**:
```json
{
  "success": true,
  "ip": "192.168.1.100",
  "port": 9500,
  "url": "http://192.168.1.100:9500",
  "status": {
    "enabled": true,
    "connected_devices": ["device_001"],
    "photos_received": 150,
    "last_sync_time": "2025-10-05T10:30:00"
  }
}
```

---

### 2. 上传照片列表

**接口**: `POST /api/wifi/upload_photo_list`

**描述**: 手机端扫描完照片后，先上传照片列表信息（不上传文件内容）

**请求头**:
```
Content-Type: application/json
```

**请求体**:
```json
{
  "device_id": "手机设备ID（如IMEI、Android ID等）",
  "photos": [
    {
      "path": "/storage/emulated/0/DCIM/Camera/IMG_20231001_123456.jpg",
      "name": "IMG_20231001_123456.jpg",
      "size": 2048576,
      "size_mb": 1.95,
      "mtime": 1696142096,
      "date": "2023-10-01 12:34:56"
    },
    {
      "path": "/storage/emulated/0/DCIM/Camera/IMG_20231002_234567.jpg",
      "name": "IMG_20231002_234567.jpg",
      "size": 3145728,
      "size_mb": 3.0,
      "mtime": 1696228496,
      "date": "2023-10-02 23:45:67"
    }
  ]
}
```

**字段说明**:
- `device_id`: 设备唯一标识符
- `photos`: 照片数组
  - `path`: 照片在手机上的完整路径
  - `name`: 文件名
  - `size`: 文件大小（字节）
  - `size_mb`: 文件大小（MB）
  - `mtime`: 修改时间戳（Unix时间戳）
  - `date`: 格式化的日期时间字符串

**响应示例**:
```json
{
  "success": true,
  "message": "成功接收 2 个照片信息",
  "count": 2
}
```

---

### 3. 上传单个照片文件

**接口**: `POST /api/wifi/upload_photo`

**描述**: 上传单个照片文件

**请求头**:
```
Content-Type: multipart/form-data
```

**表单数据**:
- `file`: 照片文件（必需）
- `relative_path`: 相对路径，如 `DCIM/Camera/IMG_20231001_123456.jpg`（可选）
- `output_dir`: 输出目录（可选，默认为 `./photos_output`）

**示例代码（Android/Java）**:
```java
// 使用OkHttp上传
RequestBody requestBody = new MultipartBody.Builder()
    .setType(MultipartBody.FORM)
    .addFormDataPart("file", file.getName(),
        RequestBody.create(MediaType.parse("image/*"), file))
    .addFormDataPart("relative_path", "DCIM/Camera/" + file.getName())
    .addFormDataPart("output_dir", "./photos_output")
    .build();

Request request = new Request.Builder()
    .url("http://192.168.1.100:9500/api/wifi/upload_photo")
    .post(requestBody)
    .build();
```

**响应示例**:
```json
{
  "success": true,
  "message": "上传成功",
  "path": "./photos_output/DCIM/Camera/IMG_20231001_123456.jpg"
}
```

---

### 4. 批量上传照片

**接口**: `POST /api/wifi/batch_upload`

**描述**: 一次上传多个照片文件

**请求头**:
```
Content-Type: multipart/form-data
```

**表单数据**:
- `files`: 多个文件（必需，可以多个）
- `relative_path_0`, `relative_path_1`, ...: 每个文件的相对路径（可选）
- `output_dir`: 输出目录（可选）

**示例代码（Android/Java）**:
```java
MultipartBody.Builder builder = new MultipartBody.Builder()
    .setType(MultipartBody.FORM);

// 添加多个文件
for (int i = 0; i < files.size(); i++) {
    File file = files.get(i);
    builder.addFormDataPart("files", file.getName(),
        RequestBody.create(MediaType.parse("image/*"), file));
    builder.addFormDataPart("relative_path_" + i, 
        "DCIM/Camera/" + file.getName());
}

builder.addFormDataPart("output_dir", "./photos_output");

Request request = new Request.Builder()
    .url("http://192.168.1.100:9500/api/wifi/batch_upload")
    .post(builder.build())
    .build();
```

**响应示例**:
```json
{
  "success": true,
  "uploaded": 2,
  "failed": 0,
  "uploaded_files": [
    "DCIM/Camera/IMG_20231001_123456.jpg",
    "DCIM/Camera/IMG_20231002_234567.jpg"
  ],
  "failed_files": []
}
```

---

### 5. 获取WiFi模式状态

**接口**: `GET /api/wifi/status`

**描述**: 获取WiFi模式当前状态

**响应示例**:
```json
{
  "success": true,
  "status": {
    "enabled": true,
    "connected_devices": ["device_001", "device_002"],
    "photos_received": 150,
    "last_sync_time": "2025-10-05T10:30:00"
  }
}
```

---

## Android客户端开发建议

### 工作流程

1. **启动APP** → 检测WiFi网络
2. **输入服务器地址** → 调用 `GET /api/wifi/info` 验证连接
3. **扫描手机照片** → 使用 MediaStore API 扫描照片
4. **上传照片列表** → 调用 `POST /api/wifi/upload_photo_list`
5. **用户选择要上传的照片** → 在PC端Web界面可以看到照片列表
6. **上传照片文件** → 循环调用 `POST /api/wifi/upload_photo` 或使用 `POST /api/wifi/batch_upload`
7. **显示进度** → 实时更新上传进度

### 推荐的Android技术栈

- **网络库**: OkHttp 或 Retrofit
- **图片扫描**: MediaStore API
- **权限管理**: READ_EXTERNAL_STORAGE / READ_MEDIA_IMAGES (Android 13+)
- **后台服务**: WorkManager 或 Service
- **界面**: Jetpack Compose 或 XML布局

### 示例代码片段（照片扫描）

```java
// 扫描手机照片（Android 10+）
public List<PhotoInfo> scanPhotos(Context context) {
    List<PhotoInfo> photos = new ArrayList<>();
    
    Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    String[] projection = {
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.DATA
    };
    
    try (Cursor cursor = context.getContentResolver().query(
            collection,
            projection,
            null,
            null,
            MediaStore.Images.Media.DATE_MODIFIED + " DESC"
    )) {
        int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
        int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
        int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
        int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED);
        int dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
        
        while (cursor.moveToNext()) {
            long id = cursor.getLong(idColumn);
            String name = cursor.getString(nameColumn);
            long size = cursor.getLong(sizeColumn);
            long dateModified = cursor.getLong(dateColumn);
            String path = cursor.getString(dataColumn);
            
            PhotoInfo photo = new PhotoInfo();
            photo.path = path;
            photo.name = name;
            photo.size = size;
            photo.sizeMb = size / 1024.0 / 1024.0;
            photo.mtime = dateModified;
            photo.date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date(dateModified * 1000));
            
            photos.add(photo);
        }
    }
    
    return photos;
}
```

---

## 测试方法

### 使用curl测试

```bash
# 1. 获取服务器信息
curl http://192.168.1.100:9500/api/wifi/info

# 2. 上传照片列表
curl -X POST http://192.168.1.100:9500/api/wifi/upload_photo_list \
  -H "Content-Type: application/json" \
  -d '{
    "device_id": "test_device",
    "photos": [
      {
        "path": "/test/photo1.jpg",
        "name": "photo1.jpg",
        "size": 1048576,
        "size_mb": 1.0,
        "mtime": 1696142096,
        "date": "2023-10-01 12:34:56"
      }
    ]
  }'

# 3. 上传单个照片
curl -X POST http://192.168.1.100:9500/api/wifi/upload_photo \
  -F "file=@/path/to/photo.jpg" \
  -F "relative_path=DCIM/Camera/photo.jpg"
```

---

## 安全建议

1. **仅在可信的局域网中使用**
2. **不要在公共WiFi中使用**
3. **可以考虑添加简单的密码验证**
4. **定期清理上传的照片**

---

## 常见问题

**Q: 手机找不到服务器？**
A: 确保手机和电脑在同一WiFi网络，检查防火墙设置。

**Q: 上传速度慢？**
A: 检查WiFi信号强度，建议使用5GHz频段的WiFi。

**Q: 上传失败？**
A: 检查手机存储权限，查看服务器日志。

**Q: 支持视频上传吗？**
A: 是的，API支持任何文件类型的上传。

---

## 联系方式

如有问题，请查看项目README或提交Issue。

