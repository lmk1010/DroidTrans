/// 接收端二维码里的那串东西。
///
/// 沿用桌面端一直在用的写法，扫码这条路才不用分两套解析：
///
///     http://192.168.49.1:9600/?c=123456&n=iPhone&s=DroidTrans-3f2&k=8a91c07b
///
///   - host / port  接收端实际绑上的地址，端口不写死（9600 被占时会是别的）
///   - c            六位配对码：组播被拦、敲门弹框没看到时的兜底
///   - n            设备名，扫完先让用户看一眼「要连的是这台」
///   - s / k        直连热点的名字和密码 —— 有它才谈得上「扫一下就连上」
///
/// 与 Android 端 network/PeerLink.java 是同一份格式，改一边就要改另一边。

import Foundation

struct PeerLink {
    let host: String
    let port: Int
    let code: String
    let name: String
    /// 直连热点名；空表示这个码不带入网信息
    let ssid: String
    let password: String

    var hasHotspot: Bool { !ssid.isEmpty }
    var baseURL: String { "http://\(host):\(port)" }

    /// 接收端画自己的码。ssid 传 nil 表示这次没开直连热点。
    static func encode(host: String, port: Int, code: String, name: String,
                       ssid: String? = nil, password: String? = nil) -> String {
        var c = URLComponents()
        c.scheme = "http"
        c.host = host
        c.port = port
        c.path = "/"
        var items = [URLQueryItem(name: "c", value: code)]
        if !name.isEmpty { items.append(URLQueryItem(name: "n", value: name)) }
        if let ssid, !ssid.isEmpty {
            items.append(URLQueryItem(name: "s", value: ssid))
            items.append(URLQueryItem(name: "k", value: password ?? ""))
        }
        c.queryItems = items
        return c.string ?? "http://\(host):\(port)/?c=\(code)"
    }

    /// 扫到的内容解析成一条链接；不是卓传的码返回 nil。
    static func parse(_ payload: String) -> PeerLink? {
        var s = payload.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return nil }
        if !s.contains("://") { s = "http://\(s)" }
        guard let c = URLComponents(string: s), let host = c.host, !host.isEmpty else {
            return nil
        }
        let q = c.queryItems ?? []
        func item(_ k: String) -> String {
            q.first { $0.name == k }?.value ?? ""
        }
        // 端口不写就按桌面端的 9500 算 —— 电脑的码历来就是这个样子
        return PeerLink(host: host, port: c.port ?? Ports.http,
                        code: item("c"), name: item("n"),
                        ssid: item("s"), password: item("k"))
    }
}
