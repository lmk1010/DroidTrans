# Material Design 设计规范实现说明

## 🎨 设计系统

本应用完全遵循 Google Material Design 设计规范进行重构。

### 颜色系统

#### 主题色（Primary）
- **主色**: `#6200ee` (Deep Purple 700)
- **主色变体**: `#7c3aed`, `#5a00d2`
- **用途**: 主要按钮、AppBar、重点元素

#### 辅助色（Secondary/Accent）
- **辅助色**: `#018786` (Teal 700)
- **用途**: 次要按钮、WiFi模式标识

#### 错误色（Error）
- **错误色**: `#b00020` (Red 800)
- **用途**: 危险操作、错误提示

#### 表面色（Surface）
- **背景**: `#fafafa` (Grey 50)
- **表面**: `#ffffff` (White)
- **分割线**: `rgba(0,0,0,0.12)`

#### 文本色
- **高强调文本**: `rgba(0,0,0,0.87)`
- **中强调文本**: `rgba(0,0,0,0.6)`
- **低强调文本**: `rgba(0,0,0,0.38)`

---

## 📐 排版系统

### 字体族
- **主字体**: Roboto (Google官方字体)
- **等宽字体**: Roboto Mono

### 字重
- Light (300)
- Regular (400)
- Medium (500)
- Bold (700)

### 字号体系
```
H1: 24px (Headline 5)
H2: 20px (Headline 6)
Body: 14px (Body 1)
Caption: 12px
```

### 字间距（Letter Spacing）
- 标题: 0.15px
- 正文: 0.25px
- 说明文字: 0.4px
- 按钮: 1.25px

---

## 🎭 组件规范

### 按钮（Buttons）
- **高度**: 36dp
- **圆角**: 4dp
- **文字**: 全大写，字重500，字间距1.25px
- **阴影**: Elevation 2dp (正常状态), 4dp (hover), 8dp (active)

### 卡片（Cards）
- **圆角**: 8dp
- **阴影**: Elevation 2dp
- **Hover阴影**: Elevation 4dp
- **边距**: 24dp内边距

### 输入框（Text Fields）
- **类型**: Filled Text Field
- **背景**: `rgba(0,0,0,0.04)`
- **聚焦时**: 底部边框2px，主题色
- **圆角**: 顶部4dp

### 进度条（Progress Indicators）
- **类型**: Linear Progress
- **高度**: 4dp
- **颜色**: 主题色
- **圆角**: 2dp

---

## 🌈 阴影系统（Elevation）

Material Design使用阴影来表现层级关系：

```css
/* Elevation 1dp */
box-shadow: 0 1px 2px rgba(0,0,0,0.1);

/* Elevation 2dp */
box-shadow: 0 2px 4px rgba(0,0,0,0.1), 0 8px 16px rgba(0,0,0,0.1);

/* Elevation 4dp */
box-shadow: 0 2px 4px rgba(0,0,0,0.14), 0 4px 8px rgba(0,0,0,0.12);

/* Elevation 8dp */
box-shadow: 0 4px 8px rgba(0,0,0,0.14), 0 8px 16px rgba(0,0,0,0.12);

/* Elevation 16dp */
box-shadow: 0 8px 16px rgba(0,0,0,0.14), 0 16px 24px rgba(0,0,0,0.12);
```

---

## 🎬 动画系统

### 缓动曲线
- **标准**: `cubic-bezier(0.4, 0, 0.2, 1)` - 280ms
- **减速**: `cubic-bezier(0, 0, 0.2, 1)` - 进入动画
- **加速**: `cubic-bezier(0.4, 0, 1, 1)` - 退出动画

### 过渡时间
- **快速**: 150ms
- **标准**: 280ms
- **慢速**: 375ms

---

## 📏 间距系统

Material Design使用8dp网格系统：

- **极小间距**: 4dp
- **小间距**: 8dp
- **标准间距**: 16dp
- **大间距**: 24dp
- **超大间距**: 32dp
- **巨大间距**: 48dp

---

## 🎨 图标系统

### Material Icons
- **USB图标**: 使用Material Design Icons的USB图标
- **WiFi图标**: 使用Material Design Icons的WiFi图标
- **尺寸**: 64dp
- **颜色**: 主题色或辅助色

### 图标SVG路径
所有图标使用Material Design Icons官方路径，确保视觉一致性。

---

## ✅ 交互规范

### 点击反馈（Ripple Effect）
虽然在纯CSS中难以实现完整的Ripple效果，但通过以下方式模拟：
- Hover时显示4%主题色叠加层
- Active时提升阴影层级

### 状态变化
- **正常状态**: 基础样式
- **Hover**: 轻微上移，增加阴影
- **Active**: 进一步增加阴影
- **Disabled**: 12%黑色背景，26%黑色文本

---

## 📱 响应式设计

### 断点
- **移动端**: < 600px
- **平板**: 600px - 1024px
- **桌面**: > 1024px

### 适配策略
- 使用Grid自适应布局
- 最小宽度280px确保移动端可用
- 弹性间距和字号

---

## 🎯 可访问性（Accessibility）

### 对比度
- 文本对比度符合WCAG AA标准
- 主题色与白色对比度 > 4.5:1

### 键盘导航
- 所有交互元素可通过Tab键访问
- 焦点状态清晰可见

### 语义化HTML
- 正确使用语义化标签
- 表单元素有明确的label

---

## 📚 参考资源

- [Material Design 官方指南](https://material.io/design)
- [Material Design 色彩系统](https://material.io/design/color)
- [Material Design 排版](https://material.io/design/typography)
- [Material Components](https://material.io/components)
- [Material Icons](https://fonts.google.com/icons)

---

## 🔄 版本历史

### v2.0 - Material Design重构
- ✅ 完整实现Material Design设计系统
- ✅ 使用Roboto字体
- ✅ 规范的颜色、排版、间距系统
- ✅ 标准的组件样式和交互
- ✅ 优雅的动画和过渡效果

---

## 💡 设计原则

1. **简洁明了**: 减少视觉噪音，突出核心功能
2. **层次分明**: 使用阴影和间距表现层级
3. **一致性**: 所有组件遵循统一的设计语言
4. **可预测**: 交互反馈清晰，符合用户预期
5. **优雅流畅**: 动画自然，过渡平滑

---

Created with ❤️ following Material Design Guidelines

