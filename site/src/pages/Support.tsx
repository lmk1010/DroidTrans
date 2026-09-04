import { ArticlePage } from '../components/Layout'
import { useT } from '../i18n'

const EMAIL = 'support@mkstore.life'

function Zh() {
  return (
    <>
      <h2>手机找不到电脑</h2>
      <p>按这个顺序排查：</p>
      <ul>
        <li><strong>电脑上的卓传打开了吗</strong>——桌面端要在运行，手机才找得到。</li>
        <li><strong>两台设备在同一个 Wi-Fi 下吗</strong>——手机连着蜂窝数据是连不上的。</li>
        <li>
          <strong>路由器可能拦了组播</strong>。不少路由器默认开着「AP 隔离」或拦截组播，
          自动发现就失效了。这时候直接在手机上输电脑界面显示的地址
          （形如 <code>192.168.1.5:9500</code>），一样能连。
        </li>
        <li>
          <strong>iPhone 用户</strong>：第一次打开时系统会问「允许卓传查找本地网络设备」，
          点了「不允许」就再也搜不到电脑。到「设置 → 卓传 → 本地网络」重新打开即可。
        </li>
        <li><strong>实在不行就开热点</strong>：手机开热点，电脑连上去，不需要路由器也能传。</li>
      </ul>

      <h2>macOS 打不开</h2>
      <p>
        卓传的 macOS 版本已经用 Apple 开发者证书签名，并通过了 Apple 公证，
        拖进「应用程序」双击就能打开，不需要在终端里执行任何命令。
      </p>
      <p>
        如果系统仍然拦下它，多半是下载没有完成或者文件损坏了 ——
        重新从<a href="/download.html">下载页</a>下一次即可。
      </p>

      <h2>配对码在哪里看</h2>
      <p>
        在电脑端界面上。手机扫那个二维码就自动完成了，不需要手输；
        实在要手输，二维码旁边有六位数字。配对只需要做一次，之后打开就能用。
      </p>
      <p>
        想撤销某台手机的授权，在电脑端的配对设置里删掉对应设备即可，
        那台手机需要重新配对才能再连上。
      </p>

      <h2>传输中断了怎么办</h2>
      <p>
        重新发一次就行。卓传两个方向都支持断点续传，已经传过去的部分不会重复搬运，
        接着上次的位置继续。
      </p>

      <h2>iPhone 上为什么没有 USB 直连</h2>
      <p>
        iOS 不开放这个能力，第三方 App 无法通过数据线直接读取相册。
        iPhone 上请使用 Wi-Fi 传输，功能是一样的，只是速度取决于你的无线网络。
        完整的平台差异见<a href="/download.html">下载页的对照表</a>。
      </p>

      <h2>传输速度慢</h2>
      <ul>
        <li>速度取决于你的 Wi-Fi。2.4GHz 频段明显慢于 5GHz，尽量连 5GHz。</li>
        <li>手机离路由器太远、中间隔墙，都会明显掉速。</li>
        <li>Android 用户插 USB 线传是最快的，不受无线网络影响。</li>
      </ul>

      <h2>还是没解决</h2>
      <p>
        把遇到的问题告诉我们，请尽量附上：设备型号、系统版本、卓传版本号，
        以及具体卡在哪一步。
      </p>
      <p>邮箱：<a href={`mailto:${EMAIL}`}>{EMAIL}</a></p>
    </>
  )
}

function En() {
  return (
    <>
      <h2>My phone can’t find the computer</h2>
      <p>Work through these in order:</p>
      <ul>
        <li><strong>Is DroidTrans running on the computer?</strong> The desktop app has to be open for the phone to find it.</li>
        <li><strong>Are both devices on the same Wi-Fi?</strong> A phone on cellular data can’t reach it.</li>
        <li>
          <strong>Your router may be blocking multicast.</strong> Many routers ship with “AP isolation”
          on, or filter multicast, which breaks automatic discovery. Type the address shown in the
          desktop app directly on your phone instead (something like <code>192.168.1.5:9500</code>).
        </li>
        <li>
          <strong>On iPhone:</strong> the first launch asks whether DroidTrans may find devices on
          your local network. If you tapped “Don’t Allow”, discovery will never work. Re-enable it
          under Settings → DroidTrans → Local Network.
        </li>
        <li><strong>Fall back to a hotspot.</strong> Turn on your phone’s hotspot and connect the computer — no router required.</li>
      </ul>

      <h2>macOS won’t open the app</h2>
      <p>
        The macOS build is signed with an Apple Developer ID and notarized by Apple. Drag it to
        Applications, double-click, and it opens — there is no Terminal command to run.
      </p>
      <p>
        If macOS still blocks it, the download most likely didn’t finish or the file is corrupt.
        Grab it again from the <a href="/en/download.html">download page</a>.
      </p>

      <h2>Where do I find the pairing code?</h2>
      <p>
        In the desktop app. Scanning the QR code does it for you — no typing needed. If you’d rather
        type it, the six digits are printed next to the code. Pairing happens once per computer.
      </p>
      <p>
        To revoke a phone’s access, remove it from the pairing settings in the desktop app. That
        phone will have to pair again before it can connect.
      </p>

      <h2>A transfer was interrupted</h2>
      <p>
        Just send it again. Transfers resume in both directions — whatever already made it across
        won’t be sent twice.
      </p>

      <h2>Why is there no USB transfer on iPhone?</h2>
      <p>
        iOS doesn’t allow it — third-party apps can’t read the camera roll over a cable. Use Wi-Fi
        transfer on iPhone instead; it does the same things, at whatever speed your wireless network
        allows. The full comparison is on the <a href="/en/download.html">download page</a>.
      </p>

      <h2>Transfers are slow</h2>
      <ul>
        <li>Speed depends on your Wi-Fi. The 2.4GHz band is noticeably slower than 5GHz — use 5GHz where you can.</li>
        <li>Distance from the router and walls in between both cost you a lot of throughput.</li>
        <li>On Android, a USB cable is the fastest route and ignores your wireless network entirely.</li>
      </ul>

      <h2>Still stuck</h2>
      <p>
        Tell us what happened. Please include your device model, OS version, the DroidTrans version,
        and which step it fails at.
      </p>
      <p>Email: <a href={`mailto:${EMAIL}`}>{EMAIL}</a></p>
    </>
  )
}

export function Support() {
  const t = useT()
  const en = t.code === 'en'
  return (
    <ArticlePage
      active="support"
      title={en ? 'Support' : '支持'}
      subtitle={en ? 'Most problems are covered below.' : '遇到问题？先看看下面这些，多半能解决。'}
    >
      {en ? <En /> : <Zh />}
    </ArticlePage>
  )
}
