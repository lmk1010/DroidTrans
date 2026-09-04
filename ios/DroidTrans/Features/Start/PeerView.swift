/// 手机互传。
///
/// 现有协议是 HTTP：一侧当服务端，另一侧当客户端。两台手机之间没有天然的
/// 服务端，所以这一屏第一件事就是问清楚「这次你是发还是收」——
/// 收的那台跑起 PeerServer 并广播 Bonjour，发的那台走原来那套雷达流程。
///
/// 好处是发送方一行都不用改：接收方通告的是同一个 _droidtrans._tcp、
/// 说的是同一套 HTTP 口，雷达把它当成一台「电脑」照常连。

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
        .onDisappear { peer.stop() }
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
        peer.stop()
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
                    peer.start()
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

    // MARK: - 正在接收

    private var receiveScreen: some View {
        VStack(spacing: Space.l) {
            Spacer(minLength: Space.xxl)

            ArtIcon(art: .inbox, size: 68)

            Text(peer.running ? L("peer.waiting") : "…")
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(Color.ink)

            Text(Store.shared.deviceName)
                .font(.system(size: 14))
                .foregroundStyle(Color.ink2)

            Text(L("peer.tapToAccept"))
                .font(.system(size: 14))
                .foregroundStyle(Color.ink2)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
                .padding(.horizontal, Space.xl)
                .padding(.top, Space.s)

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
                        Text("\(ip):\(Ports.peer)   \(spaced(peer.pairingCode))")
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
