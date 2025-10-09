#!/bin/bash
# 快速修复 - 使用 cnpm（不需要 sudo）

echo "========================================="
echo "🚀 使用 cnpm 快速修复（推荐方案）"
echo "========================================="
echo ""

# 检查是否已安装 cnpm
if command -v cnpm &> /dev/null; then
    echo "✅ cnpm 已安装"
else
    echo "📦 正在安装 cnpm..."
    npm install -g cnpm --registry=https://registry.npmmirror.com
    
    if [ $? -eq 0 ]; then
        echo "✅ cnpm 安装成功"
    else
        echo "❌ cnpm 安装失败"
        echo ""
        echo "💡 请手动运行："
        echo "   npm install -g cnpm --registry=https://registry.npmmirror.com"
        exit 1
    fi
fi

echo ""
echo "========================================="
echo "🧹 清理旧文件"
echo "========================================="
rm -rf node_modules package-lock.json
echo "✅ 清理完成"

echo ""
echo "========================================="
echo "📦 使用 cnpm 安装依赖"
echo "========================================="
echo "这会使用国内镜像，速度非常快..."
echo ""

cnpm install

if [ $? -eq 0 ]; then
    echo ""
    echo "========================================="
    echo "🎉 安装成功！"
    echo "========================================="
    echo ""
    echo "✅ 所有依赖安装完成"
    echo "✅ Electron 下载成功"
    echo ""
    echo "🚀 现在可以打包了！"
    echo "   运行: ./build_m1.sh"
    echo ""
else
    echo ""
    echo "❌ 安装失败"
    echo ""
    echo "请查看错误信息并重试"
fi

