# Electron 客户端打包指南

## 🎯 为什么使用 SQLite？

1. **单文件数据库** - 方便打包和分发
2. **无需服务器** - 嵌入式数据库，不需要额外进程
3. **跨平台** - Python和大多数语言都支持
4. **轻量高效** - 适合本地应用
5. **易于备份** - 只需复制一个 `.db` 文件

## 📦 打包步骤

### 1. 安装Node.js依赖

```bash
cd /Users/liumingkang/Code/AndroidTransfer

# 安装Electron和打包工具
npm install
```

### 2. 打包Python后端为可执行文件

使用 PyInstaller 将 Python 应用打包成单个可执行文件：

```bash
# 安装 PyInstaller
pip install pyinstaller

# 打包Flask应用
pyinstaller --onefile \
  --add-data "templates:templates" \
  --add-data "static:static" \
  --hidden-import=werkzeug \
  --hidden-import=flask \
  --hidden-import=sqlite3 \
  --name app \
  app.py

# 打包后的文件在 dist/ 目录
# 将其移动到 dist_app/ 以便Electron打包
mkdir -p dist_app
cp dist/app dist_app/
cp -r templates dist_app/
cp -r static dist_app/ 2>/dev/null || true
```

### 3. 打包Electron应用

```bash
# macOS
npm run dist:mac

# Windows (需要在Windows系统或使用wine)
npm run dist:win

# Linux
npm run dist:linux

# 所有平台
npm run dist
```

### 4. 运行打包后的应用

打包完成后，在 `dist/` 目录下会生成对应平台的安装包：

- **macOS**: `Android Transfer.dmg` 或 `Android Transfer-mac.zip`
- **Windows**: `Android Transfer Setup.exe` 或 `Android Transfer.exe` (portable)
- **Linux**: `Android Transfer.AppImage` 或 `android-transfer_*.deb`

## 🔧 开发模式运行

不打包，直接在开发环境运行：

```bash
# 启动Electron（会自动启动Python后端）
npm start
```

## 📊 SQLite 数据库结构

```sql
-- 设备表
CREATE TABLE devices (
    device_id TEXT PRIMARY KEY,
    device_name TEXT,
    last_heartbeat TEXT,
    connected_at TEXT,
    photo_count INTEGER DEFAULT 0
);

-- 批次表
CREATE TABLE batches (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    batch_id TEXT NOT NULL,
    timestamp TEXT,
    photo_count INTEGER DEFAULT 0,
    total_size INTEGER DEFAULT 0,
    total_size_mb REAL DEFAULT 0,
    status TEXT DEFAULT 'completed',
    is_legacy INTEGER DEFAULT 0,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(device_id, batch_id)
);

-- 照片表
CREATE TABLE photos (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    batch_id TEXT NOT NULL,
    name TEXT NOT NULL,
    path TEXT NOT NULL,
    size INTEGER DEFAULT 0,
    size_mb REAL DEFAULT 0,
    date TEXT,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(device_id, batch_id, name)
);
```

## 🗄️ 数据库文件位置

- **开发环境**: `./android_transfer.db`
- **生产环境**: 跟随应用安装目录

## 🔄 从JSON迁移到SQLite

如果你之前使用的是JSON存储，首次启动会自动：

1. 初始化SQLite数据库
2. 扫描 `photos_output/` 目录
3. 重建所有批次信息
4. 保存到数据库

**旧的 `device_batches.json` 已被删除**，现在使用 `android_transfer.db`

## 💾 数据备份

备份你的数据库：

```bash
# 备份
cp android_transfer.db android_transfer_backup_$(date +%Y%m%d).db

# 恢复
cp android_transfer_backup_20251009.db android_transfer.db
```

## 🚀 性能优势

相比JSON文件：

- ✅ **查询速度** - SQL索引加速查询
- ✅ **并发安全** - 内置锁机制
- ✅ **数据完整性** - ACID事务保证
- ✅ **文件大小** - 二进制存储更紧凑
- ✅ **扩展性** - 支持复杂查询和统计

## 📝 注意事项

1. **ADB工具** - 需要系统安装 `adb` 命令
2. **权限** - macOS可能需要授予网络和USB权限
3. **防火墙** - 确保 9500 端口未被占用
4. **首次启动** - 会扫描文件系统，可能需要几秒钟

## 🐛 故障排除

### 数据库锁定错误

```python
# 如果出现 "database is locked" 错误
# 检查是否有多个进程同时访问数据库
ps aux | grep app.py
```

### 清空数据库重新开始

```bash
# 删除数据库文件
rm android_transfer.db

# 重启应用，会自动重建
python app.py
```

### 查看数据库内容

```bash
# 使用SQLite命令行工具
sqlite3 android_transfer.db

# 查看所有表
.tables

# 查看批次数据
SELECT * FROM batches;

# 退出
.quit
```

## 🎨 自定义图标

替换以下文件自定义应用图标：

- `icon.icns` (macOS)
- `icon.ico` (Windows)
- `icon.png` (Linux, 512x512 PNG)

在线图标转换工具：https://www.icoconverter.com/

