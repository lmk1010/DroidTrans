/// 桌面端接口返回的数据结构。字段名跟 Go 端 writeJSON 的 key 一一对应。
///
/// 这里刻意不用 Codable 的自动解码：桌面端版本可能比 App 新，
/// 多出来的字段、少掉的字段都不该让整个响应解析失败 —— 缺字段取默认值，
/// 认不出来的枚举值直接丢掉，比抛异常让用户看到「连接失败」要好。

import Foundation

/// 对面是什么设备。手机互传的两条入口用它区分文案和引导。
enum PeerKind: Equatable {
    case iphone
    case android
}

/// 一台被发现的电脑。
struct Desktop: Identifiable, Equatable {
    let host: String
    let port: Int
    var name: String
    var engine: String
    var pairingRequired: Bool

    /// 怎么配对。"approve" 表示对面是一台手机，点一下同意就行，不用输码；
    /// 空表示老规矩（电脑），要六位配对码。
    ///
    /// 桌面端不发这个字段，所以默认值必须是「要输码」——
    /// 猜错的方向要选安全的那一边。
    var pairingMode: String = ""

    /// 上传通道优先级，来自 /api/fast/caps 的 prefer
    var prefer: [FastProtocol]
    var tcpPort: Int?
    var ftpPort: Int?

    /// 电脑上有多少东西等着手机来取
    var outboxCount: Int
    var outboxSize: Int64

    /// 这台电脑是通过手机热点连上的
    var onHotspot: Bool

    init(
        host: String,
        port: Int = Ports.http,
        name: String,
        engine: String = "",
        pairingRequired: Bool = true,
        pairingMode: String = "",
        prefer: [FastProtocol] = [],
        tcpPort: Int? = nil,
        ftpPort: Int? = nil,
        outboxCount: Int = 0,
        outboxSize: Int64 = 0,
        onHotspot: Bool = false
    ) {
        self.host = host
        self.port = port
        self.name = name
        self.engine = engine
        self.pairingRequired = pairingRequired
        self.pairingMode = pairingMode
        self.prefer = prefer
        self.tcpPort = tcpPort
        self.ftpPort = ftpPort
        self.outboxCount = outboxCount
        self.outboxSize = outboxSize
        self.onHotspot = onHotspot
    }

    /// 对面是台手机，连过去只要它点头，不用输码
    var approvesByTap: Bool { pairingMode == "approve" }

    /// 设备发现时的图形类别。
    ///
    /// 手机服务端使用 9600 端口，且新版本会通告 approve 配对模式。
    /// 名字判断只是给旧版或手输地址的响应兜底，不能只靠它识别，
    /// 因为真实 Android 机型名（例如 PLK110）通常不包含 phone/android。
    var isPhone: Bool {
        if engine == "android" || engine == "swift" || port == Ports.peer || approvesByTap {
            return true
        }
        let n = name.lowercased()
        return n.contains("iphone")
            || n.contains("phone")
            || n.contains("android")
            || n.contains("pixel")
            || n.contains("oneplus")
            || n.contains("oppo")
            || n.contains("vivo")
            || n.contains("xiaomi")
            || n.contains("redmi")
            || n.contains("huawei")
            || n.contains("honor")
            || n.contains("samsung")
    }

    /// 同一台电脑换了端口就是另一个入口，所以 id 要带上端口
    var id: String { "\(host):\(port)" }
    var baseURL: String { "http://\(host):\(port)" }

    static func == (a: Desktop, b: Desktop) -> Bool {
        a.host == b.host && a.port == b.port
    }

    static func fromWifiInfo(host: String, json j: [String: Any]) -> Desktop {
        let rawPrefer = j["prefer"] as? [Any] ?? []
        let name = (j["name"] as? String)?.trimmingCharacters(in: .whitespaces) ?? ""
        return Desktop(
            host: host,
            port: intOf(j["port"]) ?? Ports.http,
            name: name.isEmpty ? host : name,
            engine: (j["engine"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? "",
            pairingRequired: j["pairing_required"] as? Bool ?? true,
            pairingMode: j["pairing_mode"] as? String ?? "",
            // 认不出来的协议 id 直接丢掉，别让新版桌面端把老版 App 卡死
            prefer: rawPrefer.compactMap { FastProtocol(id: "\($0)") },
            tcpPort: intOf(j["tcp_port"]),
            ftpPort: intOf(j["ftp_port"]),
            outboxCount: intOf(j["outbox_count"]) ?? 0,
            outboxSize: int64Of(j["outbox_size"]) ?? 0,
            onHotspot: j["on_hotspot"] as? Bool ?? false
        )
    }
}

/// 电脑上等着被取走的一条。
struct OutboxItem: Identifiable, Equatable {
    let id: String
    let name: String

    /// -1 表示文件在登记之后被删了/移走了，桌面端会这样标出来
    let size: Int64

    /// 文件夹内的相对路径，手机照此重建目录
    let rel: String

    /// 非空表示这是一段文字/链接，不是文件
    let text: String?
    let taken: Int
    let addedAt: String?

    var isText: Bool { !(text ?? "").isEmpty }
    var isMissing: Bool { size < 0 }
    var isTaken: Bool { taken > 0 }

    static func fromJSON(_ j: [String: Any]) -> OutboxItem {
        let name = j["name"] as? String ?? L("item.untitled")
        return OutboxItem(
            id: j["id"] as? String ?? "",
            name: name,
            size: int64Of(j["size"]) ?? 0,
            rel: j["rel"] as? String ?? name,
            text: j["text"] as? String,
            taken: intOf(j["taken"]) ?? 0,
            addedAt: j["added_at"] as? String
        )
    }
}

// MARK: - JSON 取数

/// JSON 里的数字可能是 Int、Double 或 NSNumber，取决于它长什么样，
/// 直接 as? Int 会在「桌面端把 9500 序列化成 9500.0」这种情况下静默变 nil。
func intOf(_ v: Any?) -> Int? {
    if let n = v as? NSNumber { return n.intValue }
    if let s = v as? String { return Int(s) }
    return nil
}

func int64Of(_ v: Any?) -> Int64? {
    if let n = v as? NSNumber { return n.int64Value }
    if let s = v as? String { return Int64(s) }
    return nil
}
