/// 查看这个店铺注册了哪些回调。排查「付款成功但没收到通知」时用。
import { WaffoPancake } from '@waffo/pancake-ts'
import { fileURLToPath } from 'node:url'
import { dirname } from 'node:path'
import { loadEnv } from './env.ts'
import { toPem } from './pem.ts'

loadEnv(dirname(fileURLToPath(import.meta.url)))

const client = new WaffoPancake({
  merchantId: process.env.WAFFO_MERCHANT_ID!,
  privateKey: toPem(process.env.WAFFO_PRIVATE_KEY!),
})

const store = await (client as any).stores.get({ id: process.env.WAFFO_STORE_ID! }).catch(() => null)
const hooks = (store?.store ?? store)?.webhooks
if (hooks) {
  console.log(JSON.stringify(hooks, null, 1).slice(0, 1200))
} else {
  // 不同版本的 SDK 字段名不一样，把整个店铺对象打出来找一找
  console.log(JSON.stringify(store, null, 1).slice(0, 1500))
}
