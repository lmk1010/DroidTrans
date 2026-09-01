/// 卓传的收款与授权服务。
///
/// 三件事：
///   1. 官网点「购买」→ 这里向 Waffo 要一个结账地址
///   2. 付款成功 → Waffo 回调这里 → 生成激活码
///   3. 客户端输入激活码 → 这里换出一份签名许可证，之后完全离线可用
///
/// 用 Node 内置的 http，不引框架：一共四个路由，装 Express 得不偿失，
/// 而且 webhook 必须拿到未经解析的原始报文，框架的 body parser 反而碍事。

import { createServer } from 'node:http'
import { randomBytes, timingSafeEqual } from 'node:crypto'
import { resolve } from 'node:path'
import { WaffoPancake, verifyWebhook } from '@waffo/pancake-ts'
import { Store } from './store.ts'
import { issue, check, publicFrom, newCode, normalizeCode, expiryFor, type LicensePayload, type Plan } from './license.ts'
import { toPem } from './pem.ts'
import { verifyAppleTransaction, planForProduct } from './apple.ts'
import { loadEnv } from './env.ts'

import { fileURLToPath } from 'node:url'
import { dirname as _dirname } from 'node:path'

/// Node 18 上没有 import.meta.dirname（那是 20.11+ 才有的），
/// 而部署目标就是 Node 18 —— 用这种写法两边都能跑。
const HERE = _dirname(fileURLToPath(import.meta.url))


// ---- 配置 ----
loadEnv(HERE)

const need = (k: string) => {
  const v = process.env[k]
  if (!v) throw new Error(`缺少环境变量 ${k}`)
  return v
}

const PORT = Number(process.env.PORT ?? 8791)
/// 监听地址。默认只听回环，本地开发够用；
/// 服务器上 nginx 跑在容器里，容器内的 127.0.0.1 是容器自己而不是宿主机，
/// 所以部署时要把这个设成 0.0.0.0，否则反代永远连不上。
const HOST_ADDR = process.env.HOST ?? '127.0.0.1'
const SITE = process.env.SITE_ORIGIN ?? 'https://droidtrans.mkstore.life'
const LICENSE_KEY = need('LICENSE_PRIVATE_KEY')
/** 各档位对应的 Waffo 商品，由 setup 脚本创建后填进 .env。
    按档位分而不是按平台分 —— 一个激活码通吃三端。 */
const PRODUCTS: Record<string, string | undefined> = {
  year: process.env.WAFFO_PRODUCT_YEAR,
  years3: process.env.WAFFO_PRODUCT_YEARS3,
  lifetime: process.env.WAFFO_PRODUCT_LIFETIME,
}
/** iOS App 的 bundle id。Apple 的凭证里带着它，用来确认这笔交易是买给我们的 */
const APPLE_BUNDLE_ID = process.env.APPLE_BUNDLE_ID ?? 'life.mkstore.droidtrans'
/**
 * 只在开发时打开：接受 Xcode StoreKit 本地测试签发的凭证。
 * 那些凭证验不到 Apple 根证书上，生产环境打开等于谁都能白拿授权。
 */
const ALLOW_STOREKIT_TEST = process.env.ALLOW_STOREKIT_TEST === '1'

/** 同一个码允许激活几台设备。买断制不该锁死一台，但也不能无限分享。 */
const MAX_ACTIVATIONS = Number(process.env.MAX_ACTIVATIONS ?? 5)
/** 付款回跳地址凭 claim 取码的有效期。够用户从付款页走到感谢页，也够他刷新几次。 */
const CLAIM_TTL_MS = 72 * 60 * 60 * 1000

const waffo = new WaffoPancake({
  merchantId: need('WAFFO_MERCHANT_ID'),
  privateKey: toPem(need('WAFFO_PRIVATE_KEY')),
})
const store = new Store(process.env.STORE_PATH ?? resolve(HERE, '../data/licenses.json'))

// ---- 工具 ----
const json = (res: any, code: number, body: unknown) => {
  const s = JSON.stringify(body)
  res.writeHead(code, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(s),
    'access-control-allow-origin': SITE,
  })
  res.end(s)
}

const readBody = (req: any) =>
  new Promise<string>((ok, fail) => {
    let n = 0
    const parts: Buffer[] = []
    req.on('data', (c: Buffer) => {
      n += c.length
      // 正常请求都在几百字节，给出上限免得被人塞满内存
      if (n > 64 * 1024) return fail(new Error('body too large'))
      parts.push(c)
    })
    req.on('end', () => ok(Buffer.concat(parts).toString('utf8')))
    req.on('error', fail)
  })

/** 设备 ID 只接受客户端生成的短标识，不让任意长文本进入授权库。 */
const deviceIdFrom = (value: unknown): string =>
  typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/.test(value)
    ? value
    : ''

