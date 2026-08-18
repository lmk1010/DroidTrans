#!/bin/bash

# Android应用打包签名脚本（配置版）
# 使用方法: ./build_signed_apk_config.sh

set -e  # 遇到错误立即退出

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 打印带颜色的信息
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# ============================================
# 配置区域 - 请根据实际情况修改
# ============================================
KEYSTORE_FILE="keystore/release.keystore.jks"
KEYSTORE_PASSWORD="android123456"  # 请修改为实际密码
KEY_ALIAS="androidtransfer"                 # 请修改为实际别名
KEY_PASSWORD="android123456"            # 请修改为实际密钥密码
# ============================================

# 获取脚本所在目录的父目录（AndroidTransferApp目录）
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$SCRIPT_DIR"

print_info "当前工作目录: $SCRIPT_DIR"

# 检查keystore文件是否存在
KEYSTORE_PATH="$SCRIPT_DIR/$KEYSTORE_FILE"
if [ ! -f "$KEYSTORE_PATH" ]; then
    print_error "未找到keystore文件: $KEYSTORE_PATH"
    exit 1
fi

print_info "找到keystore文件: $KEYSTORE_PATH"

# 设置环境变量
export RELEASE_KEYSTORE_FILE="$KEYSTORE_FILE"
export RELEASE_KEYSTORE_PASSWORD="android123456"
export RELEASE_KEY_ALIAS="androidtransfer"
export RELEASE_KEY_PASSWORD="android123456"

print_info "开始清理项目..."
./gradlew clean

print_info "开始构建Release版本APK..."
./gradlew assembleRelease

# 检查构建是否成功
APK_PATH="$SCRIPT_DIR/app/build/outputs/apk/release/app-release.apk"
if [ -f "$APK_PATH" ]; then
    print_info "构建成功！"
    print_info "APK位置: $APK_PATH"
    
    # 获取APK信息
    APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
    print_info "APK大小: $APK_SIZE"
    
    # 验证签名
    print_info "验证APK签名..."
    if command -v apksigner &> /dev/null; then
        apksigner verify -v "$APK_PATH"
        print_info "APK签名验证通过！"
    fi
    
    # 创建输出目录并复制APK
    OUTPUT_DIR="$SCRIPT_DIR/release_apk"
    mkdir -p "$OUTPUT_DIR"
    
    # 生成带时间戳的文件名
    TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
    OUTPUT_APK="$OUTPUT_DIR/AndroidTransfer_v1.0_$TIMESTAMP.apk"
    cp "$APK_PATH" "$OUTPUT_APK"
    
    print_info "APK已复制到: $OUTPUT_APK"
    echo ""
    print_info "================================"
    print_info "构建完成！"
    print_info "================================"
    print_info "输出文件: $OUTPUT_APK"
    print_info "文件大小: $APK_SIZE"
    echo ""
else
    print_error "构建失败！未找到APK文件"
    exit 1
fi

