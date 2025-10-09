# GitHub Actions 自动构建和发布指南

本项目已配置 GitHub Actions 自动构建流水线，可以自动构建并发布以下版本：
- **Android APK** (Release 版本)
- **macOS 应用** (DMG 和 ZIP 格式，支持 x64 和 arm64 架构)
- **Windows 应用** (NSIS 安装包和便携版)

## 🚀 如何触发自动构建和发布

### 方法 1：创建 Git Tag（推荐）

当你准备好发布新版本时：

```bash
# 1. 确保所有更改都已提交
git add .
git commit -m "准备发布 v1.0.0"

# 2. 创建并推送 tag
git tag v1.0.0
git push origin v1.0.0
```

推送 tag 后，GitHub Actions 会自动：
1. 构建 Android APK
2. 构建 macOS 版本（x64 和 arm64）
3. 构建 Windows 版本
4. 创建 GitHub Release
5. 上传所有构建产物到 Release

### 方法 2：手动触发

你也可以在 GitHub 网页上手动触发构建：

1. 进入你的 GitHub 仓库
2. 点击 "Actions" 标签
3. 选择 "Build and Release" 工作流
4. 点击 "Run workflow" 按钮
5. 选择分支并点击 "Run workflow"

**注意**：手动触发不会自动创建 Release，只会生成构建产物（可在 Artifacts 中下载）。

## 📦 构建产物

成功构建后，你会在 GitHub Release 中看到以下文件：

### Android
- `app-release.apk` - Android 应用安装包

### macOS
- `Android Transfer-1.0.0-x64.dmg` - macOS Intel 芯片安装包
- `Android Transfer-1.0.0-arm64.dmg` - macOS Apple Silicon (M1/M2/M3) 安装包
- `Android Transfer-1.0.0-x64.zip` - macOS Intel 芯片压缩包
- `Android Transfer-1.0.0-arm64.zip` - macOS Apple Silicon 压缩包

### Windows
- `Android Transfer-1.0.0-x64.exe` - Windows 安装程序
- `Android Transfer-1.0.0-portable.exe` - Windows 便携版（无需安装）

## 📝 版本号管理

### 更新版本号

发布新版本前，需要更新以下文件中的版本号：

1. **Electron 应用** - `AndroidTransferClient/package.json`
   ```json
   {
     "version": "1.0.0"  // 更新这里
   }
   ```

2. **Android 应用** - `AndroidTransferApp/app/build.gradle.kts`
   ```kotlin
   defaultConfig {
       versionCode = 1      // 递增版本号
       versionName = "1.0"  // 更新版本名称
   }
   ```

### 版本号规范

建议使用语义化版本号（Semantic Versioning）：

- `v1.0.0` - 主版本.次版本.修订号
- `v1.0.0-beta.1` - 测试版本
- `v1.0.0-rc.1` - 候选版本

## 🔧 工作流配置

工作流配置文件位于：`.github/workflows/release.yml`

### 工作流包含的任务：

1. **build-android** - 在 Ubuntu 上构建 Android APK
2. **build-mac** - 在 macOS 上构建 Mac 应用
3. **build-windows** - 在 Windows 上构建 Windows 应用
4. **create-release** - 创建 GitHub Release 并上传所有构建产物

### 自定义构建

如果需要修改构建配置，可以编辑：
- Android 构建：`AndroidTransferApp/app/build.gradle.kts`
- Electron 构建：`AndroidTransferClient/package.json` (build 部分)

## 🛠️ 构建要求

### 必需的文件

确保以下文件存在：
- `AndroidTransferClient/package.json` - Electron 配置
- `AndroidTransferClient/app.spec` - Python 后端打包配置
- `AndroidTransferClient/requirements.txt` - Python 依赖
- `AndroidTransferApp/gradlew` - Gradle 包装器

### 图标文件

确保以下图标文件存在：
- `AndroidTransferClient/icon.icns` - macOS 图标
- `AndroidTransferClient/icon.ico` - Windows 图标
- `AndroidTransferClient/icon.png` - Linux 图标（可选）

## 🐛 故障排除

### 构建失败？

1. **检查 Actions 日志**
   - 进入 GitHub 仓库的 "Actions" 标签
   - 点击失败的工作流运行
   - 查看详细日志

2. **常见问题**
   - **Python 依赖问题**：确保 `requirements.txt` 包含所有必需的包
   - **Node 依赖问题**：尝试删除 `node_modules` 和 `package-lock.json` 后重新生成
   - **Gradle 构建失败**：检查 Android SDK 版本和依赖配置

3. **本地测试**
   ```bash
   # 测试 Android 构建
   cd AndroidTransferApp
   ./gradlew assembleRelease
   
   # 测试 Electron 构建
   cd AndroidTransferClient
   npm run build:all
   ```

## 📚 更多信息

- [GitHub Actions 文档](https://docs.github.com/en/actions)
- [electron-builder 文档](https://www.electron.build/)
- [Android Gradle Plugin 文档](https://developer.android.com/build/releases/gradle-plugin)

## 🎉 发布新版本的完整流程

```bash
# 1. 更新版本号
# 编辑 AndroidTransferClient/package.json
# 编辑 AndroidTransferApp/app/build.gradle.kts

# 2. 提交更改
git add .
git commit -m "chore: 准备发布 v1.0.1"

# 3. 创建并推送 tag
git tag v1.0.1
git push origin main
git push origin v1.0.1

# 4. 等待 GitHub Actions 完成构建（约 15-30 分钟）

# 5. 访问 GitHub Release 页面下载构建产物
# https://github.com/你的用户名/AndroidTransfer/releases
```

## ✅ 完成！

现在你的项目已经配置好自动化构建和发布流程了！每次创建新 tag 时，GitHub Actions 会自动构建所有平台的应用并发布到 Releases。