const requestUA = (req: any): string =>
  String(req.headers['user-agent'] ?? '').slice(0, 120)

// ---- 路由 ----
const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', 'http://localhost')

  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'access-control-allow-origin': SITE,
      'access-control-allow-headers': 'content-type',
      'access-control-allow-methods': 'POST, GET',
    })
    return res.end()
  }

  if (url.pathname === '/api/health') {
    return json(res, 200, { ok: true, ...store.stats() })
  }

  // 1) 官网点购买
  if (url.pathname === '/api/checkout' && req.method === 'POST') {
    try {
      const { plan, email, lang } = JSON.parse(await readBody(req))
      const productId = PRODUCTS[plan]
      if (!productId) return json(res, 400, { error: '未知的档位或商品未配置' })

      // 一次性取码凭据：既塞进订单 metadata，也挂在付款成功的回跳地址上。
      // 回调签发码时把两者对上，付款人回到感谢页就能直接看到自己的码 ——
      // 否则码只躺在服务器的 JSON 里，用户没有任何途径拿到它。
      const claim = randomBytes(24).toString('base64url')

      const session = await waffo.checkout.createSession({
        productId,
        currency: 'USD',
        buyerEmail: email || undefined,
        successUrl: `${SITE}${lang === 'en' ? '/en' : ''}/thanks.html?c=${claim}`,
        language: lang === 'zh' ? 'zh-Hans' : 'en',
        // 档位带在订单上，回调时据此决定许可证的有效期
        metadata: { plan, claim },
      })
      return json(res, 200, { url: session.checkoutUrl })
    } catch (e) {
      console.error('[checkout]', e)
      return json(res, 500, { error: '创建结账会话失败' })
    }
  }

  // 2) Waffo 回调
  if (url.pathname === '/api/webhook' && req.method === 'POST') {
    // 必须用原始报文验签，先 JSON.parse 会让签名对不上
    const raw = await readBody(req)
    // 到达就记一笔。只在失败时记日志的话，「回调根本没来」和
    // 「来了但什么都没发生」在日志里长得一模一样，没法排查。
    console.log(`[webhook] 收到 ${raw.length}B sig=${req.headers['x-waffo-signature'] ? 'yes' : 'no'}`)
    let event
    try {
      event = verifyWebhook(raw, req.headers['x-waffo-signature'] as string)
    } catch (e) {
      console.error('[webhook] 验签失败', e)
      return json(res, 400, { error: 'bad signature' })
    }
    console.log(`[webhook] 验签通过 type=${event.eventType} mode=${event.mode} id=${event.id}`)

    try {
      const data = event.data as any
      if (event.eventType === 'order.completed') {
        // 结账时挂在订单上的 metadata，回调里叫 orderMetadata
        const meta = (data.orderMetadata ?? {}) as Record<string, string>
        const claim = meta.claim as string | undefined
        // 回调可能重复投递，同一订单只发一个码
        const existing = store.codeForOrder(data.orderId)
        if (existing) {
          // 重投时也要把 claim 对上：首投若因故没落库，回跳地址就永远取不到码
          if (claim) store.linkClaim(claim, existing.code)
          console.log(`[webhook] 订单 ${data.orderId} 已发过码，跳过`)
        } else {
          const plan = (meta.plan ?? 'lifetime') as Plan
          const rec = {
            code: newCode(randomBytes),
            orderId: data.orderId,
            email: data.buyerEmail,
            plan,
            createdAt: new Date().toISOString(),
            activations: [],
          }
          store.put(rec, claim)
          console.log(
            `[webhook] 已签发 ${rec.code} → ${rec.email} (${plan}, ${event.mode}, claim=${claim ? 'yes' : 'no'})`,
          )
        }
      } else if (event.eventType === 'refund.succeeded') {
        // 只认退款成功。refund.failed 是「钱没退成」，
        // 用户依然持有这份授权，按退款处理会把正当客户挡在门外。
        //
        // 退款后吊销：已经装好的客户端仍能用到许可证过期，
        // 但换机时激活会被拒。做到这一步就够了，
        // 想立刻失效就得联网校验，那会伤害所有正常用户。
        store.revoke(data.orderId)
        console.log(`[webhook] 已吊销订单 ${data.orderId}`)
      } else {
        // 订阅类事件我们没开，走到这里说明后台勾了不该勾的，
        // 或者 Waffo 加了新事件 —— 记一笔，别静默吞掉
        console.log(`[webhook] 忽略事件 ${event.eventType}`)
      }
    } catch (e) {
      console.error('[webhook] 处理失败', e)
    }
    // 无论业务处理结果如何都回 200：验签已经过了，
    // 回错误码只会让 Waffo 一直重投同一条。
    return json(res, 200, { received: true })
  }

  // 3) iOS 内购换许可证
  //
  // iOS 上只能走 App Store 付款，但授权体系是三端通用的 ——
  // 所以拿 Apple 的交易凭证换一份和 Waffo 那边同格式的许可证，
  // 用户在 Mac 上也能用同一个码。
  //
  // 幂等键用 originalTransactionId：同一笔购买重装、换机、恢复购买都是它，
  // 用 transactionId 的话每次恢复都会多发一个码。
  if (url.pathname === '/api/apple/redeem' && req.method === 'POST') {
    try {
      const { jws, device_id: rawDeviceId } = JSON.parse(await readBody(req))
      if (typeof jws !== 'string' || !jws) {
        return json(res, 400, { error: 'missing_transaction' })
      }
      const deviceId = deviceIdFrom(rawDeviceId)
      if (!deviceId) return json(res, 400, { error: 'missing_device_id' })

      let tx
      try {
        tx = verifyAppleTransaction(jws, {
          here: HERE,
          bundleId: APPLE_BUNDLE_ID,
          allowTestCerts: ALLOW_STOREKIT_TEST,
        })
      } catch (e) {
        console.error('[apple] 验签失败', e)
        return json(res, 403, { error: 'bad_transaction' })
      }

      const plan = planForProduct(tx.productId)
      if (!plan) {
        console.error('[apple] 不认识的商品', tx.productId)
        return json(res, 400, { error: 'unknown_product' })
      }

      const orderId = `apple:${tx.originalTransactionId}`
      let rec = store.codeForOrder(orderId)
      if (!rec) {
        rec = {
          code: newCode(randomBytes),
          orderId,
          // Apple 不把买家邮箱给开发者，这里只能留个占位。
          // 用户想换设备时凭激活码本身找回，不靠邮箱。
          email: `apple-${tx.originalTransactionId}@appstore.local`,
          plan,
          createdAt: new Date().toISOString(),
          activations: [],
        }
        store.put(rec)
        console.log(`[apple] 已签发 ${rec.code} (${plan}, ${tx.environment})`)
      }
      if (rec.revoked) return json(res, 403, { error: 'revoked' })

      const activated = store.activateDevice(rec.code, {
        at: new Date().toISOString(),
        ua: 'ios-iap',
        deviceId,
      }, MAX_ACTIVATIONS)
      if (activated === 'full') {
        return json(res, 429, { error: 'too_many_activations', max: MAX_ACTIVATIONS })
      }

      // 直接把许可证也签出来，客户端不用再走一次 /api/activate
      const payload: LicensePayload = {
        v: 2,
        product: 'droidtrans-pro',
        plan: rec.plan,
        code: rec.code,
        deviceId,
        email: rec.email,
        issued: new Date().toISOString(),
        expires: expiryFor(rec.plan, new Date(rec.createdAt)),
        maxVersion: '1.99.99',
      }
      return json(res, 200, { code: rec.code, license: issue(payload, LICENSE_KEY) })
    } catch (e) {
      console.error('[apple]', e)
      return json(res, 500, { error: 'redeem_failed' })
    }
  }

  // 4) 付款回跳后取码
  //
  // 感谢页带着结账时生成的 claim 回来，凭它把码取出来显示。
  // 回调是异步的，付款人可能比它先到，所以「查不到」是正常状态而非错误 ——
  // 回 404 让前端接着轮询，而不是让它以为出了问题。
  if (url.pathname === '/api/claim' && req.method === 'GET') {
    const claim = url.searchParams.get('c') ?? ''
    if (!claim) return json(res, 400, { error: 'missing_claim' })

    const rec = store.codeForClaim(claim)
    if (!rec) return json(res, 404, { error: 'pending' })
    if (rec.revoked) return json(res, 403, { error: 'revoked' })

    // 回跳地址可能留在浏览器历史、被转发、被截图。
    // 让它只在付款后的短窗口内有效，过期就走人工补发。
    const age = Date.now() - Date.parse(rec.createdAt)
    if (Number.isFinite(age) && age > CLAIM_TTL_MS) return json(res, 410, { error: 'expired' })

    return json(res, 200, { code: rec.code, plan: rec.plan, email: rec.email })
  }

  // 5) 客户端拿激活码换许可证
  if (url.pathname === '/api/activate' && req.method === 'POST') {
    try {
      const { code, device_id: rawDeviceId } = JSON.parse(await readBody(req))
      const norm = normalizeCode(String(code ?? ''))
      if (!norm) return json(res, 400, { error: 'invalid_code' })
      const deviceId = deviceIdFrom(rawDeviceId)
      if (!deviceId) return json(res, 400, { error: 'missing_device_id' })

      const rec = store.get(norm)
      if (!rec) return json(res, 404, { error: 'not_found' })
      if (rec.revoked) return json(res, 403, { error: 'revoked' })
      const activated = store.activateDevice(norm, {
        at: new Date().toISOString(),
        ua: requestUA(req),
        deviceId,
      }, MAX_ACTIVATIONS)
      if (activated === 'full') {
        return json(res, 429, { error: 'too_many_activations', max: MAX_ACTIVATIONS })
      }

      // 期限从「首次激活」起算，而不是从付款起算 ——
      // 用户买了之后过两周才装，不该白白少两周。
      const payload: LicensePayload = {
        v: 2,
        product: 'droidtrans-pro',
        plan: rec.plan,
        code: rec.code,
        deviceId,
        email: rec.email,
        issued: new Date().toISOString(),
        expires: expiryFor(rec.plan, new Date(store.activationStart(norm) ?? Date.now())),
        // 买断买的是当前大版本；2.0 出来时老用户仍然能用 1.x
        maxVersion: process.env.MAX_VERSION ?? '1.99.99',
      }
      return json(res, 200, { license: issue(payload, LICENSE_KEY), email: rec.email })
    } catch (e) {
      console.error('[activate]', e)
      return json(res, 500, { error: 'server_error' })
    }
  }

  // 6) 续签：客户端定期拿旧许可证换一份新的
  //
  //    这是防破解的第三层，也是唯一不伤害离线用户的一层。
  //    许可证里的 issued 是签过名的，改不了；客户端据此判断
  //    「多久没回连过」。有网时自动续签，用户完全无感；
  //    长期连不上（或者码已被吊销）就会走到宽限期尽头自动降级。
  if (url.pathname === '/api/refresh' && req.method === 'POST') {
    try {
      const { license: old, device_id: rawDeviceId } = JSON.parse(await readBody(req))
      const payload = check(String(old ?? ''), publicFrom(LICENSE_KEY))
      if (!payload) return json(res, 400, { error: 'invalid_license' })
      const deviceId = deviceIdFrom(rawDeviceId)
      if (!deviceId) return json(res, 400, { error: 'missing_device_id' })
      if (payload.deviceId && payload.deviceId !== deviceId) {
        return json(res, 403, { error: 'device_mismatch' })
      }

      const rec = store.get(payload.code)
      if (!rec) return json(res, 404, { error: 'not_found' })
      if (rec.revoked) return json(res, 403, { error: 'revoked' })
      const now = new Date().toISOString()
      const knownDevice = store.touchDevice(payload.code, deviceId, now, requestUA(req))
        || (!payload.deviceId
          && store.claimLegacyDevice(payload.code, deviceId, now, requestUA(req)))
      if (!knownDevice) {
        return json(res, 403, { error: 'device_deactivated' })
      }

      // 只换发新的签发时间，到期时间保持不变 ——
      // 续签不是续期，年付用户不能靠反复刷新变成终生。
      const fresh: LicensePayload = {
        ...payload,
        deviceId,
        issued: new Date().toISOString(),
      }
      return json(res, 200, { license: issue(fresh, LICENSE_KEY) })
    } catch (e) {
      console.error('[refresh]', e)
      return json(res, 500, { error: 'server_error' })
    }
  }

  // 7) 释放本机激活名额。许可证签名证明调用者确实持有这个码，
  // 签进许可证的 deviceId 则阻止他拿别人的设备 ID 去注销其他设备。
  if (url.pathname === '/api/deactivate' && req.method === 'POST') {
    try {
      const { license: old, device_id: rawDeviceId } = JSON.parse(await readBody(req))
      const payload = check(String(old ?? ''), publicFrom(LICENSE_KEY))
      if (!payload) return json(res, 400, { error: 'invalid_license' })
      const deviceId = deviceIdFrom(rawDeviceId)
      if (!deviceId) return json(res, 400, { error: 'missing_device_id' })
      if (!payload.deviceId || payload.deviceId !== deviceId) {
        return json(res, 403, { error: 'device_mismatch' })
      }

      const rec = store.get(payload.code)
      if (!rec) return json(res, 404, { error: 'not_found' })
      if (rec.revoked) return json(res, 403, { error: 'revoked' })

      const removed = store.deactivateDevice(payload.code, deviceId)
      return json(res, 200, { deactivated: removed })
    } catch (e) {
      console.error('[deactivate]', e)
      return json(res, 500, { error: 'server_error' })
    }
  }

  json(res, 404, { error: 'not_found' })
})

server.listen(PORT, HOST_ADDR, () => {
  console.log(`授权服务 http://${HOST_ADDR}:${PORT}`)
  for (const [plan, id] of Object.entries(PRODUCTS)) {
    console.log(`  ${plan.padEnd(9)} ${id ?? '⚠️ 未配置'}`)
  }
})
