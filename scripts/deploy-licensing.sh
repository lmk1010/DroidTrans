#!/bin/bash
# 把授权服务部署到服务器。
#
#   ./scripts/deploy-licensing.sh
#
# 服务器跑的是 Node 18，而源码用了 TS 和 import.meta.dirname（Node 20.11+），
# 所以本地打包成一个不依赖任何东西的 .mjs 再传上去 ——
# 不在生产机上装 Node 22，也不用在那边跑 npm install。
#
# 连接参数走环境变量，密钥不进版本库。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# 服务器地址不进版本库 —— 这个仓库是公开的，把 root@IP 和端口写进去
# 等于给全网发了一份爆破目标。和 upload-r2.sh 一样从家目录的凭据文件读。
DEPLOY_ENV="${DEPLOY_ENV_FILE:-$HOME/.neox-secrets/droidtrans-deploy.env}"
[[ -f "$DEPLOY_ENV" ]] && set -a && . "$DEPLOY_ENV" && set +a

: "${SITE_SSH_HOST:?缺少 SITE_SSH_HOST。写进 $DEPLOY_ENV，例如：
  SITE_SSH_HOST=root@1.2.3.4
  SITE_SSH_PORT=22}"

HOST="$SITE_SSH_HOST"
PORT="${SITE_SSH_PORT:-22}"
REMOTE="${LICENSING_REMOTE_DIR:-/opt/droidtrans-licensing}"

SSH=(ssh -p "$PORT" -o StrictHostKeyChecking=no "$HOST")
RSH="ssh -p $PORT -o StrictHostKeyChecking=no"

[[ -f licensing/.env ]] || { echo "缺少 licensing/.env"; exit 1; }

echo "→ 打包"
(cd licensing && npm run build >/dev/null)

echo "→ 传送"
"${SSH[@]}" "mkdir -p '$REMOTE/dist' '$REMOTE/data' '$REMOTE/certs'"
rsync -az -e "$RSH" licensing/dist/server.mjs "$HOST:$REMOTE/dist/"
# Apple 的根证书。校验内购凭证时要读它，少了这个 /api/apple/redeem 一律 500
rsync -az -e "$RSH" licensing/certs/ "$HOST:$REMOTE/certs/"
# .env 里有两把私钥，权限必须锁死
rsync -az --chmod=600 -e "$RSH" licensing/.env "$HOST:$REMOTE/.env"

echo "→ 安装 systemd 单元"
"${SSH[@]}" "cat > /etc/systemd/system/droidtrans-licensing.service" <<UNIT
[Unit]
Description=DroidTrans licensing service
After=network.target

[Service]
Type=simple
WorkingDirectory=$REMOTE
ExecStart=/usr/bin/node $REMOTE/dist/server.mjs
Restart=always
RestartSec=3
# 公网入口统一走 nginx。这里必须监听 0.0.0.0：nginx 跑在容器里，
# 容器内的 127.0.0.1 指向它自己，够不到宿主机的回环。
Environment=PORT=8791
# nginx 在容器里，够不到宿主机的回环地址，必须监听所有网卡
Environment=HOST=0.0.0.0
Environment=NODE_ENV=production
StandardOutput=append:$REMOTE/service.log
StandardError=append:$REMOTE/service.log

[Install]
WantedBy=multi-user.target
UNIT

# 必须显式 restart：enable --now 对已经在跑的服务是空操作，
# 单元文件改了也不会生效，代码更是照旧跑老的。
"${SSH[@]}" "systemctl daemon-reload && systemctl enable droidtrans-licensing >/dev/null && systemctl restart droidtrans-licensing && sleep 2 && systemctl is-active droidtrans-licensing"

echo "→ 自检"
"${SSH[@]}" "curl -s --max-time 8 http://127.0.0.1:8791/api/health || echo '（起不来，看 $REMOTE/service.log）'"
echo
