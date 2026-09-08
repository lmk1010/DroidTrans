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
    /// 服务 id → host:port。服务消失时 browser 只报服务，得靠它找回那个条目。
    private var keyByService: [String: String] = [:]

    private let queue = DispatchQueue(label: "life.mkstore.droidtrans.discovery")

    func start() {
        guard browser == nil else { return }

        let params = NWParameters()
        // 本机自己也在同一网段时会把自己扫出来，没意义
        // AWDL 点对点：两台 iPhone 之间没有路由器也能互相看见。
        // 代价是解析出来的会是 fe80::…%awdl0 这种链路本地地址，
        // URL 那一层要 urlHost 把它包成 [fe80::…%25awdl0] 才用得了。
        params.includePeerToPeer = true

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
        keyByService.removeAll()
        found = []
        isBrowsing = false
    }

    // MARK: - 解析

    private func handle(_ endpoints: [NWEndpoint]) {
        var live: Set<String> = []

        for ep in endpoints {
            guard case let .service(name, type, domain, _) = ep else { continue }
            let id = "\(name)|\(type)|\(domain)"
            live.insert(id)
            guard !seen.contains(id) else { continue }
            seen.insert(id)

            resolver.resolve(name: name, type: type, domain: domain) { [weak self] host, port in
                self?.add(host: host, port: port, name: name, service: id)
            }
        }

        // browser 每次给的是**当前全量**，不在里面的就是走了的：对面停了接收、
        // 退出了 App、或者离开了这个网络。留着它，用户点下去只会等到一个超时，
        // 而他看到的是「明明在列表里，就是连不上」。
        for id in seen.subtracting(live) {
            seen.remove(id)
            if let key = keyByService.removeValue(forKey: id) {
                byKey.removeValue(forKey: key)
            }
        }
        found = byKey.values.sorted { $0.name < $1.name }
    }

    private func add(host: String, port: Int, name: String, service: String) {
        let key = "\(host):\(port)"
        keyByService[service] = key
        // Bonjour 的服务名里空格被转义成 \032，直接显示会很难看
        let display = name.replacingOccurrences(of: "\\032", with: " ")
        byKey[key] = Desktop(host: host, port: port, name: display)
        found = byKey.values.sorted { $0.name < $1.name }

        // Android 默认端口被占用时会通告备用端口，发现阶段补一次 info，
        // 这样雷达也能依据 engine 正确显示手机图标。
        Task { [weak self] in
            guard let self else { return }
            guard let enriched = try? await ApiClient(baseURL: "http://\(urlHost(host)):\(port)").info(),
                  self.byKey[key] != nil else { return }
            self.byKey[key] = enriched
            self.found = self.byKey.values.sorted { $0.name < $1.name }
        }
    }

    // MARK: - 手输 / 扫码

    /// 确认那头真的是桌面端（或另一台手机）。地址写法宽松，见 normalizeAddress。
    ///
    /// 用户只输了 IP 时两个端口都试：电脑在 9500，手机接收端在 9600。
    /// 只认 9500 的话，「手输地址」这条兜底路在手机互传里必然打空，
    /// 而用户输的 IP 完全正确 —— 他只会得到一句「连不上」。
    static func verify(_ input: String) async throws -> Desktop {
        guard let first = normalizeAddress(input) else {
            throw ApiError(L("error.badAddress", input))
        }
        var tries = [first]
        if !hasExplicitPort(input) {
            let host = URL(string: first)?.host ?? input
            tries.append("http://\(urlHost(plainHost(host))):\(Ports.peer)")
        }
        for url in tries {
            let api = ApiClient(baseURL: url)
            if await api.health() {
                return try await api.info()
            }
        }
        throw ApiError(L("error.notDroidTrans"))
    }

    /// 用户自己写了端口没有。写了就照他说的来，一个字都不猜。
    private static func hasExplicitPort(_ raw: String) -> Bool {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if !s.contains("://") { s = "http://\(s)" }
        return URL(string: s)?.port != nil
    }
}
