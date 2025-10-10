# macOS 版本构建和发布指南

## 构建步骤

### 1. 清理旧构建

```bash
cd AndroidTransferClient
rm -rf dist dist_app build
```

### 2. 运行构建脚本

```bash
./build_m1.sh
```

构建完成后会在 `dist/` 目录生成以下文件：

- `Android Transfer-1.0.0-arm64.dmg` - M1/M2/M3/M4 Mac 安装包
- `Android Transfer-1.0.0-arm64.zip` - M1/M2/M3/M4 Mac 便携版
- `Android Transfer-1.0.0-x64.dmg` - Intel Mac 安装包
- `Android Transfer-1.0.0-x64.zip` - Intel Mac 便携版

### 3. 验证签名

```bash
# 验证 arm64 版本
codesign --verify --deep --strict --verbose=2 "dist/mac-arm64/Android Transfer.app"

# 验证 x64 版本
codesign --verify --deep --strict --verbose=2 "dist/mac/Android Transfer.app"
```

如果显示 `satisfies its Designated Requirement`，说明签名成功。

### 4. 测试应用

```bash
# 挂载 DMG
open "dist/Android Transfer-1.0.0-arm64.dmg"

# 拖动应用到 Applications 文件夹测试
```

## 上传到 GitHub Release

### 方法一：使用 gh CLI（推荐）

```bash
# 确保已登录 gh
gh auth status

# 上传文件到已存在的 Release
gh release upload v1.0.0 \
  dist/Android\ Transfer-1.0.0-arm64.dmg \
  dist/Android\ Transfer-1.0.0-arm64.zip \
  dist/Android\ Transfer-1.0.0-x64.dmg \
  dist/Android\ Transfer-1.0.0-x64.zip
```

### 方法二：使用 GitHub 网页

1. 访问 https://github.com/MK-CO/AndroidTransfer/releases
2. 找到对应的 Release（如 v1.0.0）
3. 点击 "Edit release"
4. 拖动以下文件到 "Attach binaries" 区域：
   - `Android Transfer-1.0.0-arm64.dmg`
   - `Android Transfer-1.0.0-arm64.zip`
   - `Android Transfer-1.0.0-x64.dmg`
   - `Android Transfer-1.0.0-x64.zip`
5. 点击 "Update release"

## 完整发布流程

1. **本地构建 macOS 版本**
   ```bash
   cd AndroidTransferClient
   ./build_m1.sh
   ```

2. **推送 tag 触发 GitHub Actions**
   ```bash
   git tag -a v1.0.1 -m "Release v1.0.1"
   git push origin v1.0.1
   ```

3. **等待 GitHub Actions 完成**
   - 访问 https://github.com/MK-CO/AndroidTransfer/actions
   - 等待 Android 和 Windows 构建完成
   - Release 会自动创建

4. **上传 macOS 文件**
   ```bash
   gh release upload v1.0.1 \
     dist/Android\ Transfer-1.0.1-arm64.dmg \
     dist/Android\ Transfer-1.0.1-arm64.zip \
     dist/Android\ Transfer-1.0.1-x64.dmg \
     dist/Android\ Transfer-1.0.1-x64.zip
   ```

5. **完成！**
   - 访问 https://github.com/MK-CO/AndroidTransfer/releases
   - 验证所有文件都已上传

## 注意事项

- macOS 版本使用您的开发者证书签名，用户可以直接安装
- GitHub Actions 构建的 Android 和 Windows 版本无签名问题
- 建议在发布前测试所有平台的安装包
- 保持版本号一致（tag、package.json、DMG 文件名）

## 故障排除

### 签名验证失败

```bash
# 重新签名
codesign --force --deep --sign "Apple Development: mingkang liu (VB3DDX8YB9)" \
  "dist/mac-arm64/Android Transfer.app"
```

### DMG 无法打开

```bash
# 检查 DMG 是否损坏
hdiutil verify "dist/Android Transfer-1.0.0-arm64.dmg"
```

### 上传失败

```bash
# 检查 Release 是否存在
gh release view v1.0.0

# 删除已上传的文件重新上传
gh release delete-asset v1.0.0 "Android Transfer-1.0.0-arm64.dmg"
gh release upload v1.0.0 "dist/Android Transfer-1.0.0-arm64.dmg"
```
