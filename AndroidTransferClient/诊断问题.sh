#!/bin/bash
# 快速诊断 Electron 应用问题

echo "========================================="
echo "🔍 Android Transfer 问题诊断"
echo "========================================="
echo ""

# 问题 1: ADB 检查
echo "========================================="
echo "问题 1: ADB 设备检测"
echo "========================================="

if command -v adb &> /dev/null; then
    echo "✅ ADB 已安装"
    echo "   路径: $(which adb)"
    echo "   版本: $(adb version | head -1)"
    echo ""
    
    echo "检查连接的设备..."
    adb devices
    
    device_count=$(adb devices | grep -v "List" | grep "device$" | wc -l | xargs)
    if [ "$device_count" -gt 0 ]; then
        echo "✅ 找到 $device_count 个设备"
    else
        echo "⚠️  未找到设备"
        echo ""
        echo "💡 请检查："
        echo "   1. USB 线是否连接"
        echo "   2. 手机是否开启 USB 调试"
        echo "   3. 是否授权了电脑"
    fi
else
    echo "❌ ADB 未安装或不在 PATH 中"
    echo ""
    echo "💡 解决方案："
    echo "   brew install android-platform-tools"
fi

echo ""
echo "========================================="
echo "问题 2: 网络端口检查"
echo "========================================="

# 检查 9500 端口
if lsof -i :9500 &> /dev/null; then
    echo "✅ 端口 9500 正在监听"
    echo ""
    echo "详细信息:"
    lsof -i :9500
    echo ""
    
    # 检查绑定地址
    listen_addr=$(lsof -i :9500 -P -n | grep LISTEN | awk '{print $9}')
    if [[ "$listen_addr" == *"0.0.0.0"* ]]; then
        echo "✅ 绑定到 0.0.0.0（可以外部访问）"
    elif [[ "$listen_addr" == *"127.0.0.1"* ]]; then
        echo "⚠️  绑定到 127.0.0.1（只能本机访问）"
        echo ""
        echo "💡 需要修改 app.py："
        echo "   app.run(host='0.0.0.0', port=9500)"
    fi
else
    echo "❌ 端口 9500 未在监听"
    echo ""
    echo "💡 可能的原因："
    echo "   1. 应用未启动"
    echo "   2. Flask 启动失败"
    echo "   3. 端口被占用"
fi

echo ""
echo "========================================="
echo "问题 3: 防火墙检查"
echo "========================================="

# 检查防火墙状态
firewall_status=$(sudo /usr/libexec/ApplicationFirewall/socketfilterfw --getglobalstate 2>/dev/null || echo "unknown")

if [[ "$firewall_status" == *"enabled"* ]]; then
    echo "⚠️  防火墙已开启"
    echo ""
    echo "💡 解决方案："
    echo "   1. 系统偏好设置 → 安全性与隐私 → 防火墙"
    echo "   2. 点击'防火墙选项'"
    echo "   3. 添加 'Android Transfer' 应用"
    echo "   4. 允许传入连接"
else
    echo "✅ 防火墙已关闭或允许所有连接"
fi

echo ""
echo "========================================="
echo "问题 4: 网络信息"
echo "========================================="

# 获取本机 IP
LOCAL_IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "未找到")

echo "本机 IP 地址: $LOCAL_IP"
echo ""
echo "💡 WiFi 模式访问地址："
echo "   http://$LOCAL_IP:9500"
echo ""

# 测试本机访问
if curl -s http://127.0.0.1:9500 &> /dev/null; then
    echo "✅ 本机可以访问 (127.0.0.1:9500)"
else
    echo "❌ 本机无法访问 (127.0.0.1:9500)"
fi

# 测试局域网访问
if curl -s http://$LOCAL_IP:9500 &> /dev/null; then
    echo "✅ 局域网可以访问 ($LOCAL_IP:9500)"
else
    echo "⚠️  局域网无法访问 ($LOCAL_IP:9500)"
fi

echo ""
echo "========================================="
echo "问题 5: 应用权限检查"
echo "========================================="

APP_PATH="/Applications/Android Transfer.app"
if [ -d "$APP_PATH" ]; then
    echo "✅ 应用已安装: $APP_PATH"
    
    # 检查隔离属性
    if xattr "$APP_PATH" | grep -q "com.apple.quarantine"; then
        echo "⚠️  应用被隔离（可能影响功能）"
        echo ""
        echo "💡 移除隔离属性："
        echo "   sudo xattr -rd com.apple.quarantine '$APP_PATH'"
    else
        echo "✅ 应用未被隔离"
    fi
else
    echo "⚠️  应用未安装到 /Applications"
    echo "   当前可能在测试模式运行"
fi

echo ""
echo "========================================="
echo "📋 诊断总结"
echo "========================================="
echo ""
echo "请根据上面的检查结果，按照提示解决问题。"
echo ""
echo "💡 常见解决步骤："
echo ""
echo "1. 安装 ADB:"
echo "   brew install android-platform-tools"
echo ""
echo "2. 检查 app.py 绑定地址:"
echo "   确保是 host='0.0.0.0'"
echo ""
echo "3. 配置防火墙:"
echo "   系统偏好设置 → 安全性与隐私 → 防火墙选项"
echo "   添加应用并允许连接"
echo ""
echo "4. 移除应用隔离:"
echo "   sudo xattr -rd com.apple.quarantine '/Applications/Android Transfer.app'"
echo ""
echo "========================================="

