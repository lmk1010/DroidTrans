#!/usr/bin/env python3
"""把出图的手机素材裁好，并量出屏幕在图里的位置。

    ./scripts/prep-phone.py raw.png out.png [宽度]

设备墙要把封面缩略图叠在手机屏幕上，所以必须知道屏幕的精确边界。
手量会差几个像素——手机边框只有几像素宽，差一点就露出白边或盖住边框。
所以从屏幕中心做一次洪水填充：屏幕是一整片浅色，边框是深色和蓝色，
填充自己会停在边框上，量出来的就是真实边界。

fit-icon.py 不能用：它把图缩成正方形，手机是竖长的，那样会白白浪费一半画布。
"""
import json
import sys
from collections import deque

from PIL import Image

src, dst = sys.argv[1], sys.argv[2]
width = int(sys.argv[3]) if len(sys.argv) > 3 else 240

im = Image.open(src).convert("RGBA")

# 出图服务收了 background=transparent 但给回来的是纯黑底，alpha 全是 255。
# 直接用就是深色界面上一块更黑的方块，边界看得一清二楚。
#
# 抠背只能从四角灌，不能按颜色一刀切：手机自己有大片黑（屏幕黑边、灵动岛），
# 按颜色切会把它们一起挖空。从画布外围灌进来，遇到机身就停，挖掉的只有背景。
def strip_black(img, thresh=42, feather=52):
    w, h = img.size
    p = img.load()
    dark = lambda x, y: max(p[x, y][:3]) < thresh
    seen = set()
    q = deque()
    for x in range(w):
        for y in (0, h - 1):
            if dark(x, y) and (x, y) not in seen:
                seen.add((x, y)); q.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if dark(x, y) and (x, y) not in seen:
                seen.add((x, y)); q.append((x, y))
    while q:
        x, y = q.popleft()
        for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if 0 <= nx < w and 0 <= ny < h and (nx, ny) not in seen and dark(nx, ny):
                seen.add((nx, ny)); q.append((nx, ny))
    for x, y in seen:
        p[x, y] = (0, 0, 0, 0)
    # 交界那一圈是背景和机身混出来的半黑像素，全留着会是一道黑边，
    # 全砍掉又会啃掉机身轮廓。按亮度给它们一个渐变的 alpha。
    for x in range(w):
        for y in range(h):
            r, g, b, a = p[x, y]
            if a and max(r, g, b) < feather:
                p[x, y] = (r, g, b, int(255 * max(r, g, b) / feather))
    return img


im = strip_black(im)
alpha = im.split()[-1].point(lambda v: 255 if v > 40 else 0)
bbox = alpha.getbbox()
if bbox:
    im = im.crop(bbox)

px = im.load()
W, H = im.size


def light(x, y):
    r, g, b, a = px[x, y]
    return a > 200 and r > 190 and g > 190 and b > 190


# 从正中间开始灌。屏幕中心必然在屏幕里。
start = (W // 2, H // 2)
if not light(*start):
    sys.exit("图正中间不是屏幕，这张素材不能用")

seen = {start}
q = deque([start])
x0 = x1 = start[0]
y0 = y1 = start[1]
while q:
    x, y = q.popleft()
    x0, x1 = min(x0, x), max(x1, x)
    y0, y1 = min(y0, y), max(y1, y)
    for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
        if 0 <= nx < W and 0 <= ny < H and (nx, ny) not in seen and light(nx, ny):
            seen.add((nx, ny))
            q.append((nx, ny))

scale = width / W
im.resize((width, round(H * scale)), Image.LANCZOS).save(dst)

print(json.dumps({
    "file": dst,
    "size": [width, round(H * scale)],
    # 百分比，直接给 CSS 用
    "screen": {
        "x": round(x0 / W * 100, 2),
        "y": round(y0 / H * 100, 2),
        "w": round((x1 - x0 + 1) / W * 100, 2),
        "h": round((y1 - y0 + 1) / H * 100, 2),
    },
}, ensure_ascii=False))
