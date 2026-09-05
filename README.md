<div align="center">

# DroidTrans / 卓传

<img src="app_logo.svg" width="120" height="120" alt="DroidTrans Logo">

**手机与电脑之间传东西：双向、任意文件、不挑品牌**

当前版本 **1.0.3**（见仓库根目录 [`VERSION`](VERSION)）

</div>

## 下载

正式包托管在 `https://droid.mkstore.life/`：

| 平台 | 链接 |
| --- | --- |
| **Android APK** | https://droid.mkstore.life/latest.apk |
| **macOS (Apple Silicon)** | https://droid.mkstore.life/DroidTrans-1.0.3-macos-arm64.dmg |
| **版本清单** | https://droid.mkstore.life/latest.json |

桌面端启动后会检查 `latest.json`；有新版时界面顶部提示，点「立即更新」打开下载。

macOS 版本经 Apple 开发者证书签名并公证，拖进「应用程序」双击即可打开。

## 能做什么

| | 说明 |
| --- | --- |
| **手机 → 电脑** | Wi-Fi 直传，或插 USB 线由电脑直接拉取相册 |
| **电脑 → 手机** | 文件/文件夹拖进桌面窗口，手机上一键收走 |
| **任意文件** | 不只是照片视频；文档、压缩包、安装包都能传。免费版单个文件 4 GB 以内，Pro 不限 |
| **文字 / 链接** | 两个方向都能发，手机发来的会自动进电脑剪贴板 |
| **系统分享菜单** | 任何 App 里「分享 → 卓传」直达电脑 |
| **断点续传** | 两个方向都支持，中断后接着传，不重复搬运 |
| **备份** | Time Machine 式：每次备份留一份完整的，但只占新增那部分的空间 |
| **配对码** | 默认要求配对，扫电脑上的二维码即完成，不用手输 |
| **免路由直连** | 没有路由器时手机开热点，电脑连上照样传 |

## 目录

| 目录 | 是什么 | 怎么跑 |
| --- | --- | --- |
| `ios/` | **iOS 端**（Swift + SwiftUI，原生） | `cd ios && xcodegen generate && open DroidTrans.xcodeproj` |
| `android/` | **安卓端**（Java，原生） | Android Studio 打开该目录 |
| `desktop/` | **Go 桌面端**（HTTP 9500 + TCP 9501 + FTP 9502） | `cd desktop && ./build.sh` |
| `site/` | **官网**（Vite + React + TS），部署在 `droidtrans.mkstore.life` | `./scripts/deploy-site.sh` |
| `scripts/` | 构建、发布、回归脚本 | 见下 |
| `web/` | 历史遗留，浏览器版早已并进 `desktop/`，勿在此新增东西 | — |

两端都走原生，不做跨平台统一。曾经有过一个 Flutter 的 `mobile/` 想同时吃下
两端，在 iOS 转 Swift 原生之后它就没有存在的理由了，已删除。

版本号只有一处来源：仓库根的 `VERSION`。桌面端、手机端、发布清单都从它读，
不要在 gradle 或 Xcode 工程里另写一份。

分发的两条线也别搞混：

| 东西 | 放在哪 | 域名 | 怎么发 |
| --- | --- | --- | --- |
| 官网页面 | 服务器上的 neox-nginx 容器（地址见 `~/.neox-secrets/droidtrans-deploy.env`） | `droidtrans.mkstore.life` | `./scripts/deploy-site.sh` |
| APK / DMG / `latest.json` | Cloudflare R2 桶 `droidtrans` | `droid.mkstore.life` | `./scripts/upload-r2.sh` |
| iOS | App Store | — | Xcode Archive 后上传 |

**两个域名不要搞混**：`droid.mkstore.life` 是 R2 桶的自定义域，只放安装包和
`latest.json`；官网是 `droidtrans.mkstore.life`，在服务器上。

官网部署复用服务器已有的 neox-nginx 容器（和 `openexam.cc` 同一套模式），
不额外起容器。vhost 在 `deploy/droidtrans.mkstore.life.conf`，由部署脚本同步过去，
**别直接在服务器上改**。HTTPS 由 Cloudflare 终结，源站只监听 80。

官网和 R2 不同源，所以 vhost 里把 `/latest.json` 反代到了 R2（避开 CORS），
安装包则 302 跳过去（不占服务器带宽）。

### 官网

```bash
cd site
npm install
npm run dev      # 本地开发
npm run build    # 构建 + 预渲染，产物在 site/dist
```

页面文案在 `site/src/i18n/zh.ts` 和 `en.ts`，两份结构由 TS 类型锁死，
漏翻一个字段就编译不过。改文案不用碰组件。

**中文在根路径，英文在 `/en/`** —— 每个语言有独立 URL，搜索引擎才能分别收录。
构建会为 8 个页面（4 页 × 2 语言）各生成一份真实 HTML，并写出
`sitemap.xml`、`robots.txt`，以及 `canonical` / `hreflang` 头。
构建最后一步会做预渲染（`prerender.mjs`），把每页渲染成真实 HTML 写进产物——
不做的话爬虫和禁用 JS 的访客只会看到一个空的 `<div id="root">`。

版本号和下载链接由页面在运行时读 `latest.json` 得到，所以**发新版不需要重新构建官网**。

官网跑在服务器的 neox-nginx 容器里（和 `openexam.cc` 同一套模式），
`site/Dockerfile` 是一份独立镜像的备用方案，产物完全一样，需要单独跑一个容器时用。

### 验证

```bash
./scripts/check-frontend.py    # 界面静态检查（悬空引用、GET 带 body、缺文案）
./scripts/regress.sh           # 桌面端端到端回归，19 项
./scripts/regress-phone.sh     # 手机端冒烟，9 项（需连着手机/模拟器）
```

