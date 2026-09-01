/// 找电脑。
///
/// 三条路，能同时用：
///   1. Bonjour —— 桌面端通告 _droidtrans._tcp，最省事，用户什么都不用做
///   2. 扫码     —— 桌面端二维码里就是 http://ip:9500/?c=<配对码>
///   3. 手输 IP  —— 前两条都不通时的兜底（组播被路由器拦掉的情况不少见）
///
/// 第 2、3 条必须一直留着：iOS 上用户如果点了「不允许本地网络」，
/// Bonjour 不会报错，只是永远扫不到东西 —— 只留第 1 条就等于没得救。
///
/// 分工：NWBrowser 负责「有哪些服务」，ServiceResolver 负责「它们的 IP 是什么」。
/// 后者用 NetService 而不是 Network.framework 自己那套，原因见 ServiceResolver。

import Foundation
import Network

@MainActor
final class DesktopDiscovery: ObservableObject {
    @Published private(set) var found: [Desktop] = []
    /// 扫了一会儿还是空的，界面据此提示用户改用扫码
    @Published private(set) var isBrowsing = false

    private var browser: NWBrowser?
    private let resolver = ServiceResolver()
    private var byKey: [String: Desktop] = [:]
    /// 已经发起过解析的服务，避免 browser 每次回调都重来一遍
    private var seen: Set<String> = []

    private let queue = DispatchQueue(label: "life.mkstore.droidtrans.discovery")

    func start() {
        guard browser == nil else { return }

        let params = NWParameters()
        // 本机自己也在同一网段时会把自己扫出来，没意义
        params.includePeerToPeer = false

        let b = NWBrowser(for: .bonjour(type: kBonjourService, domain: nil), using: params)

        b.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in
                switch state {
                case .ready:
                    self?.isBrowsing = true
                case .failed, .cancelled:
                    self?.isBrowsing = false
                default:
                    break
                }
            }
        }

        b.browseResultsChangedHandler = { [weak self] results, _ in
            let endpoints = results.map(\.endpoint)
            Task { @MainActor in
                self?.handle(endpoints)
            }
        }

        browser = b
        b.start(queue: queue)
    }

    func stop() {
        browser?.cancel()
        browser = nil
        resolver.cancelAll()
        byKey.removeAll()
        seen.removeAll()
        found = []
        isBrowsing = false
    }

    // MARK: - 解析

    private func handle(_ endpoints: [NWEndpoint]) {
        for ep in endpoints {
            guard case let .service(name, type, domain, _) = ep else { continue }
            let id = "\(name)|\(type)|\(domain)"
            guard !seen.contains(id) else { continue }
            seen.insert(id)

            resolver.resolve(name: name, type: type, domain: domain) { [weak self] host, port in
                self?.add(host: host, port: port, name: name)
            }
        }
    }

    private func add(host: String, port: Int, name: String) {
        let key = "\(host):\(port)"
        // Bonjour 的服务名里空格被转义成 \032，直接显示会很难看
        let display = name.replacingOccurrences(of: "\\032", with: " ")
        byKey[key] = Desktop(host: host, port: port, name: display)
        found = byKey.values.sorted { $0.name < $1.name }

        // Android 默认端口被占用时会通告备用端口，发现阶段补一次 info，
        // 这样雷达也能依据 engine 正确显示手机图标。
        Task { [weak self] in
            guard let self else { return }
            guard let enriched = try? await ApiClient(baseURL: "http://\(host):\(port)").info(),
                  self.byKey[key] != nil else { return }
            self.byKey[key] = enriched
            self.found = self.byKey.values.sorted { $0.name < $1.name }
        }
    }

    // MARK: - 手输 / 扫码

    /// 确认那头真的是桌面端。地址写法宽松，见 normalizeAddress。
    static func verify(_ input: String) async throws -> Desktop {
        guard let url = normalizeAddress(input) else {
            throw ApiError(L("error.badAddress", input))
        }
        let api = ApiClient(baseURL: url)
        guard await api.health() else {
            throw ApiError(L("error.notDroidTrans"))
        }
        return try await api.info()
    }
}
