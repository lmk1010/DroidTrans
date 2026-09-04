/// 图标。
///
/// 全部手写，统一规格：24×24 视口、1.6 线宽、圆角圆帽、只用 stroke 不填色。
/// 之前用 emoji —— 每个系统渲染成不同样子，风格也压不住，
/// 换成一套线宽一致的线性图标，整页才像一个人做的。

type P = { className?: string }

const base = {
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.6,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
}

/** 手机 → 电脑：手机往上发 */
export const IconUpload = (p: P) => (
  <svg {...base} {...p}>
    <rect x="3" y="2.5" width="9" height="15" rx="2.2" />
    <path d="M6.4 14.6h2.2" />
    <path d="M16 13V7.5m0 0-2.2 2.2M16 7.5l2.2 2.2" />
    <path d="M13.2 17.5h7.3a1 1 0 0 0 1-1v-3" />
  </svg>
)

/** 电脑 → 手机：笔记本往下发 */
export const IconDownload = (p: P) => (
  <svg {...base} {...p}>
    <path d="M3 5.5a1.5 1.5 0 0 1 1.5-1.5h11A1.5 1.5 0 0 1 17 5.5v7.2" />
    <path d="M2 16.2h13.5" />
    <rect x="17.5" y="9" width="4.5" height="12" rx="1.4" />
    <path d="M10 7.6v4.2m0 0-1.8-1.8M10 11.8l1.8-1.8" />
  </svg>
)

/** 任意文件 */
export const IconFiles = (p: P) => (
  <svg {...base} {...p}>
    <path d="M8.5 2.8h5.2L18 7.1v11.3a1.8 1.8 0 0 1-1.8 1.8H8.5a1.8 1.8 0 0 1-1.8-1.8V4.6a1.8 1.8 0 0 1 1.8-1.8Z" />
    <path d="M13.4 2.9v3.6a1 1 0 0 0 1 1h3.5" />
    <path d="M3.6 6.4v12.3A2.6 2.6 0 0 0 6.2 21.3h8.4" opacity=".45" />
  </svg>
)

/** 文字 / 链接 */
export const IconLink = (p: P) => (
  <svg {...base} {...p}>
    <path d="M10.1 13.9a3.6 3.6 0 0 0 5.4.4l2.6-2.6a3.6 3.6 0 0 0-5.1-5.1l-1.5 1.5" />
    <path d="M13.9 10.1a3.6 3.6 0 0 0-5.4-.4l-2.6 2.6a3.6 3.6 0 0 0 5.1 5.1l1.5-1.5" />
  </svg>
)

/** 断点续传 */
export const IconResume = (p: P) => (
  <svg {...base} {...p}>
    <path d="M20.5 12a8.5 8.5 0 1 1-2.6-6.1" />
    <path d="M20.6 3.6v4.6h-4.6" />
    <path d="M12 7.8V12l2.8 1.7" />
  </svg>
)

/** 配对码 / 安全 */
export const IconShield = (p: P) => (
  <svg {...base} {...p}>
    <path d="M12 2.8 4.6 5.9v5.6c0 4.3 3 8.3 7.4 9.7 4.4-1.4 7.4-5.4 7.4-9.7V5.9L12 2.8Z" />
    <path d="M9 12.1l2.1 2.1 4-4.2" />
  </svg>
)

/** 热点 / 无路由 */
export const IconHotspot = (p: P) => (
  <svg {...base} {...p}>
    <circle cx="12" cy="14.8" r="2.1" />
    <path d="M8.4 11.2a5.1 5.1 0 0 1 7.2 0" />
    <path d="M5.4 8.2a9.3 9.3 0 0 1 13.2 0" />
  </svg>
)

/** 直连 / 快 */
export const IconBolt = (p: P) => (
  <svg {...base} {...p}>
    <path d="M13.2 2.6 4.8 13.2h6l-1 8.2 8.4-10.6h-6l1-8.2Z" />
  </svg>
)

/** 系统分享 */
export const IconShare = (p: P) => (
  <svg {...base} {...p}>
    <circle cx="17.5" cy="5.5" r="2.6" />
    <circle cx="6.5" cy="12" r="2.6" />
    <circle cx="17.5" cy="18.5" r="2.6" />
    <path d="m8.8 10.7 6.4-3.9M8.8 13.3l6.4 3.9" />
  </svg>
)

/** 下载箭头。按钮里用它 —— IconDownload 那个「笔记本+手机」在 17px 下糊成一团。 */
export const IconArrowDown = (p: P) => (
  <svg {...base} {...p}>
    <path d="M12 3.5v13m0 0 5-5m-5 5-5-5" />
    <path d="M4.5 20.5h15" />
  </svg>
)

/** 新增 */
export const IconPlus = (p: P) => (
  <svg {...base} {...p}>
    <path d="M12 5.5v13M5.5 12h13" />
  </svg>
)

/** 修复 */
export const IconWrench = (p: P) => (
  <svg {...base} {...p}>
    <path d="M15.6 8.4a4.2 4.2 0 0 1-5.3 5.3l-5 5a1.8 1.8 0 0 1-2.6-2.6l5-5a4.2 4.2 0 0 1 5.3-5.3L10.6 8.2l1.3 3.9 3.9 1.3 2.4-2.4a4.2 4.2 0 0 1-2.6-2.6Z" />
  </svg>
)

