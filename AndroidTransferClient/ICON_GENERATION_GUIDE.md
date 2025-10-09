# Electron 图标生成指南

## 所需图标文件

根据 `package.json` 的配置，需要以下图标文件：

- **macOS**: `icon.icns` (包含多种分辨率：16x16 到 1024x1024)
- **Windows**: `icon.ico` (包含多种分辨率：16x16 到 256x256)
- **Linux**: `icon.png` (512x512 或 1024x1024)

## 方法 1: 使用在线工具（最简单）

### 步骤：

1. 将 `icon.png.svg` 文件上传到以下网站之一：
   - https://cloudconvert.com/svg-to-png (SVG转PNG，设置为1024x1024)
   - https://iconverticons.com/online/ (PNG转icns/ico)
   - https://anyconv.com/svg-to-icns-converter/

2. 生成各平台图标：
   - 先将 SVG 转为 1024x1024 的 PNG
   - 再将 PNG 转为 icns (Mac)
   - 再将 PNG 转为 ico (Windows)
   - Linux 直接使用 512x512 的 PNG 重命名为 icon.png

## 方法 2: 使用命令行工具

### macOS 生成 icns:

```bash
# 安装 imagemagick
brew install imagemagick

# 将SVG转为PNG
convert -background none -resize 1024x1024 icon.png.svg icon_1024.png

# 创建 iconset 文件夹
mkdir icon.iconset

# 生成各种尺寸
for size in 16 32 128 256 512; do
  convert icon_1024.png -resize ${size}x${size} icon.iconset/icon_${size}x${size}.png
  convert icon_1024.png -resize $((size*2))x$((size*2)) icon.iconset/icon_${size}x${size}@2x.png
done

# 生成 icns
iconutil -c icns icon.iconset -o icon.icns

# 清理
rm -rf icon.iconset icon_1024.png
```

### Windows 生成 ico:

```bash
# 使用 imagemagick
convert -background none icon.png.svg \
  -define icon:auto-resize=256,128,96,64,48,32,16 \
  icon.ico
```

### Linux PNG:

```bash
# 直接转换为512x512的PNG
convert -background none -resize 512x512 icon.png.svg icon.png
```

## 方法 3: 使用 electron-icon-builder

```bash
# 安装
npm install -g electron-icon-builder

# 生成所有平台图标（需要先有1024x1024的PNG）
electron-icon-builder --input=./icon_1024.png --output=./
```

## 验证图标

生成后，确保以下文件存在于 `AndroidTransferClient` 目录：

```
AndroidTransferClient/
├── icon.icns    (macOS)
├── icon.ico     (Windows)
└── icon.png     (Linux)
```

## 重新打包

图标文件就位后，重新构建应用：

```bash
# macOS
npm run dist:mac

# Windows  
npm run dist:win

# Linux
npm run dist:linux
```

## 快速脚本

创建并运行以下脚本 `generate_icons.sh`:

```bash
#!/bin/bash

echo "生成 Electron 应用图标..."

# 检查依赖
if ! command -v convert &> /dev/null; then
    echo "错误: 需要安装 ImageMagick"
    echo "运行: brew install imagemagick"
    exit 1
fi

# 生成1024x1024 PNG
echo "1. 生成基础PNG..."
convert -background none -resize 1024x1024 icon.png.svg temp_1024.png

# 生成 macOS icns
echo "2. 生成 macOS icns..."
mkdir -p icon.iconset
for size in 16 32 128 256 512; do
  convert temp_1024.png -resize ${size}x${size} icon.iconset/icon_${size}x${size}.png
  convert temp_1024.png -resize $((size*2))x$((size*2)) icon.iconset/icon_${size}x${size}@2x.png
done
iconutil -c icns icon.iconset -o icon.icns
rm -rf icon.iconset

# 生成 Windows ico
echo "3. 生成 Windows ico..."
convert temp_1024.png -define icon:auto-resize=256,128,96,64,48,32,16 icon.ico

# 生成 Linux png
echo "4. 生成 Linux png..."
convert -background none -resize 512x512 icon.png.svg icon.png

# 清理
rm temp_1024.png

echo "✅ 图标生成完成！"
echo "   - icon.icns (macOS)"
echo "   - icon.ico (Windows)"
echo "   - icon.png (Linux)"
```

使用方法：

```bash
chmod +x generate_icons.sh
./generate_icons.sh
```

