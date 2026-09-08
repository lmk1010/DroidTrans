/// 把 Bonjour 服务名解析成 IP。
///
/// NWBrowser 只给服务名，不给地址。Network.framework 的官方做法是
/// 「建一条 NWConnection，从 currentPath 读对端地址」，但那条路在模拟器上
/// 会一直停在 preparing —— 浏览得到、连不上、也不报错。
///
/// NetService 是老 API，但解析这件事它做得踏实：直接给出 sockaddr 列表。
/// 唯一的坑是它靠 RunLoop 驱动，必须 schedule 到主线程；
/// 在后台队列上调 resolve 的话，delegate 一个回调都不会来。

import Foundation

@MainActor
final class ServiceResolver: NSObject {
    /// 解析中的服务。得强引用着，NetService 被释放就没有回调了。
    private var pending: [String: NetService] = [:]
    private var handlers: [String: (String, Int) -> Void] = [:]

    /// name/type/domain 来自 NWBrowser 的 .service endpoint。
    func resolve(name: String, type: String, domain: String,
                 onFound: @escaping (String, Int) -> Void) {
        let key = "\(name).\(type).\(domain)"
        guard pending[key] == nil else { return }

        let svc = NetService(domain: domain, type: type, name: name)
        svc.delegate = self
        // 少了这一行，下面的 resolve 会石沉大海
        svc.schedule(in: .main, forMode: .common)
        pending[key] = svc
        handlers[key] = onFound
        svc.resolve(withTimeout: 6)
    }

    func cancelAll() {
        for (_, svc) in pending {
            svc.stop()
            svc.remove(from: .main, forMode: .common)
        }
        pending.removeAll()
        handlers.removeAll()
    }

    private func key(of svc: NetService) -> String {
        "\(svc.name).\(svc.type)\(svc.domain)"
    }

    private func finish(_ svc: NetService, host: String?, port: Int) {
        // NetService 的 type/domain 带尾点，拼出来的 key 和 resolve 时的不完全一样，
        // 所以按 name 找回来，而不是按完整 key
        let k = handlers.keys.first { $0.hasPrefix(svc.name + ".") }
        if let host, let k, let handler = handlers[k] {
            handler(host, port)
        }
        if let k {
            pending[k]?.stop()
            pending[k]?.remove(from: .main, forMode: .common)
            pending.removeValue(forKey: k)
            handlers.removeValue(forKey: k)
        }
    }
}

extension ServiceResolver: NetServiceDelegate {
    nonisolated func netServiceDidResolveAddress(_ sender: NetService) {
        let host = Self.firstUsableIPv4(of: sender)
        let port = sender.port
        Task { @MainActor in
            self.finish(sender, host: host, port: port)
        }
    }

    nonisolated func netService(_ sender: NetService,
                                didNotResolve errorDict: [String: NSNumber]) {
        Task { @MainActor in
            self.finish(sender, host: nil, port: 0)
        }
    }

    /// 一个服务可能通告多个地址（IPv4 / IPv6 / 链路本地）。
    ///
    /// 优先普通 IPv4，其次普通 IPv6，最后才是 fe80 链路本地 ——
    /// **链路本地不能再直接丢掉**：AWDL（两台 iPhone 不经路由器直连）
    /// 通告的只有 fe80::…%awdl0，丢了它，点对点这条路就永远是空列表。
    /// 169.254 仍然排掉：那是 Wi-Fi 没拿到 DHCP 时的自说自话，连不到任何人。
    private nonisolated static func firstUsableIPv4(of svc: NetService) -> String? {
        var fallback: String?
        var linkLocal: String?
        for data in svc.addresses ?? [] {
            let addr: String? = data.withUnsafeBytes { raw -> String? in
                guard let base = raw.baseAddress else { return nil }
                let sa = base.assumingMemoryBound(to: sockaddr.self)
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                guard getnameinfo(sa, socklen_t(data.count),
                                  &host, socklen_t(host.count),
                                  nil, 0, NI_NUMERICHOST) == 0 else { return nil }
                return String(cString: host)
            }
            guard var ip = addr else { continue }
            // IPv6 带 %en0 这种作用域后缀，拼进 URL 会解析失败
            ip = ip.components(separatedBy: "%").first ?? ip

            if ip.hasPrefix("127.") || ip == "::1" { continue }
            if ip.hasPrefix("169.254.") { continue }

            // 普通 IPv4 优先。IPv6 留着当备选，总比没有强
            if !ip.contains(":") { return ip }
            if ip.lowercased().hasPrefix("fe80") {
                // 链路本地必须带上 %en0 / %awdl0 作用域，不然内核不知道走哪张网卡。
                // 上面那句 components(separatedBy: "%") 会把它切掉，所以这里用原样的。
                if linkLocal == nil { linkLocal = addr }
                continue
            }
            if fallback == nil { fallback = ip }
        }
        return fallback ?? linkLocal
    }
}
