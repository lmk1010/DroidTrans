#!/bin/bash
# Android Transfer - macOS 自动安装脚本
# 此脚本会自动移除 Gatekeeper 隔离属性

set -e

APP_NAME="Android Transfer.app"
APP_PATH="/Applications/$APP_NAME"

echo "=========================================="
echo "Android Transfer - macOS 安装脚本"
echo "=========================================="
echo ""

# 检查应用是否存在
if [ ! -d "$APP_PATH" ]; then
    echo "❌ 错误: 未找到应用"
    echo "   请确保已将 $APP_NAME 拖动到 Applications 文件夹"
    echo ""
    echo "安装步骤："
    echo "1. 打开 DMG 文件"
    echo "2. 将 Android Transfer 拖动到 Applications 文件夹"
    echo "3. 运行此脚本"
    exit 1
fi

echo "✅ 找到应用: $APP_PATH"
echo ""

# 检查是否有 sudo 权限
echo "正在移除 Gatekeeper 隔离属性..."
echo "（可能需要输入管理员密码）"
echo ""

# 移除隔离属性
sudo xattr -rd com.apple.quarantine "$APP_PATH" 2>/dev/null || true
sudo xattr -cr "$APP_PATH" 2>/dev/null || true

# 赋予执行权限
sudo chmod -R 755 "$APP_PATH" 2>/dev/null || true

echo ""
echo "=========================================="
echo "✅ 安装完成！"
echo "=========================================="
echo ""
echo "🚀 现在可以从以下位置启动应用："
echo "   • Launchpad"
echo "   • Applications 文件夹"
echo "   • Spotlight 搜索"
echo ""
echo "💡 提示："
echo "   首次运行时，如仍遇到安全提示："
echo "   1. 打开 系统偏好设置 > 隐私与安全性"
echo "   2. 在底部找到被阻止的应用"
echo "   3. 点击 \"仍要打开\""
echo ""
echo "=========================================="
