#!/bin/bash
# 重新生成 ATF3 跨语言对拍向量。改了 Protocol.swift 的线格式就跑一次这个，
# 然后 cd desktop && go test ./internal/fast/ 看 Go 端还认不认。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
OUT="$ROOT/desktop/internal/fast/testdata/atf3_vectors.json"
mkdir -p "$(dirname "$OUT")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
# Protocol.swift 是纯 Foundation，能直接在 macOS 上编，不用起模拟器
swiftc -O -o "$TMP/atf3vec" "$ROOT/ios/DroidTrans/Core/Protocol.swift" "$HERE/main.swift"
"$TMP/atf3vec" "$OUT"
