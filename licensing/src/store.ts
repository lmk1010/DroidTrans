/// 订单与激活码的存储。
///
/// 用一个 JSON 文件。这不是偷懒：授权记录的写入频率是「每笔订单一次」，
/// 上数据库反而多一个要备份、要维护、会挂的东西。
/// 写入走「临时文件 + rename」，rename 在同一文件系统内是原子的，
/// 进程在写一半时被杀也不会留下半个文件。

import { mkdirSync, readFileSync, renameSync, writeFileSync, existsSync, statSync } from 'node:fs'
import { dirname, resolve } from 'node:path'

export type Activation = {
  /** 首次占用这个设备名额的时间 */
  at: string
  /** 最近一次激活或续签时间，只用于排查，不参与期限计算 */
  lastSeenAt?: string
  ua?: string
  /**
   * 客户端生成的稳定随机 ID。
   *
   * 早期记录没有这个字段，所以必须保持可选；新客户端一旦带上，
   * 同一设备重复激活只更新 lastSeenAt，不再重复占名额。
   */
  deviceId?: string
}

export type CodeRecord = {
  code: string
  orderId: string
  email: string
  /** 档位。一个码通吃三端，所以这里记的是期限而不是平台 */
  plan: 'year' | 'years3' | 'lifetime'
  createdAt: string
  /** 首次激活时间。停用设备不会清掉，避免重新激活时重置年付期限。 */
  activatedAt?: string
  /** 每次换机激活都记一笔，用来发现异常分享 */
  activations: Activation[]
  /** 退款后置为 true，激活接口据此拒绝 */
  revoked?: boolean
}

type Data = {
  /** orderId → code，用来给 webhook 去重：同一订单重复回调不能发两个码 */
  orders: Record<string, string>
  codes: Record<string, CodeRecord>
  /**
   * claim → code。
   *
   * 结账时随机生成一个 claim 塞进订单 metadata，同时挂在付款成功的回跳地址上；
   * 回调签发完码就在这里登记。付款人回到感谢页时凭它把码取回来 ——
   * 不然码只存在于服务器的 JSON 里，用户付了钱却没有任何途径看到它。
   */
  claims: Record<string, string>
}

const EMPTY: Data = { orders: {}, codes: {}, claims: {} }

export class Store {
  private path: string
  private data!: Data
  /** 上次读到的文件修改时间，用来发现别的进程改过库 */
  private mtime = 0

  constructor(path: string) {
    this.path = resolve(path)
    mkdirSync(dirname(this.path), { recursive: true })
    this.load()
  }

  private load() {
    if (!existsSync(this.path)) {
      this.data = structuredClone(EMPTY)
      this.mtime = 0
      return
    }
    this.data = JSON.parse(readFileSync(this.path, 'utf8')) as Data
    // claims 是后加的字段，早先写下的库里没有
    this.data.claims ??= {}
    this.mtime = statSync(this.path).mtimeMs
  }

  /**
   * 每次读之前看一眼文件有没有被别人动过。
   *
   * 只在构造时读一次的话，内存副本会和磁盘悄悄分叉 —— 多开一个实例、
   * 或者人工补发一笔订单，服务就会拿着过期数据把正当用户挡在门外。
   * 一次 stat 的代价可以忽略。
   */
  private fresh() {
    try {
      const m = statSync(this.path).mtimeMs
      if (m !== this.mtime) this.load()
    } catch {
      // 文件还不存在，用内存里的空副本即可
    }
  }

  private flush() {
    const tmp = `${this.path}.tmp`
    writeFileSync(tmp, JSON.stringify(this.data, null, 2))
    renameSync(tmp, this.path)
    this.mtime = statSync(this.path).mtimeMs
  }

  /** 这个订单是否已经发过码 */
  codeForOrder(orderId: string): CodeRecord | undefined {
    this.fresh()
    const code = this.data.orders[orderId]
    return code ? this.data.codes[code] : undefined
  }

  put(rec: CodeRecord, claim?: string) {
    this.fresh()
    this.data.codes[rec.code] = rec
    this.data.orders[rec.orderId] = rec.code
    if (claim) this.data.claims[claim] = rec.code
    this.flush()
  }

  /** 把一个 claim 指向已有的码。回调重投时用，保证回跳地址始终能取到码。 */
  linkClaim(claim: string, code: string) {
    this.fresh()
    if (this.data.claims[claim] === code) return
    this.data.claims[claim] = code
    this.flush()
  }

