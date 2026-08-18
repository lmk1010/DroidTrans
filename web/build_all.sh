#!/bin/bash
# Android Transfer - 完整打包脚本（macOS/Linux）
# 一键完成所有打包流程：Python后端 + Electron应用

set -e  # 遇到错误立即退出

echo "========================================="
echo "🚀 Android Transfer 完整打包流程"
echo "========================================="

# 检查Node.js和npm
if ! command -v node &> /dev/null; then
    echo "❌ 错误: 未安装 Node.js"
    echo "   请访问 https://nodejs.org/ 下载安装"
    exit 1
fi

if ! command -v npm &> /dev/null; then
    echo "❌ 错误: 未安装 npm"
    exit 1
fi

# 检查Python
if ! command -v python3 &> /dev/null; then
    echo "❌ 错误: 未安装 Python 3"
    echo "   请访问 https://www.python.org/ 下载安装"
    exit 1
fi

echo ""
echo "✅ 环境检查通过"
echo "   Node: $(node --version)"
echo "   npm: $(npm --version)"
echo "   Python: $(python3 --version)"
echo ""

# 步骤1: 安装Python依赖
echo "========================================="
echo "📦 步骤 1/4: 安装 Python 依赖"
echo "========================================="
if [ ! -d "venv" ]; then
    echo "创建虚拟环境..."
    python3 -m venv venv
fi

echo "激活虚拟环境并安装依赖..."
source venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt
pip install pyinstaller

# 步骤2: 打包Python后端
echo ""
echo "========================================="
echo "📦 步骤 2/4: 打包 Python 后端"
echo "========================================="
chmod +x build_backend.sh
./build_backend.sh

# 退出虚拟环境
deactivate

# 步骤3: 安装Node.js依赖
echo ""
echo "========================================="
echo "📦 步骤 3/4: 安装 Node.js 依赖"
echo "========================================="
npm install

# 步骤4: 打包Electron应用
echo ""
echo "========================================="
echo "📦 步骤 4/4: 打包 Electron 应用"
echo "========================================="

# 检测操作系统
if [[ "$OSTYPE" == "darwin"* ]]; then
    echo "检测到 macOS，打包 .dmg 安装包..."
    npm run dist:mac
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    echo "检测到 Linux，打包 AppImage..."
    npm run dist:linux
else
    echo "未知操作系统: $OSTYPE"
    echo "请手动运行: npm run dist:mac 或 npm run dist:linux"
    exit 1
fi

echo ""
echo "========================================="
echo "🎉 打包完成！"
echo "========================================="
echo ""
echo "📦 安装包位置:"
echo "   dist/"
echo ""
echo "💡 安装包类型:"
if [[ "$OSTYPE" == "darwin"* ]]; then
    echo "   - .dmg (macOS 安装包)"
    echo "   - .zip (macOS 便携版)"
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    echo "   - .AppImage (Linux 通用格式)"
    echo "   - .deb (Debian/Ubuntu)"
fi
echo ""
echo "🚀 使用方法:"
echo "   1. 双击 .dmg/.AppImage 安装或运行"
echo "   2. 应用会自动启动 Python 后端"
echo "   3. 连接 Android 设备即可使用"
echo ""
echo "========================================="

