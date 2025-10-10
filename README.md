<div align="center">

# 📱 Android Transfer

<img src="app_logo.svg" width="120" height="120" alt="Android Transfer Logo">

**现代化的 Android 照片传输工具**

一键扫描 · 批量传输 · 智能续传

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Python](https://img.shields.io/badge/Python-3.7+-green.svg)](https://www.python.org/)
[![Electron](https://img.shields.io/badge/Electron-28.0+-47848F.svg)](https://www.electronjs.org/)

[功能特性](#-功能特性) • [快速开始](#-快速开始) • [使用指南](#-使用指南) • [截图预览](#-截图预览)

</div>

---

## ✨ 功能特性

### 🔌 双传输模式

<table>
<tr>
<td width="50%">

#### USB 模式
- ⚡ 高速稳定传输
- 🔒 数据安全可靠
- 📊 实时进度显示
- 🔄 断点续传支持

</td>
<td width="50%">

#### WiFi 模式
- 📡 无线便捷传输
- 🌐 局域网自动发现
- 📱 手机APP配合
- 💨 多设备批次管理

</td>
</tr>
</table>

### 🚀 核心优势

- **智能扫描** - 自动识别多个照片目录（DCIM、Pictures、Screenshots等）
- **超高性能** - M4 Mac 优化，16线程并发传输
- **断点续传** - 传输中断自动恢复，不重复传输
- **批量处理** - 支持数万张照片批量传输
- **现代UI** - Material Design 风格，操作简单直观
- **跨平台** - 支持 macOS、Windows、Linux

---

## 🚀 快速开始

### 方式一：使用 Electron 桌面应用（推荐）

下载对应平台的安装包：

- **macOS**: `Android Transfer-1.0.0-arm64.dmg` (Apple Silicon) / `Android Transfer-1.0.0-x64.dmg` (Intel)
- **Windows**: `Android Transfer-1.0.0-x64.exe`
- **Linux**: `Android Transfer-1.0.0.AppImage`

> 📦 [前往 Releases 下载](../../releases)

#### ⚠️ macOS 用户必读 - 重要！

由于应用未经 Apple 官方认证，安装后**会显示"应用已损坏"**，这是正常现象。

**必须按以下步骤操作（二选一）：**

**方法一：使用命令移除隔离属性（推荐，一步到位）**

1. 下载并安装应用到 `Applications` 文件夹
2. 打开终端（Terminal），复制粘贴以下命令并回车：
   ```bash
   sudo xattr -rd com.apple.quarantine /Applications/Android\ Transfer.app
   ```
3. 输入管理员密码（输入时不显示，输完按回车）
4. 完成！双击即可运行

**方法二：在系统设置中手动允许**

1. 下载并安装应用到 `Applications` 文件夹
2. 右键点击应用，选择"打开"（不是双击）
3. 点击"打开"确认
4. 如果仍然被阻止：
   - 打开 `系统偏好设置` > `隐私与安全性`
   - 在底部找到被阻止的应用
   - 点击 "仍要打开"

> 💡 **为什么会这样？**
>
> 开源软件通常没有购买 Apple 开发者账号（$99/年）进行代码签名和公证。macOS会阻止所有未认证的应用。这是标准的安全机制，移除隔离属性后应用完全安全可用。
>
> 详见 [macOS 安装说明](INSTALL_MACOS.md)

### 方式二：Python 脚本运行

```bash
# 克隆项目
git clone https://github.com/yourusername/AndroidTransfer.git
cd AndroidTransfer/AndroidTransferClient

# 安装依赖
pip install -r requirements.txt

# 启动服务
python app.py
```

访问 http://localhost:9500

---

## 📖 使用指南

### USB 模式

1. **准备工作**
   - 安装 ADB 工具: `brew install android-platform-tools` (macOS)
   - 手机开启 USB 调试
   - USB 连接手机到电脑

2. **传输照片**
   - 打开应用，选择 USB 模式
   - 点击"扫描照片"
   - 选择需要的照片
   - 点击"开始传输"

### WiFi 模式

1. **网络配置**
   - 确保手机和电脑在同一 WiFi 网络
   - 打开应用，选择 WiFi 模式

2. **手机端操作**
   - 安装 Android 客户端 APP
   - APP 会自动扫描并连接服务器
   - 选择照片后点击上传

3. **电脑端查看**
   - 照片自动按设备和批次分类
   - 支持预览、打开文件夹、删除等操作

---

## 🖼️ 截图预览

<div align="center">

### 模式选择界面
<img src="docs/screenshots/home.png" width="600" alt="主界面">

### WiFi 传输模式
<img src="docs/screenshots/wifi-mode.png" width="600" alt="WiFi模式">

### USB 传输模式
<img src="docs/screenshots/usb-mode.png" width="600" alt="USB模式">

</div>

---

## 🛠️ 技术栈

### 桌面端
- **Electron** - 跨平台桌面应用框架
- **Python + Flask** - 后端服务
- **Material Design** - 现代化 UI 设计

### Android 客户端
- **Java/Kotlin** - 原生 Android 开发
- **OkHttp** - 网络请求
- **Room** - 本地数据库

---

## 📋 系统要求

- **Python**: 3.7 或更高版本
- **ADB**: Android Debug Bridge（仅 USB 模式需要）
- **Node.js**: 16+ (如需构建 Electron 应用)

---

## 🔧 开发指南

### 克隆项目

```bash
git clone https://github.com/yourusername/AndroidTransfer.git
cd AndroidTransfer
```

### 启动开发服务器

```bash
cd AndroidTransferClient
pip install -r requirements.txt
python app.py
```

### 构建 Electron 应用

```bash
cd AndroidTransferClient
npm install
npm run dist:mac    # macOS
npm run dist:win    # Windows
npm run dist:linux  # Linux
```

---

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

---

## 📄 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件

---

## 🙏 致谢

- [Electron](https://www.electronjs.org/) - 跨平台桌面应用框架
- [Flask](https://flask.palletsprojects.com/) - Python Web 框架
- [Material Design](https://material.io/) - Google Material Design 设计规范

---

<div align="center">

**⭐ 如果这个项目对你有帮助，请给个 Star ⭐**

Made with ❤️ by [MK](https://github.com/yourusername)

</div>