桌面端要先跑起来；手机端冒烟全程用 intent 驱动，不依赖屏幕坐标。

桌面端是一个 Go 二进制，界面内嵌。不再使用 Flask / Python / Electron / Tauri。

手机 Wi-Fi 传文件仍然连 `http://电脑IP:9500`，协议与原来一致。

### ADB

无线配对端口需要 **platform-tools 36+**。启动时会自动选最新的 `adb`。

```bash
adb pair 192.168.x.x:45999
adb devices
```

## 桌面端

```bash
cd desktop
chmod +x build.sh
./build.sh
../dist/droidtrans
```

macOS 会得到 `dist/DroidTrans.app` 与 `dist/DroidTrans-<VERSION>-macos-arm64.dmg`。

无界面只听端口：

```bash
../dist/droidtrans -headless
```

## 发版

版本号只改仓库根目录 `VERSION`（如 `1.0.3`）。打包会读它：

- 桌面：`desktop/build.sh` 写入二进制、`Info.plist`、DMG 文件名
- Android：`versionName` / `versionCode`（`major*10000+minor*100+patch`）；CI 仍可用环境变量覆盖

上传到 R2（需本机 `~/.neox-secrets/droidtrans-r2.env`）：

```bash
# 先打好桌面 DMG 与 Android release APK
./scripts/upload-r2.sh
```

会写入 `latest.apk`、`DroidTrans-<ver>-macos-arm64.dmg`、`latest.json`。

## 开发

```bash
cd desktop
go test ./...        # 单元测试
go vet ./...
gofmt -l .           # 应当无输出
```

`.github/workflows/ci.yml` 在 push / PR 上跑 gofmt + vet + `go test -race` + 三平台编译 + Android debug 构建；
`release.yml` 只在打 `v*` tag 时出正式产物。

> macOS 的原生窗口是 cgo（Cocoa + WebKit），**必须 `CGO_ENABLED=1`**，交叉编译不出 macOS 版。

Android 正式签名走 CI secrets：`RELEASE_KEYSTORE_BASE64` / `RELEASE_KEYSTORE_PASSWORD` /
`RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`。没配就出未签名包。
本地默认读 `VERSION`；CI 仍可用 `VERSION_CODE` / `VERSION_NAME`（或 tag）覆盖。

## 备份

「已接收」记的是**某一次传输搬了什么**；备份记的是**这些照片在这台电脑上留了几份、
每份差在哪、源那边删了还能不能找回来**。两件事，两个页面。

做法照搬 Time Machine：每次备份生成一个快照目录，里面看起来是完整的一份，
但只有这次新增的文件真的写了一遍，其余全是指向上一份的**硬链接**。

```
备份目录/
  20260904_222203/   a.jpg  sub/b.jpg              ← 第一次，2 个文件真的写了
  20260904_222205/   a.jpg  sub/b.jpg              ← 全是硬链接，0 字节
  20260904_222207/   a.jpg  sub/b.jpg  c.jpg       ← 只有 c.jpg 是新的
  latest -> 20260904_222207
```

三个快照、七个文件，磁盘上只有三份内容。

硬链接还顺带解决了删除：删掉某个快照目录，其他快照里的照片一张都不会少——
删的只是一个链接，文件本身要等最后一个链接消失才真的释放。
**这是「每份都完整」和「只占增量」能同时成立的唯一原因，别改成软链接或拷贝。**

差异判断用「文件名 + 大小」，和跨批次去重同一套标准。算内容哈希更准，
但要把每张照片整个读一遍，一次几千张的备份会慢到没法用，
而它能拦下的那点误差在照片上几乎不存在。

源有两种：USB 连着的安卓手机（扫全部相册），或本机的一个文件夹。
本机源连拷贝都省了，直接硬链接，一秒钟备完。

手机源可以开「插上就备」：数据线插上那一下自动备一次。触发点放在设备刚连上
那一瞬，而不是定时器——插线这个动作本身就是「我要把东西弄下来」，定时器则会
在半夜把一台没插线的手机的计划跑空。同一个计划两次自动备份之间至少隔 30 分钟，
否则接触不良的线一分钟能触发好几次。手动点「立即备份」不受这个限制。

## 安全边界

桌面端监听 `0.0.0.0`，同一局域网内都能连得到。默认**要求配对**：

- Wi-Fi 页显示六位配对码；手机扫二维码（码就在里面）或手输一次，
  换到一个长期令牌，之后每个请求带 `X-DT-Token`。
- 发现类接口（`/api/health`、`/api/wifi/info`、`/api/fast/caps`、`/api/pair`）保持开放，
  否则手机连「这台电脑在不在」都问不出来。桌面端界面走本机，不受配对影响。
- TCP 高速通道用 `ATF2` 头带令牌，FTP 用 `PASS` 当令牌——
  否则这两条路会绕开配对。
- 配对信息存在输出目录的 `pairing.json`（不进版本库），可换码、可撤销单台设备。

此外：

- HTTP 接口只认 **IP / localhost 形式的 Host**，用域名指向 127.0.0.1 的网页（DNS rebinding）会被 403；
- 带 `Origin` 的请求只放行本机与纯 IP 来源，外部网站没法借浏览器驱动这套接口（CSRF）；
- 手机 App 是原生请求（无 Origin），照常放行；
- 删除类接口只接受输出目录内的路径，`device_id` / `batch_id` 不允许带 `/` 或 `..`。

配对可以在界面上关掉（回到「同网段谁都能连」），但不建议在公共 Wi-Fi 下这么做。
