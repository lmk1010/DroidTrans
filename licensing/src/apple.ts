/// 校验 Apple 的交易凭证。
///
/// StoreKit 2 给客户端的是一段 JWS（签名过的交易），我们不能直接信 ——
/// 客户端可以伪造任何字符串。必须自己验：
///   1. JWS 头里带着证书链（x5c），一路验到 Apple 的根证书
///   2. 用叶证书的公钥验这段 JWS 的签名
///   3. 再检查 bundleId 是不是我们的 App
///
/// 少任何一步，别人拿一段自己签的 JSON 就能白拿一份终身授权。
///
/// 这里不调 App Store Server API：那需要另一套密钥，而本地验签
/// 已经足够证明「这笔交易确实由 Apple 签发、且属于这个 App」。

import { X509Certificate, createPublicKey, createVerify } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

export type AppleTransaction = {
  transactionId: string
  originalTransactionId: string
  productId: string
  bundleId: string
  purchaseDate: number
  /** Sandbox / Production */
  environment: string
  revocationDate?: number
}

const b64urlToBuf = (s: string) => Buffer.from(s, 'base64url')

/** ES256 的签名是 r||s 各 32 字节，而 Node 的 verify 要 DER —— 不转会一律验不过 */
function joseToDer(sig: Buffer): Buffer {
  if (sig.length !== 64) return sig
  const trim = (b: Buffer) => {
    let i = 0
    while (i < b.length - 1 && b[i] === 0) i++
    const out = b.subarray(i)
    // 最高位是 1 的话要补一个 0x00，否则 DER 会当成负数
    return out[0] & 0x80 ? Buffer.concat([Buffer.from([0]), out]) : out
  }
  const r = trim(sig.subarray(0, 32))
  const s = trim(sig.subarray(32))
  const body = Buffer.concat([
    Buffer.from([0x02, r.length]), r,
    Buffer.from([0x02, s.length]), s,
  ])
  return Buffer.concat([Buffer.from([0x30, body.length]), body])
}

let rootCache: X509Certificate | null = null
function appleRoot(here: string): X509Certificate {
  if (rootCache) return rootCache
  const pem = readFileSync(join(here, '../certs/AppleRootCA-G3.pem'), 'utf8')
  rootCache = new X509Certificate(pem)
  return rootCache
}

export type VerifyOptions = {
  /** 目录，用来找根证书 */
  here: string
  bundleId: string
  /**
   * 只在开发时打开。Xcode 的 StoreKit 本地测试用它自己的根签名，
   * 验不到 Apple 根上 —— 打开后跳过链校验，但仍然解析并检查 bundleId。
   * 生产环境打开等于把门敞开。
   */
  allowTestCerts?: boolean
}

export function verifyAppleTransaction(jws: string, opts: VerifyOptions): AppleTransaction {
  const parts = jws.split('.')
  if (parts.length !== 3) throw new Error('凭证格式不对')

  const header = JSON.parse(b64urlToBuf(parts[0]).toString('utf8'))
  const payload = JSON.parse(b64urlToBuf(parts[1]).toString('utf8')) as AppleTransaction

  const chain: string[] = header.x5c ?? []
  if (!chain.length) throw new Error('凭证里没有证书链')

  const certs = chain.map((b64) =>
    new X509Certificate(Buffer.from(
      `-----BEGIN CERTIFICATE-----\n${b64}\n-----END CERTIFICATE-----`)))

  if (!opts.allowTestCerts) {
    // 链一环扣一环：叶 ← 中间 ← 根，每一环都得是上一环签的
    for (let i = 0; i < certs.length - 1; i++) {
      if (!certs[i].verify(certs[i + 1].publicKey)) {
        throw new Error('证书链对不上')
      }
    }
    const last = certs[certs.length - 1]
    const root = appleRoot(opts.here)
    // 链尾要么就是 Apple 根，要么是根签发的
    const ok = last.raw.equals(root.raw) || last.verify(root.publicKey)
    if (!ok) throw new Error('证书链不是 Apple 签发的')

    const now = new Date()
    for (const c of certs) {
      if (new Date(c.validTo) < now || new Date(c.validFrom) > now) {
        throw new Error('证书已过期')
      }
    }

    const leafKey = createPublicKey(certs[0].publicKey)
    const v = createVerify('SHA256')
    v.update(`${parts[0]}.${parts[1]}`)
    v.end()
    if (!v.verify(leafKey, joseToDer(b64urlToBuf(parts[2])))) {
      throw new Error('凭证签名不对')
    }
  }

  if (payload.bundleId !== opts.bundleId) {
    throw new Error(`凭证不属于这个 App（${payload.bundleId}）`)
  }
  if (payload.revocationDate) {
    throw new Error('这笔交易已被退款')
  }
  if (!payload.originalTransactionId || !payload.productId) {
    throw new Error('凭证缺少必要字段')
  }
  return payload
}

/** App Store 的商品 ID → 我们的档位 */
export function planForProduct(productId: string): 'year' | 'years3' | 'lifetime' | null {
  if (productId.endsWith('.pro.year')) return 'year'
  if (productId.endsWith('.pro.years3')) return 'years3'
  if (productId.endsWith('.pro.lifetime')) return 'lifetime'
  return null
}
