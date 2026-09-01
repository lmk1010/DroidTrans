/// 在 Waffo 上创建两个一次性商品。只需要跑一次。
///
///   npm run setup
///
/// 跑完把输出的两个商品 ID 回填进 .env。默认建在测试环境，
/// 确认无误后再 publish 到生产。

import { resolve } from 'node:path'
import { WaffoPancake, TaxCategory } from '@waffo/pancake-ts'

import { toPem } from './pem.ts'
import { loadEnv } from './env.ts'

import { fileURLToPath } from 'node:url'
import { dirname as _dirname } from 'node:path'

/// Node 18 上没有 import.meta.dirname（那是 20.11+ 才有的），
/// 而部署目标就是 Node 18 —— 用这种写法两边都能跑。
const HERE = _dirname(fileURLToPath(import.meta.url))

loadEnv(HERE)


const client = new WaffoPancake({
  merchantId: process.env.WAFFO_MERCHANT_ID!,
  privateKey: toPem(process.env.WAFFO_PRIVATE_KEY!),
})

const storeId = process.env.WAFFO_STORE_ID!

/// 按档位分，不按平台分 —— 一个激活码通吃 macOS / Android / iOS。
/// 阶梯定得让终生档显得划算：三年是一年的两倍（等于买两年送一年），
/// 终生再翻一倍多，多数人会直接跳到终生。
const ITEMS = [
  { key: 'WAFFO_PRODUCT_YEAR', name: 'DroidTrans Pro — 1 Year', amount: process.env.PRICE_YEAR ?? '1.99' },
  { key: 'WAFFO_PRODUCT_YEARS3', name: 'DroidTrans Pro — 3 Years', amount: process.env.PRICE_YEARS3 ?? '3.99' },
  { key: 'WAFFO_PRODUCT_LIFETIME', name: 'DroidTrans Pro — Lifetime', amount: process.env.PRICE_LIFETIME ?? '9.99' },
]

for (const item of ITEMS) {
  try {
    const { product } = await client.onetimeProducts.create({
      storeId,
      name: item.name,
      prices: {
        USD: { amount: item.amount, taxCategory: TaxCategory.Software },
      },
    })
    console.log(`✅ ${item.name}  $${item.amount}`)
    console.log(`   ${item.key}=${(product as any).id ?? (product as any).productId}`)
  } catch (e: any) {
    console.error(`❌ ${item.name} 创建失败:`, e?.message ?? e)
    if (e?.details) console.error('   ', JSON.stringify(e.details).slice(0, 300))
  }
}
