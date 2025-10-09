#!/bin/bash
# 调试模式启动 Electron 应用

echo "========================================="
echo "🐛 Electron 调试模式"
echo "========================================="
echo ""

echo "选择调试级别:"
echo ""
echo "1. 普通模式（正常运行）"
echo "2. 调试模式（基础日志）"
echo "3. 详细模式（详细日志）"
echo "4. 极详细模式（所有日志）"
echo ""
read -p "请选择 (1-4): " level

case $level in
    1)
        echo ""
        echo "🚀 启动普通模式..."
        npm start
        ;;
    2)
        echo ""
        echo "🐛 启动调试模式（基础日志）..."
        echo "   日志保存在: ~/Documents/AndroidTransfer/logs/"
        npm run start:debug
        ;;
    3)
        echo ""
        echo "🔍 启动详细模式（详细日志）..."
        echo "   日志保存在: ~/Documents/AndroidTransfer/logs/"
        echo "   同时输出 Chromium 日志"
        npm run start:verbose
        ;;
    4)
        echo ""
        echo "📊 启动极详细模式（所有日志）..."
        echo "   日志保存在: ~/Documents/AndroidTransfer/logs/"
        echo "   包含所有内部日志"
        ELECTRON_ENABLE_LOGGING=1 \
        ELECTRON_ENABLE_STACK_DUMPING=1 \
        electron . --enable-logging --v=3 --log-level=0 --remote-debugging-port=9222
        ;;
    *)
        echo "❌ 无效选项"
        exit 1
        ;;
esac

