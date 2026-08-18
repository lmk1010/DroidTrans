# DroidTrans Web — 浏览器接收端

Flask + HTML。用浏览器打开 `http://127.0.0.1:9500`。

这不是桌面 App。桌面壳在 `../desktop/`（Electron）。

```bash
./start.sh
```


## 📱 简介

Android Transfer 是一款强大的 Android 照片传输工具，支持 USB 和 WiFi 两种传输模式。使用 Electron + Flask 构建，提供现代化的桌面应用体验。

## ✨ 功能特点

- 🔌 **USB 模式**: 通过 ADB 快速传输，支持 M4 Mac 16线程超高并发
- 📡 **WiFi 模式**: 无线传输，支持批次管理和进度跟踪
- 🎯 **智能扫描**: 自动识别照片和视频，支持多种格式
- ⚡ **断点续传**: 支持中断后继续传输
- 📊 **批次管理**: 按时间批次组织照片，方便管理
- 🗄️ **SQLite数据库**: 高性能数据存储，支持并发访问
- 🎨 **现代化界面**: Material Design 风格，操作简单直观

## 🚀 快速开始

### 方式一：使用打包好的应用（推荐）

1. **下载安装包**：
   - macOS: `Android Transfer-1.0.0-arm64.dmg` (M系列芯片) 或 `Android Transfer-1.0.0-x64.dmg` (Intel芯片)
   - Windows: `Android Transfer-1.0.0-x64.exe`
   - Linux: `Android Transfer-1.0.0.AppImage`

2. **安装并运行**：
   - macOS: 打开 `.dmg`，拖动到 Applications 文件夹
   - Windows: 双击 `.exe` 安装程序
   - Linux: 添加可执行权限后运行 `.AppImage`

3. **连接设备**，开始传输

### 方式二：从源码运行（开发者）

```bash
# 1. 安装 Python 依赖
python3 -m venv venv
source venv/bin/activate  # Windows: venv\Scripts\activate
pip install -r requirements.txt

# 2. 安装 Node.js 依赖
npm install

# 3. 启动应用
npm start
```

或直接运行 Flask 后端（开发模式）：
```bash
python3 app.py
# 访问 http://127.0.0.1:9500
```

## 📦 打包应用

详细打包指南请查看 **[BUILD_GUIDE.md](BUILD_GUIDE.md)**

### 一键打包（推荐）

**macOS/Linux:**
```bash
./build_all.sh
```

**Windows:**
```cmd
build_all.bat
```

这将自动完成：
1. ✅ 安装 Python 依赖
2. ✅ 打包 Python 后端
3. ✅ 安装 Node.js 依赖
4. ✅ 打包 Electron 应用

打包完成后，安装包位于 `dist/` 目录。

### 分步打包（高级）

```bash
# 步骤 1: 打包 Python 后端
./build_backend.sh  # Windows: build_backend.bat

# 步骤 2: 打包 Electron 应用
npm run dist:mac    # macOS
npm run dist:win    # Windows
npm run dist:linux  # Linux
```

## 📁 项目结构

```
web/
├── app.py                    # Flask后端主程序
├── electron_main.js          # Electron主进程
├── package.json              # Node.js配置和打包脚本
├── requirements.txt          # Python依赖
├── app.spec                  # PyInstaller配置
├── build_all.sh              # 一键打包脚本 (Unix)
├── build_all.bat             # 一键打包脚本 (Windows)
├── build_backend.sh          # Python后端打包脚本 (Unix)
├── build_backend.bat         # Python后端打包脚本 (Windows)
├── BUILD_GUIDE.md            # 详细打包指南
├── LICENSE.txt               # MIT许可证
├── .gitignore                # Git忽略配置
├── templates/                # 前端HTML模板
│   ├── index.html           # 主页（模式选择）
│   ├── usb_mode.html        # USB模式页面
│   ├── wifi_mode.html       # WiFi模式页面
│   └── upload_progress.html # 上传进度监控页面
├── photos_output/           # 照片输出目录
│   └── {device_id}/        # 按设备ID分组
│       └── {batch_id}/     # 按批次ID分组
└── android_transfer.db     # SQLite数据库（运行后生成）
```

## 🔧 环境要求

### 运行应用
- **Android 设备**（开启 USB 调试）
- **USB 数据线**（USB 模式）或**同一局域网**（WiFi 模式）
- **ADB 工具**（USB 模式需要，可选自动包含）

### 打包应用
- **Node.js** 16+
- **Python** 3.8+
- **npm**
- **PyInstaller**（自动安装）

## 📖 使用指南

### USB 模式
1. 连接 Android 设备到电脑
2. 启用 **USB 调试**并授权
3. 点击"**USB 模式**"
4. 扫描设备照片
5. 选择保存目录并开始传输

**性能优势**：
- 16线程超高并发传输
- 支持断点续传
- 智能去重和跳过
- 实时速度监控和 ETA

### WiFi 模式
1. 确保手机和电脑在**同一局域网**
2. 点击"**WiFi 模式**"获取连接信息
3. 在 Android 应用中输入服务器地址
4. 选择照片并上传

