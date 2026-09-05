#!/bin/bash
# 生成移动端要用的整套素材。
#
#   ./scripts/gen-assets.sh          # 只补缺的
#   ./scripts/gen-assets.sh --force  # 全部重来
#
# 一套素材必须共用同一段风格描述，只换主体 ——
# 风格词各写各的，摆在一起就像从三个不同的图库里拼来的。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/ios/DroidTrans/Resources/Assets.xcassets"
RAW="$ROOT/ios/Design/raw"
DESKTOP_ART="$ROOT/desktop/frontend/art"
FORCE="${1:-}"

mkdir -p "$RAW"

STYLE="glossy soft clay 3D render, smooth rounded geometry, vivid blue #4992ff as the dominant color with clean white as the only secondary color, no other hues, soft studio lighting from upper left, gentle contact shadow, centered with generous margin, isolated on transparent background, minimal premium product icon, high detail, octane render"

gen() {
  local name="$1" subject="$2"
  local dir="${3:-$RAW}"
  local file="$dir/$name.png"
  if [[ -f "$file" && "$FORCE" != "--force" ]]; then
    echo "  · $name 已存在，跳过"
    return
  fi
  mkdir -p "$dir"
  "$ROOT/scripts/gen-image.sh" "$file" "$subject, $STYLE" 1024x1024

  # 桌面端是直接拿这张图上界面的，所以在这儿就裁好缩到 160。
  # iOS 那边留 1024 原图不动，pack-assets.sh 会自己裁。
  if [[ "$dir" == "$DESKTOP_ART" ]]; then
    "$ROOT/scripts/fit-icon.py" "$file" 160
  fi
}

echo "→ 发送入口"
gen photos "A single 3D icon: a small stack of two photo cards, the front one showing a simple mountain and sun"
gen files  "A single 3D icon: a document sheet with a folded top-right corner"
gen text   "A single 3D icon: a rounded speech bubble with three short horizontal text lines inside"

echo "→ 状态与空态"
gen inbox  "A single 3D icon: an open empty inbox tray, slightly tilted, nothing inside it"
gen link   "A single 3D icon: two interlocking rounded chain links, connected"
gen shield "A single 3D icon: a rounded shield with a keyhole in the center"
gen done   "A single 3D icon: a thick rounded checkmark inside a soft circle"

echo "→ 设备"
gen laptop "A single 3D icon: a slim modern laptop computer, three-quarter view, screen open and glowing softly"
gen phone  "A single 3D icon: a modern smartphone standing upright, three-quarter view, blank glowing screen"
gen android "A single 3D icon: a smartphone standing upright at a slight three-quarter angle, with a simple friendly robot head shape on its screen — a rounded dome with two short straight antennae and two dot eyes"

# 设备墙上的手机。和上面那些图标不一样，这两张要按机型分：
# 「已接收」第一屏就是一台台手机，iPhone 和安卓长一样的话，那一屏就白做了。
#
# 也不共用上面那段 STYLE。黏土风的手机不像手机，而这一屏要的就是「像」——
# 用户一眼要认出那是自己的哪台设备。厂商的官方产品图不能用：那是有版权的
# 素材，拿来当自家 App 的界面元素会出问题。所以自己渲染一张写实的。
#
# 机身要浅色：界面是深色的，黑机身糊在背景里看不见，抠背时也分不出机身和底。
# 背景要纯白：出图服务收了 transparent 却经常给回实心底，白底最好抠。
#
# 不能走 gen()：它调 fit-icon.py 把图缩成正方形，手机是竖长的，
# 那样一半画布是空的、手机还被缩得很小。改用 prep-phone.py，
# 它抠背、裁边，顺带把屏幕的位置量出来——封面缩略图要精确叠在屏幕上，
# 手机边框只有几像素宽，手填差一点就露白边或盖住边框。
#
# 量出来的百分比要填回 desktop/frontend/app.js 的 PHONE_ART。
PHONE_STYLE="photorealistic product photography, physically based render, polished metal and glass with realistic reflections and subtle highlights along every edge, soft even studio lighting, crisp edges, no logos, no branding, no text, isolated on a plain solid white background, centered with generous margin, ultra sharp"

