#!/bin/bash

echo "======================================"
echo "Android照片传输工具 - 启动脚本"
echo "======================================"
echo ""

# 检查Python是否安装
if ! command -v python3 &> /dev/null; then
    echo "❌ 错误: 未找到Python3，请先安装Python"
    exit 1
fi

# 检查ADB是否安装
if ! command -v adb &> /dev/null; then
    echo "❌ 错误: 未找到ADB工具，请先安装："
    echo "   macOS: brew install android-platform-tools"
    echo "   Ubuntu: sudo apt-get install android-tools-adb"
    exit 1
fi

echo "✅ Python3: $(python3 --version)"
echo "✅ ADB: $(adb --version | head -n 1)"
echo ""

# 创建虚拟环境（如果不存在）
if [ ! -d "venv" ]; then
    echo "📦 创建Python虚拟环境..."
    python3 -m venv venv
    echo ""
fi

# 激活虚拟环境
echo "🔧 激活虚拟环境..."
source venv/bin/activate

# 检查并安装依赖
if ! python -c "import flask" 2>/dev/null; then
    echo "📦 安装Python依赖..."
    pip install -r requirements.txt
    echo ""
fi

# 检查ADB设备连接
echo "🔍 检查设备连接..."
adb devices
echo ""

# 确保安装 Gunicorn
if ! python -c "import gunicorn" 2>/dev/null; then
    echo "📦 安装 Gunicorn..."
    pip install gunicorn >/dev/null
    echo ""
fi

# 启动服务（Gunicorn）
HOST="0.0.0.0"
PORT="9500"
WORKERS=${GUNICORN_WORKERS:-1}
THREADS=${GUNICORN_THREADS:-16}

echo "🚀 使用 Gunicorn 启动服务..."
echo "监听地址: http://$HOST:$PORT"
echo "Workers: $WORKERS  Threads: $THREADS"
echo "按 Ctrl+C 停止服务"
echo ""

exec gunicorn \
  --bind "$HOST:$PORT" \
  --workers "$WORKERS" \
  --worker-class gthread \
  --threads "$THREADS" \
  --timeout 120 \
  --preload \
  app:app
