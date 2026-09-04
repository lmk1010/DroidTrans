#!/bin/bash
# 由 app_logo.svg 生成菜单栏模板图，输出 desktop/menubar_icon.h。
#
#   ./scripts/gen-menubar-icon.sh
#
# logo 改了就重跑一次。需要 rsvg-convert 和 magick（brew install librsvg imagemagick）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app_logo.svg"
OUT="$ROOT/desktop/menubar_icon.h"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# 只留双箭头，扔掉圆角方形底，整体涂黑 —— 模板图的颜色由系统决定，
# 这里的黑只是「不透明」的意思。
python3 - "$SRC" "$TMP/glyph.svg" <<'PY'
import re, sys
src, dst = sys.argv[1:3]
paths = re.findall(r'<path d="([^"]+)"[^>]*/>', open(src).read())
body = "\n".join('  <path d="%s" fill="#000000"/>' % d for d in paths[1:])  # [0] 是底板
open(dst, "w").write(
    '<?xml version="1.0" encoding="UTF-8"?>\n'
    '<svg viewBox="0 0 1024 1024" xmlns="http://www.w3.org/2000/svg" '
    'width="1024" height="1024">\n%s\n</svg>\n' % body)
PY

# 裁掉留白再等比塞回方框：留白不裁，菜单栏里就比邻居小一圈。
rsvg-convert -w 1024 -h 1024 "$TMP/glyph.svg" -o "$TMP/raw.png"
magick "$TMP/raw.png" -trim +repage -resize 1000x1000 \
       -background none -gravity center -extent 1024x1024 "$TMP/fit.png"
magick "$TMP/fit.png" -resize 36x36 -strip PNG32:"$TMP/icon36.png"

python3 - "$TMP/icon36.png" <<'PY' > "$OUT"
import base64, sys, textwrap
b = base64.b64encode(open(sys.argv[1], "rb").read()).decode()
print("// 菜单栏图标：App logo 里的双箭头，去掉圆角方形底、涂黑，做成模板图。")
print("//")
print("// 由 scripts/gen-menubar-icon.sh 从 app_logo.svg 生成（36×36，即 18pt @2x），")
print("// 不要手改。")
print("//")
print("// 直接内嵌成 base64，是为了不动 build.sh 的打包步骤 ——")
print("// 多一个要 cp 进 Contents/Resources 的文件，就多一处能漏掉的地方。")
print("static const char *kMenuBarIconPNGBase64 =")
for line in textwrap.wrap(b, 76):
    print('    "%s"' % line)
print("    ;")
PY

echo "  ✓ $(basename "$OUT")  ($(wc -c < "$OUT" | tr -d ' ') 字节)"
