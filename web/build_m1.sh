#!/bin/bash
# Android Transfer - M1/M2/M4 Mac 专用打包脚本
# 针对 Apple Silicon (arm64) 架构优化

set -e  # 遇到错误立即退出

echo "========================================="
echo "🍎 Android Transfer - M1 Mac 专用打包"
echo "========================================="

# 检查是否在 macOS 上运行
if [[ "$OSTYPE" != "darwin"* ]]; then
    echo "❌ 错误: 此脚本仅支持 macOS"
    exit 1
fi

# 检测芯片架构
ARCH=$(uname -m)
if [[ "$ARCH" != "arm64" ]]; then
    echo "⚠️  警告: 检测到非 ARM 架构 ($ARCH)"
    echo "   此脚本针对 M1/M2/M4 Mac 优化"
    read -p "   是否继续? (y/n) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

echo ""
echo "✅ 系统信息:"
echo "   OS: macOS"
echo "   架构: $ARCH"
echo "   芯片: Apple Silicon"
echo ""

# 检查必需工具
echo "========================================="
echo "🔍 检查环境依赖"
echo "========================================="

# 检查 Python3
if ! command -v python3 &> /dev/null; then
    echo "❌ 错误: 未安装 Python 3"
    echo "   请运行: brew install python3"
    exit 1
fi
echo "✅ Python: $(python3 --version)"

# 检查 Node.js
if ! command -v node &> /dev/null; then
    echo "❌ 错误: 未安装 Node.js"
    echo "   请运行: brew install node"
    exit 1
fi
echo "✅ Node.js: $(node --version)"

# 检查 npm
if ! command -v npm &> /dev/null; then
    echo "❌ 错误: 未安装 npm"
    exit 1
fi
echo "✅ npm: $(npm --version)"

echo ""
echo "========================================="
echo "📦 步骤 1/4: 创建 Python 虚拟环境"
echo "========================================="

# 创建或使用现有虚拟环境
if [ ! -d "venv" ]; then
    echo "创建新的虚拟环境..."
    python3 -m venv venv
else
    echo "使用现有虚拟环境..."
fi

# 激活虚拟环境
echo "激活虚拟环境..."
source venv/bin/activate

# 升级 pip
echo "升级 pip..."
pip install --upgrade pip -q

# 安装 Python 依赖
echo "安装 Python 依赖..."
pip install -r requirements.txt -q
echo "安装 PyInstaller..."
pip install pyinstaller -q

echo "✅ Python 环境配置完成"

echo ""
echo "========================================="
echo "📦 步骤 2/4: 打包 Python 后端 (arm64)"
echo "========================================="

# 清理旧的构建文件
echo "清理旧的构建文件..."
rm -rf build dist dist_app

# 使用 PyInstaller 打包（指定 arm64 架构）
echo "开始打包 Flask 后端..."
echo "目标架构: arm64 (Apple Silicon)"

pyinstaller app.spec --clean

# 检查打包是否成功
if [ ! -d "dist/app" ]; then
    echo "❌ 错误: PyInstaller 打包失败"
    exit 1
fi

# 移动到 dist_app 目录
echo "移动打包结果..."
mv dist/app dist_app

# 验证可执行文件架构
echo ""
echo "验证可执行文件架构:"
file dist_app/app | head -1

# 清理临时文件
echo "清理临时文件..."
rm -rf build

echo "✅ Python 后端打包完成"

# 退出虚拟环境
deactivate

echo ""
echo "========================================="
echo "📦 步骤 3/4: 安装 Node.js 依赖"
echo "========================================="

# 安装 Node.js 依赖
if [ ! -d "node_modules" ]; then
    echo "安装 Node.js 依赖..."
    npm install
else
    echo "Node.js 依赖已存在，跳过..."
fi

echo "✅ Node.js 依赖安装完成"

echo ""
echo "========================================="
echo "📦 步骤 4/4: 打包 Electron 应用 (arm64)"
echo "========================================="

echo "开始打包 Electron 应用..."
echo "目标平台: macOS"
echo "目标架构: arm64 (M1/M2/M4)"
echo ""

# 设置环境变量强制使用 arm64
export ARCH=arm64

# 打包 macOS arm64 版本
npm run dist:mac

# 检查打包结果
if [ ! -d "dist" ]; then
    echo "❌ 错误: Electron Builder 打包失败"
    exit 1
fi

echo ""
echo "========================================="
echo "🎉 打包完成！"
echo "========================================="
echo ""

# 显示生成的文件
echo "📦 生成的安装包:"
ls -lh dist/*.dmg dist/*.zip 2>/dev/null || echo "   未找到 .dmg 或 .zip 文件"
echo ""

# 显示文件详细信息
if ls dist/*.dmg 1> /dev/null 2>&1; then
    echo "🔍 安装包信息:"
    for file in dist/*.dmg; do
        echo "   文件: $(basename "$file")"
        echo "   大小: $(du -h "$file" | cut -f1)"
        echo "   架构: arm64 (Apple Silicon)"
        echo ""
    done
fi

echo "========================================="
echo "✅ M1 Mac 打包成功！"
echo "========================================="
echo ""
echo "📋 安装包位置:"
echo "   dist/"
echo ""
echo "💡 文件说明:"
echo "   • Android Transfer-1.0.0-arm64.dmg - M1/M2/M4 安装包"
echo "   • Android Transfer-1.0.0-arm64.zip - M1/M2/M4 便携版"
echo ""
echo "🚀 安装方法:"
echo "   1. 打开 .dmg 文件"
echo "   2. 拖动应用到 Applications 文件夹"
echo "   3. 双击运行"
echo ""
echo "⚠️  首次运行提示:"
echo "   如遇安全警告，请执行:"
echo "   sudo xattr -rd com.apple.quarantine /Applications/Android\\ Transfer.app"
echo ""
echo "========================================="
echo "🎊 打包完成！祝使用愉快！"
echo "========================================="

