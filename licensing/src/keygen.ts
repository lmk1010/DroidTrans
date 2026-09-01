/// 生成签发许可证用的 Ed25519 密钥对。只需要跑一次。
///
///   npm run keygen
///
/// 私钥直接追加进 licensing/.env（已被 .gitignore 挡住），**不打印到终端** ——
/// 打印出来就意味着它出现在滚动缓冲、日志、以及任何在场的记录里。
/// 只有公钥会打印，它本来就要嵌进客户端。
///
/// 私钥一旦泄漏，任何人都能签发有效许可证；而换掉它会让所有已售出的
/// 许可证一起失效。它比 Waffo 的商户密钥还敏感。

import { generateKeyPairSync } from 'node:crypto'
import { appendFileSync, existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { fileURLToPath } from 'node:url'
import { dirname as _dirname } from 'node:path'

/// Node 18 上没有 import.meta.dirname（那是 20.11+ 才有的），
/// 而部署目标就是 Node 18 —— 用这种写法两边都能跑。
const HERE = _dirname(fileURLToPath(import.meta.url))


const envPath = resolve(HERE, '../.env')

if (existsSync(envPath) && readFileSync(envPath, 'utf8').includes('LICENSE_PRIVATE_KEY=')) {
  console.error('.env 里已经有 LICENSE_PRIVATE_KEY 了。')
  console.error('覆盖它会让所有已售出的许可证失效 —— 确认要换的话先手动删掉那一行。')
  process.exit(1)
}

const { publicKey, privateKey } = generateKeyPairSync('ed25519')
const priv = privateKey.export({ type: 'pkcs8', format: 'pem' }).toString().trim()
const pub = publicKey.export({ type: 'spki', format: 'pem' }).toString().trim()

appendFileSync(envPath, `\nLICENSE_PRIVATE_KEY="${priv.replace(/\n/g, '\\n')}"\n`)

console.log('私钥已写入 licensing/.env（没有打印出来）\n')
console.log('把下面这段公钥嵌进 desktop/internal/license/key.go：\n')
console.log(pub)
