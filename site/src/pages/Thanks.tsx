import { useEffect, useRef, useState } from 'react'
import { Page, SectionHead, useHref } from '../components/Layout'
import { IconCheck, IconArrowRight } from '../components/Icons'
import { useT } from '../i18n'

/// 付款成功后的落地页。Waffo 结账完成会跳到这里，地址里带着结账时生成的 claim。
///
/// 这一屏只干一件事：把激活码交到用户手上。
/// 付完钱却不知道下一步做什么，是转化流程里最容易掉人的地方。
///
/// 回调是异步的，用户完全可能比它先到这个页面，所以「查不到」是正常状态而不是错误 ——
/// 先轮询一会儿，实在拿不到才转成求助文案，而且要说清楚「钱没白花」。

type State =
  /// 首帧。服务端预渲染和客户端第一次渲染必须长得一样，
  /// 所以这里不能去读 location —— 读取放到 effect 里。
  | { s: 'boot' }
  | { s: 'waiting' }
  | { s: 'ok'; code: string }
  | { s: 'failed' }
  /// 地址里没有 claim：多半是有人直接点进来的，不该显示等待动画
  | { s: 'plain' }

const POLL_MS = 2000
const GIVE_UP_MS = 40_000

export function Thanks() {
  const t = useT()
  const href = useHref()
  const [st, setSt] = useState<State>({ s: 'boot' })
  const [copied, setCopied] = useState(false)
  const timers = useRef<number[]>([])

  useEffect(() => {
    const claim = new URLSearchParams(window.location.search).get('c')
    if (!claim) {
      setSt({ s: 'plain' })
      return
    }
    setSt({ s: 'waiting' })

    let stopped = false
    const started = Date.now()

    const tick = async () => {
      if (stopped) return
      try {
        const r = await fetch(`/api/claim?c=${encodeURIComponent(claim)}`)
        if (r.ok) {
          const { code } = await r.json()
          if (!stopped && code) return setSt({ s: 'ok', code })
        } else if (r.status !== 404) {
          // 404 是「还没签发」，会再试；其余都是不会自己好转的状态
          if (!stopped) return setSt({ s: 'failed' })
        }
      } catch {
        // 网络抖动，接着轮询
      }
      if (stopped) return
      if (Date.now() - started > GIVE_UP_MS) return setSt({ s: 'failed' })
      timers.current.push(window.setTimeout(tick, POLL_MS))
    }
    tick()

    return () => {
      stopped = true
      timers.current.forEach(clearTimeout)
      timers.current = []
    }
  }, [])

  const copy = async (code: string) => {
    try {
      await navigator.clipboard.writeText(code)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2000)
    } catch {
      // 浏览器不给剪贴板权限时，码本来就在屏幕上，用户可以自己选中
    }
  }

  const pending = st.s === 'boot' || st.s === 'waiting'

  return (
    <Page>
      <section className="tight">
        <div className="wrap narrow">
          <SectionHead
            eyebrow={t.thanks.eyebrow}
            icon={IconCheck}
            title={st.s === 'plain' || st.s === 'failed' ? t.thanks.titlePlain : t.thanks.title}
            desc={st.s === 'plain' || st.s === 'failed' ? t.thanks.descPlain : t.thanks.desc}
          />

          {st.s === 'ok' && (
            <div className="code-box appear">
              <span className="code-label">{t.thanks.codeLabel}</span>
              <div className="code-row">
                <code className="code-value">{st.code}</code>
                <button className="btn ghost small" onClick={() => copy(st.code)}>
                  {copied ? t.thanks.copied : t.thanks.copy}
                </button>
              </div>
            </div>
          )}

          {pending && (
            <div className="code-box waiting appear">
              <span className="code-label">{t.thanks.codeLabel}</span>
              <div className="code-row">
                <span className="code-skeleton" aria-hidden="true" />
                <span className="code-hint">{t.thanks.waiting}</span>
              </div>
            </div>
          )}

          {st.s === 'failed' && <div className="notice appear"><p>{t.thanks.failed}</p></div>}

          <ol className="steps-plain reveal">
            {t.thanks.steps.map((s, i) => (
              <li key={s}>
                <span className="n">0{i + 1}</span>
                {s}
              </li>
            ))}
          </ol>
          <p className="foot-note reveal">{t.thanks.note}</p>
          <div className="reveal" style={{ marginTop: 26, display: 'flex', gap: 12, flexWrap: 'wrap' }}>
            <a className="btn primary" href={href('/')}>
              {t.thanks.back}
              <IconArrowRight />
            </a>
            <a className="btn ghost" href={href('/support.html')}>{t.thanks.support}</a>
          </div>
        </div>
      </section>
    </Page>
  )
}
