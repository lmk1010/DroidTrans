import type { ReactNode, ComponentType } from 'react'
import { createContext, useContext } from 'react'
import { useReveal, useStickyHeader } from '../data/useReveal'
import { ThemeToggle } from './Theme'
import { useT, toLang } from '../i18n'
import type { LangCode } from '../i18n'

const LOGO = '/assets/logo.svg'

export type PageName = 'index' | 'download' | 'pricing' | 'thanks' | 'changelog' | 'privacy' | 'support'

/// 顶栏高亮哪一项。散在三个组件签名里各写一遍，加页面时必漏。
export type NavActive = 'home' | 'download' | 'pricing' | 'changelog' | 'support'

/// 当前页面是哪一页。语言切换按钮要靠它算出对面语言的对应地址 ——
/// 这个必须在预渲染时就定下来，等 hydration 再补会先闪一下错的链接。
export const PageContext = createContext<PageName>('index')

function pagePath(page: PageName): string {
  return page === 'index' ? '/' : `/${page}.html`
}

/// 站内链接：自动带上当前语言前缀
function useHref() {
  const t = useT()
  return (path: string) => toLang(path, t.code as LangCode)
}

function LangSwitch() {
  const t = useT()
  const page = useContext(PageContext)
  const target = t.switchTo.href === 'en' ? 'en' : 'zh'
  return (
    <a
      className="lang-btn"
      href={toLang(pagePath(page), target as LangCode)}
      title={t.switchTo.title}
      hrefLang={target}
    >
      {t.switchTo.label}
    </a>
  )
}

function Glow() {
  return <div className="glow" aria-hidden="true" />
}

export function Header({ active }: { active?: NavActive }) {
  useStickyHeader()
  const t = useT()
  const href = useHref()
  return (
    <header>
      <div className="wrap">
        <a className="brand" href={href('/')}>
          <img src={LOGO} alt="" width={28} height={28} />
          <span className="name">{t.code === 'en' ? 'DroidTrans' : '卓传'}</span>
        </a>
        <nav>
          <a href={`${href('/')}#features`} data-on={active === 'home' || undefined}>
            {t.nav.features}
          </a>
          <a href={href('/pricing.html')} data-on={active === 'pricing' || undefined}>
            {t.nav.pricing}
          </a>
          <a href={href('/changelog.html')} data-on={active === 'changelog' || undefined}>
            {t.nav.changelog}
          </a>
          <a href={href('/support.html')} data-on={active === 'support' || undefined}>
            {t.nav.support}
          </a>
          <a className="cta" href={href('/download.html')}>{t.nav.download}</a>
          <LangSwitch />
          <ThemeToggle />
        </nav>
      </div>
    </header>
  )
}

export function Footer() {
  const t = useT()
  const href = useHref()
  return (
    <footer>
      <div className="wrap">
        <span className="brand">
          <img src={LOGO} alt="" width={22} height={22} />
          {t.code === 'en' ? 'DroidTrans' : '卓传 DroidTrans'}
        </span>
        <nav>
          <a href={href('/')}>{t.footer.home}</a>
          <a href={href('/download.html')}>{t.footer.download}</a>
          <a href={href('/pricing.html')}>{t.footer.pricing}</a>
          <a href={href('/changelog.html')}>{t.footer.changelog}</a>
          <a href={href('/support.html')}>{t.footer.support}</a>
          <a href={href('/privacy.html')}>{t.footer.privacy}</a>
        </nav>
        <span className="copy">{t.footer.tagline}</span>
      </div>
    </footer>
  )
}

export function Page({
  children,
  active,
}: {
  children: ReactNode
  active?: NavActive
}) {
  useReveal()
  return (
    <>
      <Glow />
      <Header active={active} />
      {children}
      <Footer />
    </>
  )
}

export function ArticlePage({
  title,
  subtitle,
  active,
  children,
}: {
  title: string
  subtitle: string
  active?: NavActive
  children: ReactNode
}) {
  useReveal()
  return (
    <>
      <Glow />
      <Header active={active} />
      <article className="wrap narrow">
        <h1>{title}</h1>
        <p className="updated">{subtitle}</p>
        {children}
      </article>
      <Footer />
    </>
  )
}

export function SectionHead({
  eyebrow,
  icon: Icon,
  title,
  desc,
  center,
}: {
  eyebrow?: string
  icon?: ComponentType<{ className?: string }>
  title: string
  desc?: string
  center?: boolean
}) {
  return (
    <div className={`section-head reveal${center ? ' center' : ''}`}>
      {eyebrow && (
        <span className="eyebrow">
          {Icon && <Icon />}
          {eyebrow}
        </span>
      )}
      <h2>{title}</h2>
      {desc && <p>{desc}</p>}
    </div>
  )
}

export { useHref }
