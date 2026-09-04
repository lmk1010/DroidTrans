# 卓传 iOS

原生 Swift + SwiftUI，不依赖任何第三方库。

Android 端保持原生 Java（`android/`），两端各自用自己平台最顺手的方式实现同一套协议 ——
这个 App 的核心全是系统能力（Bonjour、本地网络权限、PhotoKit、Share Extension），
跨平台框架在这些地方每一个都要多垫一层插件。

## 构建

```bash
brew install xcodegen        # 只需一次
xcodegen generate            # 生成 DroidTrans.xcodeproj
open DroidTrans.xcodeproj
```

`.xcodeproj` 是生成物，不在版本库里。**加文件、加 target、改配置都改 `project.yml`**，
然后重新 `xcodegen generate`。

命令行构建与测试：

```bash
xcodebuild build -project DroidTrans.xcodeproj -scheme DroidTrans \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator'

xcodebuild test -project DroidTrans.xcodeproj -scheme DroidTrans \
  -destination 'platform=iOS Simulator,name=iPhone 16'
```

## 协议一致性

传输协议现在有三份实现：

| | 位置 |
| --- | --- |
| 服务端 | `desktop/internal/fast/fast.go` |
| Android | `android/.../network/` |
| iOS | `ios/DroidTrans/Core/Protocol.swift` |

各自的单测只能证明「自己和自己一致」。字段错位、大小端写反、长度按字符而不是
字节算 —— 这些在真机上都表现为「传到一半失败」，是最难查的一类问题。

所以有一道跨语言对拍：Swift 生成帧字节，**Go 服务端用真正的 `readHeader` 解析回来**。

```bash
ios/Tools/atf2vec/run.sh                  # 重新生成向量
cd desktop && go test ./internal/fast/    # Go 端验证
```

改了线格式就跑这两条。

## Info.plist 里几个不能少的键

都写在 `project.yml` 里，少任何一个都是**静默失效**，不会报错：

- `NSBonjourServices` — 少了它 `NWBrowser` 永远扫不到东西
- `NSLocalNetworkUsageDescription` — 少了它系统不弹权限框，等于用户默认拒绝
- `NSAppTransportSecurity.NSAllowsLocalNetworking` — 少了它 ATS 掐掉所有
  `http://192.168.x.x:9500` 的请求

## 目录

```
DroidTrans/
  App/        入口
  Core/       协议、发现、传输、存储 —— 不含任何 UI
  Features/   按功能分的界面
  Resources/  Info.plist
Tools/
  atf2vec/    跨语言对拍向量生成器
```

## 上架 App Store

### 一次性配置（已经在 `project.yml` 里了，别删）

- `TARGETED_DEVICE_FAMILY: "1"` — 只做 iPhone。XcodeGen 默认写 `"1,2"`，
  那样 App Store Connect 会强制要一套 iPad 截图，审核也会在 iPad 上跑这个竖屏界面。
- `ITSAppUsesNonExemptEncryption: false` — 只有 Curve25519 验签，属出口管制豁免。
  不写这条每次上传都要在网页上手工回答一遍。
- `Resources/PrivacyInfo.xcprivacy` — 隐私清单，主 App 和分享扩展各带一份。
  用了新的 required-reason API（磁盘空间、开机时间、键盘列表…）要回去补，
  漏一个整包收 ITMS-91053 警告。

### 截图

App Store 现在只强制 6.9"（1320×2868）一档，也就是 iPhone 16 Pro Max。
用 6.3" 的 iPhone 16 Pro 截出来是 1206×2622，传不上去。

```bash
# 原始截图（中英各一套）
xcodebuild test -project DroidTrans.xcodeproj -scheme DroidTrans \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro Max' \
  -only-testing:DroidTransUITests/ScreenshotTests \
  -resultBundlePath /tmp/shots.xcresult
# 加 -testLanguage en -testRegion US 出英文那套

# 导出成 Design/appstore/raw/{zh,en}-0N-*.png 之后，套上标题和背景
python3 Tools/appstore-shots.py
```

