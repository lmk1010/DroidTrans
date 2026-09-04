#!/bin/bash
# 把生成的 1024 原图切成 Xcode 用的 imageset。
#
#   ./scripts/pack-assets.sh
#
# 出图给的是 1024 见方、主体居中但留白多少不一的图。
# 直接拿去用的话，几个图标并排会一个大一个小 ——
# 所以先裁到实际内容边界，再统一按同一个比例留边。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RAW="$ROOT/ios/Design/raw"
OUT="$ROOT/ios/DroidTrans/Resources/Assets.xcassets"

mkdir -p "$OUT"
[[ -f "$OUT/Contents.json" ]] || cat > "$OUT/Contents.json" <<'JSON'
{"info":{"author":"xcode","version":1}}
JSON

python3 - "$RAW" "$OUT" <<'PY'
import json, os, sys
from PIL import Image

raw, out = sys.argv[1], sys.argv[2]
# 显示尺寸 88pt 见方够用了，@3x 就是 264px；再大只是浪费包体积
BASE = 88
MARGIN = 0.04   # 四周留一点，不然图标会顶到卡片边缘

for f in sorted(os.listdir(raw)):
    if not f.endswith(".png"):
        continue
    name = f[:-4]
    im = Image.open(os.path.join(raw, f)).convert("RGBA")

    # 裁到实际内容。留白多少全凭出图，不裁的话并排看大小不一
    bbox = im.split()[-1].getbbox()
    if bbox:
        im = im.crop(bbox)

    # 摆进正方形画布，保持比例居中
    side = max(im.size)
    canvas_side = int(side * (1 + MARGIN * 2))
    canvas = Image.new("RGBA", (canvas_side, canvas_side), (0, 0, 0, 0))
    canvas.paste(im, ((canvas_side - im.width)//2, (canvas_side - im.height)//2), im)

    d = os.path.join(out, f"{name}.imageset")
    os.makedirs(d, exist_ok=True)
    images = []
    for scale in (1, 2, 3):
        px = BASE * scale
        canvas.resize((px, px), Image.LANCZOS).save(os.path.join(d, f"{name}@{scale}x.png"))
        images.append({"idiom": "universal", "filename": f"{name}@{scale}x.png",
                       "scale": f"{scale}x"})
    json.dump({"images": images, "info": {"author": "xcode", "version": 1}},
              open(os.path.join(d, "Contents.json"), "w"), indent=2)
    print(f"  ✓ {name}.imageset")
PY
echo
echo "记得跑 xcodegen generate"
