#!/bin/bash
# 生成一张图。
#
#   ./scripts/gen-image.sh out.png "prompt" [1024x1024] [gpt-image-2]
#
# 接口返回的是 base64 而不是 URL，所以要自己解码落盘。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:?用法: gen-image.sh <输出.png> <prompt> [尺寸] [模型]}"
PROMPT="${2:?缺少 prompt}"
SIZE="${3:-1024x1024}"
MODEL="${4:-gpt-image-2}"

[[ -f "$ROOT/.secrets/imagegen.env" ]] || { echo "缺少 .secrets/imagegen.env" >&2; exit 1; }
set -a; . "$ROOT/.secrets/imagegen.env"; set +a

TMP="$(mktemp)"; BODY="$(mktemp)"
trap 'rm -f "$TMP" "$BODY"' EXIT

# prompt 里有引号和中文，交给 python 拼 JSON，别用字符串拼接
python3 - "$BODY" "$PROMPT" "$SIZE" "$MODEL" <<'PY'
import json, sys
body, prompt, size, model = sys.argv[1:5]
json.dump({"model": model, "prompt": prompt, "size": size,
           "background": "transparent", "output_format": "png", "n": 1},
          open(body, "w"))
PY

# 用 curl 而不是 urllib：服务端会按 User-Agent 拦，urllib 的默认 UA 直接吃 403
for attempt in 1 2 3; do
  if curl -s --max-time 300 "$IMAGE_API_BASE/v1/images/generations" \
      -H "Authorization: Bearer $IMAGE_API_KEY" \
      -H "Content-Type: application/json" \
      --data-binary "@$BODY" -o "$TMP"
  then
    if python3 - "$TMP" "$OUT" <<'PY'
import base64, json, sys
tmp, out = sys.argv[1:3]
try:
    d = json.load(open(tmp))
except Exception:
    print("返回的不是 JSON:", open(tmp, "rb").read()[:200], file=sys.stderr)
    sys.exit(1)
if not d.get("data"):
    print("服务返回:", json.dumps(d, ensure_ascii=False)[:300], file=sys.stderr)
    sys.exit(1)
open(out, "wb").write(base64.b64decode(d["data"][0]["b64_json"]))
PY
    then
      echo "  ✓ $(basename "$OUT")  ($(du -h "$OUT" | cut -f1))"
      exit 0
    fi
  fi
  echo "  第 $attempt 次失败，重试…" >&2
  sleep 3
done

echo "生成失败: $OUT" >&2
exit 1
