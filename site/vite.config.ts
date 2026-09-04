import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { resolve } from 'node:path'

// 多页构建：每个页面出一个独立的 .html。
//
// R2 是对象存储，不会把 /privacy 这样的路径映射到 privacy.html，
// 所以不能用前端路由 —— 每个页面在桶里都得是一个真实存在的 .html 对象。
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
    rollupOptions: {
      input: Object.fromEntries(
        ['zh', 'en'].flatMap((lang) =>
          ['index', 'download', 'pricing', 'thanks', 'changelog', 'privacy', 'support'].map((p) => {
            const rel = lang === 'en' ? `en/${p}.html` : `${p}.html`
            return [lang === 'en' ? `en-${p}` : p, resolve(import.meta.dirname, rel)]
          }),
        ),
      ),
    },
  },
})
