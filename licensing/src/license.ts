/// 许可证的签发与校验。
///
/// 用 Ed25519 签名。客户端内置公钥，装完之后**完全离线**就能验证 ——
/// 卓传是局域网工具，用户插着数据线往电脑倒照片时那台机器很可能没外网，
/// 联网校验会把已经付过钱的人挡在门外，这是这类软件最典型的事故。
///
/// 许可证的编码形式和 JWT 类似（两段 base64url 用点连接），但不用 JWT 库：
/// 这里只需要一种算法，引一个库反而多一层可能被绕过的实现。

import { createPrivateKey, createPublicKey, sign, verify } from 'node:crypto'

/// 从私钥导出公钥，服务端自己验证自己签发的许可证时用。
export function publicFrom(privateKeyPem: string): string {
  return createPublicKey(createPrivateKey(privateKeyPem))
    .export({ type: 'spki', format: 'pem' })
    .toString()
}

/** 授权档位 */
export type Plan = 'year' | 'years3' | 'lifetime'

export type LicensePayload = {
  /** 格式版本。改结构时递增，客户端据此判断认不认 */
  v: 2
  /** 产品标识 */
  product: 'droidtrans-pro'
  /**
   * 授权档位。
   * 一个激活码通吃 macOS / Android / iOS —— $1.99 的东西让用户
   * 每端各买一次，光解释这件事的客服成本就超过收入了。
   */
  plan: Plan
  /** 关联的激活码 */
  code: string
  /**
   * 当前许可证绑定的设备。
   *
   * 可选是为了兼容已经签发的 v2 许可证；新许可证都会带上，
   * 服务端续签和停用时据此防止拿别人的 device_id 冒充。
   */
  deviceId?: string
  /** 购买者邮箱，用于找回 */
  email: string
  /** 签发时间 ISO8601 */
  issued: string
  /** 到期时间 ISO8601；终生买断为 null */
  expires: string | null
  /**
   * 可用的版本上限。买断买的是当前大版本，
   * 2.0 出来时老用户仍能用 1.x —— 这条让将来做升级付费时不用改客户端。
   */
  maxVersion: string
}

/** 各档位的有效期，单位天。终生为 null。 */
export const PLAN_DAYS: Record<Plan, number | null> = {
  year: 365,
  years3: 365 * 3,
  lifetime: null,
}

/** 按档位算出到期时间 */
export function expiryFor(plan: Plan, from = new Date()): string | null {
  const days = PLAN_DAYS[plan]
  if (days === null) return null
  const d = new Date(from)
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString()
}

const b64u = (b: Buffer) => b.toString('base64url')
const unb64u = (s: string) => Buffer.from(s, 'base64url')

/** 用私钥签发一份许可证 */
export function issue(payload: LicensePayload, privateKeyPem: string): string {
  const key = createPrivateKey(privateKeyPem)
  const body = b64u(Buffer.from(JSON.stringify(payload), 'utf8'))
  // Ed25519 直接对原文签名，不需要先做哈希
  const sig = sign(null, Buffer.from(body, 'utf8'), key)
  return `${body}.${b64u(sig)}`
}

/** 用公钥校验。返回 null 表示不可信，调用方不要区分「格式错」和「签名错」。 */
export function check(token: string, publicKeyPem: string): LicensePayload | null {
  const dot = token.indexOf('.')
  if (dot <= 0) return null
  const body = token.slice(0, dot)
  const sig = token.slice(dot + 1)
  try {
    const ok = verify(null, Buffer.from(body, 'utf8'), createPublicKey(publicKeyPem), unb64u(sig))
    if (!ok) return null
    const payload = JSON.parse(unb64u(body).toString('utf8')) as LicensePayload
    if (payload.v !== 2 || payload.product !== 'droidtrans-pro') return null
    return payload
  } catch {
    return null
  }
}

/**
 * 生成激活码。
 *
 * 用 Crockford Base32：去掉了 I/L/O/U，避免和 1/0 看混，
 * 也避免拼出不雅的词。用户要在小窗口里手输这串东西，少一个歧义字符就少一次客服。
 */
const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'

export function newCode(random: (n: number) => Buffer): string {
  const bytes = random(12)
  let out = ''
  for (let i = 0; i < 12; i++) out += ALPHABET[bytes[i] % 32]
  return `DT-${out.slice(0, 4)}-${out.slice(4, 8)}-${out.slice(8, 12)}`
}

/** 把用户输入的激活码规整成标准形式：允许小写、允许漏掉连字符 */
export function normalizeCode(input: string): string {
  const raw = input.toUpperCase().replace(/[^0-9A-Z]/g, '')
  const body = raw.startsWith('DT') ? raw.slice(2) : raw
  if (body.length !== 12) return ''
  // 常见手误：把 O 打成 0、把 I/L 打成 1，这里统一纠正
  const fixed = body.replace(/O/g, '0').replace(/[IL]/g, '1')
  if (![...fixed].every((c) => ALPHABET.includes(c))) return ''
  return `DT-${fixed.slice(0, 4)}-${fixed.slice(4, 8)}-${fixed.slice(8, 12)}`
}
