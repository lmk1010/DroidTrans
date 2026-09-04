#!/usr/bin/env python3
"""把出图的 1024 原图裁到内容边界，统一留边后缩到指定尺寸（默认 160）。

    ./scripts/fit-icon.py art/logo.png [160]

出图给的是 1024 见方、主体居中但留白多少不一的图，
不裁的话几个图标并排会一个大一个小。留边比例和 pack-assets.sh 保持一致。
"""
import sys
from PIL import Image

MARGIN = 0.04

path = sys.argv[1]
size = int(sys.argv[2]) if len(sys.argv) > 2 else 160

im = Image.open(path).convert("RGBA")
bbox = im.split()[-1].getbbox()
if bbox:
    im = im.crop(bbox)
side = int(max(im.size) * (1 + MARGIN * 2))
canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
canvas.paste(im, ((side - im.width) // 2, (side - im.height) // 2), im)
canvas.resize((size, size), Image.LANCZOS).save(path)
