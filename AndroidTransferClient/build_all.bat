@echo off
REM Android Transfer - 完整打包脚本（Windows）
REM 一键完成所有打包流程：Python后端 + Electron应用

echo =========================================
echo 🚀 Android Transfer 完整打包流程
echo =========================================

REM 检查Node.js和npm
where node >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo ❌ 错误: 未安装 Node.js
    echo    请访问 https://nodejs.org/ 下载安装
    pause
    exit /b 1
)

where npm >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo ❌ 错误: 未安装 npm
    pause
    exit /b 1
)

REM 检查Python
where python >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo ❌ 错误: 未安装 Python 3
    echo    请访问 https://www.python.org/ 下载安装
    pause
    exit /b 1
)

echo.
echo ✅ 环境检查通过
node --version
npm --version
python --version
echo.

REM 步骤1: 安装Python依赖
echo =========================================
echo 📦 步骤 1/4: 安装 Python 依赖
echo =========================================
if not exist "venv" (
    echo 创建虚拟环境...
    python -m venv venv
)

echo 激活虚拟环境并安装依赖...
call venv\Scripts\activate.bat
python -m pip install --upgrade pip
pip install -r requirements.txt
pip install pyinstaller

REM 步骤2: 打包Python后端
echo.
echo =========================================
echo 📦 步骤 2/4: 打包 Python 后端
echo =========================================
call build_backend.bat

REM 退出虚拟环境
call venv\Scripts\deactivate.bat

REM 步骤3: 安装Node.js依赖
echo.
echo =========================================
echo 📦 步骤 3/4: 安装 Node.js 依赖
echo =========================================
call npm install

REM 步骤4: 打包Electron应用
echo.
echo =========================================
echo 📦 步骤 4/4: 打包 Electron 应用
echo =========================================
echo 打包 Windows 安装包...
call npm run dist:win

echo.
echo =========================================
echo 🎉 打包完成！
echo =========================================
echo.
echo 📦 安装包位置:
echo    dist\
echo.
echo 💡 安装包类型:
echo    - .exe (Windows 安装程序)
echo    - portable.exe (Windows 便携版)
echo.
echo 🚀 使用方法:
echo    1. 双击 .exe 安装或运行
echo    2. 应用会自动启动 Python 后端
echo    3. 连接 Android 设备即可使用
echo.
echo =========================================
pause