genphone() {
  local name="$1" subject="$2"
  local raw="$RAW/$name.png" out="$DESKTOP_ART/$name.png"
  if [[ -f "$out" && "$FORCE" != "--force" ]]; then
    echo "  · $name 已存在，跳过"
    return
  fi
  mkdir -p "$RAW" "$DESKTOP_ART"
  "$ROOT/scripts/gen-image.sh" "$raw" "$subject, $PHONE_STYLE" 1024x1024
  "$ROOT/scripts/prep-phone.py" "$raw" "$out" 240
}

echo "→ 设备墙的手机（按机型分）"
genphone phone-ios "A modern flagship smartphone photographed perfectly straight-on from the front, orthographic view with zero perspective and zero tilt, portrait orientation, bright silver polished titanium frame that clearly stands out, rounded corners, a horizontal pill-shaped cutout centered near the top of the display, a thin white home indicator line at the bottom of the display, the display is switched off showing uniform deep black glass"
genphone phone-android "A modern Android flagship smartphone photographed perfectly straight-on from the front, orthographic view with zero perspective and zero tilt, portrait orientation, bright silver aluminium frame that clearly stands out, slightly tighter corner radius than an iPhone, a single small round punch-hole camera centered near the top edge of the display, no notch, two side buttons on the right edge, the display is switched off showing uniform deep black glass"

echo "→ 桌面端（macOS 客户端界面用）"
# 和移动端共用同一段 STYLE。风格串各写各的，三端摆在一起就像三个产品。
gen usb    "A single 3D icon: a USB-C cable plug, angled, cable curving behind it" "$DESKTOP_ART"
gen wifi   "A single 3D icon: a Wi-Fi signal symbol, three curved arcs rising from a dot" "$DESKTOP_ART"
gen drop   "A single 3D icon: a document sheet dropping into an open folder, motion implied by slight tilt" "$DESKTOP_ART"
gen album  "A single 3D icon: an empty photo album, open, no pictures inside" "$DESKTOP_ART"
gen photoslib "A single 3D icon: a stack of photo prints being lifted out of an open box, the top print showing a simple mountain and sun" "$DESKTOP_ART"
gen transfer "A single 3D icon: several photo cards flowing along a gentle upward arc from left to right, motion implied by their staggered spacing" "$DESKTOP_ART"
gen done "A single 3D icon: a thick rounded checkmark inside a soft circle" "$DESKTOP_ART"
gen unplug "A single 3D icon: a cable plug disconnected from its socket, a small gap between them" "$DESKTOP_ART"
gen grid   "A single 3D icon: four rounded squares arranged in a 2x2 grid, slightly raised" "$DESKTOP_ART"
gen logo   "A single 3D icon: two thick rounded arrows chasing each other head-to-tail in a circular sync loop, the top arrow sweeping right and the bottom arrow sweeping left, forming an open ring with a clear gap on each side, the center of the ring completely empty and fully transparent with nothing inside it, clean crisp edges" "$DESKTOP_ART"

# logo 不生成 —— 它是品牌资产，只有一份，就是 app_logo.svg。
# 让模型「画一个差不多的」出来，摆在 App 里就是两个不一样的标志。
echo "→ 标志（从 app_logo.svg 栅格化，不是生成的）"
rsvg-convert -w 1024 -h 1024 "$ROOT/app_logo.svg" -o "$RAW/logo.png"
echo "  ✓ logo"

echo
echo "原图在 $RAW"
echo "桌面端素材在 $DESKTOP_ART"
echo "下一步：./scripts/pack-assets.sh 把它们切成 @1x/@2x/@3x 放进 $OUT"
