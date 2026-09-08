#!/usr/bin/env bash
# 把当前 VERSION 的桌面 DMG + Android APK 上传到 mkstore droidtrans 桶，并写 latest.json。
# 凭据：~/.neox-secrets/droidtrans-r2.env 或仓库 .env
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
VERSION="$(tr -d '[:space:]' < VERSION | sed 's/^v//')"
ENV_FILE="${R2_ENV_FILE:-$HOME/.neox-secrets/droidtrans-r2.env}"
[[ -f "$ENV_FILE" ]] || ENV_FILE="$ROOT/.env"
# shellcheck disable=SC1090
set -a; source "$ENV_FILE"; set +a
: "${R2_ENDPOINT:?}"; : "${R2_ACCESS_KEY_ID:?}"; : "${R2_SECRET_ACCESS_KEY:?}"
: "${R2_BUCKET:=droidtrans}"
: "${R2_PUBLIC_BASE:=https://droid.mkstore.life}"
R2_PUBLIC_BASE="${R2_PUBLIC_BASE%/}"

VENV="${R2_VENV:-/tmp/droidtrans-r2-venv}"
if [[ ! -x "$VENV/bin/python" ]]; then
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install -q boto3
fi

DMG="$ROOT/dist/DroidTrans-${VERSION}-macos-arm64.dmg"

# 挑 APK 必须验签，不能只按时间取最新的那个。
#
# gradle 在没配签名时会在 app/build/outputs/apk/release/ 留一个
# app-release-unsigned.apk，而它恰好比手工归档的正式包新——按时间取就正好取到它，
# 一路传上去覆盖 latest.apk，所有安卓用户的更新从此装不上，而且这边毫无提示。
# 挑 APK 必须验签，不能只按时间取最新的那个。
#
# gradle 在没配签名时会在 app/build/outputs/apk/release/ 留一个
# app-release-unsigned.apk，而它恰好比手工归档的正式包新——按时间取就正好取到它，
# 一路传上去覆盖 latest.apk，所有安卓用户的更新从此装不上，而且这边毫无提示。
#
# 整段写在这里而不是包成函数：函数得用 $(...) 取返回值，那是个子 shell，
# 里面对「验没验过」这个标记的赋值传不回来，外面永远看到「没验过」。
APKSIGNER="$(ls "$HOME"/Library/Android/sdk/build-tools/*/apksigner 2>/dev/null | tail -1 || true)"
APK_SRC=""
APK_VERIFIED=0
for f in $(ls -t "$ROOT"/android/app/build/outputs/apk/release/*.apk \
                 "$ROOT"/android/release_apk/*.apk 2>/dev/null); do
  if [[ -n "$APKSIGNER" ]]; then
    if "$APKSIGNER" verify "$f" >/dev/null 2>&1; then
      APK_SRC="$f"; APK_VERIFIED=1; break
    fi
  elif [[ "$f" != *unsigned* ]]; then
    APK_SRC="$f"; break
  fi
done

[[ -f "$DMG" ]] || { echo "missing $DMG — run desktop/build.sh first"; exit 1; }
[[ -n "$APK_SRC" && -f "$APK_SRC" ]] || {
  echo "没有找到签名有效的 release APK。" >&2
  echo "  未签名的包不能上线：装不上、也覆盖不了已装的版本。" >&2
  echo "  正式包由 CI 用 RELEASE_KEYSTORE_* 打，或把签好的包放进 android/release_apk/。" >&2
  exit 1
}
# 说清楚到底验没验。都走到发版这一步了，一句含糊的「已验过」比不说更坏。
if [[ "$APK_VERIFIED" == 1 ]]; then
  echo "apk  $(basename "$APK_SRC")  （签名已验过）"
else
  echo "apk  $(basename "$APK_SRC")" >&2
  echo "warn: 找不到 apksigner，只按文件名判断，没有真正验签" >&2
fi

APK_VER="$ROOT/dist/DroidTrans-${VERSION}.apk"
APK_LATEST="$ROOT/dist/DroidTrans-latest.apk"
cp -f "$APK_SRC" "$APK_VER"
cp -f "$APK_SRC" "$APK_LATEST"

NOTES="${RELEASE_NOTES:-手机端布局对齐与间距优化}"
export VERSION R2_ENDPOINT R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY R2_BUCKET R2_PUBLIC_BASE
export DMG APK_VER APK_LATEST NOTES

"$VENV/bin/python" - <<'PY'
import hashlib, json, os, mimetypes
from datetime import datetime, timezone
import boto3
from botocore.client import Config

def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()

endpoint = os.environ['R2_ENDPOINT']
bucket = os.environ['R2_BUCKET']
base = os.environ['R2_PUBLIC_BASE'].rstrip('/')
version = os.environ['VERSION']
s3 = boto3.client(
    's3', endpoint_url=endpoint,
    aws_access_key_id=os.environ['R2_ACCESS_KEY_ID'],
    aws_secret_access_key=os.environ['R2_SECRET_ACCESS_KEY'],
    region_name='auto', config=Config(signature_version='s3v4'),
)

uploads = [
    (os.environ['DMG'], f'DroidTrans-{version}-macos-arm64.dmg', 'application/x-apple-diskimage'),
    # latest.dmg 是个不带版本号的固定地址，和 latest.apk 对称。
    # 官网在拿不到 latest.json 时要有地方兜底，否则 macOS 的下载按钮只能置灰。
    (os.environ['DMG'], 'latest.dmg', 'application/x-apple-diskimage'),
    (os.environ['APK_VER'], f'DroidTrans-{version}.apk', 'application/vnd.android.package-archive'),
    (os.environ['APK_LATEST'], 'latest.apk', 'application/vnd.android.package-archive'),
]
digests = {}
sizes = {}
for path, key, ctype in uploads:
    digests[key] = sha256(path)
    sizes[key] = os.path.getsize(path)
    print(f'upload s3://{bucket}/{key}  ({os.path.getsize(path)} bytes)')
    extra = {'ContentType': ctype, 'CacheControl': 'public, max-age=60'}
    s3.upload_file(path, bucket, key, ExtraArgs=extra)

manifest = {
    'version': version,
    'notes': os.environ.get('NOTES', ''),
    'published_at': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
    'macos_arm64': {
        'url': f'{base}/DroidTrans-{version}-macos-arm64.dmg',
        'sha256': digests[f'DroidTrans-{version}-macos-arm64.dmg'],
        # size 给官网显示「24.6 MB」用，也让人下载前知道要花多少流量
        'size': sizes[f'DroidTrans-{version}-macos-arm64.dmg'],
    },
    'android': {
        'url': f'{base}/latest.apk',
        'sha256': digests['latest.apk'],
        'size': sizes['latest.apk'],
    },
}
body = json.dumps(manifest, ensure_ascii=False, indent=2).encode()
print('upload latest.json')
s3.put_object(
    Bucket=bucket, Key='latest.json', Body=body,
    ContentType='application/json; charset=utf-8',
    CacheControl='public, max-age=60',
)
print(json.dumps(manifest, ensure_ascii=False, indent=2))
print('public:', f'{base}/latest.json')
print('apk:', f'{base}/latest.apk')
print('dmg:', manifest['macos_arm64']['url'])
PY
