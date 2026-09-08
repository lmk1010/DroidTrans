/// 与 Go 桌面端对齐的协议常量与线格式。
///
/// 桌面端源码位置（改协议时几边一起改）：
///   desktop/internal/fast/fast.go     ATF1/ATF2 帧、TCP 9501、FTP 9502
///   desktop/internal/app/app.go       HTTP 9500 路由表
///   desktop/internal/bonjour/         _droidtrans._tcp 服务通告
///   android/.../network/              Android 端的同一套实现
///
/// 这份是第三个实现（Go / Java / Swift），字节不一致就会在真机上表现为
/// 「传到一半失败」这种最难查的问题，所以 DroidTransTests 里有一组
/// 写死的十六进制向量做对拍。

import Foundation

enum Ports {
    /// HTTP：REST 接口与界面
    static let http = 9500
    /// 快传 TCP：ATF2 裸流，没有 HTTP 开销
    static let fastTCP = 9501
    /// FTP：TCP 走不通时的备选
    static let ftp = 9502
    /// 手机当接收方时监听的端口。
    ///
    /// 特意避开 9500：一台机器上可能同时跑着桌面端和模拟器里的手机端，
    /// 撞端口的话后起来的那个会静默失败。发送方的端口是从 Bonjour 解析出来的，
    /// 不是写死的 9500，所以换端口对它完全透明。
    static let peer = 9600
}

/// Bonjour 服务类型。必须同时写进 Info.plist 的 NSBonjourServices，
/// 否则 NWBrowser 静默扫不到任何东西 —— 不报错，就是永远空列表。
let kBonjourService = "_droidtrans._tcp"

/// 配对令牌走这个请求头，HTTP 和 ATF2 两条通道都认它。
let kTokenHeader = "X-DT-Token"

/// 不需要配对也能问的路径 —— 与 Go 端 openPath() 一一对应。
/// 手机在配对前得先能问出「这台电脑在不在、要不要配对」。
let kOpenPaths: Set<String> = [
    "/api/health",
    "/api/wifi/info",
    "/api/fast/caps",
    "/api/pair",
]

/// 上传通道，优先级从高到低，与 /api/fast/caps 的 prefer 字段对齐。
enum FastProtocol: String, CaseIterable {
    case tcp = "tcp"
    case ftp = "ftp"
    case httpPut = "http_put"
    case httpMultipart = "http_multipart"

    /// 桌面端加了新协议而这版 App 还不认识时返回 nil，跳过即可 ——
    /// 不能因为多了一个陌生的 id 就让整台电脑不可用。
    init?(id: String) {
        self.init(rawValue: id)
    }
}

// MARK: - ATF2 帧

enum ATFError: Error, LocalizedError {
    case tokenTooLong(Int)
    case nameTooLong(String)
    case negativeSize(Int64)

    var errorDescription: String? {
        switch self {
        case .tokenTooLong(let n):
            return "token exceeds 512 bytes (\(n))"
        case .nameTooLong(let name):
            return "file name exceeds 4096 bytes: \(name)"
        case .negativeSize(let n):
            return "size cannot be negative: \(n)"
        }
    }
}

/// ATF2 传输头。
///
///   ATF1: magic | nameLen | name | size                     （旧版，无令牌）
///   ATF2: magic | tokenLen | token | nameLen | name | size   （当前）
///
/// 长度和大小都是大端；nameLen/tokenLen 是 uint32，size 是 uint64。
/// 头之后紧跟 size 字节的文件内容，服务端回 "OK\n" 或 "ERR <原因>\n"。
///
/// 只发 ATF2：ATF1 没有令牌，等于谁都能往电脑上写文件。
func buildATF3Header(name: String, size: Int64, token: String) throws -> Data {
    let nameBytes = Array(name.utf8)
    let tokenBytes = Array(token.utf8)

    // 服务端 readStr 的上限：token 512、name 4096，超了直接判 "field too long"。
    // 在这里拦下来，比让用户等到传输中途才收到一句错误要好。
    guard tokenBytes.count <= 512 else { throw ATFError.tokenTooLong(tokenBytes.count) }
    guard nameBytes.count <= 4096 else { throw ATFError.nameTooLong(name) }
    guard size >= 0 else { throw ATFError.negativeSize(size) }

    var out = Data()
    out.append(contentsOf: Array("ATF3".utf8))
    out.append(u32be(UInt32(tokenBytes.count)))
    out.append(contentsOf: tokenBytes)
    out.append(u32be(UInt32(nameBytes.count)))
    out.append(contentsOf: nameBytes)
    out.append(u64be(UInt64(size)))
    return out
}

