#!/bin/bash
# 手机端冒烟：桌面端要在运行，手机/模拟器要连着（adb devices 能看到）。
# 全程用 intent 驱动，不依赖屏幕坐标，可重复跑。
#
#   ./scripts/regress-phone.sh
set -u
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=com.mk.androidtransfer
BASE=http://127.0.0.1:9500
APK="$(dirname "$0")/../android/app/build/outputs/apk/debug/app-debug.apk"
pass=0; fail=0
chk() { if [ "$2" = "$3" ]; then echo "  ✅ $1"; pass=$((pass+1)); else echo "  ❌ $1  实际=$2 期望=$3"; fail=$((fail+1)); fi; }

echo "【1】环境"
DEV=$("$ADB" devices | sed -n '2p' | awk '{print $2}')
chk "手机已连接" "${DEV:-none}" "device"
chk "桌面端在跑" "$(curl -s -o /dev/null -w %{http_code} $BASE/api/health)" "200"
[ "$fail" -gt 0 ] && { echo; echo "环境不满足，先启动桌面端并连上手机"; exit 1; }

IP=$(curl -s $BASE/api/wifi/info | python3 -c "import json,sys;print(json.load(sys.stdin)['ip'])")
CODE=$(curl -s $BASE/api/pair/info | python3 -c "import json,sys;print(json.load(sys.stdin).get('code',''))")

echo "【2】安装与启动"
if [ -f "$APK" ]; then
  "$ADB" install -r "$APK" >/dev/null 2>&1
  chk "安装成功" "$?" "0"
else
  echo "  ⏭  没有 debug APK，跳过安装（先跑 ./gradlew assembleDebug）"
fi
"$ADB" logcat -c
"$ADB" shell am force-stop $PKG
"$ADB" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 4
chk "启动后进程还在" "$("$ADB" shell pidof $PKG >/dev/null 2>&1 && echo yes || echo no)" "yes"

echo "【3】手机 → 电脑：分享文字"
pbcopy < /dev/null 2>/dev/null || true
MARK="冒烟测试-$(date +%H%M%S)"
"$ADB" shell "am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '$MARK' -n $PKG/.ShareReceiverActivity" >/dev/null 2>&1
sleep 5
chk "文字进了电脑剪贴板" "$(pbpaste 2>/dev/null)" "$MARK"

echo "【4】手机 → 电脑：分享文件"
BEFORE=$(curl -s $BASE/api/inbox | python3 -c "import json,sys;print(json.load(sys.stdin).get('last_file',''))")
IMG=$("$ADB" shell "content query --uri content://media/external/images/media --projection _id 2>/dev/null" | head -1 | sed 's/.*_id=//' | tr -d '\r')
if [ -n "$IMG" ]; then
  "$ADB" shell "am start -a android.intent.action.SEND -t image/jpeg --eu android.intent.extra.STREAM content://media/external/images/media/$IMG --grant-read-uri-permission -n $PKG/.ShareReceiverActivity" >/dev/null 2>&1
  sleep 8
  AFTER=$(curl -s $BASE/api/inbox | python3 -c "import json,sys;print(json.load(sys.stdin).get('last_file',''))")
  chk "文件到了电脑" "$([ "$AFTER" != "$BEFORE" ] && echo yes || echo no)" "yes"
else
  echo "  ⏭  手机上没有图片，跳过"
fi

echo "【5】电脑 → 手机：待取清单"
TMP=$(mktemp -d); echo "hello-phone" > "$TMP/smoke.txt"
curl -s -X POST -H 'Content-Type: application/json' -d "{\"paths\":[\"$TMP/smoke.txt\"]}" $BASE/api/outbox/add >/dev/null
ID=$(curl -s $BASE/api/outbox | python3 -c "
import json,sys
items=json.load(sys.stdin)['items']
print(next((i['id'] for i in items if i['name']=='smoke.txt'), ''))")
chk "文件已入队" "$([ -n "$ID" ] && echo yes || echo no)" "yes"
# 手机取文件必须带令牌：这里用配对码换一个，模拟 App 的行为
TOKEN=$(curl -s -X POST -H 'Content-Type: application/json' -d "{\"code\":\"$CODE\",\"device_id\":\"phone-smoke\",\"device_name\":\"冒烟\"}" http://$IP:9500/api/pair | python3 -c "import json,sys;print(json.load(sys.stdin).get('token',''))")
GOT=$("$ADB" shell "curl -s -H 'X-DT-Token: $TOKEN' http://$IP:9500/api/outbox/file/$ID 2>/dev/null" | tr -d '\r')
if [ -z "$GOT" ]; then
  echo "  ⏭  手机上没有 curl，改从电脑侧验证取件"
  GOT=$(curl -s -H "X-DT-Token: $TOKEN" http://$IP:9500/api/outbox/file/$ID)
fi
chk "内容取得到且一致" "$GOT" "hello-phone"
curl -s -X POST -d '{}' $BASE/api/outbox/remove >/dev/null; rm -rf "$TMP"

echo "【6】崩溃检查"
chk "全程无崩溃" "$("$ADB" logcat -d 2>/dev/null | grep -c 'FATAL EXCEPTION')" "0"

echo
echo "通过 $pass 项，失败 $fail 项"
exit $fail
