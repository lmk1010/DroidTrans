"""把出图服务给回的实心底抠掉。

出图请求里写了 background=transparent，但服务端时灵时不灵：有时给回真的
透明底，有时给回纯白或纯黑的实心底，alpha 全是 255。实心底的图放到深色界面上
就是一块方块，而且这事编译和构建都不会吭声，只能靠眼睛发现。
所以出图之后一律过一遍这里。

抠背只能从四角灌进来，不能按颜色一刀切：图标自己也有白色和黑色的部分
（手机的黑屏、时钟的白表盘），一刀切会把它们一起挖空。
"""
from collections import deque


def strip_solid_background(im, tol=20, feather=46):
    """im 是 RGBA。已经是透明底就原样返回。"""
    alpha = im.split()[-1]
    w, h = im.size
    # 已经有大片全透明的像素，说明服务端这次给的是透明底，不用动
    if alpha.histogram()[0] > w * h * 0.02:
        return im

    px = im.load()
    corners = [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]
    bg = tuple(sum(px[c][i] for c in corners) // 4 for i in range(3))

    def near(p, t):
        return max(abs(px[p][i] - bg[i]) for i in range(3)) <= t

    seen = set()
    q = deque()
    edges = [(x, y) for x in range(w) for y in (0, h - 1)] + \
            [(x, y) for y in range(h) for x in (0, w - 1)]
    for p in edges:
        if p not in seen and near(p, tol):
            seen.add(p)
            q.append(p)
    while q:
        x, y = q.popleft()
        for n in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if 0 <= n[0] < w and 0 <= n[1] < h and n not in seen and near(n, tol):
                seen.add(n)
                q.append(n)
    for x, y in seen:
        px[x, y] = (0, 0, 0, 0)

    # 交界那一圈是背景和主体混出来的过渡色，全留着是一道白边（或黑边），
    # 全砍掉又会啃掉主体轮廓。按它离背景色有多远给一个渐变的 alpha。
    #
    # 只能作用在挨着背景的那一圈。扫全图的话，主体内部凡是接近背景色的像素
    # 都会被打成半透明——白底上的白色时钟表盘、黑底上的黑色屏幕，正好是
    # 这套素材里最常见的两样东西，会被生生挖出一个洞。
    edge = set()
    for x, y in seen:
        for n in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if 0 <= n[0] < w and 0 <= n[1] < h and n not in seen:
                edge.add(n)
    for x, y in edge:
        r, g, b, a = px[x, y]
        if not a:
            continue
        d = max(abs((r, g, b)[i] - bg[i]) for i in range(3))
        if d < feather:
            px[x, y] = (r, g, b, int(255 * d / feather))
    return im
