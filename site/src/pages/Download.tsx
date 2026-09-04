import { Page, SectionHead } from '../components/Layout'
import { useManifest, humanSize } from '../data/manifest'
import {
  IconApple, IconAndroid, IconCheck, IconMinus, IconArrowDown, IconGrid,
} from '../components/Icons'
import { useT } from '../i18n'
import type { Dict } from '../i18n'

const OS_ICON = { macos: IconApple, ios: IconApple, android: IconAndroid }

type Platform = Dict['download']['platforms'][number]

function PlatformCol({ p }: { p: Platform }) {
  const t = useT()
  const m = useManifest()
  const data = m.status === 'ready' ? m.data : undefined
  const Icon = OS_ICON[p.id]

  const asset = p.id === 'macos' ? data?.macos_arm64 : p.id === 'android' ? data?.android : undefined
  // 清单取不到时的固定兜底地址，两个平台对称
  const fallback = p.id === 'android' ? '/latest.apk' : p.id === 'macos' ? '/latest.dmg' : undefined
  const href = asset?.url ?? fallback
  const size = humanSize(asset?.size)

  return (
    <div className="platform reveal">
      <div className="head">
        <span className="os"><Icon /></span>
        <h3>{p.name}</h3>
        {p.pending
          ? <span className="badge soon">{t.download.soon}</span>
          : data?.version ? <span className="badge">{data.version}</span> : null}
      </div>
      <p className="tagline">{p.tagline}</p>

      <ul>
        {p.points.map((pt) => (
          <li key={pt.text} className={'off' in pt && pt.off ? 'off' : undefined}>
            {'off' in pt && pt.off ? <IconMinus /> : <IconCheck />}
            {pt.text}
          </li>
        ))}
      </ul>

      <div className="req">{p.requirement}</div>

      {p.pending ? (
        <span className="btn ghost" aria-disabled="true">{t.download.pendingIos}</span>
      ) : (
        <a className="btn primary" href={href ?? '#'} aria-disabled={href ? undefined : true}>
          <IconArrowDown />
          {p.id === 'macos' ? t.download.getDmg : t.download.getApk}
          {size && <span className="sz">{size}</span>}
        </a>
      )}
    </div>
  )
}

export function Download() {
  const t = useT()
  return (
    <Page active="download">
      <section className="tight" style={{ paddingBottom: 40 }}>
        <div className="wrap">
          <SectionHead
            eyebrow={t.download.eyebrow}
            icon={IconArrowDown}
            title={t.download.title}
            desc={t.download.desc}
          />
          <div className="platforms">
            {t.download.platforms.map((p) => (
              <PlatformCol key={p.id} p={p} />
            ))}
          </div>
          <p className="foot-note reveal">
            {t.download.footnote}
          </p>
        </div>
      </section>

      <section className="tight ruled">
        <div className="wrap">
          <SectionHead
            eyebrow={t.compat.eyebrow}
            icon={IconGrid}
            title={t.compat.title}
            desc={t.compat.desc}
          />
          <div className="table-scroll reveal">
            <table className="compat">
              <thead>
                <tr>
                  <th>{t.compat.head.feature}</th>
                  <th>{t.compat.head.android}</th>
                  <th>{t.compat.head.ios}</th>
                </tr>
              </thead>
              <tbody>
                {t.compat.rows.map((r) => (
                  <tr key={r.name}>
                    <td>{r.name}</td>
                    <td>{r.android === true ? <span className="yes"><IconCheck /></span> : <span className="no">{r.android}</span>}</td>
                    <td>{r.ios === true ? <span className="yes"><IconCheck /></span> : <span className="no">{r.ios}</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </section>
    </Page>
  )
}
