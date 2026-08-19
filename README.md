<div align="center">

# DroidTrans / 卓传

<img src="app_logo.svg" width="120" height="120" alt="DroidTrans Logo">

**Android 照片传输：手机 App · Go 桌面端**

</div>

## 目录

| 目录 | 是什么 | 怎么跑 |
| --- | --- | --- |
| `android/` | 手机 App | Android Studio 打开该目录 |
| `desktop/` | **Go 桌面端**（HTTP 9500 + TCP 9501 + FTP 9502） | `cd desktop && ./build.sh` |

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

桌面端监听 `0.0.0.0`，同一局域网内都能连，**没有账号密码**。因此：

- HTTP 接口只认 **IP / localhost 形式的 Host**，用域名指向 127.0.0.1 的网页（DNS rebinding）会被 403；
- 带 `Origin` 的请求只放行本机与纯 IP 来源，外部网站没法借浏览器驱动这套接口（CSRF）；
- 手机 App 是原生请求（无 Origin），照常放行；
- 删除类接口只接受输出目录内的路径，`device_id` / `batch_id` 不允许带 `/` 或 `..`。

仍然成立的前提：**在不可信的公共 Wi-Fi 下不要开着桌面端**——
9501 (TCP) / 9502 (FTP) 目前对局域网内任何设备开放，没有配对码。
