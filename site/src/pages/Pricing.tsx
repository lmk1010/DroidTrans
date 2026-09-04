import { useState } from 'react'
import { Page, SectionHead } from '../components/Layout'
import { plans, type PlanId } from '../data/plans'
import { IconCheck, IconArrowRight, IconShield, IconBolt } from '../components/Icons'
import { useT } from '../i18n'

/// 购买。向授权服务要一个结账地址，然后跳过去。
/// 失败时把按钮恢复原状并给一句人话 —— 卡在「正在跳转…」是最糟的体验。
function useCheckout() {
  const [busy, setBusy] = useState<PlanId | null>(null)
  const [error, setError] = useState('')

  const buy = async (plan: PlanId, lang: string) => {
    setBusy(plan)
    setError('')
    try {
      const r = await fetch('/api/checkout', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ plan, lang }),
      })
      const data = (await r.json()) as { url?: string }
      if (!r.ok || !data.url) throw new Error('no url')
      window.location.href = data.url
    } catch {
      setBusy(null)
      return false
    }
    return true
  }

  return { busy, error, setError, buy }
}

export function Pricing() {
  const t = useT()
  const { busy, buy } = useCheckout()
  const [failed, setFailed] = useState(false)

  const onBuy = async (id: PlanId) => {
    const ok = await buy(id, t.code)
    if (!ok) setFailed(true)
  }

  return (
    <Page active="pricing">
      <section className="tight">
        <div className="wrap">
          <SectionHead
            eyebrow={t.pricing.eyebrow}
            icon={IconBolt}
            title={t.pricing.title}
            desc={t.pricing.desc}
          />

          <div className="tiers">
            {/* 免费档：不是引流噱头，列的都是真能一直白用的东西 */}
            <div className="tier reveal">
              <div className="tier-head">
                <h3>{t.pricing.freeTitle}</h3>
                <div className="tier-price">
                  <span className="amt">$0</span>
                </div>
                <p className="tier-note">{t.pricing.freeNote}</p>
              </div>
              <ul>
                {t.pricing.free.map((f) => (
                  <li key={f}>
                    <IconCheck />
                    {f}
                  </li>
                ))}
              </ul>
            </div>

            {/* Pro：三个档位共用同一张功能清单，差别只在能用多久 */}
            <div className="tier pro reveal">
              <div className="tier-head">
                <h3>{t.pricing.proTitle}</h3>
                <p className="tier-note">{t.pricing.proNote}</p>
              </div>
              <ul>
                {t.pricing.pro.map((f) => (
                  <li key={f}>
                    <IconCheck />
                    {f}
                  </li>
                ))}
              </ul>

              <div className="periods">
                {plans.map((p) => (
                  <div className={`period${p.featured ? ' featured' : ''}`} key={p.id}>
                    <div className="period-name">{t.pricing.periods[p.id]}</div>
                    <div className="period-price">
                      <span className="cur">$</span>
                      {p.price}
                    </div>
                    {/* 始终渲染，靠 min-height 占位 —— 否则没有提示文字的那档按钮会偏高 */}
                    <div className="period-hint">{t.pricing.hint[p.id]}</div>
                    <button
                      className={`btn ${p.featured ? 'primary' : 'ghost'}`}
                      onClick={() => onBuy(p.id)}
                      disabled={busy !== null}
                      type="button"
                    >
                      {busy === p.id ? t.pricing.buying : t.pricing.buy}
                      {busy !== p.id && <IconArrowRight />}
                    </button>
                  </div>
                ))}
              </div>

              {failed && <p className="tier-error">{t.pricing.failed}</p>}

              <div className="tier-foot">
                <span>
                  <IconShield />
                  {t.pricing.devices}
                </span>
                <span>{t.pricing.refund}</span>
              </div>
            </div>
          </div>

          <p className="foot-note reveal">{t.pricing.haveCode}</p>
        </div>
      </section>
    </Page>
  )
}
