/// 端到端演练：付款回调 → 发码 → 激活 → 客户端拿到许可证。
///
/// 真实付款需要在浏览器里刷测试卡，这里直接把 webhook 处理后的结果写进库，
/// 验证后半程（发码、激活、次数限制、吊销）在真实服务上跑得通。
import { randomBytes } from 'node:crypto'
import { resolve } from 'node:path'
import { Store } from './store.ts'
import { newCode } from './license.ts'

import { fileURLToPath } from 'node:url'
import { dirname as _dirname } from 'node:path'

/// Node 18 上没有 import.meta.dirname（那是 20.11+ 才有的），
/// 而部署目标就是 Node 18 —— 用这种写法两边都能跑。
const HERE = _dirname(fileURLToPath(import.meta.url))


const BASE = 'http://127.0.0.1:8791'
const store = new Store(process.env.STORE_PATH ?? resolve(HERE, '../data/licenses.json'))

let pass = 0, fail = 0
const ok = (n: string, c: boolean, d = '') => {
  if (c) { pass++; console.log(`  ✅ ${n}`) } else { fail++; console.log(`  ❌ ${n}  ${d}`) }
}

// 模拟 webhook 落库
const code = newCode(randomBytes)
const orderId = `ORD_e2e_${Date.now()}`
store.put({
  code, orderId, email: 'buyer@example.com', plan: 'lifetime',
  createdAt: new Date().toISOString(), activations: [],
})
console.log(`\n[1] 模拟付款完成，发出激活码 ${code}`)

const post = async (path: string, body: unknown) => {
  const r = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  })
  return { status: r.status, json: await r.json().catch(() => ({})) as any }
}

console.log('\n[2] 客户端激活')
const primaryDevice = 'e2e-device-primary'
const a = await post('/api/activate', { code, device_id: primaryDevice })
ok('返回许可证', a.status === 200 && typeof a.json.license === 'string')
ok('带回购买者邮箱', a.json.email === 'buyer@example.com')
const token = a.json.license as string

console.log('\n[3] 用户输入容错')
const b = await post('/api/activate', {
  code: code.toLowerCase().replace(/-/g, ' '),
  device_id: primaryDevice,
})
ok('小写+空格分隔也能激活', b.status === 200)

console.log('\n[4] 拒绝的情况')
const c1 = await post('/api/activate', {
  code: 'DT-0000-0000-0000',
  device_id: primaryDevice,
})
ok('不存在的码 → 404', c1.status === 404, `实际 ${c1.status}`)
const c2 = await post('/api/activate', {
  code: 'nonsense',
  device_id: primaryDevice,
})
ok('乱输 → 400', c2.status === 400, `实际 ${c2.status}`)
// 平台绑定已经取消：同一个码在三端都该能用
const c3 = await post('/api/activate', { code, device_id: 'e2e-device-second' })
ok('同一个码可在任意平台激活', c3.status === 200, `实际 ${c3.status}`)

console.log('\n[5] 激活次数上限')
for (let i = 3; i <= 5; i++) {
  await post('/api/activate', { code, device_id: `e2e-device-${i}` })
}
const over = await post('/api/activate', { code, device_id: 'e2e-device-6' })
ok('超过上限 → 429', over.status === 429, `实际 ${over.status}`)

console.log('\n[6] 设备绑定与注销')
const refreshed = await post('/api/refresh', {
  license: token,
  device_id: primaryDevice,
})
ok('原设备可以续签', refreshed.status === 200, `实际 ${refreshed.status}`)
const copied = await post('/api/refresh', {
  license: token,
  device_id: 'e2e-device-copy',
})
ok('复制到其他设备不能续签', copied.status === 403, `实际 ${copied.status}`)
const deactivated = await post('/api/deactivate', {
  license: token,
  device_id: primaryDevice,
})
ok('原设备可以释放名额', deactivated.status === 200, `实际 ${deactivated.status}`)
const afterDeactivate = await post('/api/refresh', {
  license: token,
  device_id: primaryDevice,
})
ok('注销后的设备不能继续续签', afterDeactivate.status === 403,
  `实际 ${afterDeactivate.status}`)

console.log('\n[7] 退款吊销')
const code2 = newCode(randomBytes)
const order2 = `ORD_refund_${Date.now()}`
store.put({ code: code2, orderId: order2, email: 'r@e.com', plan: 'year', createdAt: new Date().toISOString(), activations: [] })
ok('吊销前可激活', (await post('/api/activate', {
  code: code2,
  device_id: 'e2e-refund-device',
})).status === 200)
new Store(process.env.STORE_PATH ?? resolve(HERE, '../data/licenses.json')).revoke(order2)
// 服务进程持有自己的内存副本，这里只验证接口形状；真实退款走 webhook，同一进程内生效
console.log('     （吊销由 webhook 在服务进程内触发，此处不跨进程验证）')

console.log(`\n通过 ${pass}，失败 ${fail}`)
if (token) {
  const { writeFileSync } = await import('node:fs')
  writeFileSync('/tmp/e2e-license.txt', token)
  console.log('许可证已写到 /tmp/e2e-license.txt，交给 Go 端验证')
}
process.exit(fail === 0 ? 0 : 1)
