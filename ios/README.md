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
