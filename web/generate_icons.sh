#!/bin/bash

echo "🎨 生成 Electron 应用图标..."

cd "$(dirname "$0")"

# 检查依赖
if ! command -v convert &> /dev/null; then
    echo "❌ 错误: 需要安装 ImageMagick"
    echo "📦 安装命令: brew install imagemagick"
    exit 1
fi

# 生成1024x1024 PNG
echo "1️⃣  生成基础PNG (1024x1024)..."
convert -background none -resize 1024x1024 icon.png.svg temp_1024.png

# 生成 macOS icns
echo "2️⃣  生成 macOS icns..."
mkdir -p icon.iconset
for size in 16 32 128 256 512; do
  convert temp_1024.png -resize ${size}x${size} icon.iconset/icon_${size}x${size}.png
  convert temp_1024.png -resize $((size*2))x$((size*2)) icon.iconset/icon_${size}x${size}@2x.png
done
iconutil -c icns icon.iconset -o icon.icns
rm -rf icon.iconset

# 生成 Windows ico
echo "3️⃣  生成 Windows ico..."
convert temp_1024.png -define icon:auto-resize=256,128,96,64,48,32,16 icon.ico

# 生成 Linux png
echo "4️⃣  生成 Linux png (512x512)..."
convert -background none -resize 512x512 icon.png.svg icon.png

# 清理
rm temp_1024.png

echo ""
echo "✅ 图标生成完成！"
echo ""
echo "生成的文件："
echo "   📱 icon.icns   - macOS 图标"
echo "   🪟 icon.ico    - Windows 图标"
echo "   🐧 icon.png    - Linux 图标"
echo ""
echo "下一步："
echo "   运行 'npm run dist' 重新打包应用"

