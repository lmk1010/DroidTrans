# Android照片传输工具 - M4 Mac 极速版 ⚡

一个基于Python的Android手机照片传输工具，支持**ADB模式**和**WiFi模式**两种传输方式，**针对M4 Mac进行极致性能优化**。

## 🎯 传输模式

### 🔌 ADB模式
- 通过USB数据线连接
- 传输速度快，稳定可靠
- 需要开启USB调试

### 📡 WiFi模式（新功能）
- 通过WiFi无线传输
- 无需数据线，更加便捷
- 手机和电脑需在同一局域网
- 需要配合Android客户端APP使用

## 功能特点

- ✅ **双模式支持**：ADB模式和WiFi模式自由切换
- ✅ 自动扫描手机多个照片目录（DCIM、Pictures、Screenshots等）
- ✅ Web界面展示照片列表，支持搜索和排序
- ✅ 16线程超高并发传输（M4 Mac极速优化）
- ✅ 24线程暴力并发扫描（极速定位照片）
- ✅ 支持断点续传（自动保存进度，随时恢复）
- ✅ 自动重试机制（每个文件最多重试2次）
- ✅ 实时显示传输进度和速度预估
- ✅ 支持选择性传输（可勾选需要的照片）
- ✅ 适合大批量照片传输（测试支持3万+照片）
- ✅ WiFi模式API完整，方便开发Android客户端

## 系统要求

- Python 3.7+
- ADB工具（Android Debug Bridge）
- 已开启USB调试的Android设备

## 安装步骤

### 1. 安装ADB工具

**macOS:**
```bash
brew install android-platform-tools
```

**Ubuntu/Debian:**
```bash
sudo apt-get install android-tools-adb
```

**Windows:**
下载 [Android SDK Platform Tools](https://developer.android.com/studio/releases/platform-tools) 并添加到环境变量

### 2. 创建Python虚拟环境（推荐）

```bash
cd AndroidTransfer

# 创建虚拟环境
python3 -m venv venv

# 激活虚拟环境
source venv/bin/activate  # macOS/Linux
# 或者
venv\Scripts\activate     # Windows

# 安装依赖
pip install -r requirements.txt
```

### 3. 配置Android设备

1. 在手机上开启"开发者选项"
   - 设置 → 关于手机 → 连续点击"版本号"7次
2. 开启"USB调试"
   - 设置 → 开发者选项 → USB调试
3. 通过USB连接手机到电脑
4. 在手机上授权此电脑的ADB访问

### 4. 验证ADB连接

```bash
adb devices
```

应该显示：
```
List of devices attached
xxxxxxxxxx    device
```

## 使用方法

### 方法一：使用启动脚本（推荐）

```bash
./start.sh
```

启动脚本会自动：
- 检查ADB和Python环境
- 创建并激活虚拟环境
- 安装依赖
- 启动服务

### 方法二：手动启动

```bash
# 激活虚拟环境
source venv/bin/activate  # macOS/Linux
# 或
venv\Scripts\activate     # Windows

# 启动服务
python app.py
```

启动后会显示：
```
Android照片传输工具
============================================================
照片输出目录: ./photos_output
每批传输数量: 50
============================================================

请确保:
1. 已安装ADB工具
2. 手机已通过USB连接并开启USB调试
3. 手机已授权此电脑的ADB访问

启动服务中...
访问 http://127.0.0.1:9500 开始使用
============================================================
```

### 2. 打开Web界面

在浏览器中访问：http://127.0.0.1:9500

### 3. 操作流程

1. **检查设备** - 点击"检查设备"按钮，确认手机已正确连接
2. **扫描照片** - 点击"扫描照片"按钮，等待扫描完成（3万张照片大约需要2-5分钟）
3. **选择照片** - 在列表中勾选需要传输的照片，或点击"全选"
4. **开始传输** - 点击"开始传输"按钮，等待传输完成
5. **查看结果** - 传输的照片保存在 `./photos_output` 目录

### 4. 功能说明

**搜索和排序：**
- 支持按文件名搜索
- 支持按日期、名称、大小排序

**传输特点：**
- 每次传输一个文件，避免批量超时
- 自动跳过已存在的文件（按大小判断）
- 失败的文件会自动重试3次
- 保持原始目录结构

**进度显示：**
- 实时显示当前传输的文件
- 显示传输进度百分比
- 显示传输速度统计

## 配置说明

可以在 `app.py` 中修改以下配置：

```python
OUTPUT_DIR = "./photos_output"  # 照片输出目录
BATCH_SIZE = 50                 # 每批传输的照片数量（当前未使用，逐个传输）
MAX_RETRIES = 3                 # 最大重试次数
```

扫描的目录包括：
```python
PHOTO_DIRS = [
    '/sdcard/DCIM',
    '/sdcard/Pictures',
    '/sdcard/Screenshots',
    '/sdcard/Download',
    '/storage/emulated/0/DCIM',
    '/storage/emulated/0/Pictures',
    '/storage/emulated/0/Screenshots',
]
```

## 常见问题

### 1. 扫描很慢怎么办？

扫描速度取决于手机照片数量和USB连接速度。3万张照片大约需要2-5分钟是正常的。

### 2. 传输中断怎么办？

- 重新点击"开始传输"即可，已传输的文件会自动跳过
- 检查USB连接是否稳定
- 确保手机屏幕保持常亮，避免进入休眠

### 3. 提示"未检测到设备"？

请检查：
1. 手机是否通过USB连接
2. 是否开启了USB调试
3. 是否授权了ADB访问
4. 运行 `adb devices` 确认设备已连接

### 4. 传输速度慢？

- USB 2.0 速度约为 5-10 MB/s，这是正常的
- 3万张照片（假设平均每张5MB）约需要数小时
- 建议晚上开始传输，保持电脑和手机不休眠

### 5. 某些照片传输失败？

- 工具会自动重试3次
- 失败的文件会在控制台显示
- 可以在传输完成后重新选择失败的照片再次传输

## 技术架构

- **后端**: Python Flask
- **前端**: HTML + JavaScript + CSS
- **通信**: RESTful API + AJAX
- **ADB**: subprocess调用adb命令行工具

## 注意事项

1. 传输期间请保持：
   - USB连接稳定
   - 手机屏幕常亮或调高自动锁屏时间
   - 电脑不要进入休眠

2. 传输的照片会保持原始目录结构，例如：
   ```
   photos_output/
   ├── DCIM/
   │   ├── Camera/
   │   │   ├── IMG_001.jpg
   │   │   └── IMG_002.jpg
   │   └── Screenshots/
   └── Pictures/
   ```

3. 已传输的照片不会被重复传输（通过文件大小判断）

## 许可证

MIT License

## 作者

Created with Claude Code
