import { useState } from 'react'
import { Page, SectionHead } from '../components/Layout'
import { releases } from '../data/changelog'
import { IconPlus, IconWrench, IconClock, IconChevronDown } from '../components/Icons'
import { useT } from '../i18n'

/// 一页显示多少个版本。版本会越攒越多，一次全铺出来页面会长到没边，
/// 也拖慢首屏 —— 超过这个数就折叠，点一次多放一页。
const PAGE = 8

export function Changelog() {
  const t = useT()
  const en = t.code === 'en'
  const [shown, setShown] = useState(PAGE)

  const visible = releases.slice(0, shown)
  const rest = releases.length - visible.length

  return (
    <Page active="changelog">
      <section className="tight">
        <div className="wrap narrow">
          <SectionHead
            eyebrow={en ? 'Changelog' : '版本记录'}
            icon={IconClock}
            title={en ? 'What’s new' : '每个版本改了什么'}
            desc={
              en
                ? 'Every release and what changed in it. Newest first.'
                : '每次发版改了哪些东西，新的在上面。'
            }
          />

          <ol className="timeline">
            {visible.map((r) => (
              <li className="release reveal" key={r.version}>
                <div className="rel-head">
                  <span className="ver-tag">
                    {r.version}
                    {r.current && <em>{en ? 'current' : '当前版本'}</em>}
                  </span>
                  <time dateTime={r.date}>{r.date}</time>
                </div>
                <p className="summary">{en ? r.summary.en : r.summary.zh}</p>
                <ul className="entries">
                  {r.entries.map((e) => (
                    <li key={e.zh} className={e.kind}>
                      <span className="mark" aria-hidden="true">
                        {e.kind === 'fix' ? <IconWrench /> : <IconPlus />}
                      </span>
                      <span>{en ? e.en : e.zh}</span>
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ol>

          {rest > 0 && (
            <div className="more-wrap">
              <button className="btn ghost" onClick={() => setShown((n) => n + PAGE)}>
                {en ? `Show ${Math.min(rest, PAGE)} older releases` : `再看 ${Math.min(rest, PAGE)} 个更早的版本`}
                <IconChevronDown />
              </button>
            </div>
          )}

          <p className="foot-note reveal">
            {en
              ? 'Versions 1.0.1 and 1.0.2 were never published, so their changes are listed under 1.0.3.'
              : '1.0.1 与 1.0.2 没有正式发布，那段时间的改动都归在 1.0.3 下面。'}
          </p>
        </div>
      </section>
    </Page>
  )
}
