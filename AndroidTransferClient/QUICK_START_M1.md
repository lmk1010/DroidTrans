# 🍎 M1/M2/M4 Mac 快速打包指南

## 🎯 适用对象

- 只会 HTML 前端和 Python 后端
- 使用 M1/M2/M4 芯片的 MacBook
- 想要快速打包成可安装的 `.dmg` 文件

## ⚡ 超快速打包（3分钟完成）

### 步骤 1: 打开终端

```bash
# 进入项目目录
cd AndroidTransferClient
```

### 步骤 2: 测试环境（可选但推荐）

```bash
# 运行环境测试脚本
./test_build.sh
```

如果显示 "🎉 环境检查通过"，继续下一步。

### 步骤 3: 一键打包

```bash
# M1/M2/M4 专用打包脚本
./build_m1.sh
```

**等待 3-5 分钟**，脚本会自动完成：
1. ✅ 创建 Python 虚拟环境
2. ✅ 安装所有依赖
3. ✅ 打包 Python 后端
4. ✅ 打包 Electron 应用

### 步骤 4: 找到安装包

打包完成后，在 `dist/` 目录找到：

```
dist/
├── Android Transfer-1.0.0-arm64.dmg  ← 安装包（推荐）
└── Android Transfer-1.0.0-arm64.zip  ← 便携版
```

## 🎊 完成！

双击 `.dmg` 文件，拖动到 Applications 即可使用！

---

## 📋 详细步骤说明

### 环境准备（首次需要）

如果你是第一次打包，需要安装以下工具：

#### 1. 安装 Homebrew（如果还没有）

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

#### 2. 安装 Python 3

```bash
brew install python3
```

验证安装：
```bash
python3 --version
# 应显示: Python 3.x.x
```

#### 3. 安装 Node.js

```bash
brew install node
```

验证安装：
```bash
node --version
npm --version
```

### 打包流程详解

#### 自动打包（推荐）

```bash
# 一条命令完成所有步骤
./build_m1.sh
```

#### 手动打包（了解原理）

如果你想了解打包过程，可以分步执行：

**步骤 1: 准备 Python 环境**

```bash
# 创建虚拟环境
python3 -m venv venv

# 激活虚拟环境
source venv/bin/activate

# 安装依赖
pip install -r requirements.txt
pip install pyinstaller
```

**步骤 2: 打包 Python 后端**

```bash
# 清理旧文件
rm -rf build dist dist_app

# 使用 PyInstaller 打包
pyinstaller app.spec --clean

# 移动结果
mv dist/app dist_app

# 退出虚拟环境
deactivate
```

**步骤 3: 安装 Node.js 依赖**

```bash
npm install
```

**步骤 4: 打包 Electron 应用**

```bash
# 打包 macOS arm64 版本
npm run dist:mac
```

## 🐛 常见问题

### 问题 1: `command not found: python3`

**解决**：
```bash
brew install python3
```

### 问题 2: `command not found: node`

**解决**：
```bash
brew install node
```

### 问题 3: 打包时提示权限错误

**解决**：
```bash
# 给脚本添加执行权限
chmod +x build_m1.sh test_build.sh
```

### 问题 4: PyInstaller 打包失败

**解决**：
```bash
# 重新创建虚拟环境
rm -rf venv
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
pip install pyinstaller
./build_backend.sh
```

### 问题 5: Electron Builder 报错

**解决**：
```bash
# 清理 node_modules 重新安装
rm -rf node_modules
npm install
npm run dist:mac
```

### 问题 6: 安装包无法打开

**症状**: "无法打开，因为它来自身份不明的开发者"

**解决**：
```bash
# 方法 1: 右键点击应用，选择"打开"

# 方法 2: 命令行移除隔离属性
sudo xattr -rd com.apple.quarantine /Applications/Android\ Transfer.app
```

### 问题 7: 打包后体积很大

这是正常的，因为包含了：
- Python 解释器和所有库
- Node.js 运行时
- Electron 框架
- 所有依赖

通常大小：80-100 MB

## 📊 打包时间参考

在 M1/M2/M4 Mac 上：

- **首次打包**: 5-8 分钟（需要下载依赖）
- **后续打包**: 2-3 分钟
- **仅后端**: 30 秒
- **仅前端**: 1-2 分钟

## 💡 提示和技巧

### 技巧 1: 加速打包

```bash
# 如果只修改了 Python 代码，只需重新打包后端
./build_backend.sh
npm run dist:mac

# 如果只修改了前端，跳过后端打包
npm run dist:mac
```

### 技巧 2: 开发模式测试

打包前先在开发模式测试：

```bash
# 测试 Python 后端
python3 app.py
# 访问 http://localhost:9500

# 测试 Electron 集成
npm start
```

### 技巧 3: 查看打包日志

```bash
# 打包时保存日志
./build_m1.sh 2>&1 | tee build.log

# 查看日志
cat build.log
```

## 📦 打包后的测试

### 1. 基本功能测试

```bash
# 打开 dmg
open dist/Android\ Transfer-1.0.0-arm64.dmg

# 拖动到 Applications 后测试
open /Applications/Android\ Transfer.app
```

### 2. 检查应用架构

```bash
# 验证是否是 arm64
file /Applications/Android\ Transfer.app/Contents/MacOS/Android\ Transfer
# 应显示: arm64
```

### 3. 功能测试清单

- [ ] 应用能正常启动
- [ ] USB 模式能识别设备
- [ ] WiFi 模式能显示 IP
- [ ] 能扫描照片
- [ ] 能传输照片
- [ ] 数据库正常工作

## 🎓 进阶：自定义打包

### 修改应用名称

编辑 `package.json`:
```json
{
  "productName": "你的应用名"
}
```

### 修改应用图标

替换以下文件：
- `icon.icns` (macOS)
- `icon.ico` (Windows)
- `icon.png` (Linux)

### 修改版本号

编辑 `package.json`:
```json
{
  "version": "1.0.0"
}
```

## 📚 相关文档

- [完整打包指南](BUILD_GUIDE.md)
- [通用打包脚本](build_all.sh)
- [项目 README](README.md)

## 🆘 需要帮助？

1. 先运行 `./test_build.sh` 检查环境
2. 查看终端输出的错误信息
3. 参考上面的"常见问题"部分
4. 查看完整的 [BUILD_GUIDE.md](BUILD_GUIDE.md)

---

**祝打包顺利！** 🎉

如果成功了，你将得到一个可以安装在任何 M1/M2/M4 Mac 上的应用！

