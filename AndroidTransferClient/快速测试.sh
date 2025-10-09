#!/bin/bash
# 快速测试修改后的应用

echo "========================================="
echo "🧪 快速测试修复"
echo "========================================="
echo ""

echo "📋 测试内容:"
echo "   1. ADB 路径配置"
echo "   2. 输出目录自动切换"
echo "   3. WiFi 网络访问"
echo ""

echo "========================================="
echo "方式 1: 开发模式测试（推荐）"
echo "========================================="
echo ""
echo "运行命令:"
echo "   npm start"
echo ""
echo "测试项目:"
echo "   ✅ USB 模式能否识别设备"
echo "   ✅ WiFi 模式能否访问"
echo "   ✅ 上传目录是否正确 (应该在 ./photos_output)"
echo ""

echo "========================================="
echo "方式 2: 打包后测试"
echo "========================================="
echo ""
echo "1. 打包应用:"
echo "   ./build_m1.sh"
echo ""
echo "2. 安装并运行"
echo ""
echo "3. 测试项目:"
echo "   ✅ USB 模式能否识别设备"
echo "   ✅ WiFi 模式能否从手机访问"
echo "   ✅ 上传目录是否在 ~/Documents/AndroidTransfer/"
echo ""

echo "========================================="
echo "验证输出目录"
echo "========================================="
echo ""
echo "开发模式应该看到:"
echo "   ls ./photos_output"
echo ""

if [ -d "./photos_output" ]; then
    echo "✅ 开发目录存在"
    ls -la ./photos_output | head -10
else
    echo "⚠️  开发目录不存在（正常，首次运行会创建）"
fi

echo ""
echo "打包后应该看到:"
echo "   ls ~/Documents/AndroidTransfer/"
echo ""

if [ -d "$HOME/Documents/AndroidTransfer" ]; then
    echo "✅ 用户文档目录存在"
    ls -la ~/Documents/AndroidTransfer/ | head -10
else
    echo "⚠️  用户文档目录不存在（正常，首次运行会创建）"
fi

echo ""
echo "========================================="
echo "🚀 现在开始测试"
echo "========================================="
echo ""
echo "运行以下命令测试:"
echo ""
echo "开发模式:"
echo "   npm start"
echo ""
echo "或者打包:"
echo "   ./build_m1.sh"
echo ""

