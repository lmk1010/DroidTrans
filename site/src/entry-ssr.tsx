/// 构建期预渲染入口。
///
/// 官网靠搜索引擎带量，不能只给爬虫一个空的 <div id="root">。
/// 每个页面 × 每个语言都渲染成真实 HTML 注进产物，浏览器再 hydrate 接管。

import { renderToString } from 'react-dom/server'
import { LangContext, dicts } from './i18n'
import { PageContext } from './components/Layout'
import type { LangCode } from './i18n'
import type { PageName } from './components/Layout'
import { Home } from './pages/Home'
import { Download } from './pages/Download'
import { Privacy } from './pages/Privacy'
import { Support } from './pages/Support'
import { Changelog } from './pages/Changelog'
import { Pricing } from './pages/Pricing'
import { Thanks } from './pages/Thanks'

const pages = { index: Home, download: Download, pricing: Pricing, thanks: Thanks, changelog: Changelog, privacy: Privacy, support: Support }

export function render(name: PageName, lang: LangCode): string {
  const Page = pages[name]
  return renderToString(
    <LangContext.Provider value={dicts[lang]}>
      <PageContext.Provider value={name}>
        <Page />
      </PageContext.Provider>
    </LangContext.Provider>,
  )
}

/// 页面的 <title> / description 也从词典来，保证文案只有一个出处
export function meta(name: PageName, lang: LangCode) {
  return dicts[lang].meta[name]
}

export const pageNames = Object.keys(pages) as PageName[]
export const langs: LangCode[] = ['zh', 'en']