**功能特点**：
- 自动服务发现（UDP广播）
- 多设备同时连接
- 批次管理和历史记录
- 实时上传进度跟踪

## 🗄️ SQLite 数据库

本项目使用 SQLite 数据库存储，优势：

- ✅ **性能更好** - SQL 索引加速查询
- ✅ **并发安全** - 内置锁机制
- ✅ **数据完整性** - ACID 事务
- ✅ **易于打包** - 单文件数据库
- ✅ **跨平台** - 无需额外安装

数据库文件：`android_transfer.db`（首次运行自动创建）

### 数据库表结构

```sql
-- 设备表
devices (device_id, device_name, last_heartbeat, photo_count)

-- 批次表
batches (device_id, batch_id, timestamp, photo_count, total_size_mb, status)

-- 照片表
photos (device_id, batch_id, name, path, size, date)
```

## 📱 配合 Android App 使用

1. Android App 在 `../android/` 目录
2. 使用 Android Studio 编译并安装到手机
3. 手机和电脑连接同一 WiFi
4. 在 Web 端选择"WiFi 模式"
5. Android App 扫描服务器并连接
6. 选择照片上传

## 🛠️ 技术栈

- **后端**: Python 3, Flask, SQLite
- **前端**: HTML5, CSS3, JavaScript (Material Design)
- **桌面**: Electron
- **打包**: PyInstaller, Electron Builder
- **传输**: ADB (USB), HTTP/WebSocket (WiFi)
- **Android**: Java, Retrofit, OkHttp

## 🐛 常见问题

### 1. 找不到 ADB 工具
**问题**: USB 模式无法识别设备

**解决**:
- 安装 Android Platform Tools
- 将 ADB 添加到系统 PATH
- 或在打包时包含 ADB 可执行文件

### 2. macOS 安全警告
**问题**: "无法打开，因为它来自身份不明的开发者"

**解决**:
```bash
# 方法 1: 右键点击应用选择"打开"
# 方法 2: 运行以下命令
sudo xattr -rd com.apple.quarantine /Applications/Android\ Transfer.app
```

### 3. Windows Defender 警告
**问题**: Windows Defender 阻止运行

**解决**:
- 选择"更多信息" → "仍要运行"
- 或添加到 Windows Defender 例外列表

### 4. 设备连接失败
**问题**: USB 模式下找不到设备

**解决**:
- 检查 USB 调试是否开启
- 尝试撤销 USB 调试授权并重新授权
- 检查 USB 数据线是否支持数据传输
- 尝试更换 USB 端口

### 5. 数据库问题
**问题**: 批次或照片数据丢失

**解决**:
```bash
# 重建数据库（会丢失历史记录）
rm android_transfer.db

# 查看数据库内容
sqlite3 android_transfer.db "SELECT * FROM batches;"
```

### 6. 端口占用
**问题**: 9500 端口被占用

**解决**:
```bash
# 检查端口占用
lsof -i :9500  # macOS/Linux
netstat -ano | findstr :9500  # Windows

# 修改端口：编辑 app.py 最后一行的 port=9500
```

## 📝 开发指南

### 启动开发环境

```bash
# 方式 1: 仅启动 Flask 后端
python3 app.py

# 方式 2: 启动 Electron + Flask
npm start
```

### 调试

- **Flask 后端**: 查看终端输出日志
- **Electron 前端**: 
  - macOS: `Cmd+Option+I`
  - Windows/Linux: `F12`
  - 打开开发者工具

### 开发建议

1. **开发时**：直接用 `python3 app.py` 运行，快速迭代
2. **测试 Electron**：用 `npm start` 测试集成
3. **测试打包**：用 `npm run dist` 测试完整打包流程
4. **性能测试**：使用大量照片测试并发和稳定性

## 📊 性能指标

### M4 Mac + USB 3.0
- **扫描速度**: 1000-3000 文件/秒（24线程并发）
- **传输速度**: 50-80 MB/s（16线程并发）
- **26,000 文件扫描**: 约 10-20 秒
- **180GB 传输**: 约 40-60 分钟（首次）
- **增量传输**: 秒级跳过已存在文件

### WiFi 模式
- **上传速度**: 取决于网络带宽（通常 10-30 MB/s）
- **并发上传**: 支持多设备同时上传
- **批次处理**: 实时进度跟踪

## 📄 许可证

MIT License - 详见 [LICENSE.txt](LICENSE.txt)

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

贡献指南：
1. Fork 本项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 🔗 相关文档

- [详细打包指南](BUILD_GUIDE.md)
- [WiFi API 文档](WIFI_MODE_API.md)
- [Material Design 指南](MATERIAL_DESIGN_GUIDE.md)
- [WiFi 调试指南](DEBUG_WIFI_DEVICE.md)
- Android App 源码: `../android/`

## 📮 联系方式

如有问题或建议，请创建 Issue 或查看相关文档。

---

**享受快速传输照片的体验！** 📸✨
