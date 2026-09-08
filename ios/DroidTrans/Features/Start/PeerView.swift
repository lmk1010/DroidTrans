/// 手机互传。
///
/// 现有协议是 HTTP：一侧当服务端，另一侧当客户端。两台手机之间没有天然的
/// 服务端，所以这一屏第一件事就是问清楚「这次你是发还是收」——
/// 收的那台跑起 PeerServer 并广播 Bonjour，发的那台走原来那套雷达流程。
///
/// 好处是发送方一行都不用改：接收方通告的是同一个 _droidtrans._tcp、
/// 说的是同一套 HTTP 口，雷达把它当成一台「电脑」照常连。

import CoreImage
import SwiftUI

struct PeerView: View {
    let kind: PeerKind

    @EnvironmentObject private var app: AppState
    @StateObject private var peer = PeerServer.shared
    @State private var receiving = false

    var body: some View {
        ZStack {
            AppBackground()

            if receiving {
                if peer.connectedName != nil && peer.received.isEmpty {
                    connectedScreen
                } else {
                    receiveScreen
                }
            } else {
                rolePicker
            }

            VStack {
                HStack {
                    HomeButton { leave() }
                    Spacer()
                }
                Spacer()
            }
            .padding(.leading, Space.gutter - 10)
            .padding(.top, Space.s)
        }
        .preferredColorScheme(.dark)
        // 离开这一屏**不能**停监听：App 现在是常驻可被发现的，
        // 停掉的话用户一退出这屏，别人就再也找不到他了。
        // 真要停由「停止接收」那个按钮负责。
        // 对面在雷达上点了这台，这里弹一下。把关就在这一下 ——
        // 设备名是对面自己报的，只当提示看，真正决定开不开门的是这个人。
        .alert(knockTitle, isPresented: knocking) {
            Button(L("peer.knock.deny"), role: .cancel) { peer.deny() }
            Button(L("peer.knock.allow")) { peer.approve() }
        }
    }

    private var knocking: Binding<Bool> {
        Binding(get: { peer.knock != nil },
                set: { if !$0 { peer.deny() } })
    }

    private var knockTitle: String {
        "\(peer.knock?.name ?? L("peer.someone")) \(L("peer.knock.title"))"
    }

    private func leave() {
        app.route = .start
    }

    // MARK: - 选角色

    private var rolePicker: some View {
        VStack(spacing: Space.xl) {
            Spacer()

            VStack(spacing: Space.m) {
                ArtIcon(art: kind == .iphone ? .phone : .android, size: 76)
                Text(kind == .iphone ? L("start.iphone") : L("start.android"))
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(Color.ink)
                Text(L("peer.role"))
                    .font(.system(size: 14.5))
                    .foregroundStyle(Color.ink2)
            }

            VStack(spacing: Space.m) {
                roleCard(icon: "paperplane.fill",
                         title: L("peer.send"), sub: L("peer.send.sub"),
                         id: "peer-send") { app.route = .findDesktop(phonesOnly: true) }

                roleCard(icon: "tray.and.arrow.down.fill",
                         title: L("peer.recv"), sub: L("peer.recv.sub"),
                         id: "peer-recv") {
                    peer.startIfIdle()
                    receiving = true
                }
            }
            .padding(.horizontal, Space.gutter)

            Spacer()
            Spacer()
        }
    }

