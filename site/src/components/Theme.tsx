/// 日/夜切换。
///
/// 优先级：用户手选 > 系统偏好。选择存在 localStorage，跨页面跨会话都记得。
/// 没选过就跟着系统走，系统切换时页面实时跟着变。
///
/// 首屏闪白的问题在各页 <head> 里的内联脚本解决 —— 那段脚本在 CSS 生效前
/// 就把 data-theme 写到 <html> 上了，React 挂载得太晚，来不及。

import { useEffect, useState } from 'react'
import { IconSun, IconMoon } from './Icons'

const KEY = 'dt-theme'
type Theme = 'light' | 'dark'

function current(): Theme {
  if (typeof document === 'undefined') return 'dark'
  const set = document.documentElement.dataset.theme
  if (set === 'light' || set === 'dark') return set
  return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
}

export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>('dark')

  // 服务端渲染时读不到 localStorage，挂载后再同步一次真实值
  useEffect(() => setTheme(current()), [])

  // 用户没手选过的话，跟随系统实时变化
  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: light)')
    const onChange = () => {
      try {
        if (localStorage.getItem(KEY)) return
      } catch { /* 隐私模式下读不到，当作没选过 */ }
      setTheme(mq.matches ? 'light' : 'dark')
    }
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])

  const toggle = () => {
    const next: Theme = theme === 'dark' ? 'light' : 'dark'
    document.documentElement.dataset.theme = next
    try {
      localStorage.setItem(KEY, next)
    } catch { /* 存不下就只在本页生效，不影响使用 */ }
    setTheme(next)
  }

  return (
    <button
      className="theme-btn"
      onClick={toggle}
      type="button"
      aria-label={theme === 'dark' ? '切换到浅色' : '切换到深色'}
      title={theme === 'dark' ? '切换到浅色' : '切换到深色'}
    >
      {theme === 'dark' ? <IconSun /> : <IconMoon />}
    </button>
  )
}
