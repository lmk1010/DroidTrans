/// 全局连接状态。
///
/// 「连上了没有」这件事，界面上有好几处要看（首页、发送、接收），
/// 各自去查会得出不一致的结论，所以只有这一个来源。

import Foundation
import SwiftUI

@MainActor
final class AppState: ObservableObject {
    enum Phase: Equatable {
        case disconnected
        /// 找到电脑了，但它要求配对，还没拿到令牌
        case needsPairing(Desktop)
        /// 对面是台手机：不用输码，等它点头就行
        case awaitingApproval(Desktop)
        case connected(Desktop)
    }

    /// 没连上电脑时，用户停在哪一屏。
    ///
    /// 以前「没连上」只有一种样子：雷达页，一进来就开扫。
    /// 现在多了一个家（StartView），雷达只是从家出发的三条路之一。
    enum Route: Equatable {
        case start
        /// phonesOnly：从「手机互传 → 我要发」进来的，雷达只列手机。
        ///
        /// 之前这里不带参数，于是选了「发给另一台手机」之后，雷达把
        /// 局域网里的电脑也一并列出来 —— 点下去弹的是「和这台电脑配对」，
        /// 和用户刚刚做的选择直接冲突。
        case findDesktop(phonesOnly: Bool = false)
        case peer(PeerKind)
    }

    @Published var route: Route = .start
    @Published private(set) var phase: Phase = .disconnected
    @Published var busy = false
    @Published var error: String?

    /// 刚才连着的那台。用户点「切换」回到雷达页时留个后路，
    /// 不然他就得重新在雷达上找一遍、甚至重新配对。
    @Published private(set) var lastConnected: Desktop?

    private let store = Store.shared
    private(set) var api: ApiClient?

    /// 点「切换电脑」时把当前这条连接停在这儿，不拆。
    ///
    /// 拆了的话，用户想退回主界面就得重新走一遍网络握手 ——
    /// 他按的是「返回」，不该为此等一次连接、更不该看到「连不上」。
    private var parkedApi: ApiClient?

    var desktop: Desktop? {
        switch phase {
        case .connected(let d), .needsPairing(let d), .awaitingApproval(let d): return d
        case .disconnected: return nil
        }
    }

    var isConnected: Bool {
        if case .connected = phase { return true }
        return false
    }

    // MARK: - 连接

    /// 连一台电脑。
    ///
    /// 已经有令牌的话直接验一下还好不好用 —— 电脑那边可能已经把这台手机撤销了，
    /// 那种情况下要退回配对，而不是让用户在后面每个操作上撞 403。
    func connect(to desktop: Desktop) async {
        busy = true
        defer { busy = false }

        let token = store.token(for: desktop)
        let client = ApiClient(baseURL: desktop.baseURL, token: token)

        do {
            let info = try await client.info(deviceId: store.deviceId, deviceName: store.deviceName)

            if !info.pairingRequired {
                api = client
                phase = .connected(info)
                rememberHome(info)
                return
            }

            if token.isEmpty {
                api = client
                // 对面是台手机的话，别让用户去抄六位码 ——
                // 敲一下门，等它点「同意」就行。
                // 对面是手机就一律「点一下同意」，第一次也不要码。
                // 只看 pairing_mode 的话，对面版本旧一点、或者那次回包没带上
                // 这个字段，用户就会被推到一屏根本用不上的六位码前面。
                phase = (info.approvesByTap || info.isPhone)
                    ? .awaitingApproval(info) : .needsPairing(info)
                return
            }

            // 拿一个需要鉴权的接口探一下令牌还认不认
            do {
                _ = try await client.outbox()
                api = client
                phase = .connected(info)
                rememberHome(info)
            } catch let e as ApiError where e.needsPairing {
                // 电脑上撤销了这台手机，留着废令牌只会让后面每一步都失败
                store.clearToken(for: info)
                api = ApiClient(baseURL: info.baseURL)
                phase = .needsPairing(info)
            }
        } catch {
            self.error = message(of: error)
        }
    }

    /// 手输地址或扫码之后走这条。
    func connect(toAddress raw: String, pairingCode: String? = nil) async {
        busy = true
        defer { busy = false }
        do {
            let desktop = try await DesktopDiscovery.verify(raw)
            busy = false
            await connect(to: desktop)
            // 二维码里带了配对码就顺手配上，省掉用户再手输六位
            if let code = pairingCode, !code.isEmpty, case .needsPairing = phase {
                await pair(code: code)
            }
        } catch {
            self.error = message(of: error)
        }
    }

    // MARK: - 配对

    /// 敲门：不带码地请求一次，挂在那儿等对面点头。
    ///
    /// 用的还是 /api/pair，只是 code 留空 —— 对面认得这个约定，
    /// 会把这条请求挂起来弹个框问它的主人。所以协议一个字节都没改。
    func knock() async {
        guard case .awaitingApproval(let desktop) = phase else { return }
        busy = true
        defer { busy = false }

        let client = ApiClient(baseURL: desktop.baseURL)
        do {
            let token = try await client.pair(
                code: "",
                deviceId: store.deviceId,
                deviceName: store.deviceName
            )
            store.setToken(token, for: desktop)
            await client.setToken(token)
            api = client
            phase = .connected(desktop)
            rememberHome(desktop)
        } catch {
            self.error = message(of: error)
            phase = .disconnected
        }
    }

