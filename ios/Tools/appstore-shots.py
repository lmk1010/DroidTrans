#!/usr/bin/env python3
"""把模拟器截图拼成 App Store 用的营销图。

  python3 ios/Tools/appstore-shots.py

输入是 ScreenshotTests 截的原始图（必须在 iPhone 16 Pro Max 上跑，
6.9" 是 App Store 现在唯一必交的 iPhone 尺寸，1320×2868）。
输出同样是 1320×2868 —— 直接传 App Store Connect，不用再缩放。

背景那团光是出图服务生成的（scripts/gen-image.sh），不是 CSS 渐变：
纯 CSS 的径向渐变有肉眼可见的色带，放大到 2868px 高更明显。
"""
import asyncio, base64, json, pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
# 默认出 6.9"（1320×2868）—— App Store Connect 现在主推这个槽位。
# 版本页上要是只给了 6.5" 的槽，用 SHOT_SIZE=1284x2778 再跑一遍即可。
import os
W, H = (int(x) for x in os.environ.get("SHOT_SIZE", "1320x2868").split("x"))

# 每张图：原始截图、主标题、副标题。
#
# 顺序即 App Store 里的展示顺序，**搜索结果只展示前三张** ——
# 所以前三张必须讲功能。之前是「会员页、首页、我的」，三张里两张在讲钱，
# 用户扫过去看到的全是付费页，等于把最贵的三个位置浪费掉了。
# 会员页挪到最后：它是看完功能之后才该出现的东西。
SHOTS = {
    "zh": [
        ("00-radar",    "打开就找到<br>你的电脑", "同一个 Wi-Fi，点一下连上"),
        ("02-transfer", "几个 GB<br>一口气传完", "断了从断点接着传，不用重来"),
        ("03-gallery",  "取回来的<br>都在这儿", "照片、视频、文档、压缩包"),
        ("04-history",  "传过什么<br>一目了然", "原图原视频，不转码不压缩"),
        ("05-pro",      "一次买断<br>不是订阅", "基础传输永远免费"),
    ],
    "en": [
        ("00-radar",    "Finds your<br>computer at once", "Same Wi-Fi, one tap to connect"),
        ("02-transfer", "Gigabytes,<br>in one go", "Drops out? It resumes, never restarts"),
        ("03-gallery",  "Everything you<br>pulled back", "Photos, video, documents, archives"),
        ("04-history",  "See exactly<br>what moved", "Originals — no transcoding, no loss"),
        ("05-pro",      "One-time purchase<br>Not a subscription", "Basic transfer is always free"),
    ],
}

PAGE = """<!doctype html><meta charset="utf-8"><style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { width: %(W)spx; height: %(H)spx; overflow: hidden;
         background: linear-gradient(180deg, #11162a 0%%, #0a0d14 62%%);
         font-family: -apple-system, "PingFang SC", "Helvetica Neue", sans-serif; }
  /* 出图生成的光晕。铺满并调低透明度，只用来给深色底加层次 */
  .glow { position: absolute; inset: 6%% -30%% auto -30%%; height: 74%%;
          background: url("data:image/png;base64,%(BG)s") center/cover no-repeat;
          opacity: 0.3; filter: blur(10px) saturate(0.85); }
  /* 压一层深色回去：光晕本身够亮，直接铺上去会盖过截图，
     标题也会在浅蓝底上发灰。 */
  .veil { position: absolute; inset: 0;
          background: linear-gradient(180deg, rgba(10,13,20,0.78) 0%%,
                                              rgba(10,13,20,0.34) 34%%,
                                              rgba(10,13,20,0.72) 100%%); }
  .copy { position: absolute; top: 132px; left: 0; right: 0; text-align: center;
          padding: 0 86px; }
  h1 { font-size: 92px; line-height: 1.16; font-weight: 800; letter-spacing: -0.02em;
       color: #fff; }
  p  { margin-top: 26px; font-size: 40px; line-height: 1.4;
       color: rgba(255,255,255,0.58); }
  /* 截图本身。圆角对齐 iPhone 16 Pro Max 的屏幕曲率，
     不套设备边框 —— Apple 的审核指南不禁止，但带边框会让内容变小一圈。 */
  .shot { position: absolute; left: 50%%; bottom: -78px; transform: translateX(-50%%);
          width: 1000px; border-radius: 74px; overflow: hidden;
          box-shadow: 0 60px 120px -30px rgba(0,0,0,0.85),
                      0 0 0 2px rgba(255,255,255,0.10);
          background: #0a0d14; }
  .shot img { display: block; width: 100%%; }
</style>
<div class="glow"></div>
<div class="veil"></div>
<div class="copy"><h1>%(TITLE)s</h1><p>%(SUB)s</p></div>
<div class="shot"><img src="data:image/png;base64,%(SHOT)s"></div>
"""


def b64(p: pathlib.Path) -> str:
    return base64.b64encode(p.read_bytes()).decode()


async def main():
    from playwright.async_api import async_playwright

    src = ROOT / "ios" / "Design" / "appstore" / "raw"
    out = ROOT / "ios" / "Design" / "appstore"
    if not src.exists():
        sys.exit(f"缺少原始截图目录 {src}")

    bg = b64(ROOT / "ios" / "Design" / "appstore" / "bg-glow.png")

    async with async_playwright() as pw:
        browser = await pw.chromium.launch()
        page = await browser.new_page(viewport={"width": W, "height": H})
        for lang, items in SHOTS.items():
            for i, (name, title, sub) in enumerate(items, 1):
                raw = src / f"{lang}-{name}.png"
                if not raw.exists():
                    print(f"跳过 {raw.name}（没有这张原图）")
                    continue
                html = PAGE % {"W": W, "H": H, "BG": bg, "SHOT": b64(raw),
                               "TITLE": title, "SUB": sub}
                await page.set_content(html)
                await page.wait_for_timeout(300)
                sub = lang if (W, H) == (1320, 2868) else f"{lang}-{W}x{H}"
                dst = out / sub / f"{i}-{name.split('-', 1)[1]}.png"
                dst.parent.mkdir(parents=True, exist_ok=True)
                await page.screenshot(path=str(dst))
                print("✓", dst.relative_to(ROOT))
        await browser.close()


asyncio.run(main())
