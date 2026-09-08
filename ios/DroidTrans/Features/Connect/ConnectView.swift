/// 找电脑。App 没连上电脑时的整个世界。

import SwiftUI

struct ConnectView: View {
    /// 从「手机互传 → 我要发」进来时为真，雷达上只列手机。
    ///
    /// 用户刚说完「我要发给另一台手机」，紧接着看到自己的 Mac 混在
    /// 雷达里、点下去还弹「和这台电脑配对」—— 那是在推翻他刚做的选择。
    var phonesOnly: Bool = false

    @EnvironmentObject private var app: AppState
    @StateObject private var discovery = DesktopDiscovery()

    @State private var showManual = false
    @State private var showTrouble = false
    @State private var showScanner = false

    var body: some View {
        ZStack {
            AppBackground()

            VStack(spacing: 0) {
                header

                Spacer(minLength: Space.m)

                RadarView(
                    devices: visible,
                    scanning: discovery.isBrowsing,
                    onTap: { d in Task { await app.connect(to: d) } }
                )
                .padding(.horizontal, Space.s)

                Spacer(minLength: Space.m)

                footer
            }

            // 浮在最上层，不挤占标题那一行
            VStack {
                HStack {
                    homeButton
                    Spacer()
                }
                Spacer()
            }
            .padding(.leading, Space.gutter - 10)   // 图标在 44 的框里居中，视觉左边缘要补回来
            .padding(.top, Space.s)
        }
        .preferredColorScheme(.dark)
        .task { discovery.start() }
        .onDisappear { discovery.stop() }
        .sheet(isPresented: $showManual) {
            ManualAddressSheet { address in
                Task { await app.connect(toAddress: address) }
            }
        }
        .sheet(isPresented: $showScanner) {
            ScannerSheet { payload in
                showScanner = false
                Task { await handleScan(payload) }
            }
        }
        .sheet(isPresented: $showTrouble) {
            TroubleshootSheet()
        }
        .overlay {
            if app.busy {
                ZStack {
                    Color.black.opacity(0.35).ignoresSafeArea()
                    ProgressView().controlSize(.large).tint(.white)
                }
            }
        }
        .alert(L("connect.failed"), isPresented: .constant(app.error != nil)) {
            Button(L("common.ok")) { app.error = nil }
        } message: {
            Text(app.error ?? "")
        }
    }

    // MARK: - 头

    private var header: some View {
        VStack(spacing: Space.s) {
            Text(L("connect.title"))
                .font(.system(size: 28, weight: .bold))
                .foregroundStyle(Color.ink)

            Text(statusText)
                .font(.system(size: 14.5))
                .foregroundStyle(Color.ink2)
                .animation(.default, value: statusText)
        }
        .padding(.top, Space.l)
    }

    /// 左上角的 home 键。
    ///
    /// 原来是一颗「‹ 电脑名」的玻璃胶囊，占掉标题上方一整行，
    /// 名字长一点就把「卓传」顶下去。这里只要一个图标 ——
    /// 无边框、直接浮在背景上，不参与竖排布局，标题该在哪就在哪。
    ///
    /// 没连过任何电脑时它不消失，只是暗下来且点不动：
    /// 一个会来回出现的按钮，比一个常驻的灰按钮更让人分神。
    /// 返回主界面。
    ///
    /// 只做一件事：把界面切回去。连接一直停在 AppState 里没断过，
    /// 所以按下去是即时的 —— 不发包、不转圈、不会弹「连不上」。
    ///
    /// 左上角的 home 键：回主界面。
    ///
    /// 到这一步它才终于是个诚实的返回键 —— 主界面（StartView）现在任何时候都在，
    /// 所以这里只改一个路由，不发包、不转圈、不会失败、不会变灰。
    ///
    /// 之前它试过连上次那台电脑、试过认雷达上的设备，都是在拿「返回」当「重连」用。
    /// 用户按 home 想看到的是主界面，不是一次网络请求。
    private var homeButton: some View {
        HomeButton { app.route = .start }
    }

    /// 雷达上该显示哪些。默认全都显示；只找手机时把电脑滤掉。
    private var visible: [Desktop] {
        phonesOnly ? discovery.found.filter(\.isPhone) : discovery.found
    }

    private var statusText: String {
        if !visible.isEmpty {
            return visible.count == 1 ? L("connect.status.one") : L("connect.status.many")
        }
        return discovery.isBrowsing ? L("connect.status.scanning") : L("connect.status.preparing")
    }

    // MARK: - 底

