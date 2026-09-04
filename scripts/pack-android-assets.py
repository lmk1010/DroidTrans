#!/usr/bin/env python3
"""把 ios/Design/raw 里的 3D 原图切成安卓各密度的 drawable。

    ./scripts/pack-android-assets.py

两端共用同一批原图。各出各的素材，摆在一起就是两个产品 ——
所以这里不重新生成，只是把 iOS 那套按安卓的密度桶再切一遍。

裁边规则和 pack-assets.sh 一致：先裁到实际内容，再统一按同一比例留边，
不然几个图标并排会一个大一个小。
"""
import os
import sys
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, "ios", "Design", "raw")
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")

# 基准 56dp。安卓最大到 xxxhdpi(4x)，再大就只是白占安装包
BASE_DP = 56
MARGIN = 0.04
BUCKETS = {
    "drawable-mdpi": 1.0,
    "drawable-hdpi": 1.5,
    "drawable-xhdpi": 2.0,
    "drawable-xxhdpi": 3.0,
    "drawable-xxxhdpi": 4.0,
}

# 安卓端用得上的那几张。全切进去只会撑大 APK
WANTED = ["logo", "laptop", "phone", "android", "inbox", "link", "shield", "sync",
          "photos", "files", "text", "dropbox"]


def fit(im):
    """裁到内容边界，再摆进一个统一留边的正方形画布。"""
    bbox = im.split()[-1].getbbox()
    if bbox:
        im = im.crop(bbox)
    side = int(max(im.size) * (1 + MARGIN * 2))
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(im, ((side - im.width) // 2, (side - im.height) // 2), im)
    return canvas


def main():
    if not os.path.isdir(RAW):
        sys.exit("找不到 %s —— 先跑 ./scripts/gen-assets.sh" % RAW)

    for name in WANTED:
        src = os.path.join(RAW, name + ".png")
        if not os.path.isfile(src):
            print("  · %s 没有原图，跳过" % name)
            continue

        canvas = fit(Image.open(src).convert("RGBA"))
        # art_ 前缀，和现有那些手写的 xml drawable 分开，一眼看得出是位图素材
        out_name = "art_%s.png" % name
        for bucket, scale in BUCKETS.items():
            d = os.path.join(RES, bucket)
            os.makedirs(d, exist_ok=True)
            px = int(BASE_DP * scale)
            canvas.resize((px, px), Image.LANCZOS).save(os.path.join(d, out_name))
        print("  ✓ %s（%d 个密度）" % (out_name, len(BUCKETS)))

    print()
    print("素材在 %s/drawable-*" % RES)
    print("布局里这样引用：android:src=\"@drawable/art_logo\"")


if __name__ == "__main__":
    main()