/** 时间线 */
export const IconClock = (p: P) => (
  <svg {...base} {...p}>
    <circle cx="12" cy="12" r="8.6" />
    <path d="M12 7.2V12l3.2 1.9" />
  </svg>
)

export const IconChevronDown = (p: P) => (
  <svg {...base} {...p}>
    <path d="m6 9.5 6 6 6-6" />
  </svg>
)

export const IconCheck = (p: P) => (
  <svg {...base} {...p}>
    <path d="m4.5 12.4 4.8 4.8L19.5 7" />
  </svg>
)

export const IconMinus = (p: P) => (
  <svg {...base} {...p}>
    <path d="M6 12h12" />
  </svg>
)

export const IconArrowRight = (p: P) => (
  <svg {...base} {...p}>
    <path d="M4.5 12h14m0 0-5.2-5.2M18.5 12l-5.2 5.2" />
  </svg>
)

export const IconSun = (p: P) => (
  <svg {...base} {...p}>
    <circle cx="12" cy="12" r="4.2" />
    <path d="M12 2.6v2.2M12 19.2v2.2M2.6 12h2.2M19.2 12h2.2M5.4 5.4l1.6 1.6M17 17l1.6 1.6M18.6 5.4 17 7M7 17l-1.6 1.6" />
  </svg>
)

export const IconMoon = (p: P) => (
  <svg {...base} {...p}>
    <path d="M20.5 14.4A8.6 8.6 0 0 1 9.6 3.5a8.6 8.6 0 1 0 10.9 10.9Z" />
  </svg>
)

/** 定位/雷达：用在「本地直连」那句上，比一个圆点有意义 */
export const IconRadar = (p: P) => (
  <svg {...base} {...p}>
    <circle cx="12" cy="12" r="2.2" />
    <path d="M8.6 15.4a4.8 4.8 0 0 1 0-6.8" />
    <path d="M15.4 8.6a4.8 4.8 0 0 1 0 6.8" />
    <path d="M5.9 18.1a9.9 9.9 0 0 1 0-12.2M18.1 5.9a9.9 9.9 0 0 1 0 12.2" opacity=".5" />
  </svg>
)

export const IconGrid = (p: P) => (
  <svg {...base} {...p}>
    <rect x="3.4" y="3.4" width="7" height="7" rx="2" />
    <rect x="13.6" y="3.4" width="7" height="7" rx="2" />
    <rect x="3.4" y="13.6" width="7" height="7" rx="2" />
    <rect x="13.6" y="13.6" width="7" height="7" rx="2" />
  </svg>
)

export const IconRoute = (p: P) => (
  <svg {...base} {...p}>
    <circle cx="5.5" cy="18.5" r="2.4" />
    <circle cx="18.5" cy="5.5" r="2.4" />
    <path d="M8 18.5h5.2a3.3 3.3 0 0 0 0-6.6h-2.4a3.3 3.3 0 0 1 0-6.6H16" />
  </svg>
)

/* ---- 平台图标：下载区用，需要一眼认出是哪个系统 ---- */

export const IconApple = (p: P) => (
  <svg viewBox="0 0 24 24" fill="currentColor" {...p}>
    <path d="M16.4 12.7c0-2.4 2-3.6 2.1-3.6-1.1-1.7-2.9-1.9-3.5-1.9-1.5-.15-2.9.88-3.65.88-.76 0-1.9-.86-3.13-.84-1.6.02-3.08.93-3.9 2.37-1.67 2.9-.43 7.2 1.2 9.55.8 1.15 1.75 2.44 3 2.4 1.2-.05 1.66-.78 3.11-.78 1.45 0 1.86.78 3.13.75 1.3-.02 2.11-1.17 2.9-2.33.91-1.33 1.29-2.62 1.31-2.69-.03-.01-2.51-.96-2.53-3.8Z" />
    <path d="M14.2 5.6c.66-.8 1.11-1.92.99-3.03-.95.04-2.11.64-2.8 1.44-.61.7-1.15 1.84-1.01 2.93 1.07.08 2.16-.54 2.82-1.34Z" />
  </svg>
)

export const IconAndroid = (p: P) => (
  <svg viewBox="0 0 24 24" fill="currentColor" {...p}>
    <path d="M6.9 8.3h10.2c.5 0 .9.4.9.9v7.4c0 .6-.5 1.1-1.1 1.1h-.9v2.4a1.2 1.2 0 1 1-2.4 0v-2.4h-2.2v2.4a1.2 1.2 0 1 1-2.4 0v-2.4h-.9A1.1 1.1 0 0 1 6 16.6V9.2c0-.5.4-.9.9-.9Z" />
    <rect x="2.6" y="8.4" width="2.4" height="7.2" rx="1.2" />
    <rect x="19" y="8.4" width="2.4" height="7.2" rx="1.2" />
    <path d="M8.1 7.1c.4-2 2-3.4 3.9-3.4s3.5 1.4 3.9 3.4H8.1Z" />
    <circle cx="10.1" cy="5.4" r=".55" fill="var(--bg)" />
    <circle cx="13.9" cy="5.4" r=".55" fill="var(--bg)" />
    <path d="M9.2 3 8.3 1.6M14.8 3l.9-1.4" stroke="currentColor" strokeWidth="1.1" strokeLinecap="round" fill="none" />
  </svg>
)
