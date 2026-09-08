/// 手机当接收方。
///
/// 现有协议是 HTTP：电脑当服务端，手机当客户端。手机对手机，
/// 就得有一台先站到服务端那一侧 —— 这个文件就是那一半。
///
/// 它只实现发送方真正会打的那几个口，字节格式照 Protocol.swift 来：
///
///   GET  /api/health      在不在、是不是卓传
///   GET  /api/wifi/info   叫什么、要不要配对、走哪条上传通道
///   GET  /api/fast/caps   同上，发送方握手时会问
///   POST /api/pair        六位码换令牌
///   GET  /api/outbox      发送方拿它验令牌还好不好用（这边永远是空清单）
///   PUT  /api/fast/put    收文件
///
/// 加上 Bonjour 广播同一个 _droidtrans._tcp，对面那台的雷达就会像发现电脑
/// 一样发现这台手机 —— 发送方那一侧一行都不用改。
///
/// 端口用 Ports.peer（9600），不占桌面端的 9500 —— 见那边的注释。

import Foundation
import Network
import UIKit

@MainActor
final class PeerServer: ObservableObject {
    static let shared = PeerServer()

    @Published private(set) var running = false
    @Published private(set) var pairingCode = ""
    @Published private(set) var boundPort: Int
    /// 配对令牌已经发出后才有值。敲门阶段不能算已连接。
    @Published private(set) var connectedName: String?

    /// 有人在敲门，等这台点头。
    ///
    /// 配对码不该是主路 —— 对面在雷达上点你一下，你这台弹一句
    /// 「XXX 想连过来」，同意就通，一个字都不用输。
    /// 码退成兜底：手输地址、或者组播被拦掉发现不到的时候才用得上。
    @Published var knock: Knock?

    struct Knock: Identifiable, Equatable {
        let id = UUID()
        /// 对面报的设备名。它是对面自己填的，只当提示看，不当身份用 ——
        /// 真正的把关是这台的主人点不点那个「同意」。
        let name: String
    }

    private var knockWaiter: CheckedContinuation<String?, Never>?
    /// 已经收到的文件，界面上按顺序列出来
    @Published private(set) var received: [ReceivedFile] = []

    /// 正在收的那个：名字、已收字节、总字节。
    ///
    /// 没有它，界面在整个文件落盘之前一个字都不动 —— 传一个大视频
    /// 就是几十秒的死界面，用户会以为卡住了或者根本没连上。
    @Published private(set) var incoming: (name: String, got: Int64, total: Int64)?
    @Published private(set) var lastError: String?

    struct ReceivedFile: Identifiable, Equatable {
        let id = UUID()
        let name: String
        let size: Int64
        let url: URL
    }

    private var listener: NWListener?
    private var conns: [ObjectIdentifier: PeerConnection] = [:]
    /// 配对过的令牌。只活在内存里 —— 这一次「我要收」结束就作废，
    /// 手机不是常驻服务，没有必要留一份长期授权在磁盘上。
    private var tokens: Set<String> = []

    private let queue = DispatchQueue(label: "life.mkstore.droidtrans.peer")
    private let requestedPort: UInt16
    private let advertiseService: Bool

    /// 线上固定 9600；测试传 0 让系统分配空闲端口，避免用例之间争抢。
    init(port: UInt16 = UInt16(Ports.peer), advertiseService: Bool = true) {
        requestedPort = port
        boundPort = Int(port)
        connectedName = nil
        self.advertiseService = advertiseService
    }

    var deviceName: String { Store.shared.deviceName }

