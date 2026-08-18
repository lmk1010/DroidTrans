#!/bin/bash
# Android Transfer - Python后端打包脚本
# 使用PyInstaller将Flask应用打包成独立可执行文件

set -e  # 遇到错误立即退出

echo "=================================="
echo "🔨 Android Transfer 后端打包"
echo "=================================="

# 检查是否安装了pyinstaller
if ! command -v pyinstaller &> /dev/null; then
    echo "⚠️  PyInstaller 未安装，正在安装..."
    pip install pyinstaller
fi

# 清理旧的构建文件
echo ""
echo "🧹 清理旧的构建文件..."
rm -rf build dist dist_app

# 使用PyInstaller打包
echo ""
echo "📦 使用 PyInstaller 打包 Python 后端..."
pyinstaller app.spec --clean

# 将打包结果移动到 dist_app 目录（Electron Builder 会使用）
echo ""
echo "📁 移动打包结果到 dist_app 目录..."
mv dist/app dist_app

# 清理临时文件
echo ""
echo "🧹 清理临时文件..."
rm -rf build

echo ""
echo "=================================="
echo "✅ Python 后端打包完成！"
echo "   输出目录: dist_app/"
echo "=================================="
echo ""
echo "💡 提示："
echo "   - 可执行文件: dist_app/app"
echo "   - 包含所有依赖和资源文件"
echo "   - 可以独立运行，无需Python环境"
echo ""
echo "🚀 下一步："
echo "   运行 npm run dist:mac 或 npm run dist:win"
echo "   打包完整的桌面应用"
echo "=================================="

