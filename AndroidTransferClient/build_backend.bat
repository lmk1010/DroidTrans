@echo off
REM Android Transfer - Python后端打包脚本 (Windows)
REM 使用PyInstaller将Flask应用打包成独立可执行文件

echo ==================================
echo 🔨 Android Transfer 后端打包
echo ==================================

REM 检查是否安装了pyinstaller
pyinstaller --version >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo ⚠️  PyInstaller 未安装，正在安装...
    pip install pyinstaller
)

REM 清理旧的构建文件
echo.
echo 🧹 清理旧的构建文件...
if exist build rmdir /s /q build
if exist dist rmdir /s /q dist
if exist dist_app rmdir /s /q dist_app

REM 使用PyInstaller打包
echo.
echo 📦 使用 PyInstaller 打包 Python 后端...
pyinstaller app.spec --clean

REM 将打包结果移动到 dist_app 目录（Electron Builder 会使用）
echo.
echo 📁 移动打包结果到 dist_app 目录...
move dist\app dist_app

REM 清理临时文件
echo.
echo 🧹 清理临时文件...
if exist build rmdir /s /q build

echo.
echo ==================================
echo ✅ Python 后端打包完成！
echo    输出目录: dist_app\
echo ==================================
echo.
echo 💡 提示：
echo    - 可执行文件: dist_app\app.exe
echo    - 包含所有依赖和资源文件
echo    - 可以独立运行，无需Python环境
echo.
echo 🚀 下一步：
echo    运行 npm run dist:win
echo    打包完整的桌面应用
echo ==================================
pause

