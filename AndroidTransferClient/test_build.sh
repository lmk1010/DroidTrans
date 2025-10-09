#!/bin/bash
# 快速测试打包环境是否就绪

echo "========================================="
echo "🧪 Android Transfer - 打包环境测试"
echo "========================================="
echo ""

# 测试结果数组
TESTS_PASSED=0
TESTS_FAILED=0

# 测试函数
test_command() {
    local name=$1
    local command=$2
    
    if command -v $command &> /dev/null; then
        local version=$($command --version 2>&1 | head -n1)
        echo "✅ $name: $version"
        TESTS_PASSED=$((TESTS_PASSED + 1))
        return 0
    else
        echo "❌ $name: 未安装"
        TESTS_FAILED=$((TESTS_FAILED + 1))
        return 1
    fi
}

# 检查操作系统
echo "📋 系统信息:"
echo "   OS: $OSTYPE"
echo "   架构: $(uname -m)"
if [[ "$OSTYPE" == "darwin"* ]]; then
    echo "   系统: macOS $(sw_vers -productVersion)"
fi
echo ""

# 检查必需工具
echo "🔍 检查工具依赖:"
test_command "Python 3" python3
test_command "Node.js" node
test_command "npm" npm

echo ""

# 检查 Python 模块
echo "🐍 检查 Python 模块:"
source venv/bin/activate 2>/dev/null || true

if python3 -c "import flask" 2>/dev/null; then
    echo "✅ Flask: 已安装"
    TESTS_PASSED=$((TESTS_PASSED + 1))
else
    echo "❌ Flask: 未安装"
    TESTS_FAILED=$((TESTS_FAILED + 1))
fi

if python3 -c "import PyInstaller" 2>/dev/null; then
    echo "✅ PyInstaller: 已安装"
    TESTS_PASSED=$((TESTS_PASSED + 1))
else
    echo "⚠️  PyInstaller: 未安装（将自动安装）"
fi

deactivate 2>/dev/null || true

echo ""

# 检查 Node.js 模块
echo "📦 检查 Node.js 模块:"
if [ -d "node_modules/electron" ]; then
    echo "✅ Electron: 已安装"
    TESTS_PASSED=$((TESTS_PASSED + 1))
else
    echo "⚠️  Electron: 未安装（将自动安装）"
fi

if [ -d "node_modules/electron-builder" ]; then
    echo "✅ Electron Builder: 已安装"
    TESTS_PASSED=$((TESTS_PASSED + 1))
else
    echo "⚠️  Electron Builder: 未安装（将自动安装）"
fi

echo ""

# 检查项目文件
echo "📁 检查项目文件:"
files_to_check=(
    "app.py"
    "electron_main.js"
    "package.json"
    "requirements.txt"
    "app.spec"
    "templates/index.html"
)

for file in "${files_to_check[@]}"; do
    if [ -f "$file" ]; then
        echo "✅ $file"
        TESTS_PASSED=$((TESTS_PASSED + 1))
    else
        echo "❌ $file: 缺失"
        TESTS_FAILED=$((TESTS_FAILED + 1))
    fi
done

echo ""
echo "========================================="
echo "📊 测试结果:"
echo "   通过: $TESTS_PASSED"
echo "   失败: $TESTS_FAILED"
echo "========================================="
echo ""

if [ $TESTS_FAILED -eq 0 ]; then
    echo "🎉 环境检查通过！可以开始打包"
    echo ""
    echo "💡 运行以下命令开始打包:"
    echo "   ./build_m1.sh        # M1/M2/M4 Mac 专用"
    echo "   ./build_all.sh       # 通用打包"
    exit 0
else
    echo "⚠️  环境存在问题，请先解决以上错误"
    echo ""
    echo "💡 常见解决方案:"
    echo "   Python 依赖: pip install -r requirements.txt"
    echo "   Node.js 依赖: npm install"
    exit 1
fi

