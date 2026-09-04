/// 读发布清单 latest.json。
///
/// 版本号和下载地址都从它来，发版只更新那个文件，页面不用重新构建。
/// 站点和清单同源（都在 R2 桶根），不涉及跨域。

import { useEffect, useState } from 'react'

export type Asset = { url: string; sha256?: string; size?: number }

export type Manifest = {
  version: string
  notes?: string
  published_at?: string
  macos_arm64?: Asset
  android?: Asset
}

export function humanSize(n?: number): string {
  if (n === undefined || n === null) return ''
  const u = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let v = n
  while (v >= 1024 && i < u.length - 1) {
    v /= 1024
    i++
  }
  return `${i === 0 ? v : v.toFixed(1)} ${u[i]}`
}

export type ManifestState =
  | { status: 'loading' }
  | { status: 'ready'; data: Manifest }
  | { status: 'error' }

export function useManifest(): ManifestState {
  const [state, setState] = useState<ManifestState>({ status: 'loading' })

  useEffect(() => {
    let alive = true
    fetch('/latest.json', { cache: 'no-cache' })
      .then((r) => (r.ok ? r.json() : Promise.reject(r.status)))
      .then((data: Manifest) => {
        if (alive) setState({ status: 'ready', data })
      })
      .catch(() => {
        // 取不到清单不能让下载按钮变死链：latest.apk 是固定地址，一直可用
        if (alive) setState({ status: 'error' })
      })
    return () => {
      alive = false
    }
  }, [])

  return state
}
