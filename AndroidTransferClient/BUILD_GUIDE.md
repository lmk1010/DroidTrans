# Android Transfer 打包指南

## 📦 打包说明

本指南将帮你将整个应用（Python 后端 + Electron 前端）打包成一个可安装的桌面应用。

## 🎯 打包目标

- **macOS**: `.dmg` 安装包 / `.zip` 便携版
- **Windows**: `.exe` 安装程序 / 便携版 `.exe`
- **Linux**: `.AppImage` / `.deb` 包

## 📋 环境要求

### 必需软件

1. **Node.js** (v16 或更高版本)
   - 下载：https://nodejs.org/
   - 验证：`node --version`

2. **Python 3** (3.8 或更高版本)
   - 下载：https://www.python.org/
   - 验证：`python3 --version` (macOS/Linux) 或 `python --version` (Windows)

3. **npm** (随 Node.js 自动安装)
   - 验证：`npm --version`

### macOS 额外要求

- Xcode Command Line Tools: `xcode-select --install`

### Windows 额外要求

- Visual Studio Build Tools (可选，但推荐)

## 🚀 一键打包（推荐）

### macOS / Linux

```bash
cd AndroidTransferClient
chmod +x build_all.sh
./build_all.sh
```

### Windows

```cmd
cd AndroidTransferClient
build_all.bat
```

这将自动完成：
1. 安装 Python 依赖
2. 打包 Python 后端
3. 安装 Node.js 依赖  
4. 打包 Electron 应用

打包完成后，安装包位于 `dist/` 目录。

## 📝 分步打包（高级）

如果你想分步执行打包流程：

### 步骤 1: 安装依赖

```bash
# Python 依赖
python3 -m venv venv
source venv/bin/activate  # Windows: venv\Scripts\activate
pip install -r requirements.txt
pip install pyinstaller

# Node.js 依赖
npm install
```

### 步骤 2: 打包 Python 后端

**macOS/Linux:**
```bash
./build_backend.sh
```

**Windows:**
```cmd
build_backend.bat
```

这会将 Flask 后端打包到 `dist_app/` 目录。

### 步骤 3: 打包 Electron 应用

**macOS:**
```bash
npm run dist:mac
```

**Windows:**
```cmd
npm run dist:win
```

**Linux:**
```bash
npm run dist:linux
```

## 📦 输出文件说明

打包完成后，`dist/` 目录包含：

### macOS
- `Android Transfer-1.0.0-x64.dmg` - Intel 芯片安装包
- `Android Transfer-1.0.0-arm64.dmg` - Apple Silicon (M1/M2/M4) 安装包
- `Android Transfer-1.0.0-x64.zip` - Intel 便携版
- `Android Transfer-1.0.0-arm64.zip` - Apple Silicon 便携版

### Windows
- `Android Transfer-1.0.0-x64.exe` - 安装程序
- `Android Transfer-1.0.0-portable.exe` - 便携版（无需安装）

### Linux
- `Android Transfer-1.0.0.AppImage` - 通用格式（推荐）
- `android-transfer_1.0.0_amd64.deb` - Debian/Ubuntu 包

## 🎯 使用打包后的应用

### macOS
1. 打开 `.dmg` 文件
2. 拖动 `Android Transfer.app` 到 `Applications` 文件夹
3. 双击运行（如遇安全提示，在"系统偏好设置 > 安全性与隐私"中允许）

### Windows
1. 双击 `.exe` 安装程序
2. 按提示完成安装
3. 从开始菜单或桌面快捷方式启动

### Linux
1. 给 `.AppImage` 添加可执行权限：`chmod +x Android\ Transfer-1.0.0.AppImage`
2. 双击运行，或命令行：`./Android\ Transfer-1.0.0.AppImage`

## 🔧 常见问题

### 1. PyInstaller 打包失败

**症状**: 缺少模块或导入错误

**解决**:
- 确保已安装所有 Python 依赖：`pip install -r requirements.txt`
- 检查 `app.spec` 中的 `hiddenimports` 列表
- 尝试清理缓存：`pyinstaller --clean app.spec`

### 2. Electron Builder 打包失败

**症状**: 找不到 Python 后端

**解决**:
- 确保先执行了 `build_backend.sh` 或 `build_backend.bat`
- 检查 `dist_app/` 目录是否存在
- 清理并重新打包：`rm -rf dist && npm run dist:mac`

### 3. macOS 安全警告

**症状**: "无法打开，因为它来自身份不明的开发者"

**解决**:
1. 右键点击应用 → 选择"打开"
2. 或在终端运行：`sudo xattr -rd com.apple.quarantine /Applications/Android\ Transfer.app`

### 4. Windows Defender 警告

**症状**: Windows Defender 阻止运行

**解决**:
- 这是正常的，因为应用未签名
- 选择"更多信息" → "仍要运行"
- 或添加到 Windows Defender 例外列表

### 5. 打包后应用无法连接 ADB

**症状**: 应用启动后找不到设备

**原因**: ADB 工具未包含在打包中

**解决**: 
- 用户需要单独安装 Android Platform Tools
- 或在打包时将 ADB 可执行文件包含进去（修改 `app.spec`）

## 📊 打包大小优化

默认打包后的应用大小：
- macOS: ~80-100 MB
- Windows: ~60-80 MB
- Linux: ~70-90 MB

### 减小体积的方法

1. **排除不必要的依赖**
   - 检查 `requirements.txt`，移除未使用的包

2. **使用 UPX 压缩** (已在 `app.spec` 中启用)
   ```python
   exe = EXE(
       ...
       upx=True,  # 启用 UPX 压缩
   )
   ```

3. **排除测试文件和文档**
   - 修改 `package.json` 中的 `files` 配置

## 🔐 代码签名（可选）

为了避免安全警告，可以对应用进行代码签名：

### macOS
需要 Apple Developer 账号（$99/年）：
```bash
# 设置签名身份
export CSC_LINK=/path/to/certificate.p12
export CSC_KEY_PASSWORD=your_password
npm run dist:mac
```

### Windows
需要代码签名证书：
```bash
# 设置签名证书
set CSC_LINK=path\to\certificate.pfx
set CSC_KEY_PASSWORD=your_password
npm run dist:win
```

## 📚 相关文档

- [Electron Builder 文档](https://www.electron.build/)
- [PyInstaller 文档](https://pyinstaller.org/)
- [本项目 README](README.md)

## 🆘 获取帮助

如果遇到问题：
1. 检查本文档的"常见问题"部分
2. 查看终端输出的详细错误信息
3. 确保所有依赖都已正确安装
4. 尝试清理并重新打包

## 📝 更新日志

### v1.0.0
- 首次发布
- 支持 USB 和 WiFi 模式
- 支持 macOS、Windows、Linux
- 一键打包脚本

---

**祝打包顺利！** 🎉

