# 🚀 快速发布指南

## 一键发布新版本

只需要 3 个命令即可发布新版本到 GitHub Releases！

### 步骤 1: 更新版本号

编辑以下文件中的版本号：

1. **Electron 应用**：`AndroidTransferClient/package.json`
   ```json
   "version": "1.0.1"  // 改成新版本
   ```

2. **Android 应用**：`AndroidTransferApp/app/build.gradle.kts`
   ```kotlin
   versionCode = 2        // 递增版本号
   versionName = "1.0.1"  // 改成新版本
   ```

### 步骤 2: 提交并创建 Tag

```bash
# 提交更改
git add .
git commit -m "chore: 发布 v1.0.1"

# 创建并推送 tag（版本号必须以 v 开头）
git tag v1.0.1
git push origin main
git push origin v1.0.1
```

### 步骤 3: 等待构建完成

推送 tag 后，GitHub Actions 会自动开始构建：

1. 访问：`https://github.com/你的用户名/AndroidTransfer/actions`
2. 等待约 **15-30 分钟**
3. 构建完成后，在 `https://github.com/你的用户名/AndroidTransfer/releases` 查看发布

## 📦 发布内容

每次发布会自动生成以下文件：

### Android
- `app-release.apk` - Android 安装包

### macOS
- `Android Transfer-1.0.1-x64.dmg` - Intel Mac 安装包
- `Android Transfer-1.0.1-arm64.dmg` - Apple Silicon Mac 安装包
- `Android Transfer-1.0.1-x64.zip` - Intel Mac 压缩包
- `Android Transfer-1.0.1-arm64.zip` - Apple Silicon Mac 压缩包

### Windows
- `Android Transfer-1.0.1-x64.exe` - Windows 安装程序
- `Android Transfer-1.0.1-portable.exe` - Windows 便携版

## ⚠️ 注意事项

1. **必须先提交图标文件**：确保 `AndroidTransferClient` 目录下有以下文件：
   - `icon.icns` (macOS 图标)
   - `icon.ico` (Windows 图标)
   - `icon.png` (Linux 图标，可选)

   如果没有，运行：
   ```bash
   cd AndroidTransferClient
   ./generate_icons.sh
   git add icon.icns icon.ico icon.png
   git commit -m "chore: 添加图标文件"
   git push
   ```

2. **Tag 必须以 v 开头**：例如 `v1.0.0`、`v2.0.0-beta.1`

3. **不要删除 Tag**：如果构建失败，修复后创建新的 tag

## 🔍 查看构建状态

在 GitHub Actions 页面可以实时查看构建进度：
- ✅ 绿色：构建成功
- ❌ 红色：构建失败（点击查看日志）
- 🟡 黄色：正在构建

## 🐛 构建失败？

1. 点击失败的任务查看详细日志
2. 修复问题后提交代码
3. 创建新的 tag 重新构建

常见问题：
- **Python 依赖缺失**：检查 `requirements.txt`
- **Node 依赖缺失**：删除 `package-lock.json` 重新生成
- **缺少图标文件**：运行 `generate_icons.sh` 生成图标
- **Gradle 构建失败**：检查 `build.gradle.kts` 配置

## 📖 详细文档

查看 `RELEASE_GUIDE.md` 了解更多详情。

