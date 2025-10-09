#!/bin/bash
# 修复 npm 安装问题 - 配置国内镜像

echo "========================================="
echo "🔧 修复 npm/Electron 下载问题"
echo "========================================="
echo ""

echo "📋 当前问题："
echo "   Electron 下载失败（socket hang up）"
echo "   原因：国外服务器访问慢或超时"
echo ""

echo "🔧 解决方案："
echo "   配置国内镜像源（淘宝镜像）"
echo ""

# 清理缓存和旧文件
echo "========================================="
echo "步骤 1: 清理缓存"
echo "========================================="
echo "删除 node_modules..."
rm -rf node_modules

echo "删除 package-lock.json..."
rm -f package-lock.json

echo "清理 npm 缓存..."
npm cache clean --force

echo "✅ 清理完成"
echo ""

# 配置镜像
echo "========================================="
echo "步骤 2: 配置国内镜像"
echo "========================================="

echo "设置 npm 淘宝镜像..."
npm config set registry https://registry.npmmirror.com

echo "设置 Electron 镜像..."
npm config set electron_mirror https://npmmirror.com/mirrors/electron/

echo "设置 Electron Builder 镜像..."
npm config set electron_builder_binaries_mirror https://npmmirror.com/mirrors/electron-builder-binaries/

echo "✅ 镜像配置完成"
echo ""

# 显示当前配置
echo "========================================="
echo "当前 npm 配置："
echo "========================================="
npm config get registry
npm config get electron_mirror
echo ""

# 重新安装依赖
echo "========================================="
echo "步骤 3: 重新安装依赖"
echo "========================================="
echo "这可能需要 3-5 分钟，请耐心等待..."
echo ""

npm install

# 检查是否成功
if [ $? -eq 0 ]; then
    echo ""
    echo "========================================="
    echo "🎉 修复成功！"
    echo "========================================="
    echo ""
    echo "✅ npm 依赖安装完成"
    echo "✅ Electron 下载成功"
    echo ""
    echo "🚀 下一步："
    echo "   运行 ./build_m1.sh 继续打包"
    echo ""
else
    echo ""
    echo "========================================="
    echo "❌ 安装仍然失败"
    echo "========================================="
    echo ""
    echo "💡 尝试以下方案："
    echo ""
    echo "方案 1: 使用 cnpm（推荐）"
    echo "   npm install -g cnpm --registry=https://registry.npmmirror.com"
    echo "   cnpm install"
    echo ""
    echo "方案 2: 手动下载 Electron"
    echo "   查看详细说明: cat ELECTRON_DOWNLOAD_FIX.md"
    echo ""
    echo "方案 3: 使用代理"
    echo "   export https_proxy=http://127.0.0.1:7890"
    echo "   npm install"
    echo ""
fi

