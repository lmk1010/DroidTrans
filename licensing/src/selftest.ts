/// 授权链路自检。改动签发或校验逻辑后跑一遍。
///
///   npm run selftest

import { randomBytes, generateKeyPairSync } from 'node:crypto'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { issue, check, newCode, normalizeCode, expiryFor, PLAN_DAYS, type LicensePayload } from './license.ts'
import { Store, type CodeRecord } from './store.ts'

let pass = 0
let fail = 0
const ok = (name: string, cond: boolean, detail = '') => {
  if (cond) { pass++; console.log(`  ✅ ${name}`) }
  else { fail++; console.log(`  ❌ ${name}  ${detail}`) }
}

const { publicKey, privateKey } = generateKeyPairSync('ed25519')
const priv = privateKey.export({ type: 'pkcs8', format: 'pem' }).toString()
const pub = publicKey.export({ type: 'spki', format: 'pem' }).toString()

console.log('\n[1] 激活码')
const code = newCode(randomBytes)
ok(`格式 ${code}`, /^DT-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$/.test(code))
ok('不含易混字符 I/L/O/U', !/[ILOU]/.test(code.slice(3)))
const codes = new Set(Array.from({ length: 2000 }, () => newCode(randomBytes)))
ok(`2000 个不重复 (${codes.size})`, codes.size === 2000)

console.log('\n[2] 用户输入纠错')
ok('小写照收', normalizeCode(code.toLowerCase()) === code)
ok('漏掉连字符照收', normalizeCode(code.replace(/-/g, '')) === code)
ok('两端空格照收', normalizeCode(`  ${code}  `) === code)
ok('把 O 打成 0 能纠正', normalizeCode('DT-O123-4567-89AB') === 'DT-0123-4567-89AB')
ok('把 I/L 打成 1 能纠正', normalizeCode('DT-I23L-4567-89AB') === 'DT-1231-4567-89AB')
ok('长度不对判无效', normalizeCode('DT-123-456') === '')

console.log('\n[3] 签发与校验')
// 用年付做基准，这样「伪造成终生」才是一次真的篡改
const payload: LicensePayload = {
  v: 2, product: 'droidtrans-pro', plan: 'year',
  code, deviceId: 'device-test-001', email: 'a@b.com', issued: new Date().toISOString(),
  expires: expiryFor('year'), maxVersion: '1.99.99',
}
const token = issue(payload, priv)
ok(`签发成功 (${token.length} 字符)`, token.includes('.') && token.length < 500)
const back = check(token, pub)
ok('验签通过且内容一致',
  back?.code === code && back?.email === 'a@b.com' && back?.deviceId === 'device-test-001')

console.log('\n[4] 防篡改')
const [body, sig] = token.split('.')
// 把年付伪造成终生 —— 这正是攻击者最想干的那件事
const evil = Buffer.from(
  JSON.stringify({ ...payload, plan: 'lifetime', expires: null, email: 'attacker@evil.com' }),
  'utf8',
).toString('base64url')
ok('改内容后验签失败', check(`${evil}.${sig}`, pub) === null)
ok('改签名后验签失败', check(`${body}.${Buffer.from('x'.repeat(64)).toString('base64url')}`, pub) === null)
const { publicKey: otherPub } = generateKeyPairSync('ed25519')
ok('换一把公钥验不过', check(token, otherPub.export({ type: 'spki', format: 'pem' }).toString()) === null)
ok('残缺串不崩溃', check('garbage', pub) === null && check('', pub) === null && check('a.b', pub) === null)

console.log('\n[5] 档位与有效期')
ok('终生档没有到期时间', expiryFor('lifetime') === null)
ok('年付 365 天', PLAN_DAYS.year === 365)
ok('三年 1095 天', PLAN_DAYS.years3 === 1095)
const yearExp = expiryFor('year', new Date('2026-01-01T00:00:00Z'))
ok(`年付到期日算得对 (${yearExp?.slice(0, 10)})`, yearExp?.startsWith('2027-01-01') === true)
const lic3 = issue({ ...payload, plan: 'years3', expires: expiryFor('years3') }, priv)
const back3 = check(lic3, pub)
ok('三年档签发后能验回', back3?.plan === 'years3' && typeof back3?.expires === 'string')

console.log('\n[6] 设备名额')
const dir = mkdtempSync(join(tmpdir(), 'droidtrans-license-'))
try {
  const deviceStore = new Store(join(dir, 'licenses.json'))
  const deviceCode = 'DT-0123-4567-89AB'
  const rec: CodeRecord = {
    code: deviceCode,
    orderId: 'test-order',
    email: 'device@example.com',
    plan: 'lifetime',
    createdAt: '2026-01-01T00:00:00.000Z',
    activations: [],
  }
  deviceStore.put(rec)
  ok('新设备占用一个名额',
    deviceStore.activateDevice(deviceCode, {
      at: '2026-01-02T00:00:00.000Z',
      deviceId: 'device-alpha',
      ua: 'test',
    }, 1) === 'created')
  ok('同一设备重复激活不增加名额',
    deviceStore.activateDevice(deviceCode, {
      at: '2026-01-03T00:00:00.000Z',
      deviceId: 'device-alpha',
      ua: 'test-2',
    }, 1) === 'existing'
    && deviceStore.get(deviceCode)?.activations.length === 1)
  ok('不同设备会受到名额上限限制',
    deviceStore.activateDevice(deviceCode, {
      at: '2026-01-03T00:00:00.000Z',
      deviceId: 'device-beta',
    }, 1) === 'full')
  ok('停用释放名额且重复调用无害',
    deviceStore.deactivateDevice(deviceCode, 'device-alpha')
    && !deviceStore.deactivateDevice(deviceCode, 'device-alpha')
    && deviceStore.get(deviceCode)?.activations.length === 0)
  ok('停用后首次激活时间仍保留',
    deviceStore.activationStart(deviceCode) === '2026-01-02T00:00:00.000Z')
  ok('重新激活不会重置年付起算时间',
    deviceStore.activateDevice(deviceCode, {
      at: '2026-02-02T00:00:00.000Z',
      deviceId: 'device-beta',
    }, 1) === 'created'
    && deviceStore.activationStart(deviceCode) === '2026-01-02T00:00:00.000Z')

  const legacyCode = 'DT-ABCD-EFGH-JKMP'
  deviceStore.put({
    ...rec,
    code: legacyCode,
    orderId: 'legacy-order',
    activations: [{ at: '2026-01-01T00:00:00.000Z', ua: 'old-client' }],
  })
  ok('旧记录首次回来时认领原名额',
    deviceStore.activateDevice(legacyCode, {
      at: '2026-01-04T00:00:00.000Z',
      deviceId: 'device-legacy',
    }, 1) === 'existing'
    && deviceStore.get(legacyCode)?.activations[0]?.deviceId === 'device-legacy')
} finally {
  rmSync(dir, { recursive: true, force: true })
}

console.log(`\n通过 ${pass}，失败 ${fail}`)
process.exit(fail === 0 ? 0 : 1)