    private func roleCard(icon: String, title: String, sub: String,
                          id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: Space.l) {
                Image(systemName: icon)
                    .font(.system(size: 20, weight: .medium))
                    .foregroundStyle(Color.brand)
                    .frame(width: 46, height: 46)

                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Color.ink)
                    Text(sub)
                        .font(.system(size: 13))
                        .foregroundStyle(Color.ink2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: Space.s)
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Color.ink3)
            }
            .padding(Space.l)
            .frame(maxWidth: .infinity, alignment: .leading)
            .glass(radius: Radius.card)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id)
    }

    // MARK: - 已连接，等待发送

    private var connectedScreen: some View {
        VStack(spacing: Space.l) {
            Spacer(minLength: Space.xxl)

            ArtIcon(art: .link, size: 68)

            Text(L("peer.connected"))
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(Color.ink)

            Text(peer.connectedName ?? L("peer.someone"))
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Color.brand)

            Text(L("peer.connectedHint"))
                .font(.system(size: 14))
                .foregroundStyle(Color.ink2)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
                .padding(.horizontal, Space.xl)

            ProgressView()
                .tint(Color.brand)
                .padding(.top, Space.s)

            Spacer()

            Button(L("peer.stop")) {
                peer.stop()
                receiving = false
            }
            .font(.system(size: 15))
            .foregroundStyle(Color.ink2)
            .padding(.bottom, Space.xl)
        }
    }

    /// 接收界面的大标题。四态：还没起来 / 正在收 / 已连上 / 在等人连。
    private var headline: String {
        if !peer.running { return "…" }
        if peer.incoming != nil || !peer.received.isEmpty { return L("peer.receiving") }
        if peer.connectedName != nil { return L("peer.connected") }
        return L("peer.waiting")
    }

    /// 正在收的那个文件，边收边显示 —— 不然大文件传着传着界面像死了一样。
    @ViewBuilder private var incomingRow: some View {
        if let now = peer.incoming {
            VStack(spacing: 4) {
                Text(now.name)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(Color.ink)
                    .lineLimit(1)
                    .truncationMode(.middle)
                ProgressView(value: Double(now.got),
                             total: Double(max(now.total, 1)))
                    .tint(Color.brand)
                    .frame(maxWidth: 240)
                Text("\(bytes(now.got)) / \(bytes(now.total))")
                    .font(.system(size: 12))
                    .foregroundStyle(Color.ink3)
            }
            .padding(.top, Space.s)
        }
    }

    private func bytes(_ n: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: n, countStyle: .file)
    }

    // MARK: - 二维码

    /// 用 CoreImage 画码。没有引第三方库：这一个功能不值得多一个依赖。
    ///
    /// CIQRCodeGenerator 出的图只有几十像素，直接放大会糊成一片，
    /// 所以先按整数倍放大再交给 SwiftUI（配合 .interpolation(.none)，边缘才是硬的）。
    private func qrImage(for payload: String) -> UIImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(payload.utf8), forKey: "inputMessage")
        // M 级容错：码里有 SSID 和密码时内容不短，纠错级别再高会把格子压得太密
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let out = filter.outputImage else { return nil }
        let scaled = out.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        let ctx = CIContext()
        guard let cg = ctx.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }

    // MARK: - 正在接收

    private var receiveScreen: some View {
        VStack(spacing: Space.l) {
            Spacer(minLength: Space.xxl)

            ArtIcon(art: .inbox, size: 68)

            // 标题要跟着状态走。原来不管收没收都写「等待连接」——
            // 对面早就连上了、文件也在往这儿进，屏幕上还说在等，
            // 用户只会以为没连上。
            Text(headline)
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(Color.ink)

            Text(Store.shared.deviceName)
                .font(.system(size: 14))
                .foregroundStyle(Color.ink2)

            incomingRow

            Text(L("peer.tapToAccept"))
                .font(.system(size: 14))
                .foregroundStyle(Color.ink2)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
                .padding(.horizontal, Space.xl)
                .padding(.top, Space.s)

            // 二维码：对面扫一下，「找到这台」和「连上它的网」两步一起走完。
            // 没有码的话，对面要么指望组播扫得到（路由器一拦就没了），
            // 要么手输 IP 加端口。
            if let ip = peer.localIP, peer.running,
               let img = qrImage(for: PeerLink.encode(
                    host: ip, port: peer.boundPort,
                    code: peer.pairingCode, name: Store.shared.deviceName)) {
                // 底色不能跟着深色主题走：二维码要浅底深码才扫得动，
                // 反色的码大多数扫描器都不认。但一块生硬的纯白很扎眼，
                // 所以给它圆角和一点点灰，让它像张卡片。
                Image(uiImage: img)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: 168, height: 168)
                    .padding(Space.m)
                    .background(Color(white: 0.95))
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .padding(.top, Space.m)
                    .accessibilityIdentifier("peer-qr")

                Text(L("peer.qrHint"))
                    .font(.system(size: 13))
                    .foregroundStyle(Color.ink2)
            }

            Spacer(minLength: Space.l)

            // 地址和配对码是兜底，不是主路。
            // 组播被路由器拦掉、或者对面压根搜不到这台的时候才用得上，
            // 所以它们摆在下面、小字、灰的 —— 正常情况下用户根本不用看。
            if peer.localIP != nil || !peer.pairingCode.isEmpty {
                VStack(spacing: Space.xs) {
                    Text(L("peer.codeFallback"))
                        .font(.system(size: 11.5))
                        .foregroundStyle(Color.ink3)
                    if let ip = peer.localIP {
                        // 端口按实际绑上的那个来：9600 被占时服务端会退到系统分配的端口，
                        // 这里还写死 9600 的话，手输地址那条兜底路指向的是一个没人听的端口
                        Text("\(ip):\(peer.boundPort)   \(spaced(peer.pairingCode))")
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(Color.ink2)
                            .accessibilityIdentifier("peer-code")
                    } else {
                        Text(L("peer.needWifi"))
                            .font(.system(size: 12.5))
                            .foregroundStyle(Color.ink3)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, Space.xl)
                    }
                }
            }

            if let e = peer.lastError {
                Text(e)
                    .font(.system(size: 12.5))
                    .foregroundStyle(Color.danger)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, Space.xl)
            }

            if !peer.received.isEmpty {
                VStack(alignment: .leading, spacing: Space.s) {
                    Text("\(L("peer.got")) \(peer.received.count)")
                        .accessibilityIdentifier("peer-got-count")
                        .font(.system(size: 12))
                        .foregroundStyle(Color.ink3)
                    ForEach(peer.received.suffix(4)) { f in
                        HStack(spacing: Space.s) {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(Color.ok)
                                .font(.system(size: 13))
                            Text(f.name)
                                .font(.system(size: 13))
                                .foregroundStyle(Color.ink)
                                .lineLimit(1)
                            Spacer()
                        }
                    }
                }
                .padding(.horizontal, Space.gutter)
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            Spacer()

            Button(L("peer.stop")) {
                peer.stop()
                receiving = false
            }
            .font(.system(size: 15))
            .foregroundStyle(Color.ink2)
            .padding(.bottom, Space.xl)
        }
    }

    /// 六位码分成两组三位，念给对面听的时候不容易串行
    private func spaced(_ s: String) -> String {
        guard s.count == 6 else { return s }
        let i = s.index(s.startIndex, offsetBy: 3)
        return "\(s[s.startIndex..<i]) \(s[i...])"
    }
}
