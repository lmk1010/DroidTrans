#!/bin/bash
# 端到端回归：桌面端必须已在运行（./desktop/build.sh && open dist/DroidTrans.app）。
# USB 一节需要连着手机或开着模拟器；没有设备时那两项会失败，其余仍可跑。
#
#   ./scripts/regress.sh
set -u
ADB=~/Library/Android/sdk/platform-tools/adb
BASE=http://127.0.0.1:9500
IP=$(curl -s $BASE/api/wifi/info | python3 -c "import json,sys;print(json.load(sys.stdin)['ip'])")
LAN=http://$IP:9500
pass=0; fail=0
chk() { # chk 描述 实际 期望
  if [ "$2" = "$3" ]; then echo "  ✅ $1"; pass=$((pass+1));
  else echo "  ❌ $1  实际=$2 期望=$3"; fail=$((fail+1)); fi
}

echo "【0】界面静态检查"
"$(dirname "$0")/check-frontend.py" > /tmp/regress_fe.txt 2>&1
chk "无悬空引用 / GET 带 body / 缺文案" "$?" "0"
[ -s /tmp/regress_fe.txt ] && sed 's/^/     /' /tmp/regress_fe.txt | grep "❌" || true

echo "【1】服务与发现"
chk "health 可达" "$(curl -s -o /dev/null -w %{http_code} $BASE/api/health)" "200"
chk "电脑名已返回" "$(curl -s $BASE/api/wifi/info | python3 -c "import json,sys;print('yes' if json.load(sys.stdin).get('name') else 'no')")" "yes"
chk "mDNS 在广播" "$( (dns-sd -B _droidtrans._tcp > /tmp/r_dns.txt 2>&1 &) ; sleep 3; pkill -f 'dns-sd -B'; grep -c droidtrans /tmp/r_dns.txt | head -1 | awk '{print ($1>0)?"yes":"no"}')" "yes"

echo "【2】配对"
chk "未配对被挡" "$(curl -s -o /dev/null -w %{http_code} $LAN/api/inbox)" "401"
CODE=$(curl -s $BASE/api/pair/info | python3 -c "import json,sys;print(json.load(sys.stdin)['code'])")
chk "错误码被拒" "$(curl -s -X POST -d '{"code":"000000"}' $LAN/api/pair | python3 -c "import json,sys;print(json.load(sys.stdin)['success'])")" "False"
TOKEN=$(curl -s -X POST -H 'Content-Type: application/json' -d "{\"code\":\"$CODE\",\"device_id\":\"regress\",\"device_name\":\"回归测试\"}" $LAN/api/pair | python3 -c "import json,sys;print(json.load(sys.stdin).get('token',''))")
chk "正确码换到令牌" "$([ -n "$TOKEN" ] && echo yes || echo no)" "yes"
chk "带令牌可访问" "$(curl -s -o /dev/null -w %{http_code} -H "X-DT-Token: $TOKEN" $LAN/api/inbox)" "200"

echo "【3】电脑 → 手机（outbox）"
rm -rf /tmp/rg && mkdir -p /tmp/rg/sub && echo hello > /tmp/rg/a.txt && echo world > /tmp/rg/sub/b.txt
curl -s -X POST -H 'Content-Type: application/json' -d '{"paths":["/tmp/rg"]}' $BASE/api/outbox/add >/dev/null
chk "目录展开成 2 条" "$(curl -s $BASE/api/outbox | python3 -c "import json,sys;print(len(json.load(sys.stdin)['items']))")" "2"
ID=$(curl -s $BASE/api/outbox | python3 -c "import json,sys;print(json.load(sys.stdin)['items'][0]['id'])")
chk "手机可取（带令牌）" "$(curl -s -o /dev/null -w %{http_code} -H "X-DT-Token: $TOKEN" $LAN/api/outbox/file/$ID)" "200"
chk "Range 续传" "$(curl -s -o /dev/null -w %{http_code} -H "X-DT-Token: $TOKEN" -H 'Range: bytes=0-2' $LAN/api/outbox/file/$ID)" "206"
chk "未配对取不走" "$(curl -s -o /dev/null -w %{http_code} $LAN/api/outbox/file/$ID)" "401"

echo "【4】文字互传"
pbcopy < /dev/null
curl -s -X POST -H "X-DT-Token: $TOKEN" -H 'Content-Type: application/json' -d '{"text":"回归测试文本 https://x.example","device_id":"regress","device_name":"回归测试"}' $LAN/api/inbox/text >/dev/null
chk "文字进电脑剪贴板" "$(pbpaste)" "回归测试文本 https://x.example"
curl -s -X POST -H 'Content-Type: application/json' -d '{"text":"发给手机的一段话"}' $BASE/api/outbox/text >/dev/null
chk "文字条目带 text 字段" "$(curl -s $BASE/api/outbox | python3 -c "
import json,sys
print('yes' if any(i.get('text') for i in json.load(sys.stdin)['items']) else 'no')")" "yes"

echo "【5】USB"
DEV=$(curl -s $BASE/api/device_status | python3 -c "import json,sys;print(json.load(sys.stdin)['connected'])")
chk "设备已连接" "$DEV" "True"
curl -s -X POST $BASE/api/scan >/dev/null; sleep 6
chk "扫到相册" "$(curl -s $BASE/api/scan_result | python3 -c "import json,sys;print(len(json.load(sys.stdin)['albums'])>0)")" "True"

echo "【6】界面异常上报"
chk "上报接口可用" "$(curl -s -X POST -H 'Content-Type: application/json' -d '{"message":"regress probe","where":"/"}' $BASE/api/client_error | python3 -c "import json,sys;print(json.load(sys.stdin)['success'])")" "True"
chk "空消息被拒" "$(curl -s -o /dev/null -w %{http_code} -X POST -H 'Content-Type: application/json' -d '{"message":""}' $BASE/api/client_error)" "400"

echo "【7】清理"
curl -s -X POST -d '{}' $BASE/api/outbox/remove >/dev/null
chk "队列已清空" "$(curl -s $BASE/api/outbox | python3 -c "import json,sys;print(json.load(sys.stdin)['count'])")" "0"

echo
echo "通过 $pass 项，失败 $fail 项"
exit $fail
