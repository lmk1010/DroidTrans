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
CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o "$BIN" .
echo "built $BIN ($(du -h "$BIN" | awk '{print $1}'))"

if [[ "$(uname)" == "Darwin" ]]; then
  APP="$OUT/DroidTrans.app"
  rm -rf "$APP"
  mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
  cp "$BIN" "$APP/Contents/MacOS/droidtrans"
  cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>DroidTrans</string>
  <key>CFBundleDisplayName</key><string>DroidTrans</string>
  <key>CFBundleIdentifier</key><string>com.mk.droidtrans</string>
  <key>CFBundleVersion</key><string>1.0.0</string>
  <key>CFBundleShortVersionString</key><string>1.0.0</string>
  <key>CFBundleExecutable</key><string>droidtrans</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>LSMinimumSystemVersion</key><string>11.0</string>
  <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
PLIST
  echo "app $APP"
fi
