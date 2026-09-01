/// 签发一份测试许可证，给 Go 端做跨语言对拍用。
///   node --experimental-strip-types src/mktoken.ts <platform>
import { resolve } from 'node:path'
import { randomBytes } from 'node:crypto'
import { issue, newCode } from './license.ts'

import { expiryFor, type Plan } from './license.ts'
import { loadEnv } from './env.ts'

import { fileURLToPath } from 'node:url'
import { dirname as _dirname } from 'node:path'

/// Node 18 上没有 import.meta.dirname（那是 20.11+ 才有的），
/// 而部署目标就是 Node 18 —— 用这种写法两边都能跑。
const HERE = _dirname(fileURLToPath(import.meta.url))

loadEnv(HERE)


const plan = (process.argv[2] ?? 'lifetime') as Plan
// 第三个参数：把「到期时间」往前挪多少天，用来造已过期的样本。
// 签发时间保持为现在 —— 真实场景就是这样：用户一直在联网使用，
// 只是订阅到期了。把两者一起往前挪会同时触发「太久没回连」，测不出想测的分支。
const expiredDays = Number(process.argv[3] ?? 0)
const base = new Date()

process.stdout.write(
  issue(
    {
      v: 2,
      product: 'droidtrans-pro',
      plan,
      code: newCode(randomBytes),
      email: 'crosscheck@example.com',
      issued: base.toISOString(),
      expires:
        expiredDays > 0
          ? new Date(Date.now() - expiredDays * 86400_000).toISOString()
          : expiryFor(plan, base),
      maxVersion: '1.99.99',
    },
    process.env.LICENSE_PRIVATE_KEY!,
  ),
)
