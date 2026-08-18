#!/bin/bash

# 快速生成Android应用签名密钥库脚本（自动化版本）
# 使用方法: ./generate_keystore_auto.sh [密码] [别名]

set -e  # 遇到错误立即退出

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 打印带颜色的信息
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_title() {
    echo -e "${BLUE}================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}================================${NC}"
}

# 获取脚本所在目录的父目录（AndroidTransferApp目录）
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$SCRIPT_DIR"

print_title "Android应用密钥库快速生成工具"

# 默认配置
DEFAULT_PASSWORD="${1:-android123456}"
DEFAULT_ALIAS="${2:-androidtransfer}"
KEYSTORE_DIR="$SCRIPT_DIR/keystore"
KEYSTORE_FILE="$KEYSTORE_DIR/release.keystore.jks"

# 创建keystore目录
mkdir -p "$KEYSTORE_DIR"

# 检查是否已存在密钥库
if [ -f "$KEYSTORE_FILE" ]; then
    print_warning "发现已存在的密钥库文件"
    
    # 备份旧密钥库
    BACKUP_FILE="$KEYSTORE_DIR/release.keystore.jks.backup_$(date +%Y%m%d_%H%M%S)"
    print_info "备份旧密钥库到: $BACKUP_FILE"
    mv "$KEYSTORE_FILE" "$BACKUP_FILE"
    
    print_warning "⚠️  注意: 使用新密钥库签名的应用将无法更新使用旧密钥库签名的应用！"
    echo ""
fi

# 使用默认配置
KEYSTORE_PASSWORD="$DEFAULT_PASSWORD"
KEY_ALIAS="$DEFAULT_ALIAS"
KEY_PASSWORD="$DEFAULT_PASSWORD"

# 证书信息
CERT_NAME="AndroidTransfer Developer"
CERT_OU="Development"
CERT_O="AndroidTransfer"
CERT_L="Beijing"
CERT_ST="Beijing"
CERT_C="CN"
VALIDITY=10000

# 构建DN字符串
DNAME="CN=$CERT_NAME, OU=$CERT_OU, O=$CERT_O, L=$CERT_L, ST=$CERT_ST, C=$CERT_C"

print_info "使用以下配置生成密钥库:"
echo "  密钥库密码: $KEYSTORE_PASSWORD"
echo "  密钥别名: $KEY_ALIAS"
echo "  证书主体: $DNAME"
echo ""

print_info "正在生成密钥库..."

# 生成密钥库
keytool -genkeypair \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity $VALIDITY \
    -keystore "$KEYSTORE_FILE" \
    -storepass "$KEYSTORE_PASSWORD" \
    -keypass "$KEY_PASSWORD" \
    -dname "$DNAME" \
    -storetype JKS

if [ $? -eq 0 ]; then
    echo ""
    print_info "✅ 密钥库生成成功！"
    echo ""
    
    # 显示密钥库信息
    print_title "密钥库信息"
    keytool -list -v -keystore "$KEYSTORE_FILE" -storepass "$KEYSTORE_PASSWORD"
    
    echo ""
    print_title "配置信息汇总"
    echo -e "${GREEN}密钥库文件:${NC} keystore/release.keystore.jks"
    echo -e "${GREEN}密钥库密码:${NC} $KEYSTORE_PASSWORD"
    echo -e "${GREEN}密钥别名:${NC} $KEY_ALIAS"
    echo -e "${GREEN}密钥密码:${NC} $KEY_PASSWORD"
    echo ""
    
    # 将配置信息保存到文件
    CONFIG_FILE="$KEYSTORE_DIR/keystore_info.txt"
    cat > "$CONFIG_FILE" << EOF
# Android应用签名密钥库配置信息
# 生成时间: $(date)
# 
# ⚠️ 重要: 请妥善保管此文件，不要提交到Git仓库！

密钥库文件路径: keystore/release.keystore.jks
密钥库密码: $KEYSTORE_PASSWORD
密钥别名: $KEY_ALIAS
密钥密码: $KEY_PASSWORD

# 证书信息
姓名(CN): $CERT_NAME
组织单位(OU): $CERT_OU
组织名称(O): $CERT_O
城市(L): $CERT_L
省份(ST): $CERT_ST
国家(C): $CERT_C
有效期: $VALIDITY 天

# 使用方法:
# 1. 在打包脚本中使用上述配置信息
# 2. 或设置环境变量:
#    export RELEASE_KEYSTORE_FILE="keystore/release.keystore.jks"
#    export RELEASE_KEYSTORE_PASSWORD="$KEYSTORE_PASSWORD"
#    export RELEASE_KEY_ALIAS="$KEY_ALIAS"
#    export RELEASE_KEY_PASSWORD="$KEY_PASSWORD"
EOF
    
    print_info "配置信息已保存到: $CONFIG_FILE"
    
    # 创建.gitignore确保不提交密码文件和密钥库
    GITIGNORE_FILE="$KEYSTORE_DIR/.gitignore"
    cat > "$GITIGNORE_FILE" << EOF
# 忽略密钥库文件和密码信息
*.jks
keystore_info.txt
*.jks.backup_*
EOF
    print_info "已创建.gitignore文件保护密钥信息"
    
    # 同时更新build_signed_apk_config.sh中的密码
    CONFIG_SCRIPT="$SCRIPT_DIR/app/build_signed_apk_config.sh"
    if [ -f "$CONFIG_SCRIPT" ]; then
        print_info "正在更新打包脚本中的密码配置..."
        
        # 使用sed更新配置
        sed -i.bak "s/KEYSTORE_PASSWORD=\".*\"/KEYSTORE_PASSWORD=\"$KEYSTORE_PASSWORD\"/" "$CONFIG_SCRIPT"
        sed -i.bak "s/KEY_ALIAS=\".*\"/KEY_ALIAS=\"$KEY_ALIAS\"/" "$CONFIG_SCRIPT"
        sed -i.bak "s/KEY_PASSWORD=\".*\"/KEY_PASSWORD=\"$KEY_PASSWORD\"/" "$CONFIG_SCRIPT"
        rm -f "$CONFIG_SCRIPT.bak"
        
        print_info "打包脚本配置已更新"
    fi
    
    echo ""
    print_title "生成完成！"
    print_warning "⚠️  重要提示:"
    echo "  1. 请妥善保管密码信息，不要泄露或丢失"
    echo "  2. 密码信息保存在: $CONFIG_FILE"
    echo "  3. 密钥库文件: $KEYSTORE_FILE"
    echo ""
    print_info "现在可以使用以下命令打包签名应用:"
    echo "  cd app"
    echo "  ./build_signed_apk_config.sh  (无需输入密码)"
    echo "  或"
    echo "  ./build_signed_apk.sh         (需要手动输入密码)"
    echo ""
    
else
    print_error "密钥库生成失败！"
    exit 1
fi

