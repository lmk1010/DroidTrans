/// 版本记录。
///
/// 数据来源：仓库的 git 历史和 tag。中间的 1.0.1 / 1.0.2 没有留下发布记录，
/// 所以不在这里凭空造版本号 —— 1.0.0 之后到 1.0.3 之间的改动，
/// 统一归在 1.0.3 名下。
///
/// 以后每次发版，在这个文件顶部补一条，别再靠事后翻 commit 复原。

export type Entry = { zh: string; en: string; kind?: 'add' | 'fix' }

export type Release = {
  version: string
  date: string
  /// 当前正在分发的版本
  current?: boolean
  summary: { zh: string; en: string }
  entries: Entry[]
}

export const releases: Release[] = [
  {
    version: '1.0.3',
    date: '2026-08-26',
    current: true,
    summary: {
      zh: '手机端界面重整，桌面端加入更新检查。',
      en: 'Reworked the mobile interface and added update checking to the desktop app.',
    },
    entries: [
      { kind: 'add', zh: '桌面端会自动检查新版本，有更新时在界面顶部提示', en: 'The desktop app now checks for updates and shows a notice when one is available' },
      { kind: 'add', zh: '打开手机就能看见「电脑上有几个文件等着取」', en: 'Your phone now shows how many files are waiting on the computer' },
      { kind: 'add', zh: '取走的文件不再占着待取清单', en: 'Files you’ve collected no longer clutter the pending list' },
      { kind: 'add', zh: '免路由直连：手机开热点，电脑连上去照样传', en: 'Router-free transfers — turn on your phone’s hotspot and connect the computer' },
      { kind: 'add', zh: '文字与链接双向互传，并接入系统分享菜单', en: 'Two-way text and link transfer, plus system share sheet support' },
      { kind: 'add', zh: '配对码与扫码连接，高速通道同样校验令牌', en: 'Pairing codes and QR pairing; the fast channel verifies tokens too' },
      { kind: 'add', zh: '电脑也能发文件给手机，双向传输补齐', en: 'The computer can send files to your phone — transfers now work both ways' },
      { kind: 'add', zh: '手机可以发任意文件，不再限于相册', en: 'Send any file from your phone, not just photos' },
      { kind: 'fix', zh: '手机端布局对齐，降低信息密度', en: 'Tightened up mobile layout and reduced visual clutter' },
      { kind: 'fix', zh: '一台设备都没配对时不再返回空值导致列表出错', en: 'Fixed an error when no device had been paired yet' },
      { kind: 'fix', zh: '窗口变窄时导航文字不再竖着折行', en: 'Navigation text no longer wraps vertically in narrow windows' },
    ],
  },
  {
    version: '1.0.0',
    date: '2025-10-10',
    summary: {
      zh: '第一个公开版本。',
      en: 'First public release.',
    },
    entries: [
      { kind: 'add', zh: 'Wi-Fi 传输与 USB 直连拉取相册', en: 'Wi-Fi transfer and USB camera-roll import' },
      { kind: 'add', zh: '断点续传与局域网自动发现', en: 'Resumable transfers and automatic discovery on the local network' },
      { kind: 'add', zh: '桌面端改用 Go 重写，界面内嵌，不再依赖 Flask 与 Tauri', en: 'Desktop app rewritten in Go with an embedded UI — no more Flask or Tauri' },
      { kind: 'add', zh: 'macOS 原生窗口与标准 Dock 图标', en: 'Native macOS window and a proper Dock icon' },
    ],
  },
]
