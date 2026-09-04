/// 三步上手的配图。
///
/// 步骤区原本三段纯文字，读起来像说明书。这里给每步画一个场景：
/// 电脑亮出二维码 → 手机扫它 → 文件开始流动。
///
/// 和首屏主视觉同一套画法：SVG + CSS 变量配色，跟随主题，
/// 没有位图资源，任何屏幕密度下都清晰。

type P = { className?: string }

const wrap = {
  viewBox: '0 0 260 150',
  fill: 'none',
  xmlns: 'http://www.w3.org/2000/svg',
}

/** 1 · 电脑上打开，屏幕里是配对二维码 */
export const ArtScreen = (p: P) => (
  <svg {...wrap} {...p}>
    <rect x="52" y="18" width="156" height="100" rx="9" fill="var(--art-shell)" stroke="var(--art-line)" />
    <rect x="59" y="25" width="142" height="86" rx="5" fill="var(--art-screen)" />
    <circle cx="67" cy="32" r="2" fill="var(--art-dim)" />
    <circle cx="74" cy="32" r="2" fill="var(--art-dim)" />
    <circle cx="81" cy="32" r="2" fill="var(--art-dim)" />

    {/* 二维码 */}
    <rect x="106" y="44" width="48" height="48" rx="6" fill="var(--art-panel)" />
    <g fill="var(--art-qr)">
      <rect x="113" y="51" width="11" height="11" rx="2.5" />
      <rect x="136" y="51" width="11" height="11" rx="2.5" />
      <rect x="113" y="74" width="11" height="11" rx="2.5" />
      <rect x="116" y="54" width="5" height="5" rx="1" fill="var(--art-panel)" />
      <rect x="139" y="54" width="5" height="5" rx="1" fill="var(--art-panel)" />
      <rect x="116" y="77" width="5" height="5" rx="1" fill="var(--art-panel)" />
      <rect x="129" y="52" width="3" height="3" /><rect x="133" y="57" width="3" height="3" />
      <rect x="129" y="62" width="3" height="3" /><rect x="138" y="67" width="3" height="3" />
      <rect x="129" y="72" width="3" height="3" /><rect x="133" y="77" width="3" height="3" />
      <rect x="143" y="72" width="3" height="3" /><rect x="129" y="82" width="3" height="3" />
      <rect x="138" y="82" width="3" height="3" /><rect x="143" y="62" width="3" height="3" />
    </g>
    <rect x="112" y="98" width="36" height="4" rx="2" fill="var(--art-dim)" />
    {/* 底座 */}
    <path d="M34 122h192l-6 7a4 4 0 0 1-3 1.6H43a4 4 0 0 1-3-1.6l-6-7Z" fill="var(--art-shell)" stroke="var(--art-line)" />
  </svg>
)

/** 2 · 手机对着二维码扫，取景框里有一条来回扫描的线 */
export const ArtScan = (p: P) => (
  <svg {...wrap} {...p}>
    <rect x="95" y="12" width="70" height="126" rx="13" fill="var(--art-shell)" stroke="var(--art-line)" />
    <rect x="100" y="17" width="60" height="116" rx="9" fill="var(--art-screen)" />
    <rect x="118" y="21" width="24" height="6" rx="3" fill="var(--art-notch)" />

    {/* 取景框四角 */}
    <g stroke="var(--brand)" strokeWidth="2" strokeLinecap="round" fill="none">
      <path d="M110 52v-6a3 3 0 0 1 3-3h6" />
      <path d="M150 52v-6a3 3 0 0 0-3-3h-6" />
      <path d="M110 92v6a3 3 0 0 0 3 3h6" />
      <path d="M150 92v6a3 3 0 0 1-3 3h-6" />
    </g>
    {/* 框里的码 */}
    <g fill="var(--art-qr)" opacity=".75">
      <rect x="117" y="55" width="8" height="8" rx="2" />
      <rect x="135" y="55" width="8" height="8" rx="2" />
      <rect x="117" y="81" width="8" height="8" rx="2" />
      <rect x="128" y="66" width="4" height="4" /><rect x="136" y="70" width="4" height="4" />
      <rect x="128" y="78" width="4" height="4" /><rect x="140" y="82" width="4" height="4" />
    </g>
    {/* 扫描线 */}
    <rect className="scanline" x="108" y="47" width="44" height="2" rx="1" fill="var(--brand)" />
    <rect x="112" y="112" width="36" height="4" rx="2" fill="var(--art-dim)" />
  </svg>
)

/** 3 · 配对完成，文件开始流动 */
export const ArtFlow = (p: P) => (
  <svg {...wrap} {...p}>
    <rect x="6" y="34" width="96" height="70" rx="8" fill="var(--art-shell)" stroke="var(--art-line)" />
    <rect x="12" y="40" width="84" height="58" rx="4" fill="var(--art-screen)" />
    <rect x="20" y="50" width="30" height="4" rx="2" fill="var(--art-dim)" />
    <rect x="20" y="60" width="48" height="4" rx="2" fill="var(--art-dim2)" />
    <rect x="20" y="70" width="40" height="4" rx="2" fill="var(--art-dim2)" />
    <rect x="20" y="84" width="60" height="5" rx="2.5" fill="var(--art-panel)" />
    <rect className="art-bar" x="20" y="84" width="60" height="5" rx="2.5" fill="var(--brand)" />

    <rect x="196" y="22" width="58" height="106" rx="12" fill="var(--art-shell)" stroke="var(--art-line)" />
    <rect x="201" y="27" width="48" height="96" rx="8" fill="var(--art-screen)" />
    <rect x="215" y="31" width="20" height="5" rx="2.5" fill="var(--art-notch)" />
    <rect x="209" y="46" width="32" height="4" rx="2" fill="var(--art-dim)" />
    <rect x="209" y="58" width="24" height="4" rx="2" fill="var(--art-dim2)" />
    <rect x="209" y="70" width="30" height="4" rx="2" fill="var(--art-dim2)" />

    {/* 传输通道 */}
    <path
      id="flow-wire"
      d="M108 70 C 140 44, 164 96, 190 70"
      stroke="var(--art-wire)"
      strokeWidth="1.4"
      strokeDasharray="3 5"
      strokeLinecap="round"
    />
    <g className="flow-dots">
      <circle className="fd f1" r="4" fill="var(--brand)" />
      <circle className="fd f2" r="3" fill="var(--brand)" />
    </g>
  </svg>
)

export const stepArt = [ArtScreen, ArtScan, ArtFlow]
