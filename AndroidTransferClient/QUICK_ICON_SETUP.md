# 快速设置 Electron 图标

## 最快方案（无需安装工具）

### 方法 1: 使用在线工具

1. **打开在线转换器**: https://cloudconvert.com/svg-to-icns

2. **上传文件**: 上传 `icon.png.svg`

3. **设置参数**:
   - 输出格式选择: ICNS (Mac)、ICO (Windows)、PNG (Linux)
   - 分辨率: 1024x1024 (PNG) 或默认 (ICNS/ICO)

4. **下载生成的文件**:
   - `icon.icns` → 放入 AndroidTransferClient/ 目录
   - `icon.ico` → 放入 AndroidTransferClient/ 目录  
   - `icon.png` (512x512) → 放入 AndroidTransferClient/ 目录

---

## 方法 2: 安装 ImageMagick 后生成

```bash
# 安装 ImageMagick
brew install imagemagick

# 运行生成脚本
cd /Users/liumingkang/Code/AndroidTransfer/AndroidTransferClient
./generate_icons.sh
```

---

## 方法 3: 使用 macOS 预览应用 (手动)

### 生成 PNG:
1. 在 Finder 中双击打开 `icon.png.svg`
2. 文件 → 导出 → 格式选择 PNG → 分辨率 512x512
3. 另存为 `icon.png`

### 生成 ICNS:
```bash
# 创建多种尺寸的PNG文件夹结构
mkdir icon.iconset
# 手动使用预览应用导出不同尺寸，或运行：
sips -z 16 16 icon.png --out icon.iconset/icon_16x16.png
sips -z 32 32 icon.png --out icon.iconset/icon_16x16@2x.png
sips -z 32 32 icon.png --out icon.iconset/icon_32x32.png
sips -z 64 64 icon.png --out icon.iconset/icon_32x32@2x.png
sips -z 128 128 icon.png --out icon.iconset/icon_128x128.png
sips -z 256 256 icon.png --out icon.iconset/icon_128x128@2x.png
sips -z 256 256 icon.png --out icon.iconset/icon_256x256.png
sips -z 512 512 icon.png --out icon.iconset/icon_256x256@2x.png
sips -z 512 512 icon.png --out icon.iconset/icon_512x512.png
sips -z 1024 1024 icon.png --out icon.iconset/icon_512x512@2x.png

# 生成icns
iconutil -c icns icon.iconset -o icon.icns

# 清理
rm -rf icon.iconset
```

---

## 验证图标是否正确

生成后，在 AndroidTransferClient 目录应该有：

```
AndroidTransferClient/
├── icon.icns    ✅ macOS
├── icon.ico     ✅ Windows
└── icon.png     ✅ Linux
```

---

## 重新打包应用

```bash
cd /Users/liumingkang/Code/AndroidTransfer/AndroidTransferClient

# 打包 macOS 版本
npm run dist:mac

# 或打包所有平台
npm run dist
```

新图标将会在打包后的应用中显示。

---

## 我建议

**最快的方式**: 使用在线工具 https://cloudconvert.com 

1. 上传 `icon.png.svg`
2. 转换为 ICNS (macOS)
3. 转换为 ICO (Windows)  
4. 转换为 PNG 512x512 (Linux)
5. 下载并放入 AndroidTransferClient 目录
6. 运行 `npm run dist:mac` 重新打包

完成！🎉

