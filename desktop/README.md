# desktop — DroidTrans 桌面端（Tauri）

比 Electron 小一个数量级：用系统 WebView，安装包大约几 MB 到十几 MB。

打开后会拉起 `web/` 的 Flask（`http://127.0.0.1:9500`）。

```bash
cd desktop/src-tauri
cargo tauri icon ../../web/icon.png
cargo tauri build --bundles app,dmg
```

产物在 `desktop/src-tauri/target/release/bundle/`。
