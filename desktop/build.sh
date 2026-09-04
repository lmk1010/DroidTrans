#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
REPO="$(cd "$ROOT/.." && pwd)"
VERSION="$(tr -d '[:space:]' < "$REPO/VERSION" | sed 's/^v//')"
VERSION="${VERSION:-0.0.0}"
echo "go mod tidy / build  version=$VERSION"
go mod tidy
OUT="${ROOT}/../dist"
mkdir -p "$OUT"
BIN="$OUT/droidtrans"
GOOS="${GOOS:-$(go env GOOS)}"
GOARCH="${GOARCH:-$(go env GOARCH)}"
LDFLAGS="-s -w -X droidtrans/internal/update.Version=${VERSION}"
if [[ "$(uname)" == "Darwin" ]]; then
  CGO_ENABLED=1 go build -trimpath -ldflags="$LDFLAGS" -o "$BIN" .
else
  CGO_ENABLED=0 go build -trimpath -ldflags="$LDFLAGS" -o "$BIN" .
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
  cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>DroidTrans</string>
  <key>CFBundleDisplayName</key><string>DroidTrans</string>
  <key>CFBundleIdentifier</key><string>com.mk.droidtrans</string>
  <key>CFBundleVersion</key><string>${VERSION}</string>
  <key>CFBundleShortVersionString</key><string>${VERSION}</string>
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
  # 签名。
  #
  # 找得到 Developer ID 证书就正式签，否则退回 ad-hoc（-s -）。
  # ad-hoc 只能让程序在本机跑起来，对 Gatekeeper 完全无效 ——
  # 用户下载后照样会看到「无法验证开发者」。
  #
  # 要正式签名需要先在本机装好证书：
  #   Xcode → Settings → Accounts → Manage Certificates → + → Developer ID Application
  # 装好之后 `security find-identity -v -p codesigning` 能看到它。
  # 注意末尾的 || true：没有证书时 grep 返回 1，而 VAR="$(...)" 这种赋值
  # 会继承命令替换的退出码，在 set -e 下会让整个脚本在这一行静默退出 ——
  # 表现是构建到一半就没了，连 dmg 都不生成。
  SIGN_ID="${MACOS_SIGN_IDENTITY:-}"
  if [[ -z "$SIGN_ID" ]]; then
    SIGN_ID="$(security find-identity -v -p codesigning 2>/dev/null \
      | grep "Developer ID Application" | head -1 | sed -E 's/.*"(.*)"/\1/' || true)"
  fi

  if [[ -n "$SIGN_ID" ]]; then
    echo "codesign  $SIGN_ID"
    # --options runtime 是公证的硬性前提，少了它 notarytool 会直接拒收
    codesign --force --deep --timestamp --options runtime \
      -s "$SIGN_ID" "$APP"
    codesign --verify --deep --strict "$APP" && echo "  签名校验通过"
    SIGNED=1
  else
    codesign --force --deep -s - "$APP" >/dev/null 2>&1 || true
    echo "codesign  未找到 Developer ID 证书，退回 ad-hoc 签名"
    echo "          用户下载后仍会遇到「无法验证开发者」"
    SIGNED=0
  fi
  echo "app $APP"

  STAGE="/tmp/droidtrans-dmg"
  rm -rf "$STAGE"
  mkdir -p "$STAGE"
  cp -R "$APP" "$STAGE/DroidTrans.app"
  ln -s /Applications "$STAGE/Applications"
  # 签过名并公证的包，用户双击就能开；只有未签名的产物才需要教人绕过 Gatekeeper。
  if [[ "${SIGNED:-0}" == "1" ]]; then
    cat > "$STAGE/先看这里.txt" <<'EOF'
把 DroidTrans 拖进 Applications，推出磁盘后再打开。

本应用已通过 Apple 签名与公证，双击即可运行。
EOF
  else
    cat > "$STAGE/先看这里.txt" <<'EOF'
把 DroidTrans 拖进 Applications，推出磁盘后再打开。

这是一个未签名的开发构建。若提示已损坏，终端执行：
xattr -cr /Applications/DroidTrans.app
EOF
  fi
  DMG="$OUT/DroidTrans-${VERSION}-macos-arm64.dmg"
  rm -f "$DMG"
  hdiutil create -volname "DroidTrans ${VERSION}" -srcfolder "$STAGE" -ov -format UDZO "$DMG" >/dev/null
  echo "dmg $DMG ($(du -h "$DMG" | awk '{print $1}'))"

  # 公证。
  #
  # 签名只证明「是谁做的」，公证才让 Gatekeeper 放行 ——
  # 少了这一步，用户下载后依然要手动 xattr。
  #
  # 需要先存一份凭据（只需做一次）：
  #   xcrun notarytool store-credentials droidtrans \\
  #     --apple-id <你的 Apple ID> --team-id 24D88Q3K3S \\
  #     --password <App 专用密码，appleid.apple.com 生成>
  if [[ "${SIGNED:-0}" == "1" ]] && xcrun notarytool history --keychain-profile "${NOTARY_PROFILE:-droidtrans}" >/dev/null 2>&1; then
    echo "notarize  提交中（几分钟）…"
    if xcrun notarytool submit "$DMG" --keychain-profile "${NOTARY_PROFILE:-droidtrans}" --wait; then
      # stapler 把公证票据钉进 DMG，用户首次打开就不需要联网核验
      xcrun stapler staple "$DMG" && echo "notarize  已公证并装订"
    else
      echo "notarize  失败，产出的包仍需用户手动 xattr"
    fi
  elif [[ "${SIGNED:-0}" == "1" ]]; then
    echo "notarize  跳过：没有名为 ${NOTARY_PROFILE:-droidtrans} 的公证凭据"
  fi
fi
