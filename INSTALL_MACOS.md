# macOS 安装说明

## 安装步骤

### 方法一：使用 ZIP 文件（推荐）

1. 下载 `Android-Transfer-1.0.0-arm64.zip`（M1/M2/M3/M4 Mac）或 `Android-Transfer-1.0.0-x64.zip`（Intel Mac）
2. 解压 ZIP 文件
3. 将 `Android Transfer.app` 拖动到 `Applications` 文件夹
4. 打开终端，执行以下命令移除隔离属性：
   ```bash
   sudo xattr -rd com.apple.quarantine /Applications/Android\ Transfer.app
   ```
5. 双击运行应用

### 方法二：使用 DMG 文件

1. 下载对应架构的 DMG 文件
2. 打开 DMG 文件
3. 将应用拖动到 `Applications` 文件夹
4. **重要**：打开终端，执行以下命令：
   ```bash
   sudo xattr -rd com.apple.quarantine /Applications/Android\ Transfer.app
   ```
5. 双击运行应用

## 为什么需要执行 xattr 命令？

由于应用未经过 Apple 官方代码签名，macOS Gatekeeper 会将其标记为"已损坏"。执行 `xattr` 命令可以移除隔离属性，允许应用正常运行。

这是开源软件的常见做法，完全安全。

## 首次运行

首次运行时，如果仍然遇到安全提示：

1. 打开 `系统偏好设置` > `隐私与安全性`
2. 在底部找到被阻止的应用
3. 点击"仍要打开"

## 芯片架构选择

- **M1/M2/M3/M4 Mac (Apple Silicon)**：下载 `arm64` 版本
- **Intel Mac**：下载 `x64` 版本

## 遇到问题？

如果仍然无法运行，请尝试：

```bash
# 完全移除隔离属性和扩展属性
sudo xattr -cr /Applications/Android\ Transfer.app

# 赋予执行权限
sudo chmod -R 755 /Applications/Android\ Transfer.app
```
