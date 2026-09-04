#!/usr/bin/env python3
"""把 StoreKit 配置补进 scheme 的 TestAction。

XcodeGen 只认 run 下的 storeKitConfiguration，写在 test 下会被忽略 ——
于是 UI 测试跑起来的 App 拿不到商品，界面上是一排「—」，
而测试本身照样绿着（它测的是界面在不在，不是价格对不对）。

xcodegen generate 之后自动跑，见 project.yml 的 postGenCommand。
"""
import re
import sys
from pathlib import Path

SCHEME = Path(__file__).resolve().parent.parent / \
    "ios/DroidTrans.xcodeproj/xcshareddata/xcschemes/DroidTrans.xcscheme"

REF = ('      <StoreKitConfigurationFileReference\n'
       '         identifier = "../../DroidTrans/Resources/Products.storekit">\n'
       '      </StoreKitConfigurationFileReference>\n')

def main() -> int:
    if not SCHEME.exists():
        print(f"找不到 scheme: {SCHEME}", file=sys.stderr)
        return 1

    s = SCHEME.read_text()
    m = re.search(r"<TestAction\b.*?</TestAction>", s, re.S)
    if not m:
        print("scheme 里没有 TestAction", file=sys.stderr)
        return 1

    block = m.group(0)
    if "StoreKitConfigurationFileReference" in block:
        return 0

    fixed = block.replace("</TestAction>", REF + "   </TestAction>")
    SCHEME.write_text(s.replace(block, fixed))
    print("  已把 StoreKit 配置补进 TestAction")
    return 0

if __name__ == "__main__":
    sys.exit(main())