private func u32be(_ v: UInt32) -> Data {
    var b = v.bigEndian
    return Data(bytes: &b, count: 4)
}

private func u64be(_ v: UInt64) -> Data {
    var b = v.bigEndian
    return Data(bytes: &b, count: 8)
}

// MARK: - 应答

/// 服务端应答的状态字节。与 desktop/internal/fast/fast.go 里的常量一一对应。
///
/// ATF3 之前应答是 "OK\n" / "ERR ...\n" 这样的文本，现在改成二进制 ——
/// 因为握手阶段要回一个 u64 偏移量，二进制数字和换行分隔的文本混在同一条流上，
/// 偏移量里恰好出现 0x0A 就会被当成行尾，这类 bug 只在特定文件大小下复现。
enum ATFStatus: UInt8 {
    /// 继续，后面跟 8 字节大端偏移量：从第几个字节开始发
    case go = 0x00
    /// 拒绝，后面跟 4 字节长度 + UTF-8 原因
    case error = 0x01
    /// 收完了，落盘成功
    case done = 0x02
    /// 超出免费额度。和 error 同样的帧格式，但分开一个状态字 ——
    /// 混在普通错误里，用户只会以为传输坏了，看不到升级这条路。
    case upgrade = 0x03
}

func u64be(from data: Data) -> Int64 {
    var v: UInt64 = 0
    for b in data.prefix(8) { v = (v << 8) | UInt64(b) }
    return Int64(bitPattern: v)
}

func u32be(from data: Data) -> UInt32 {
    var v: UInt32 = 0
    for b in data.prefix(4) { v = (v << 8) | UInt32(b) }
    return v
}

// MARK: - 地址

/// IPv6 地址拼进 URL 要加方括号；链路本地还带一个 %en0 / %awdl0 作用域，
/// 那个百分号在 URL 里必须写成 %25，否则整条 URL 解析失败。
///
/// AWDL（iPhone 之间不经路由器直连）给出的**只有**链路本地地址，
/// 所以这条路能不能走通，全看这个函数。
func urlHost(_ host: String) -> String {
    guard host.contains(":") else { return host }   // 普通 IPv4
    return "[" + host.replacingOccurrences(of: "%", with: "%25") + "]"
}

/// 反过来：从 URL 那种写法还原成裸地址，用作 id 和显示。
func plainHost(_ raw: String) -> String {
    var h = raw
    if h.hasPrefix("["), h.hasSuffix("]") {
        h = String(h.dropFirst().dropLast())
    }
    return h.replacingOccurrences(of: "%25", with: "%")
}

/// 把用户输入或二维码内容整理成 http://host:port。
///
/// 接受 "192.168.1.5"、"192.168.1.5:9500"、"http://192.168.1.5:9500" 三种写法。
func normalizeAddress(_ raw: String) -> String? {
    var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !s.isEmpty else { return nil }
    if !s.contains("://") { s = "http://\(s)" }
    guard let u = URL(string: s), let host = u.host, !host.isEmpty else { return nil }
    let port = u.port ?? Ports.http
    // URL.host 把 IPv6 的方括号剥掉了，直接拼回去会得到一条解析不了的地址
    return "http://\(urlHost(plainHost(host))):\(port)"
}
