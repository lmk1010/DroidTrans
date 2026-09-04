// 构建后把每个页面渲染成真实 HTML 注入产物，并补齐 SEO 头。
//
// 由 package.json 的 build 串起来：vite build（客户端）→ 本脚本。
import { build } from 'vite'
import { readFile, writeFile, rm } from 'node:fs/promises'
import { resolve } from 'node:path'

const SITE = 'https://droidtrans.mkstore.life'
const root = import.meta.dirname
const ssrDir = resolve(root, '.ssr-tmp')

// 单独构建一份 SSR bundle，用完就删，不进发布产物
await build({
  root,
  logLevel: 'warn',
  build: { ssr: resolve(root, 'src/entry-ssr.tsx'), outDir: ssrDir, emptyOutDir: true },
})

const { render, meta, pageNames, langs } = await import(resolve(ssrDir, 'entry-ssr.js'))

/** 页面在站点里的路径。中文在根，英文在 /en/。 */
const urlOf = (page, lang) => {
  const p = page === 'index' ? '/' : `/${page}.html`
  return lang === 'en' ? `/en${p === '/' ? '/' : p}` : p
}

/** 产物文件位置，和 vite 的入口一一对应 */
const fileOf = (page, lang) =>
  resolve(root, 'dist', lang === 'en' ? `en/${page}.html` : `${page}.html`)

const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;')

let count = 0
for (const lang of langs) {
  for (const page of pageNames) {
    const file = fileOf(page, lang)
    let html = await readFile(file, 'utf8')
    const m = meta(page, lang)
    const url = SITE + urlOf(page, lang)
    // 付款成功页不该被搜索引擎收录：它只对刚付完钱的人有意义，
    // 被搜到反而会让人以为不用付款就能拿到激活码。
    const noindex = page === 'thanks'

    const body = render(page, lang)
    if (!html.includes('<div id="root"></div>')) {
      throw new Error(`${file} 里找不到挂载点，预渲染没法注入`)
    }
    html = html.replace('<div id="root"></div>', `<div id="root">${body}</div>`)

    // 语言标记要写在 <html> 上：屏幕阅读器靠它选发音，
    // 浏览器靠它选断行规则，前端也靠它判断当前语言。
    html = html.replace('<html lang="zh-CN">', `<html lang="${lang === 'en' ? 'en' : 'zh-CN'}">`)
    html = html.replace('<title>DroidTrans</title>', `<title>${esc(m.title)}</title>`)
    html = html.replace('<meta name="description" content="">',
      `<meta name="description" content="${esc(m.desc)}">`)

    // hreflang 告诉搜索引擎两个语言是同一页面的不同版本，
    // 不写的话它们会被当成互相抄袭的重复内容。
    const alts = noindex
      ? ''
      : langs
          .map((l) => `<link rel="alternate" hreflang="${l === 'en' ? 'en' : 'zh-Hans'}" href="${SITE}${urlOf(page, l)}">`)
          .join('\n')
    const head = [
      noindex ? '<meta name="robots" content="noindex, nofollow">' : `<link rel="canonical" href="${url}">`,
      alts,
      noindex ? '' : `<link rel="alternate" hreflang="x-default" href="${SITE}${urlOf(page, 'zh')}">`,
      `<meta property="og:type" content="website">`,
      `<meta property="og:url" content="${url}">`,
      `<meta property="og:title" content="${esc(m.title)}">`,
      `<meta property="og:description" content="${esc(m.desc)}">`,
      `<meta property="og:locale" content="${lang === 'en' ? 'en_US' : 'zh_CN'}">`,
      `<meta name="twitter:card" content="summary">`,
    ].filter(Boolean).join('\n')
    html = html.replace('</head>', head + '\n</head>')

    await writeFile(file, html)
    count++
    console.log(`预渲染 ${lang}/${page}  (+${(body.length / 1024).toFixed(1)} kB)`)
  }
}

// 搜索引擎的入口清单
// 站点地图里也不列 thanks
const indexable = pageNames.filter((p) => p !== 'thanks')
const urls = langs.flatMap((l) => indexable.map((p) => ({ url: SITE + urlOf(p, l), l, p })))
const sitemap = `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"
        xmlns:xhtml="http://www.w3.org/1999/xhtml">
${urls.map(({ url, p }) => `  <url>
    <loc>${url}</loc>
${langs.map((l) => `    <xhtml:link rel="alternate" hreflang="${l === 'en' ? 'en' : 'zh-Hans'}" href="${SITE}${urlOf(p, l)}"/>`).join('\n')}
  </url>`).join('\n')}
</urlset>
`
await writeFile(resolve(root, 'dist/sitemap.xml'), sitemap)
await writeFile(resolve(root, 'dist/robots.txt'),
  `User-agent: *\nAllow: /\n\nSitemap: ${SITE}/sitemap.xml\n`)

await rm(ssrDir, { recursive: true, force: true })
console.log(`\n共 ${count} 个页面，另有 sitemap.xml 和 robots.txt`)
