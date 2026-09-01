/// 读取 .env。
///
/// 抽出来是因为 server / setup / mktoken 各写了一份，
/// 而其中一份的正则写成了 `[A-Z_]+` —— 变量名里带数字（WAFFO_PRODUCT_YEARS3）
/// 就整行被跳过，表现是「三年档商品未配置」，排查起来完全看不出是解析的问题。

import { readFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'

export function loadEnv(dir: string) {
  const file = resolve(dir, '../.env')
  if (!existsSync(file)) return
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    // 变量名允许数字，只是不能开头
    const m = line.match(/^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/)
    if (!m) continue
    let v = m[2].trim()
    if (v.startsWith('"') && v.endsWith('"')) v = v.slice(1, -1).replace(/\\n/g, '\n')
    if (!process.env[m[1]]) process.env[m[1]] = v
  }
}
