import puppeteer from 'puppeteer-core'

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const url = process.argv[2]
const EMAIL = process.argv[3] ?? 'e2e-test@droidtrans.test'
// Waffo 文档给的测试卡：这张一定成功
const CARD = '4576750000000110'

const wait = (ms) => new Promise((r) => setTimeout(r, ms))

// 结账页跟着会话的 language 走，按钮文案会是中文。
// 只认英文的话，脚本会在第一页就卡住而且看起来像「按钮不见了」。
const LABELS = {
  continue: ['Continue', '继续'],
  pay: ['Pay', '支付', '付款', '立即支付'],
  quickFill: ['Success', '成功'],
}
const clickByText = (page, names) =>
  page.evaluate((names) => {
    const btn = [...document.querySelectorAll('button')].find((b) =>
      names.includes((b.innerText || '').trim()),
    )
    if (!btn) return null
    btn.click()
    return (btn.innerText || '').trim()
  }, names)

const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new', args: ['--no-sandbox'] })
const page = await browser.newPage()
await page.setViewport({ width: 1280, height: 1000 })

try {
  await page.goto(url, { waitUntil: 'networkidle2', timeout: 45000 })
  await wait(2500)

  console.log('第一步：填邮箱')
  // 会话带了 buyerEmail 时输入框是预填的，直接 type 会把两个地址接成一个
  await page.evaluate(() => {
    const el = document.querySelector('#checkout-email')
    if (el) {
      el.value = ''
      el.dispatchEvent(new Event('input', { bubbles: true }))
    }
  })
  await page.type('#checkout-email', EMAIL, { delay: 20 })
  await wait(400)
  // 点 Continue 会整页跳转，必须等导航结束再碰 DOM，
  // 否则执行上下文被销毁，后面所有操作都会抛错
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 45000 }).catch(() => {}),
    clickByText(page, LABELS.continue),
  ])
  await wait(6000)
  await page.screenshot({ path: '/tmp/checkout2.png' })

  console.log('第二步：用测试环境的 Quick Fill 填卡')
  // 有效期/CVV/姓名是填完卡号后才渲染的，手工逐个 type 会漏。
  // 测试环境自带 Quick Fill，点一下就把整套合法测试卡填好，更可靠。
  const quick = await clickByText(page, LABELS.quickFill)
  console.log('  Quick Fill:', quick ? '已点击' : '没找到，回退到手工填写')

  if (!quick) {
    const type = async (name, value) => {
      const el = await page.$(`input[name="${name}"]`)
      if (el) { await el.click(); await el.type(value, { delay: 25 }) }
    }
    await type('payMethodProperties.card.pan', CARD)
    await type('payMethodProperties.card.expiry', '12/30')
    await type('payMethodProperties.card.cvv', '123')
    await type('payMethodProperties.card.name', 'E2E Test')
  }
  await wait(1500)
  await page.screenshot({ path: '/tmp/checkout3.png' })

  console.log('第三步：提交付款')
  const clicked = await clickByText(page, LABELS.pay)
  console.log('  点了:', clicked ?? '（没找到付款按钮）')
  await wait(12000)
  await page.screenshot({ path: '/tmp/checkout4.png' })
  console.log('  最终地址:', page.url())

  // 感谢页拿到码要等两件事：Waffo 投递回调、页面轮询到结果。
  // 这一步才是真正的验收 —— 前面全成功但码没出现，用户依然一无所获。
  console.log('第四步：等感谢页显示激活码')
  const code = await page
    .waitForSelector('.code-value', { timeout: 90000 })
    .then((el) => page.evaluate((e) => e.textContent, el))
    .catch(() => null)
  console.log('  激活码:', code ?? '（没等到 —— 看 /tmp/checkout4.png 和服务端日志）')
  await page.screenshot({ path: '/tmp/checkout5.png' })
} catch (e) {
  console.log('出错:', String(e).slice(0, 200))
  await page.screenshot({ path: '/tmp/checkout-err.png' }).catch(() => {})
} finally {
  await browser.close()
}
