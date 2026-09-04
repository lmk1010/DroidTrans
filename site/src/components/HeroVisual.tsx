import { useEffect, useRef, useState } from 'react'
import { MacBook, IPhone } from './Devices'
import { useT } from '../i18n'

/// 首屏主视觉：两个使用场景轮流出现。
///
///   电脑 ↔ 手机   —— 桌面端亮出配对码，手机连上来
///   手机 ↔ 手机   —— 两台手机直接互传
///
/// 设备外壳用 CSS 构造，屏幕里是产品的真实截图（跑起客户端截的，
/// 不是画的示意图）。连线是一层绝对定位的 SVG，只负责画光带。

const SCENES = [
  { id: 'pc', left: 'mac' as const },
  { id: 'p2p', left: 'phone' as const },
]

/** 场景停留时长。太短看不清截图内容，太长像卡住了。 */
const DWELL = 6200

export function HeroVisual() {
  const t = useT()
  const en = t.code === 'en'
  const ref = useRef<HTMLDivElement>(null)
  const [i, setI] = useState(0)
  const [live, setLive] = useState(true)

  // 滚出视口就停掉轮播和动画，不在看不见的地方空转
  useEffect(() => {
    const el = ref.current
    if (!el || typeof IntersectionObserver === 'undefined') return
    const io = new IntersectionObserver(([e]) => setLive(e.isIntersecting), {
      rootMargin: '120px',
    })
    io.observe(el)
    return () => io.disconnect()
  }, [])

  useEffect(() => {
    if (!live) return
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
    const timer = window.setInterval(() => setI((n) => (n + 1) % SCENES.length), DWELL)
    return () => window.clearInterval(timer)
  }, [live])

  const captions = en
    ? ['Computer and phone', 'Between two phones']
    : ['电脑与手机之间', '两台手机之间']

  return (
    <div className={`hero-visual${live ? '' : ' paused'}`} ref={ref}>
      <div className="stage">
        {/* 场景一：电脑 ↔ 手机 */}
        <div className={`scene${i === 0 ? ' on' : ''}`} aria-hidden={i !== 0}>
          <MacBook scale={0.46} src="/assets/app-desktop.webp" alt={en ? 'DroidTrans on macOS' : '卓传桌面端'} />
          <Beam />
          <IPhone scale={0.2} className="fore" src="/assets/app-mobile.webp" alt={en ? 'DroidTrans on the phone' : '卓传手机端'} />
        </div>

        {/* 场景二：手机 ↔ 手机 */}
        <div className={`scene twin${i === 1 ? ' on' : ''}`} aria-hidden={i !== 1}>
          <IPhone scale={0.24} className="tilt-l" src="/assets/app-p2p.webp" alt={en ? 'Phone to phone transfer' : '手机互传'} />
          <Beam short />
          <IPhone scale={0.24} className="tilt-r" src="/assets/app-mobile.webp" alt={en ? 'DroidTrans on the phone' : '卓传手机端'} />
        </div>
      </div>

      {/* 场景指示 */}
      <div className="stage-dots">
        {SCENES.map((s, n) => (
          <button
            key={s.id}
            className={n === i ? 'on' : undefined}
            onClick={() => setI(n)}
            aria-label={captions[n]}
            title={captions[n]}
            type="button"
          />
        ))}
      </div>
    </div>
  )
}

/** 两台设备之间的数据流。一段渐变光带沿轨道滑过去。 */
function Beam({ short }: { short?: boolean }) {
  const d = short ? 'M6 40 C 40 8, 76 8, 110 40' : 'M6 54 C 44 10, 82 10, 118 46'
  return (
    <svg className="beam-wrap" viewBox={short ? '0 0 116 56' : '0 0 124 66'} fill="none" aria-hidden="true">
      <defs>
        <linearGradient id={short ? 'bg2' : 'bg1'} x1="0" y1="0" x2="1" y2="0">
          <stop offset="0" stopColor="#4992ff" stopOpacity="0" />
          <stop offset="0.5" stopColor="#9cc8ff" />
          <stop offset="1" stopColor="#4992ff" stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={d} stroke="var(--dev-wire)" strokeWidth="1.3" strokeDasharray="3 5" strokeLinecap="round" opacity=".5" />
      <path className="beam" pathLength={100} d={d} stroke={`url(#${short ? 'bg2' : 'bg1'})`} strokeWidth="2.4" strokeLinecap="round" />
      <path className="beam b2" pathLength={100} d={d} stroke={`url(#${short ? 'bg2' : 'bg1'})`} strokeWidth="1.4" strokeLinecap="round" />
    </svg>
  )
}
