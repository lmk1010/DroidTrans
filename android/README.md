# Android照片传输助手

基于 Material Design 设计的 Android 照片传输客户端，支持通过 WiFi 将手机照片和视频上传到电脑。

## 功能特性

✨ **核心功能**
- 📷 扫描手机照片和视频
- 📤 WiFi 无线传输到电脑
- ✅ 多选照片批量上传
- 📊 实时上传进度显示
- 🎨 Material Design 3 设计规范

## 技术栈

- **编程语言**: Java
- **最低SDK**: Android 7.0 (API 24)
- **目标SDK**: Android 14 (API 35)
- **设计规范**: Material Design 3
- **网络框架**: Retrofit 2 + OkHttp 4
- **图片加载**: Glide
- **界面组件**: Material Components

## 使用步骤

### 1. 启动服务器

在电脑上运行 Python 服务器：

```bash
python app.py
```

服务器默认运行在 `http://[你的IP]:9500`

### 2. 安装 Android APP

1. 使用 Android Studio 打开项目
2. 连接手机或启动模拟器
3. 点击运行按钮安装 APP

### 3. 连接服务器

1. 确保手机和电脑在同一 WiFi 网络
2. 在 APP 中输入服务器地址（例如：`http://192.168.10.168:9500`）
3. 点击"连接"按钮

### 4. 扫描照片

1. 点击"扫描照片"按钮
2. 授予存储权限（首次使用）
3. 等待扫描完成

### 5. 上传照片

1. 在照片列表中选择要上传的照片
2. 可以使用"全选"/"取消全选"按钮
3. 点击右下角的上传按钮
4. 等待上传完成

## 界面预览

### Material Design 设计

- **主题色**: Deep Purple (#6200EE)
- **辅助色**: Teal (#018786)
- **卡片圆角**: 8dp
- **阴影层级**: 2dp~8dp
- **字体**: Roboto

### 主要界面

1. **服务器连接卡片**: 输入服务器地址并连接
2. **操作按钮卡片**: 扫描照片、全选/取消全选
3. **照片列表卡片**: 显示扫描到的照片，支持多选
4. **悬浮上传按钮**: 右下角的上传按钮

## API 接口

详见 [WIFI_MODE_API.md](../WIFI_MODE_API.md)

主要接口：
- `GET /api/wifi/info` - 获取服务器信息
- `POST /api/wifi/upload_photo_list` - 上传照片列表
- `POST /api/wifi/upload_photo` - 上传单个照片
- `GET /api/wifi/status` - 获取状态

## 权限说明

APP 需要以下权限：

- **网络权限**: 连接服务器上传照片
- **存储权限**: 读取手机中的照片和视频
  - Android 13+: `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`
  - Android 12及以下: `READ_EXTERNAL_STORAGE`

## 目录结构

```
app/src/main/java/com/mk/androidtransfer/
├── MainActivity.java           # 主界面Activity
├── adapter/
│   └── PhotoAdapter.java      # 照片列表适配器
├── model/
│   ├── PhotoInfo.java         # 照片信息模型
│   ├── ApiResponse.java       # API响应模型
│   └── PhotoListRequest.java  # 照片列表请求模型
├── network/
│   ├── ApiService.java        # 网络API接口
│   └── RetrofitClient.java    # Retrofit客户端
└── utils/
    └── PhotoScanner.java      # 照片扫描工具
```

## 构建说明

### 使用 Android Studio

1. 打开项目
2. 等待 Gradle 同步完成
3. 点击 Build -> Make Project
4. 运行到设备或模拟器

### 使用命令行

```bash
# 清理构建
./gradlew clean

# 构建 Debug 版本
./gradlew assembleDebug

# 安装到设备
./gradlew installDebug
```

## 常见问题

### Q: 连接服务器失败？
A:
- 确保手机和电脑在同一WiFi网络
- 检查服务器地址是否正确
- 确保服务器正在运行
- 检查防火墙设置

### Q: 扫描不到照片？
A:
- 确保已授予存储权限
- 检查手机中是否有照片
- 尝试重新启动APP

### Q: 上传速度慢？
A:
- 检查WiFi信号强度
- 建议使用5GHz频段WiFi
- 避免同时上传大量照片

### Q: 上传失败？
A:
- 检查网络连接
- 确保服务器正在运行
- 查看 Logcat 日志排查问题

## 开发者信息

- 遵循 Google Material Design 设计规范
- 使用 AndroidX 组件库
- 支持 Android 7.0 及以上版本

## 版本历史

### v1.0.0 (2025-10-09)
- ✨ 初始版本发布
- 📷 支持照片和视频扫描
- 📤 支持WiFi传输
- 🎨 Material Design 3 界面

## License

MIT License

---

Created with ❤️ following Material Design Guidelines
