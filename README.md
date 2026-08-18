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
