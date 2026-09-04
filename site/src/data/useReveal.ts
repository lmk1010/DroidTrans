/// 滚动进场。
///
/// 元素进入视口时加 .in，CSS 负责那 14px 的位移和淡入。只触发一次 ——
/// 来回滚动时反复播放动画很烦人。
///
/// 用 IntersectionObserver 而不是监听 scroll：不占主线程，滚动不会卡。

import { useEffect } from 'react'

export function useReveal() {
  useEffect(() => {
    const els = document.querySelectorAll<HTMLElement>('.reveal')
    if (!els.length) return

    // 浏览器太老或用户要求减少动画时，直接全部显示，别让内容看不见
    if (
      typeof IntersectionObserver === 'undefined' ||
      window.matchMedia('(prefers-reduced-motion: reduce)').matches
    ) {
      els.forEach((el) => el.classList.add('in'))
      return
    }

    const io = new IntersectionObserver(
      (entries) => {
        for (const e of entries) {
          if (e.isIntersecting) {
            e.target.classList.add('in')
            io.unobserve(e.target)
          }
        }
      },
      { rootMargin: '0px 0px -12% 0px', threshold: 0.05 },
    )
    els.forEach((el) => io.observe(el))

    // 兜底：观察器万一没回调（某些内嵌浏览器、极端视口、扩展干扰），
    // 内容会一直停在 opacity:0。1.5 秒后无条件放出来 ——
    // 动画没播到无所谓，内容看不见是事故。
    const failsafe = window.setTimeout(() => {
      document.querySelectorAll('.reveal:not(.in)').forEach((el) => el.classList.add('in'))
    }, 1500)

    return () => {
      window.clearTimeout(failsafe)
      io.disconnect()
    }
  }, [])
}

/// 顶栏滚动后才显出分隔线，页面在最顶部时保持干净
export function useStickyHeader() {
  useEffect(() => {
    const header = document.querySelector('header')
    if (!header) return
    const onScroll = () => header.classList.toggle('scrolled', window.scrollY > 8)
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])
}
