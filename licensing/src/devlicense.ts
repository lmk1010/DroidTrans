/// 给本机签一份开发用的许可证，直接落到桌面端读的位置。
///
///   node --experimental-strip-types src/devlicense.ts [plan]
///
/// 和 mktoken.ts 的区别：那个是给 Go 端做跨语言对拍的裸串，不绑设备；
/// 这个绑当前这台机器的 device id 并且直接装好，用来在本机验证
/// Pro 功能的完整路径（付费墙、导出、恢复…），不用真买一个码。
///
/// 只在开发机上用。私钥不在仓库里，别人跑不出来。
import { execFileSync } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { writeFileSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { dirname as _dirname } from 'node:path'
import { issue, newCode, expiryFor, type Plan } from './license.ts'
import { loadEnv } from './env.ts'

const HERE = _dirname(fileURLToPath(import.meta.url))
loadEnv(HERE)

const plan = (process.argv[2] ?? 'lifetime') as Plan

// 路径和 device id 都问桌面端自己要 —— 在这里各写一份迟早会分叉，
// 而分叉的结果是「装了却不生效」，查起来很费时间。
const out = execFileSync('go', ['run', './cmd/licpath'], {
  cwd: HERE + '/../../desktop',
  encoding: 'utf8',
}).trim().split('\n')
const [licPath, deviceId] = out

const token = issue({
  v: 2,
  product: 'droidtrans-pro',
  plan,
  code: newCode(randomBytes),
  deviceId,
  email: 'dev@localhost',
  issued: new Date().toISOString(),
  expires: expiryFor(plan, new Date()),
  maxVersion: '1.99.99',
} as any, process.env.LICENSE_PRIVATE_KEY!)

mkdirSync(dirname(licPath), { recursive: true })
writeFileSync(licPath, token)
console.log(`已装 ${plan} 许可证 → ${licPath}`)
console.log('撤销：删掉这个文件，然后重启桌面端')
