#!/usr/bin/env python3
"""把出图的手机素材抠背、裁好。

    ./scripts/prep-phone.py raw.png out.png [宽度]

抠背从四角灌，灌到机身就停，挖掉的只有背景。不能按颜色一刀切：
背景是白的时候机身高光也是白的，背景是黑的时候屏幕和黑边框也是黑的，
一刀切会把机身自己挖空。

出图服务收了 background=transparent 但经常给回实心底（白或黑都出现过），
所以背景色不写死，取四角自己认。

fit-icon.py 不能用：它把图缩成正方形，手机是竖长的，一半画布会白白浪费。
"""
import json
import sys
from collections import deque

from PIL import Image

src, dst = sys.argv[1], sys.argv[2]
width = int(sys.argv[3]) if len(sys.argv) > 3 else 240

im = Image.open(src).convert("RGBA")
W, H = im.size
px = im.load()


def near(a, b, tol):
    return max(abs(a[i] - b[i]) for i in range(3)) <= tol


def flood(seeds, ref, tol):
    """从 seeds 出发，收集所有与 ref 颜色相近且连通的像素。"""
    seen = set()
    q = deque()
    for s in seeds:
        if near(px[s], ref, tol):
            seen.add(s)
            q.append(s)
    while q:
        x, y = q.popleft()
        for n in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if 0 <= n[0] < W and 0 <= n[1] < H and n not in seen and near(px[n], ref, tol):
                seen.add(n)
                q.append(n)
    return seen


# ---- 抠背 ----
corners = [(0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1)]
bg = tuple(sum(px[c][i] for c in corners) // 4 for i in range(3)) + (255,)
edges = [(x, y) for x in range(W) for y in (0, H - 1)] + \
        [(x, y) for y in range(H) for x in (0, W - 1)]
back = flood(edges, bg, 20)
for x, y in back:
    px[x, y] = (0, 0, 0, 0)

# 交界那一圈是背景和机身混出来的过渡色，留着是一道亮边（或黑边），
# 砍掉又会啃掉机身轮廓。按它离背景色有多远给一个渐变的 alpha。
for x in range(W):
    for y in range(H):
        r, g, b, a = px[x, y]
        if not a:
            continue
        d = max(abs((r, g, b)[i] - bg[i]) for i in range(3))
        if d < 46:
            px[x, y] = (r, g, b, int(255 * d / 46))

# ---- 裁到机身 ----
alpha = im.split()[-1].point(lambda v: 255 if v > 40 else 0)
box = alpha.getbbox()
if box:
    im = im.crop(box)
cw, ch = im.size

scale = width / cw
im.resize((width, round(ch * scale)), Image.LANCZOS).save(dst)

print(json.dumps({"file": dst, "size": [width, round(ch * scale)]}, ensure_ascii=False))
