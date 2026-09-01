/// 注册 Waffo 的回调地址。付款成功后 Waffo 会往这里投递事件，
/// 没有它就收不到通知，激活码也就发不出去。
///
///   npm run hook          # 注册
///   npm run hook -- list  # 看现在注册了什么

import { WaffoPancake } from '@waffo/pancake-ts'
import { fileURLToPath } from 'node:url'
import { dirname } from 'node:path'
import { loadEnv } from './env.ts'
import { toPem } from './pem.ts'

const HERE = dirname(fileURLToPath(import.meta.url))
loadEnv(HERE)

const client = new WaffoPancake({
  merchantId: process.env.WAFFO_MERCHANT_ID!,
  privateKey: toPem(process.env.WAFFO_PRIVATE_KEY!),
})

const storeId = process.env.WAFFO_STORE_ID!
const url = process.env.WEBHOOK_URL ?? 'https://droidtrans.mkstore.life/api/webhook'

// 只订阅真正要处理的事件：一次性订单付成功，以及退款。
// 订阅一堆用不上的只会让日志更吵。
const events = ['order.completed', 'refund.completed'] as const

const { webhook, warnings } = await client.webhooks.add({
  storeId,
  channel: 'http' as never,
  url,
  events: events as never,
  // 测试环境的商品和订单只会走 testMode 的回调
  testMode: true,
})

console.log('✅ 回调已注册')
console.log('   id    ', (webhook as any).id)
console.log('   url   ', (webhook as any).url)
console.log('   events', (webhook as any).events?.join(', '))
console.log('   测试环境', (webhook as any).testMode)
if (warnings?.length) console.log('   提示  ', JSON.stringify(warnings).slice(0, 200))