    private var footer: some View {
        VStack(spacing: Space.m) {
            if visible.isEmpty && discovery.isBrowsing {
                // 搜不到是有具体原因的，直接把原因和出路说清楚，
                // 比让用户对着空雷达猜要好
                Text(L("connect.hint"))
                    .font(.system(size: 12.5))
                    .foregroundStyle(Color.ink3)
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)
                    .padding(.horizontal, Space.xl)

                // 电脑上还没装的话，得告诉人家去哪装 —— 光说「电脑上要开着卓传」
                // 对没装过的人是死路。这也是审核员会走到的那一步。
                //
                // 链的是下载页而不是定价页：指向可购买页面的行为召唤
                // 违反审核指南 3.1.1。
                Link(L("connect.getDesktop"),
                     destination: URL(string: "https://droidtrans.mkstore.life/download.html")!)
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundStyle(Color.brand)

                // 空雷达是最容易劝退的一屏。原来这里只有一句「有些路由器会拦掉
                // 自动发现」—— 而搜不到的真实原因往往是访客网络的设备隔离，
                // 或者压根没有路由器（在车上、在外面）。后一种只有热点能解，
                // 一句提示塞不下，给它一页。
                Button {
                    showTrouble = true
                } label: {
                    Text(L("trouble.link"))
                        .font(.system(size: 12.5, weight: .medium))
                        .foregroundStyle(Color.brand)
                }
                .accessibilityIdentifier("trouble-link")
            }

            HStack(spacing: Space.m) {
                Button {
                    showScanner = true
                } label: {
                    Label(L("connect.scan"), systemImage: "qrcode.viewfinder")
                }
                .buttonStyle(GhostButtonStyle())

                Button {
                    showManual = true
                } label: {
                    Label(L("connect.manual"), systemImage: "keyboard")
                }
                .buttonStyle(GhostButtonStyle())
            }
            .padding(.horizontal, Space.gutter)
        }
        .padding(.bottom, Space.xl)
    }

    // MARK: - 扫码结果

    /// 桌面端二维码里是 http://ip:9500/?c=187931 —— 地址后面挂着配对码，
    /// 扫一下就能连上并配好，用户不用再手输那六位。
    ///
    /// 手机接收端的码里还可能带着它开的直连热点（s/k）：那就先把网连上。
    /// 不然「扫一下就能传」在没有路由器的场合只是句空话 —— 用户还得退出 App、
    /// 去设置里翻热点、手输一串随机密码。
    private func handleScan(_ payload: String) async {
        guard let link = PeerLink.parse(payload) else {
            app.error = L("error.badAddress", payload)
            return
        }
        var joinFailure: String?
        if link.hasHotspot {
            do {
                try await HotspotJoin.join(ssid: link.ssid, password: link.password)
            } catch {
                // 连不上也照样往下走：用户可能本来就在同一个网里，
                // 这时候码里的地址依然是通的，不该在这儿把人拦下。
                // 这句话留到真的连不上时再说 —— 连上了还弹一句「没能自动入网」
                // 只会让人以为出了什么事。
                joinFailure = error.localizedDescription
            }
        }
        await app.connect(toAddress: link.baseURL,
                          pairingCode: link.code.isEmpty ? nil : link.code)
        if app.error != nil, let joinFailure {
            app.error = joinFailure
        }
    }
}

// MARK: - 手输地址

private struct ManualAddressSheet: View {
    var onSubmit: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        ZStack {
            AppBackground()

            VStack(alignment: .leading, spacing: Space.l) {
                HStack {
                    Button(L("common.cancel")) { dismiss() }
                        .foregroundStyle(Color.ink2)
                    Spacer()
                    Text(L("manual.title"))
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Color.ink)
                    Spacer()
                    Button(L("common.cancel")) { }.opacity(0).disabled(true)
                }

                Text(L("manual.hint"))
                    .font(.system(size: 13.5))
                    .foregroundStyle(Color.ink2)

                TextField("", text: $text, prompt: Text(L("manual.placeholder")).foregroundColor(Color.ink3))
                    .font(.system(size: 20, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.ink)
                    .keyboardType(.numbersAndPunctuation)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .focused($focused)
                    .padding(Space.l)
                    .glass(radius: Radius.tile)

                Button(L("common.connect")) {
                    onSubmit(text)
                    dismiss()
                }
                .buttonStyle(PrimaryButtonStyle(enabled: !trimmed.isEmpty))
                .disabled(trimmed.isEmpty)

                Spacer()
            }
            .padding(Space.gutter)
        }
        .preferredColorScheme(.dark)
        .onAppear { focused = true }
    }

    private var trimmed: String { text.trimmingCharacters(in: .whitespaces) }
}

// MARK: - 连不上怎么办

/// 空雷达的出路。
///
/// 四条按可能性排：同一网络 → 访客网络的设备隔离 → 没有路由器 → 手输地址。
/// 第三条是关键的一条：在外面、车上、没有 Wi-Fi 的地方，热点是唯一能走的路，
/// 而且直连速度通常比公共 Wi-Fi 还快。用户不会自己想到这一层。
private struct TroubleshootSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            AppBackground()

            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Spacer()
                    Text(L("trouble.title"))
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Color.ink)
                    Spacer()
                }
                .overlay(alignment: .trailing) {
                    Button(L("common.done")) { dismiss() }
                        .foregroundStyle(Color.ink2)
                }
                .padding(.bottom, Space.l)

                ScrollView {
                    VStack(alignment: .leading, spacing: Space.l) {
                        item("trouble.same.title", "trouble.same.body")
                        item("trouble.guest.title", "trouble.guest.body")
                        item("trouble.hotspot.title", "trouble.hotspot.body")
                        item("trouble.manual.title", "trouble.manual.body")
                    }
                    .padding(.bottom, Space.xl)
                }
            }
            .padding(Space.gutter)
        }
        .preferredColorScheme(.dark)
    }

    private func item(_ title: String, _ body: String) -> some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            Text(L(title))
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Color.ink)
            Text(L(body))
                .font(.system(size: 13.5))
                .foregroundStyle(Color.ink2)
                .lineSpacing(4)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
