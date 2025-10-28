择一：测试本地构建的应用

1. 打开 Finder，进入 /Users/liumingkang/Code/AndroidTransfer/AndroidTransferClient/dist/
2. 双击 Android Transfer-1.0.0-arm64.dmg
3. 安装并测试应用是否正常工作

选择二：直接发布新版本

# 1. 删除旧 tag 并推送新 tag（触发 GitHub Actions）
git tag -d v1.0.0
git push origin :refs/tags/v1.0.0
git tag -a v1.0.0 -m "Release v1.0.0 - Android Transfer 首个正式版本"
git push origin v1.0.0

# 2. 等待 GitHub Actions 完成（约 10-15 分钟）
# 访问 https://github.com/MK-CO/AndroidTransfer/actions 查看进度

# 3. Actions 完成后，上传 macOS 文件
gh release upload v1.0.0 \
dist/Android\ Transfer-1.0.0-arm64.dmg \
dist/Android\ Transfer-1.0.0-arm64.zip \
dist/Android\ Transfer-1.0.0-x64.dmg \
dist/Android\ Transfer-1.0.0-x64.zip

现在的工作流程

以后每次发布新版本：

1. 本地构建 macOS：cd AndroidTransferClient && ./build_m1.sh
2. 推送 tag：git tag -a v1.0.x -m "..." && git push origin v1.0.x
3. 等待 GitHub Actions 构建 Android + Windows
4. 手动上传 macOS 文件到 Release