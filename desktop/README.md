# desktop — DroidTrans（Go）

一个二进制：HTTP `9500` + TCP `9501` + FTP `9502`，内嵌界面。不再使用 Flask / Python / Tauri。

```bash
cd desktop
chmod +x build.sh
./build.sh
./../dist/droidtrans          # 打开界面
./../dist/droidtrans -headless # 只听端口
```

macOS 会生成 `dist/DroidTrans.app`。未签名，若 Gatekeeper 拦截：先拖进应用程序，再 `xattr -cr /Applications/DroidTrans.app`。
