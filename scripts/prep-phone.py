#!/usr/bin/env python3
"""把出图的手机素材抠背、裁好。

    ./scripts/prep-phone.py raw.png out.png [宽度]

抠背交给 bgstrip.py（和图标那条路共用一份）。

fit-icon.py 不能用：它把图缩成正方形，手机是竖长的，一半画布会白白浪费。
"""
import json
import sys
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bgstrip import strip_solid_background  # noqa: E402

src, dst = sys.argv[1], sys.argv[2]
width = int(sys.argv[3]) if len(sys.argv) > 3 else 240

im = strip_solid_background(Image.open(src).convert("RGBA"))

# ---- 裁到机身 ----
alpha = im.split()[-1].point(lambda v: 255 if v > 40 else 0)
box = alpha.getbbox()
if box:
    im = im.crop(box)
cw, ch = im.size

scale = width / cw
im.resize((width, round(ch * scale)), Image.LANCZOS).save(dst)

print(json.dumps({"file": dst, "size": [width, round(ch * scale)]}, ensure_ascii=False))
