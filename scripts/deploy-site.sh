#!/bin/bash
# 构建官网并部署到服务器（droidtrans.mkstore.life）。
#
#   ./scripts/deploy-site.sh            # 构建 + 部署
#   ./scripts/deploy-site.sh --dry-run  # 只看会传什么，不动线上
#   SKIP_BUILD=1 ./scripts/deploy-site.sh
#
# 部署模式跟服务器上 openexam.cc 那套一致：
#   Cloudflare 终结 HTTPS → 回源本机 80 → neox-nginx 容器托管静态文件
# 复用已有的 nginx 容器，不额外起容器、不额外占内存，也不用自己签证书。
#
# 连接参数走环境变量，密码不进版本库。建议配好 ssh key 免密：
#   ssh-copy-id -p "$SITE_SSH_PORT" "$SITE_SSH_HOST"
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# 服务器地址不进版本库 —— 这个仓库是公开的，把 root@IP 和端口写进去
# 等于给全网发了一份爆破目标。和 upload-r2.sh 一样从家目录的凭据文件读。
DEPLOY_ENV="${DEPLOY_ENV_FILE:-$HOME/.neox-secrets/droidtrans-deploy.env}"
[[ -f "$DEPLOY_ENV" ]] && set -a && . "$DEPLOY_ENV" && set +a

: "${SITE_SSH_HOST:?缺少 SITE_SSH_HOST，写进 $DEPLOY_ENV}"

HOST="$SITE_SSH_HOST"
PORT="${SITE_SSH_PORT:-22}"
REMOTE_DIR="${SITE_REMOTE_DIR:-/opt/neox-nginx/html/droidtrans}"
NGINX_CONTAINER="${SITE_NGINX_CONTAINER:-neox-nginx}"
VHOST_SRC="$ROOT/deploy/droidtrans.mkstore.life.conf"
VHOST_DST="/opt/neox-nginx/conf.d/droidtrans.mkstore.life.conf"

DRY=""
[[ "${1:-}" == "--dry-run" ]] && DRY=1

SSH=(ssh -p "$PORT" -o StrictHostKeyChecking=no "$HOST")
RSYNC_RSH="ssh -p $PORT -o StrictHostKeyChecking=no"

if [[ -z "${SKIP_BUILD:-}" ]]; then
  command -v npm >/dev/null || { echo "需要 npm 才能构建官网"; exit 1; }
  [[ -d site/node_modules ]] || (cd site && npm install)
  (cd site && npm run build)
  echo
fi
[[ -f site/dist/index.html ]] || { echo "site/dist 里没有 index.html"; exit 1; }

if [[ -n "$DRY" ]]; then
  echo "【演练】会做这些事："
  echo "  1. 把 site/dist/ 同步到 $HOST:$REMOTE_DIR"
  rsync -avn --delete -e "$RSYNC_RSH" site/dist/ "$HOST:$REMOTE_DIR/" | sed 's/^/     /'
  echo "  2. 写入 vhost：$VHOST_DST"
  echo "  3. 在 $NGINX_CONTAINER 里 nginx -t，通过才 reload"
  exit 0
fi

echo "→ 同步静态文件"
"${SSH[@]}" "mkdir -p '$REMOTE_DIR'"
rsync -az --delete -e "$RSYNC_RSH" site/dist/ "$HOST:$REMOTE_DIR/"

echo "→ 写入 vhost"
"${SSH[@]}" "cat > '$VHOST_DST'" < "$VHOST_SRC"

# 配置错了 reload 会影响这台机器上所有站点，所以必须先测再重载
echo "→ 校验 nginx 配置"
if ! "${SSH[@]}" "docker exec $NGINX_CONTAINER nginx -t"; then
  echo "配置没通过校验，已回滚 vhost，线上不受影响" >&2
  "${SSH[@]}" "rm -f '$VHOST_DST'"
  exit 1
fi

echo "→ 重载 nginx"
"${SSH[@]}" "docker exec $NGINX_CONTAINER nginx -s reload"

echo "→ 自检（绕过 DNS，直接打本机）"
"${SSH[@]}" "for p in / /download.html /en/ /en/download.html /sitemap.xml /latest.json; do
               printf '  %-22s ' \"\$p\"
               curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: droidtrans.mkstore.life' \"http://127.0.0.1\$p\"
             done"

echo
echo "完成。DNS 生效后访问 https://droidtrans.mkstore.life/"