  codeForClaim(claim: string): CodeRecord | undefined {
    this.fresh()
    const code = this.data.claims[claim]
    return code ? this.data.codes[code] : undefined
  }

  get(code: string): CodeRecord | undefined {
    this.fresh()
    return this.data.codes[code]
  }

  recordActivation(code: string, act: Activation) {
    this.fresh()
    const rec = this.data.codes[code]
    if (!rec) return
    rec.activatedAt ??= act.at
    rec.activations.push(act)
    this.flush()
  }

  /**
   * 为设备占一个激活名额。
   *
   * 返回 existing 时代表该设备已经激活过；这种情况只更新时间，
   * 不增加名额。检查上限和写入放在同一个同步方法里，避免两个请求
   * 同时看到「还剩一个名额」后都写进去。
   */
  activateDevice(
    code: string,
    act: Activation & { deviceId: string },
    max: number,
  ): 'created' | 'existing' | 'full' | 'missing' {
    this.fresh()
    const rec = this.data.codes[code]
    if (!rec) return 'missing'
    const derivedStart = rec.activations
      .map((item) => item.at)
      .filter(Boolean)
      .sort()[0]
    const filledLegacyStart = !rec.activatedAt && Boolean(derivedStart)
    if (filledLegacyStart) rec.activatedAt = derivedStart

    const existing = rec.activations.find((item) => item.deviceId === act.deviceId)
    if (existing) {
      existing.lastSeenAt = act.at
      if (act.ua) existing.ua = act.ua
      this.flush()
      return 'existing'
    }

    // 升级兼容：旧版已经占过名额，但记录里还没有 deviceId。
    // 新客户端第一次回来时认领其中一个旧名额，不额外增加计数。
    const legacy = rec.activations.find((item) => !item.deviceId)
    if (legacy) {
      legacy.deviceId = act.deviceId
      legacy.lastSeenAt = act.at
      if (act.ua) legacy.ua = act.ua
      this.flush()
      return 'existing'
    }
    if (rec.activations.length >= max) {
      if (filledLegacyStart) this.flush()
      return 'full'
    }

    rec.activatedAt ??= act.at
    rec.activations.push(act)
    this.flush()
    return 'created'
  }

  activationStart(code: string): string | undefined {
    this.fresh()
    const rec = this.data.codes[code]
    if (!rec) return undefined
    if (rec.activatedAt) return rec.activatedAt
    const earliest = rec.activations
      .map((item) => item.at)
      .filter(Boolean)
      .sort()[0]
    if (!earliest) return undefined
    rec.activatedAt = earliest
    this.flush()
    return earliest
  }

  hasDevice(code: string, deviceId: string): boolean {
    this.fresh()
    return this.data.codes[code]?.activations.some((act) => act.deviceId === deviceId) ?? false
  }

  touchDevice(code: string, deviceId: string, at: string, ua?: string): boolean {
    this.fresh()
    const act = this.data.codes[code]?.activations.find((item) => item.deviceId === deviceId)
    if (!act) return false
    act.lastSeenAt = at
    if (ua) act.ua = ua
    this.flush()
    return true
  }

  /** 旧许可证首次续签时，把一个无设备 ID 的历史名额绑定到当前设备。 */
  claimLegacyDevice(code: string, deviceId: string, at: string, ua?: string): boolean {
    this.fresh()
    const act = this.data.codes[code]?.activations.find((item) => !item.deviceId)
    if (!act) return false
    act.deviceId = deviceId
    act.lastSeenAt = at
    if (ua) act.ua = ua
    this.flush()
    return true
  }

  /** 释放指定设备的名额。重复停用按成功处理，所以只返回是否真的删到记录。 */
  deactivateDevice(code: string, deviceId: string): boolean {
    this.fresh()
    const rec = this.data.codes[code]
    if (!rec) return false
    const before = rec.activations.length
    rec.activations = rec.activations.filter((act) => act.deviceId !== deviceId)
    if (rec.activations.length === before) return false
    this.flush()
    return true
  }

  revoke(orderId: string) {
    this.fresh()
    const rec = this.codeForOrder(orderId)
    if (!rec) return
    rec.revoked = true
    this.flush()
  }

  stats() {
    this.fresh()
    const codes = Object.values(this.data.codes)
    return {
      total: codes.length,
      activated: codes.filter((c) => c.activations.length > 0).length,
      revoked: codes.filter((c) => c.revoked).length,
    }
  }
}