    /// 收到的东西放这儿。Documents 下面，「文件」App 里能直接看到。
    ///
    /// nonisolated：它只碰 FileManager，不读任何 actor 状态。
    /// 连接那一侧要在非主线程上算分片路径，隔一次 MainActor 跳转没必要。
    nonisolated var inboxDir: URL {
        let base = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        // 目录名不能叫 Inbox。
        //
        // `Documents/Inbox` 是 iOS 的保留目录：别的 App 通过「用其他应用打开」
        // 递进来的文件由系统放在那儿，而系统只给 App 读和删的权限，**不允许写**。
        // 真机上因此每一个文件都收不下，报的是
        // 「你没有将文件“Inbox”存储到文件夹“Documents”中的权限」。
        // 模拟器不执行这条限制，所以这个 bug 在模拟器上永远看不见。
        let dir = base.appendingPathComponent("Received", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    // MARK: - 开关

    /// 已经在监听就什么都不做。App 前台常驻监听走这条，
    /// 免得每次切回前台都把正在进行的传输掐掉。
    func startIfIdle() {
        guard listener == nil else { return }
        start()
    }

    func start() {
        guard listener == nil else { return }
        lastError = nil
        received = []
        incoming = nil
        connectedName = nil
        tokens = []
        pairingCode = Self.newCode()

        Task { await bindWithRetry() }
    }

    /// 绑端口，撞上就退让重试。
    ///
    /// 刚 stop() 完立刻再 start()（用户点了「停止接收」又改主意），
    /// 上一个套接字还没被内核放开，这时候直接报「端口打不开」是冤枉的 ——
    /// 等两百毫秒它自己就好了。
    private func bindWithRetry() async {
        for _ in 0..<15 {
            if bind() { return }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        lastError = L("peer.err.port")
    }

    /// 绑上了返回 true。失败不写 lastError —— 那是重试用尽之后才该说的话。
    private func bind() -> Bool {
        do {
            let params = NWParameters.tcp
            params.allowLocalEndpointReuse = true
            // 开点对点（AWDL）：没有路由器的场合，两台 iPhone 直接就能互相看见 ——
            // 这正是这个 App 最该好用的场合。
            //
            // 代价要认：这时候对面拿到的是 fe80::…%awdl0 这种链路本地地址，
            // 和 Wi-Fi 网段对不上，排查「为什么连的是这个地址」会绕一下；
            // 界面上那条兜底地址给的仍然是 Wi-Fi/热点的 IPv4，手输那条路不受影响。
            params.includePeerToPeer = true

            let port = requestedPort == 0
                ? NWEndpoint.Port.any
                : NWEndpoint.Port(rawValue: requestedPort)!
            let l = try NWListener(using: params, on: port)
            // 广播的名字就是设备名，对面雷达上显示的就是这个
            if advertiseService {
                l.service = NWListener.Service(name: deviceName, type: kBonjourService)
            }

            l.stateUpdateHandler = { [weak self] state in
                Task { @MainActor in
                    switch state {
                    case .ready:
                        self?.running = true
                        self?.boundPort = Int(l.port?.rawValue ?? self?.requestedPort ?? 0)
                    case .failed(let e):
                        self?.lastError = e.localizedDescription
                        self?.stop()
                    case .cancelled:
                        self?.running = false
                    default:
                        break
                    }
                }
            }

            l.newConnectionHandler = { [weak self] nw in
                guard let self else { return }
                let c = PeerConnection(nw: nw, server: self, queue: self.queue)
                Task { @MainActor in self.conns[ObjectIdentifier(c)] = c }
                c.start()
            }

            l.start(queue: queue)
            listener = l
            return true
        } catch {
            return false
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
        for (_, c) in conns { c.cancel() }
        conns = [:]
        tokens = []
        connectedName = nil
        running = false
        finishKnock(nil)   // 别把还挂着的那条连接留在那儿等超时
    }

    /// 只给 PeerConnection 用（拆到另一个文件了，所以不能 fileprivate）
    func drop(_ c: PeerConnection) {
        conns.removeValue(forKey: ObjectIdentifier(c))
    }

    // MARK: - 配对

    private static func newCode() -> String {
        String(format: "%06d", Int.random(in: 0..<1_000_000))
    }

    /// 六位码换令牌。码不对返回 nil。
    /// 只给 PeerConnection 用（拆到另一个文件了，所以不能 fileprivate）
    func pair(code: String) -> String? {
        guard code.trimmingCharacters(in: .whitespaces) == pairingCode else { return nil }
        return issueToken()
    }

    /// 不带码的请求 = 敲门。挂起这条连接，等这台的主人点头。
    ///
    /// 同时只招呼一个人：正等着一个的时候又来一个，直接回绝 ——
    /// 两个弹窗叠在一起，用户根本分不清自己在给谁开门。
    func requestApproval(from name: String) async -> String? {
        guard knock == nil, knockWaiter == nil else { return nil }

        return await withCheckedContinuation { k in
            knockWaiter = k
            knock = Knock(name: name)

            // 没人理就当拒绝。挂着不放的话，对面一直转圈转到超时，
            // 而它得到的信息是「连不上」，不是「没人同意」。
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: 45_000_000_000)
                self?.deny()
            }
        }
    }

    func approve() { finishKnock(issueToken()) }

    func deny() { finishKnock(nil) }

    private func finishKnock(_ token: String?) {
        guard let w = knockWaiter else { return }
        knockWaiter = nil
        knock = nil
        w.resume(returning: token)
    }

    private func issueToken() -> String {
        let tok = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        tokens.insert(tok)
        return tok
    }

    /// 只给 PeerConnection 用（拆到另一个文件了，所以不能 fileprivate）
    func valid(_ token: String) -> Bool {
        !token.isEmpty && tokens.contains(token)
    }

    /// 配对成功，发送方已经拿到令牌，可以把接收端切到连接等待页。
    func noteConnected(name: String) {
        connectedName = name
    }

    /// 只给 PeerConnection 用（拆到另一个文件了，所以不能 fileprivate）
    func note(_ f: ReceivedFile) {
        incoming = nil
        received.append(f)
    }

    /// 收到一半时的进度。只给 PeerConnection 用。
    func noteProgress(name: String, got: Int64, total: Int64) {
        incoming = (name, got, total)
    }

    /// 本机在当前局域网下的地址。手输地址那条路要用它。
    var localIP: String? { Self.wifiAddress() }

    /// 这个网卡有多值得报出去，数字越小越优先；nil = 别用。
    ///
    /// **热点必须排在 Wi-Fi 前面。** 这台手机自己开个人热点时，
    /// 对方是连到 bridge100（172.20.10.1）上的，不是 en0 ——
    /// 而热点常常是在没有 Wi-Fi 的场合开的（在外面、没路由器），
    /// 那时 en0 干脆没有地址。只认 en0 的话，用户开了热点、对面也连上了，
    /// 这边却显示「先连上 Wi-Fi」，手输地址那条兜底路直接断掉。
    ///
    /// pdp_ip* 是蜂窝，对面连不过来，不要。
    nonisolated static func interfaceRank(_ name: String) -> Int? {
        if name.hasPrefix("bridge") { return 0 } // 个人热点：这台是热点主人
        if name == "ap1" { return 1 }            // 部分机型的热点接口
        if name == "en0" { return 2 }            // Wi-Fi
        if name == "en1" { return 3 }
        return nil
    }

    private static func wifiAddress() -> String? {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return nil }
        defer { freeifaddrs(head) }

        var out: String?
        var bestRank = Int.max
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let flags = Int32(ptr.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0 else { continue }
            guard ptr.pointee.ifa_addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            let name = String(cString: ptr.pointee.ifa_name)
            guard let rank = interfaceRank(name), rank < bestRank else { continue }

            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(ptr.pointee.ifa_addr, socklen_t(ptr.pointee.ifa_addr.pointee.sa_len),
                           &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                out = String(cString: host)
                bestRank = rank
            }
        }
        return out
    }
}
