/// 语言解析与词典。
///
/// 中文在根路径，英文在 /en/ 下 —— 每个语言有自己的 URL，
/// 搜索引擎才能分别收录。用 ?lang= 或纯前端切换的话，
/// 两个语言在搜索结果里是同一个页面，等于白做。

import { createContext, useContext } from 'react'
import { zh } from './zh'
import { en } from './en'
import type { Dict } from './zh'

export type { Dict }
export type LangCode = 'zh' | 'en'

export const dicts: Record<LangCode, Dict> = { zh, en }

/// 当前页面的语言。SSR 时由预渲染脚本指定，浏览器里从 <html lang> 读。
export const LangContext = createContext<Dict>(zh)

export function useT(): Dict {
  return useContext(LangContext)
}

/// 把一个站内路径换算到目标语言。
///   toLang('/download.html', 'en')  → '/en/download.html'
///   toLang('/en/download.html', 'zh') → '/download.html'
export function toLang(path: string, lang: LangCode): string {
  const bare = path.replace(/^\/en(\/|$)/, '/')
  const clean = bare === '' ? '/' : bare
  return lang === 'en' ? `/en${clean === '/' ? '/' : clean}` : clean
}

/// 浏览器里判断当前语言：只看 <html lang>，那是预渲染写死的，
/// 不会因为 hydration 时机不同而摇摆。
export function detectLang(): LangCode {
  if (typeof document === 'undefined') return 'zh'
  return document.documentElement.lang.startsWith('en') ? 'en' : 'zh'
}
