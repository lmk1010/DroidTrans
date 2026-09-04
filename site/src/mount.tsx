/// 页面挂载。四个入口共用这一段，省得每个入口各写一遍 Provider。
///
/// 语言从 <html lang> 读 —— 那是预渲染时写死的，服务端和浏览器看到的
/// 是同一个值，hydration 不会失配。

import { StrictMode } from 'react'
import type { ComponentType } from 'react'
import { hydrateRoot } from 'react-dom/client'
import { LangContext, dicts, detectLang } from './i18n'
import { PageContext } from './components/Layout'
import type { PageName } from './components/Layout'
import 'devices.css/dist/devices.min.css'
import './styles.css'

export function mount(page: PageName, Comp: ComponentType) {
  const root = document.getElementById('root')
  if (!root) return
  hydrateRoot(
    root,
    <StrictMode>
      <LangContext.Provider value={dicts[detectLang()]}>
        <PageContext.Provider value={page}>
          <Comp />
        </PageContext.Provider>
      </LangContext.Provider>
    </StrictMode>,
  )
}
