/// 中文文案。英文版在 en.ts，两份的结构必须一致（TS 会强制检查）。
///
/// 改文案只动这两个文件，不碰组件。

import {
  IconUpload, IconDownload, IconFiles, IconLink, IconResume,
  IconShield, IconHotspot, IconShare,
} from '../components/Icons'

export const zh = {
  code: 'zh' as 'zh' | 'en',
  htmlLang: 'zh-CN',
  label: '中文',
  /// 语言切换按钮上显示的「对面那个语言」
  switchTo: { href: 'en', label: 'EN', title: 'Switch to English' },

  nav: { features: '功能', changelog: '更新', pricing: '定价', support: '支持', download: '下载' },

  hero: {
    eyebrow: '本地直连，文件不上云',
    title: ['手机和电脑之间', '传东西，就这么简单'],
    lead: ['双向互传，任意文件，不挑品牌。走你自己的 Wi-Fi，文件从手机', '直接', '到电脑，不经过任何服务器。'],
    primary: '免费下载',
    secondary: '看看怎么用',
    meta: (v: string, d: string) => `最新版本 ${v} · ${d} · macOS 与 Android 现已可用`,
  },

  features: {
    eyebrow: '功能',
    title: '日常要用的，都在这儿',
    desc: '不是功能清单堆砌，是把「在手机和电脑之间搬东西」这件事做完整。',
    items: [
      { icon: IconUpload, title: '手机 → 电脑', body: 'Wi-Fi 直传；也可以插 USB 线，由电脑直接把整个相册拉过去。' },
      { icon: IconDownload, title: '电脑 → 手机', body: '文件或整个文件夹拖进桌面窗口，手机上一键收走，目录结构照旧。' },
      { icon: IconFiles, title: '任意文件', body: '不只是照片视频。文档、压缩包、安装包都能传，免费版单个文件 4 GB 以内。' },
      { icon: IconLink, title: '文字和链接', body: '两个方向都能发。手机发过来的自动进电脑剪贴板，直接就能粘。' },
      { icon: IconResume, title: '断点续传', body: '两个方向都支持。中断之后接着传，已经过去的部分不重复搬。' },
      { icon: IconShield, title: '配对才能连', body: '扫一次二维码换一个长期令牌。同一个局域网里，别人连不进来。' },
      { icon: IconHotspot, title: '没有路由器也行', body: '手机开热点，电脑连上去，照传不误。出差在酒店也不耽误事。' },
      { icon: IconShare, title: '系统分享菜单', body: '任何 App 里「分享 → 卓传」，直接送到电脑，不用先存一份。' },
    ],
  },

  steps: {
    eyebrow: '上手',
    title: '配一次，以后打开就能用',
    desc: '没有账号，不用注册，不需要登录任何东西。',
    items: [
      { title: '电脑上打开卓传', body: '桌面端会显示一个二维码和局域网地址。两台设备连同一个 Wi-Fi。' },
      { title: '手机扫码配对', body: '扫那个二维码就完成了，不用手输。一台电脑只需要配一次。' },
      { title: '开始传', body: '选文件发过去，或者把电脑那边准备好的东西一键收走。' },
    ],
  },

  cta: { title: '现在就把它装上', desc: '桌面端和手机端配合使用。全部免费。', button: '前往下载' },

  download: {
    eyebrow: '下载',
    title: '选你的设备',
    desc: '手机端和桌面端配合使用：电脑上装一个，手机上装一个，然后扫码配对。',
    soon: '敬请期待',
    getDmg: '下载 DMG',
    getApk: '下载 APK',
    pendingIos: '即将上架 App Store',
    footnote:
      'macOS 版本已通过 Apple 签名与公证，拖进「应用程序」双击即可打开，不需要任何额外设置。',
    platforms: [
      {
        id: 'macos' as const, name: 'macOS', tagline: '桌面端，传输的另一头',
        requirement: 'macOS 12 及以上　·　Apple Silicon',
        points: [
          { text: '拖进窗口即可发给手机' },
          { text: 'USB 直接拉取手机相册' },
          { text: '手机发来的文字自动进剪贴板' },
          { text: '一个独立程序，不装后台服务' },
        ],
      },
      {
        id: 'android' as const, name: 'Android', tagline: '手机端',
        requirement: 'Android 7.0 及以上',
        points: [
          { text: '相册、任意文件、文字链接' },
          { text: 'USB 与 Wi-Fi 双通道' },
          { text: '系统分享菜单直达' },
          { text: '断点续传' },
        ],
      },
      {
        id: 'ios' as const, name: 'iPhone / iPad', tagline: '手机端',
        requirement: 'iOS 15.5 及以上',
        points: [
          { text: '相册、文件、文字链接' },
          { text: '系统分享菜单直达' },
          { text: '断点续传' },
          { text: 'USB 直连（iOS 系统不开放）', off: true },
        ],
        pending: true,
      },
    ],
  },

  compat: {
    eyebrow: '兼容性',
    title: '各平台的差异',
    desc: '系统本身的限制，先说清楚，免得装了才发现。',
    head: { feature: '功能', android: 'Android', ios: 'iOS' },
    rows: [
      { name: 'Wi-Fi 双向互传', android: true, ios: true },
      { name: '任意文件收发', android: true, ios: '经「文件」App' },
      { name: '文字 / 链接互传', android: true, ios: true },
      { name: '断点续传', android: true, ios: true },
      { name: '系统分享菜单', android: true, ios: true },
      { name: '扫码配对', android: true, ios: true },
      { name: 'USB 线直接拉相册', android: true, ios: '系统不开放' },
      { name: '手机开热点直连', android: true, ios: '需手动开热点' },
    ] as { name: string; android: boolean | string; ios: boolean | string }[],
  },

  pricing: {
    eyebrow: '定价',
    title: '基础功能永远免费',
    desc: '局域网互传、断点续传、配对、分享菜单，一样不少，也不设任何限制。付费买的是省事，不是能用。',
    freeTitle: '免费',
    freeNote: '不需要注册，不需要付费',
    free: [
      'Wi-Fi 双向传输，单个文件 4 GB 以内',
      '断点续传，中断了接着传',
      '扫码配对，局域网内别人连不进来',
      '文字与链接互传',
      '系统分享菜单直达',
      'USB 手动传照片',
      '照片图库导出，每次 1000 项',
    ],
    proTitle: 'Pro',
    proNote: '一个激活码，macOS、Android、iOS 都能用',
    pro: [
      '单个文件不限大小',
      '照片图库导出不限量',
      '相册增量同步：只传新增的，已传过的自动跳过',
      '实况照片完整导出：静态图和动态一起',
      'USB 整册导入',
      '跨批次去重',
      '后续新增的高级功能',
    ],
    periods: { year: '一年', years3: '三年', lifetime: '终生' },
    hint: { year: '', years3: '相当于买两年送一年', lifetime: '一次买断，不再续费' },
    buy: '购买',
    buying: '正在跳转…',
    failed: '下单失败，稍后再试',
    devices: '一个激活码可在 5 台设备上激活',
    refund: '不满意可以退款',
    haveCode: '已经买过？在桌面端的设置里输入激活码即可。',
  },

  thanks: {
    eyebrow: '购买成功',
    title: '这是你的激活码',
    titlePlain: '感谢你的购买',
    desc: '请把它保存好。在桌面端「设置 → 授权」里输入这个码，高级功能立刻解锁。',
    descPlain: '如果这个页面没有显示你的激活码，把付款用的邮箱告诉我们，会马上补给你。',
    codeLabel: '激活码',
    waiting: '正在签发，请稍候…',
    copy: '复制',
    copied: '已复制',
    failed: '暂时没能取到你的激活码。付款是成功的，别担心 —— 联系我们并附上付款邮箱，会立刻补给你。',
    steps: [
      '打开桌面端的卓传',
      '进入设置 → 授权',
      '粘贴激活码，点激活',
    ],
    note: '一个激活码可以在 5 台设备上激活，macOS、Android、iOS 通用。',
    back: '回到首页',
    support: '联系我们',
  },

  footer: {
    home: '首页', download: '下载', changelog: '版本记录', pricing: '定价', support: '支持', privacy: '隐私政策',
    tagline: '文件在你的设备之间直接传输，不经过我们的服务器。',
  },

  meta: {
    index: {
      title: '卓传 DroidTrans — 手机和电脑之间传东西',
      desc: '手机与电脑双向互传：任意文件、不挑品牌、走本地 Wi-Fi 直连，文件不经过任何服务器。支持 Android、iOS、macOS。',
    },
    download: {
      title: '下载 — 卓传 DroidTrans',
      desc: '下载卓传：macOS 桌面端、Android APK，iOS 版即将上架 App Store。',
    },
    privacy: { title: '隐私政策 — 卓传 DroidTrans', desc: '卓传不收集、不上传、不存储你的任何文件和个人信息。' },
    support: { title: '支持 — 卓传 DroidTrans', desc: '卓传的常见问题与联系方式。' },
    pricing: { title: '定价 — 卓传 DroidTrans', desc: '基础功能永远免费。Pro 一个激活码通用三端：一年 $5.99、三年 $11.99、终生 $14.99。' },
    thanks: { title: '购买成功 — 卓传 DroidTrans', desc: '你的卓传 Pro 激活码。' },
    changelog: { title: '版本记录 — 卓传 DroidTrans', desc: '卓传每个版本改了什么。' },
  },
}

export type Dict = typeof zh
