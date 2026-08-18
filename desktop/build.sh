#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
echo "go mod tidy / build"
go mod tidy
OUT="${ROOT}/../dist"
mkdir -p "$OUT"
BIN="$OUT/droidtrans"
GOOS="${GOOS:-$(go env GOOS)}"
GOARCH="${GOARCH:-$(go env GOARCH)}"
if [[ "$(uname)" == "Darwin" ]]; then
  CGO_ENABLED=1 go build -trimpath -ldflags="-s -w" -o "$BIN" .
else
  CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o "$BIN" .
fi
echo "built $BIN ($(du -h "$BIN" | awk '{print $1}'))"

if [[ "$(uname)" == "Darwin" ]]; then
  APP="$OUT/DroidTrans.app"
  rm -rf "$APP"
  mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
  cp "$BIN" "$APP/Contents/MacOS/droidtrans"
  ICONSET="/tmp/droidtrans.iconset"
  PADDED="/tmp/droidtrans-icon-1024.png"
  SRC_SVG="$ROOT/../app_logo.svg"
  if command -v rsvg-convert >/dev/null && command -v python3 >/dev/null && [[ -f "$SRC_SVG" ]]; then
    # macOS 标准图标：1024 画布里内容 824（Apple 模板），四周透明边系统会认
    rsvg-convert -w 824 -h 824 "$SRC_SVG" -o /tmp/droidtrans-logo-824.png
    python3 - <<'PY'
from PIL import Image
logo = Image.open("/tmp/droidtrans-logo-824.png").convert("RGBA")
canvas = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
canvas.paste(logo, ((1024 - logo.width) // 2, (1024 - logo.height) // 2), logo)
px = canvas.load()
for i in range(1024):
    px[i, 0] = (0, 0, 0, 1)
    px[i, 1023] = (0, 0, 0, 1)
    px[0, i] = (0, 0, 0, 1)
    px[1023, i] = (0, 0, 0, 1)
canvas.save("/tmp/droidtrans-icon-1024.png")
PY
    rm -rf "$ICONSET" && mkdir -p "$ICONSET"
    for s in 16 32 128 256 512; do
      sips -z $s $s "$PADDED" --out "$ICONSET/icon_${s}x${s}.png" >/dev/null
      sips -z $((s*2)) $((s*2)) "$PADDED" --out "$ICONSET/icon_${s}x${s}@2x.png" >/dev/null
    done
    iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/icon.icns"
  else
  ICON_SRC=""
  for p in \
    "$ROOT/src-tauri/icons/icon.icns" \
    "$ROOT/../web/icon.icns" \
    "$ROOT/src-tauri/icons/icon-1024.png"
  do
    if [[ -f "$p" ]]; then ICON_SRC="$p"; break; fi
  done
  if [[ -n "$ICON_SRC" ]]; then
    if [[ "$ICON_SRC" == *.icns ]]; then
      cp "$ICON_SRC" "$APP/Contents/Resources/icon.icns"
    elif command -v sips >/dev/null && command -v iconutil >/dev/null; then
      ICONSET="/tmp/droidtrans.iconset"
      rm -rf "$ICONSET" && mkdir -p "$ICONSET"
      for s in 16 32 128 256 512; do
        sips -z $s $s "$ICON_SRC" --out "$ICONSET/icon_${s}x${s}.png" >/dev/null
        sips -z $((s*2)) $((s*2)) "$ICON_SRC" --out "$ICONSET/icon_${s}x${s}@2x.png" >/dev/null
      done
      iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/icon.icns"
    fi
  fi
  fi
  cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>DroidTrans</string>
  <key>CFBundleDisplayName</key><string>DroidTrans</string>
  <key>CFBundleIdentifier</key><string>com.mk.droidtrans</string>
  <key>CFBundleVersion</key><string>1.0.2</string>
  <key>CFBundleShortVersionString</key><string>1.0.2</string>
  <key>CFBundleExecutable</key><string>droidtrans</string>
  <key>CFBundleIconFile</key><string>icon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>LSMinimumSystemVersion</key><string>11.0</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>LSMultipleInstancesProhibited</key><true/>
  <key>NSUserNotificationAlertStyle</key><string>banner</string>
</dict>
</plist>
PLIST
  codesign --force --deep -s - "$APP" >/dev/null 2>&1 || true
  echo "app $APP"

  STAGE="/tmp/droidtrans-dmg"
  rm -rf "$STAGE"
  mkdir -p "$STAGE"
  cp -R "$APP" "$STAGE/DroidTrans.app"
  ln -s /Applications "$STAGE/Applications"
  cat > "$STAGE/先看这里.txt" <<'EOF'
把 DroidTrans 拖进 Applications，推出磁盘后再打开。

若提示已损坏，终端执行：
xattr -cr /Applications/DroidTrans.app
EOF
  DMG="$OUT/DroidTrans-1.0.2-macos-arm64.dmg"
  rm -f "$DMG"
  hdiutil create -volname DroidTrans -srcfolder "$STAGE" -ov -format UDZO "$DMG" >/dev/null
  echo "dmg $DMG ($(du -h "$DMG" | awk '{print $1}'))"
fi
