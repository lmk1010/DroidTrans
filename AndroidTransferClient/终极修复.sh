#!/bin/bash
# 终极修复方案 - 完全不需要 sudo 权限

echo "========================================="
echo "🎯 终极修复方案（无需权限）"
echo "========================================="
echo ""

echo "💡 方案说明："
echo "   直接使用项目中的 .npmrc 配置"
echo "   不安装全局工具，完全本地化"
echo ""

# 清理
echo "========================================="
echo "步骤 1: 清理旧文件"
echo "========================================="
rm -rf node_modules package-lock.json
echo "✅ 清理完成"

echo ""
echo "========================================="
echo "步骤 2: 配置环境变量"
echo "========================================="

# 设置环境变量（不修改全局配置）
export ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/
export ELECTRON_BUILDER_BINARIES_MIRROR=https://npmmirror.com/mirrors/electron-builder-binaries/
export npm_config_registry=https://registry.npmmirror.com

echo "✅ Electron 镜像: $ELECTRON_MIRROR"
echo "✅ Builder 镜像: $ELECTRON_BUILDER_BINARIES_MIRROR"
echo "✅ npm 镜像: $npm_config_registry"

echo ""
echo "========================================="
echo "步骤 3: 安装依赖（使用国内镜像）"
echo "========================================="
echo "请耐心等待 3-5 分钟..."
echo ""

# 使用环境变量安装
npm install --registry=https://registry.npmmirror.com

if [ $? -eq 0 ]; then
    echo ""
    echo "========================================="
    echo "🎉 安装成功！"
    echo "========================================="
    echo ""
    echo "✅ 所有依赖已安装"
    echo "✅ Electron 下载完成"
    echo ""
    echo "📦 已安装的关键包："
    ls node_modules | grep -E "electron|electron-builder" | head -5
    echo ""
    echo "🚀 现在可以打包了！"
    echo ""
    echo "运行以下命令开始打包："
    echo "   ./build_m1.sh"
    echo ""
    echo "或者分步打包："
    echo "   1. ./build_backend.sh    # 打包 Python 后端"
    echo "   2. npm run dist:mac       # 打包 Electron 应用"
    echo ""
else
    echo ""
    echo "========================================="
    echo "❌ 安装失败"
    echo "========================================="
    echo ""
    echo "💡 最后的办法："
    echo ""
    echo "方法 1: 清理全部缓存"
    echo "   rm -rf ~/.npm"
    echo "   ./终极修复.sh"
    echo ""
    echo "方法 2: 使用另一个终端"
    echo "   关闭所有终端"
    echo "   重新打开终端"
    echo "   cd $PWD"
    echo "   ./终极修复.sh"
    echo ""
    echo "方法 3: 联系支持"
    echo "   查看完整日志："
    echo "   cat ~/.npm/_logs/*-debug-0.log | tail -50"
    echo ""
fi

