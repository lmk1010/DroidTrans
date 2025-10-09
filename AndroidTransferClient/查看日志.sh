#!/bin/bash
# 查看 Electron 日志

LOG_DIR="$HOME/Documents/AndroidTransfer/logs"

echo "========================================="
echo "📋 Electron 日志查看工具"
echo "========================================="
echo ""

if [ ! -d "$LOG_DIR" ]; then
    echo "❌ 日志目录不存在: $LOG_DIR"
    echo "💡 请先运行应用生成日志"
    exit 1
fi

# 列出所有日志文件
echo "📁 日志目录: $LOG_DIR"
echo ""
echo "可用的日志文件:"
echo ""

ls -lht "$LOG_DIR" | tail -n +2 | nl

echo ""
echo "========================================="
echo "选择操作:"
echo "========================================="
echo ""
echo "1. 查看最新日志（实时）"
echo "2. 查看最新日志（完整）"
echo "3. 查看所有日志文件"
echo "4. 搜索错误信息"
echo "5. 搜索内存相关信息"
echo "6. 清理旧日志（保留最近5个）"
echo "7. 退出"
echo ""
read -p "请输入选项 (1-7): " choice

case $choice in
    1)
        echo ""
        echo "📊 实时查看最新日志 (Ctrl+C 退出):"
        echo "========================================="
        LATEST_LOG=$(ls -t "$LOG_DIR"/electron-*.log | head -1)
        tail -f "$LATEST_LOG"
        ;;
    2)
        echo ""
        echo "📄 查看最新日志（完整）:"
        echo "========================================="
        LATEST_LOG=$(ls -t "$LOG_DIR"/electron-*.log | head -1)
        cat "$LATEST_LOG"
        ;;
    3)
        echo ""
        echo "📚 查看所有日志文件:"
        echo "========================================="
        for log in "$LOG_DIR"/electron-*.log; do
            echo ""
            echo "文件: $(basename $log)"
            echo "----------------------------------------"
            head -20 "$log"
            echo "..."
            tail -20 "$log"
            echo ""
        done
        ;;
    4)
        echo ""
        echo "🔍 搜索错误信息:"
        echo "========================================="
        grep -i "error\|错误\|failed\|失败" "$LOG_DIR"/electron-*.log | tail -50
        ;;
    5)
        echo ""
        echo "💾 搜索内存相关信息:"
        echo "========================================="
        grep -i "memory\|内存\|oom\|leak" "$LOG_DIR"/electron-*.log | tail -50
        ;;
    6)
        echo ""
        echo "🧹 清理旧日志..."
        cd "$LOG_DIR"
        ls -t electron-*.log | tail -n +6 | xargs rm -f
        echo "✅ 已清理，保留最近5个日志文件"
        ls -lht electron-*.log
        ;;
    7)
        echo "👋 退出"
        exit 0
        ;;
    *)
        echo "❌ 无效选项"
        exit 1
        ;;
esac

echo ""
echo "========================================="
echo "✅ 完成"
echo "========================================="