成品在 `Design/appstore/{zh,en}/`，1320×2868，直接传。
背景那团光是出图服务生成的（`scripts/gen-image.sh`），纯 CSS 渐变在 2868px
高度上有肉眼可见的色带。

### 付费页的规矩

`Features/Me/ProView.swift` 上写的每一条都必须对应真实的、已经接了门控的能力。
审核看不出这笔钱买到什么，会按 3.1.2 打回；写了实际没有的功能是另一档问题。

页面底部的「服务条款 / 隐私政策」两个链接不能少，审核会点。
我们没有自己的 EULA，用 Apple 的标准版就是合规的。

### 提交前还需要人工做的

1. **Xcode 登录开发者账号**（Settings → Accounts）。这台机器现在只有
   `Developer ID Application`（那是 macOS 公证用的），没有 iOS 分发证书，
   `xcodebuild archive` 会直接报 `No Accounts` / `No profiles`。
2. App Store Connect 里建三个商品，ID 与 `Resources/Products.storekit` 一致：
   `…pro.year`、`…pro.years3`（非续期订阅）、`…pro.lifetime`（非消耗型）。
3. 填隐私问卷：我们不收集任何数据，全选「不收集」，与 `PrivacyInfo.xcprivacy` 一致。


## UI 测试怎么跑（不给全环境变量就是白跑）

```bash
CODE=$(curl -s http://127.0.0.1:9500/api/pair/info \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['code'])")

env TEST_RUNNER_DT_DESKTOP=<Mac 的局域网 IP> TEST_RUNNER_PAIR_CODE=$CODE \
    DT_HOST=<Mac 的局域网 IP> DT_CODE=$CODE \
    TEST_RUNNER_SEND_TEXT=hello \
    TEST_RUNNER_LICENSING_BASE=https://droidtrans.mkstore.life \
  xcodebuild test -project DroidTrans.xcodeproj -scheme DroidTrans \
    -destination 'platform=iOS Simulator,name=iPhone 16 Pro,arch=arm64' \
    -resultBundlePath /tmp/r.xcresult
```

**别看命令行那句 `** TEST SUCCEEDED **`。** 全部 skip 掉也会打印它。
每次都去结果包里数：

```bash
xcrun xcresulttool get test-results tests --path /tmp/r.xcresult
```

### 三个把人骗过去的坑

**一、`xcodebuild` 只转发 `TEST_RUNNER_` 前缀的环境变量。**
写 `PAIR_CODE=…` 传不进测试进程，用例会静默 skip，而 skip 在输出里
跟 pass 长得几乎一样。

**二、判断「已进主界面」不能用两屏都有的元素。**
`open-me` 在启动选择页和主界面上都有，拿它当判据的话，脚手架在启动页
就以为连上了直接返回，后面每一步都对不上 —— 报出来是「雷达上没出现电脑」，
看着像网络问题。`ConnectFlowTests` / `ProGateTests` / `ReconnectTests`
在启动选择页上线之后就一直红着或跳着，根因全是这个。
现在统一用只有主界面才有的 `open-gallery`。

**三、加了新的一屏，所有自己 launch 的用例都要跟着改。**
不走 `launchConnected` 的用例（`ConnectFlowTests`、`ReconnectTests`）
得自己先点 `start-desktop`，否则永远走不到雷达。

### 状态污染

StoreKit 测试环境里的交易是**跨次持久**的。跑过一次购买用例之后，
下一轮的「免费版应该被挡住」会失败，报「门禁没生效」——
看着像产品 bug，其实是上一轮的状态。

`-uitest-no-license` 现在会同时忽略 StoreKit 已持有的交易
（`IAP.refreshOwned` 里的 `pretendNotPurchased`）。
注意只作用于启动时恢复历史交易那一步：**当次购买必须照常走完**，
在购买回调里提前 return 会跳过兑换和 `t.finish()`，交易不 finish 就会一直重放。
