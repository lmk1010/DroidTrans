<div align="center">

# DroidTrans / 卓传

<img src="app_logo.svg" width="120" height="120" alt="DroidTrans Logo">

**手机与电脑之间传东西：双向、任意文件、不挑品牌**

</div>

## 能做什么

| | 说明 |
| --- | --- |
| **手机 → 电脑** | Wi-Fi 直传，或插 USB 线由电脑直接拉取相册 |
| **电脑 → 手机** | 文件/文件夹拖进桌面窗口，手机上一键收走 |
| **任意文件** | 不只是照片视频；文档、压缩包、安装包都能传 |
| **文字 / 链接** | 两个方向都能发，手机发来的会自动进电脑剪贴板 |
| **系统分享菜单** | 任何 App 里「分享 → 卓传」直达电脑 |
| **断点续传** | 两个方向都支持，中断后接着传，不重复搬运 |
| **配对码** | 默认要求配对，扫电脑上的二维码即完成，不用手输 |
| **免路由直连** | 没有路由器时手机开热点，电脑连上照样传 |

## 目录

| 目录 | 是什么 | 怎么跑 |
| --- | --- | --- |
| `android/` | 手机 App | Android Studio 打开该目录 |
| `desktop/` | **Go 桌面端**（HTTP 9500 + TCP 9501 + FTP 9502） | `cd desktop && ./build.sh` |
| `scripts/` | 端到端回归脚本 | `./scripts/regress.sh` |

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

macOS 会得到 `dist/DroidTrans.app`。未签名时先拖进应用程序，再执行：

```bash
xattr -cr /Applications/DroidTrans.app
```

无界面只听端口：

```bash
../dist/droidtrans -headless
```

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
versionCode 取 CI run number，versionName 取 tag 名。

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
