import { Page, SectionHead, useHref } from '../components/Layout'
import { HeroVisual } from '../components/HeroVisual'
import { useManifest } from '../data/manifest'
import { IconArrowRight, IconRadar, IconGrid, IconRoute } from '../components/Icons'
import { stepArt } from '../components/StepArt'
import { useT } from '../i18n'

function HeroMeta() {
  const t = useT()
  const m = useManifest()
  if (m.status !== 'ready' || !m.data.version) return <p className="meta" />
  return <p className="meta">{t.hero.meta(m.data.version, m.data.published_at?.slice(0, 10) ?? '')}</p>
}

export function Home() {
  const t = useT()
  const href = useHref()
  return (
    <Page active="home">
      <div className="hero">
        <div className="wrap grid2">
          <div>
            <span className="eyebrow enter" style={{ ["--i" as string]: 0 }}>
              <IconRadar />
              {t.hero.eyebrow}
            </span>
            <h1 className="enter" style={{ ["--i" as string]: 1 }}>
              {t.hero.title.map((line) => (
                <span className="ln" key={line}>
                  {line}
                </span>
              ))}
            </h1>
            <p className="lead enter" style={{ ["--i" as string]: 2 }}>
              {t.hero.lead[0]}
              <strong>{t.hero.lead[1]}</strong>
              {t.hero.lead[2]}
            </p>
            <div className="actions enter" style={{ ["--i" as string]: 3 }}>
              <a className="btn primary" href={href('/download.html')}>
                {t.hero.primary}
                <IconArrowRight />
              </a>
              <a className="btn ghost" href="#how">{t.hero.secondary}</a>
            </div>
            <HeroMeta />
          </div>
          <div className="enter" style={{ ["--i" as string]: 2.5 }}>
            <HeroVisual />
          </div>
        </div>
      </div>

      <section id="features">
        <div className="wrap">
          <SectionHead
            eyebrow={t.features.eyebrow}
            icon={IconGrid}
            title={t.features.title}
            desc={t.features.desc}
          />
          <div className="features">
            {t.features.items.map((f, i) => {
              const Icon = f.icon
              return (
                <div className="feature reveal" key={f.title} style={{ transitionDelay: `${(i % 4) * 55}ms` }}>
                  <span className="ico"><Icon /></span>
                  <h3>{f.title}</h3>
                  <p>{f.body}</p>
                </div>
              )
            })}
          </div>
        </div>
      </section>

      <section id="how" className="tight ruled">
        <div className="wrap">
          <SectionHead
            eyebrow={t.steps.eyebrow}
            icon={IconRoute}
            title={t.steps.title}
            desc={t.steps.desc}
          />
          <div className="steps">
            {t.steps.items.map((s, i) => {
              const Art = stepArt[i]
              return (
                <div className="step reveal" key={s.title} style={{ transitionDelay: `${i * 90}ms` }}>
                  <div className="step-art">{Art && <Art />}</div>
                  <span className="n">0{i + 1}</span>
                  <h3>{s.title}</h3>
                  <p>{s.body}</p>
                </div>
              )
            })}
          </div>
        </div>
      </section>

      <section className="tight">
        <div className="wrap">
          <div className="cta-band reveal">
            <h2>{t.cta.title}</h2>
            <p>{t.cta.desc}</p>
            <a className="btn primary" href={href('/download.html')}>
              {t.cta.button}
              <IconArrowRight />
            </a>
          </div>
        </div>
      </section>
    </Page>
  )
}