    func pair(code: String) async {
        guard case .needsPairing(let desktop) = phase else { return }
        busy = true
        defer { busy = false }

        let client = ApiClient(baseURL: desktop.baseURL)
        do {
            let token = try await client.pair(
                code: code.trimmingCharacters(in: .whitespaces),
                deviceId: store.deviceId,
                deviceName: store.deviceName
            )
            store.setToken(token, for: desktop)
            await client.setToken(token)
            api = client
            phase = .connected(desktop)
            rememberHome(desktop)
        } catch {
            self.error = message(of: error)
        }
    }

    /// 启动时悄悄连回上次那台电脑。
    ///
    /// 配对是一次性的事，每次打开 App 都要用户再点一次雷达上的图标，
    /// 那这个「配对」就白配了。
    ///
    /// 全程不弹错也不转菊花：连不上（电脑关了、换了网络）就静静留在雷达页，
    /// 用户会看到扫描在跑，该点哪台点哪台 —— 一个「上次的电脑连不上」的
    /// 弹窗在这里只会碍事。
    /// 测试专用：跳过发现，直接连一台指定的电脑。
    ///
    ///     -uitest-desktop 192.168.1.5
    ///
    /// 模拟器上的 Bonjour 不可靠（本地网络权限、mDNS 都可能拦住），
    /// 结果是绝大多数界面在 CI 上根本走不到，等于没有验收。
    /// 这个开关只在带 -uitest-fresh 时生效，正式包里没有任何入口。
    private func connectForTesting() async {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-uitest-desktop"),
              i + 1 < args.count else { return }
        let host = args[i + 1]
        guard let desktop = try? await DesktopDiscovery.verify(host) else { return }
        await connect(to: desktop)
    }

    func restoreLastSession() async {
        // UI 测试要从干净状态起步，自动重连会让雷达页整个跳过去
        if ProcessInfo.processInfo.arguments.contains("-uitest-fresh") {
            store.resetForTesting()
            // 传输记录也要清。它原来不清 —— 于是每跑一次用例就往里多攒几条，
            // 出上架截图时「传输记录」那一屏是同一个文件重复四遍，
            // 一看就是假数据；用例之间也不再是干净状态。
            History.shared.clear()
            // 取回来的文件也清掉。同理：不清的话「图库」那一屏会把
            // 历次用例留下的东西全堆在一起，出上架截图时混着一堆
            // 陈年测试文件，也让「空态长什么样」这条永远测不到。
            let docs = FileManager.default
                .urls(for: .documentDirectory, in: .userDomainMask)[0]
            for f in (try? FileManager.default.contentsOfDirectory(
                        at: docs, includingPropertiesForKeys: nil)) ?? [] {
                try? FileManager.default.removeItem(at: f)
            }
            await connectForTesting()
            return
        }
        guard case .disconnected = phase,
              let host = store.lastHost,
              let desktop = try? await DesktopDiscovery.verify(host) else { return }

        let tok = store.token(for: desktop)

        // 没配对过就别自动跳进配对页，那等于替用户做决定
        guard !tok.isEmpty || !desktop.pairingRequired else { return }

        await connect(to: desktop)
        // 自动重连失败是可以接受的，不该弹窗打扰
        if !isConnected { error = nil }
    }

    /// 回雷达页挑另一台，但不忘记现在这台。
    ///
    /// 和「断开连接」是两回事：点切换的人只是想换一台，
    /// 把令牌和 lastHost 一起清掉的话，他连回来还得重新配对一次。
    func switchDevice() {
        if case .connected(let d) = phase {
            lastConnected = d
            parkedApi = api
        }
        api = nil
        phase = .disconnected
        route = .findDesktop()   // 点「切换电脑」就是要挑一台，直接进雷达
    }

    /// 雷达页的 home 键能不能按。
    ///
    /// 只有「身后确实还停着一屏主界面」时才能按 —— 也就是从主界面点
    /// 「切换电脑」过来的那次。冷启动进来是没有主界面可返回的，
    /// 这时候按钮该是灰的，而不是假装能回去、按下去偷偷发起一次连接。
    var canGoHome: Bool { lastConnected != nil }

    /// 返回主界面。
    ///
    /// 纯粹是把界面切回去：连接一直停在 parkedApi 里没断过，
    /// 所以这里不发一个包、不转一次菊花、也不可能失败。
    func goHome() {
        guard let d = lastConnected else { return }
        api = parkedApi
        parkedApi = nil
        lastConnected = nil
        phase = .connected(d)
    }

    private func rememberHome(_ d: Desktop) {
        store.lastHost = d.baseURL
        store.lastName = d.name
    }

    func disconnect() {
        api = nil
        parkedApi = nil
        phase = .disconnected
        route = .start         // 主动断开就回家，而不是被扔进一个正在转的雷达
        lastConnected = nil
        // 断开是明确的意图。留着 lastHost 的话下次启动又自动连回来，
        // 用户会觉得这个「断开」根本没生效
        store.lastHost = nil
        store.lastName = nil
    }

    private func message(of error: Error) -> String {
        (error as? ApiError)?.message
            ?? (error as? FastSendError)?.message
            ?? error.localizedDescription
    }
}
