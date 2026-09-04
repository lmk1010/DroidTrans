import { ArticlePage } from '../components/Layout'
import { useT } from '../i18n'

/// 隐私政策。两个语言各写一份正文 —— 这种带加粗、表格、链接的长文
/// 塞进词典会变成一堆碎字符串，还会丢掉富文本结构。

const permissionsZh = [
  { name: '本地网络', why: '在同一个 Wi-Fi 下找到你的电脑并连上它。只在局域网内使用，不访问互联网。' },
  { name: '相机', why: '扫电脑上的二维码完成配对。只在扫码界面使用，不拍照、不录像。' },
  { name: '相册', why: '让你挑要发到电脑的照片视频，以及把电脑发来的图片存进相册。只处理你选中的那些。' },
  { name: '文件访问', why: '读取你选择要发送的文件，保存接收到的文件。' },
  { name: '通知', why: '传输完成或失败时告诉你一声。' },
]

const permissionsEn = [
  { name: 'Local network', why: 'To find your computer on the same Wi-Fi and connect to it. Used only within your local network — never to reach the internet.' },
  { name: 'Camera', why: 'To scan the pairing QR code shown on your computer. Used only on the scanning screen; nothing is photographed or recorded.' },
  { name: 'Photos', why: 'To let you pick photos and videos to send, and to save incoming images. Only the items you select are touched.' },
  { name: 'Files', why: 'To read the files you choose to send and to save the ones you receive.' },
  { name: 'Notifications', why: 'To tell you when a transfer finishes or fails.' },
]

function Zh() {
  return (
    <>
      <p>
        <strong>卓传不收集你的任何个人信息，也不会把你的文件上传到任何服务器。</strong>
        这不是一句口号，而是这个 App 的工作方式决定的：文件在你的手机和你的电脑之间
        直接传输，走的是你自己的局域网，中间没有第三方。
      </p>

      <h2>我们不收集什么</h2>
      <p>下面这些，卓传一律不收集、不上传、不存储：</p>
      <ul>
        <li>你传输的文件内容、文件名，以及任何文件相关的信息</li>
        <li>你的照片、视频、通讯录、位置</li>
        <li>账号、手机号、邮箱等身份信息（卓传根本不需要注册）</li>
        <li>设备标识符、广告 ID、使用行为等统计数据</li>
      </ul>
      <p>卓传没有账号体系，也没有接入任何广告或数据分析 SDK。</p>

      <h2>数据存在哪里</h2>
      <ul>
        <li><strong>你传的文件</strong>：只存在于你的手机和你指定的电脑目录里。</li>
        <li>
          <strong>配对信息</strong>：手机和电脑第一次配对后，双方各自在本机保存一个令牌，
          用来确认「是这台设备」。它只存在你的设备上，不会外传。你随时可以在电脑端撤销。
        </li>
        <li>
          <strong>传输记录</strong>：电脑端会在本机记录传过哪些文件，方便你回头找。
          这个记录不出你的电脑，可以随时清空。
        </li>
      </ul>

      <h2>为什么需要这些权限</h2>
      <div className="scroll-x">
        <table className="compat">
          <thead><tr><th>权限</th><th>用来做什么</th></tr></thead>
          <tbody>
            {permissionsZh.map((p) => (
              <tr key={p.name}><td>{p.name}</td><td>{p.why}</td></tr>
            ))}
          </tbody>
        </table>
      </div>
      <p>二维码识别在你的设备本地完成，图像不会离开设备。</p>

      <h2>网络请求</h2>
      <p>
        卓传只在两种情况下发起网络请求：一是和你自己的电脑通信（局域网内），
        二是检查有没有新版本（访问本站）。检查更新时只会取一个版本号清单，
        不携带任何设备或用户信息。
      </p>

      <h2>儿童隐私</h2>
      <p>卓传不面向儿童收集任何信息——事实上它不收集任何人的信息。</p>

      <h2>政策变更</h2>
      <p>
        如果这份政策有变化，我们会更新本页顶部的日期。由于卓传的设计就是不收集数据，
        我们不预期会有实质性的改变。
      </p>

      <h2>联系我们</h2>
      <p>有任何疑问，可以通过 <a href="/support.html">支持页面</a> 上的方式联系我们。</p>
    </>
  )
}

function En() {
  return (
    <>
      <p>
        <strong>DroidTrans collects no personal information and never uploads your files to any server.</strong>{' '}
        That is not a marketing line — it follows from how the app works. Files move directly
        between your phone and your computer over your own local network. There is no third party
        in the middle.
      </p>

      <h2>What we don’t collect</h2>
      <p>None of the following is collected, uploaded or stored:</p>
      <ul>
        <li>The contents or names of the files you transfer, or anything about them</li>
        <li>Your photos, videos, contacts or location</li>
        <li>Accounts, phone numbers, email addresses — DroidTrans has no sign-up at all</li>
        <li>Device identifiers, advertising IDs, or usage analytics</li>
      </ul>
      <p>There is no account system, and no advertising or analytics SDK is bundled in the app.</p>

      <h2>Where your data lives</h2>
      <ul>
        <li><strong>The files you transfer</strong> exist only on your phone and in the folder you chose on your computer.</li>
        <li>
          <strong>Pairing information.</strong> After the first pairing, each device keeps a token
          locally to recognise the other. It never leaves your devices, and you can revoke it from
          the desktop app at any time.
        </li>
        <li>
          <strong>Transfer history.</strong> The desktop app keeps a local record of what was
          transferred so you can find things later. It stays on your computer and can be cleared
          whenever you like.
        </li>
      </ul>

      <h2>Why each permission is needed</h2>
      <div className="scroll-x">
        <table className="compat">
          <thead><tr><th>Permission</th><th>What it’s for</th></tr></thead>
          <tbody>
            {permissionsEn.map((p) => (
              <tr key={p.name}><td>{p.name}</td><td>{p.why}</td></tr>
            ))}
          </tbody>
        </table>
      </div>
      <p>QR codes are decoded on your device. The camera image never leaves it.</p>

      <h2>Network requests</h2>
      <p>
        DroidTrans makes network requests in exactly two cases: talking to your own computer on the
        local network, and checking this site for a newer version. The update check fetches a small
        version manifest and carries no device or user information.
      </p>

      <h2>Children’s privacy</h2>
      <p>DroidTrans collects nothing from children — it collects nothing from anyone.</p>

      <h2>Changes to this policy</h2>
      <p>
        If this policy changes we will update the date at the top of this page. Since the app is
        built not to collect data in the first place, we don’t expect material changes.
      </p>

      <h2>Contact</h2>
      <p>If anything here is unclear, reach us through the <a href="/en/support.html">support page</a>.</p>
    </>
  )
}

export function Privacy() {
  const t = useT()
  const en = t.code === 'en'
  return (
    <ArticlePage
      title={en ? 'Privacy Policy' : '隐私政策'}
      subtitle={en ? 'Last updated 30 August 2026' : '最后更新：2026 年 8 月 30 日'}
    >
      {en ? <En /> : <Zh />}
    </ArticlePage>
  )
}
