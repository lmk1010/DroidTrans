/// ATF3 快传：直连桌面端 TCP 9501，把文件裸流推过去。
///
/// 走这条路而不是 HTTP multipart，是因为大文件（几十 GB 的视频）
/// 在 multipart 上会多一层编码和内存拷贝，速度差得很明显。
///
/// ATF3 比上一版多了一次握手：发完文件头之后先等服务端回一个偏移量，
/// 再从那里开始发。多这一个来回，换来的是「传到 99GB 断网，重连只补最后 1GB」。
/// 导一半卡住、只能从头再来，正是用户抛弃系统自带导入工具的头号原因。

import Foundation
import Network

/// 传输进度。total 为 0 表示大小未知。
struct SendProgress {
    let sent: Int64
    let total: Int64
    var fraction: Double {
        total > 0 ? min(max(Double(sent) / Double(total), 0), 1) : 0
    }
}

struct FastSendError: Error, LocalizedError {
    let message: String
    /// 是不是「超出免费额度」。界面据此把付费页直接推出来，
    /// 而不是只弹一句错误让用户自己去猜。
    let needsPro: Bool
    var errorDescription: String? { message }
    init(_ m: String, needsPro: Bool = false) {
        message = m
        self.needsPro = needsPro
    }
}

/// 一次传输占一个实例，不复用 —— 连接状态和应答缓冲都是一次性的。
actor FastSender {
    private let host: String
    private let port: Int
    /// 配对令牌。桌面端关掉配对时可以是空串，服务端会放行。
    private let token: String
    private let connectTimeout: TimeInterval

    /// 一次写多少。太小了系统调用开销占比高，太大了进度回调会卡顿。
    private static let chunkSize = 256 * 1024

    init(host: String, token: String, port: Int = Ports.fastTCP,
         connectTimeout: TimeInterval = 8) {
        self.host = host
        self.port = port
        self.token = token
        self.connectTimeout = connectTimeout
    }

    /// 把 fileURL 发到电脑上，落地文件名为 remoteName
    /// （可以带相对路径，桌面端 SafeJoin 会挡掉 ../ 逃逸）。
    func sendFile(
        _ fileURL: URL,
        remoteName: String? = nil,
        onProgress: (@Sendable (SendProgress) -> Void)? = nil
    ) async throws {
        let attrs = try FileManager.default.attributesOfItem(atPath: fileURL.path)
        let size = (attrs[.size] as? NSNumber)?.int64Value ?? 0
        let name = remoteName ?? fileURL.lastPathComponent

        let header = try buildATF3Header(name: name, size: size, token: token)

        let conn = try await connect()
        // 无论成败都要关，不然连接会挂到超时
        defer { conn.cancel() }

        let reply = ReplyStream()
        // 应答要在写之前就开始收：服务端校验失败时会立刻回错误并关连接，
        // 等写完再读的话，这中间的写入会先炸成 broken pipe，真正的原因就丢了。
        startReceiving(conn, into: reply)

        try await send(conn, data: header)

        // ---- 握手：服务端说从哪儿接着发 ----
        let offset = try await readAccept(reply)
        if offset >= size {
            // 电脑上已经有一份完整的了，一个字节都不用发
            onProgress?(SendProgress(sent: size, total: size))
            try await readFinal(reply)
            return
        }

        guard let handle = try? FileHandle(forReadingFrom: fileURL) else {
            throw FastSendError(L("error.cantOpenFile", name))
        }
        defer { try? handle.close() }
        if offset > 0 {
            try handle.seek(toOffset: UInt64(offset))
        }

        var sent = offset
        // 进度从 offset 起报。从 0 重新爬一遍的话，用户会以为又从头传了。
        onProgress?(SendProgress(sent: sent, total: size))

        while sent < size {
            try Task.checkCancellation()

            // 服务端在传输中途主动说话只有一种情况：它出问题了（磁盘写不下、
            // 路径不对…）。它说完就关连接，这时候继续写只会拿到 broken pipe，
            // 把真正的原因盖掉。停下来去读那句话。
            if await reply.hasBytes { break }

            let chunk = handle.readData(ofLength: Self.chunkSize)
            if chunk.isEmpty { break }

            do {
                try await send(conn, data: chunk)
            } catch {
                // 连接已经断了。服务端在断开前说过话的话，那句话才是真原因。
                if await reply.hasBytes { break }
                throw error
            }
            sent += Int64(chunk.count)
            onProgress?(SendProgress(sent: sent, total: size))
        }

        try await readFinal(reply)
    }

    /// 读握手应答，返回该从第几个字节开始发。
    private func readAccept(_ reply: ReplyStream) async throws -> Int64 {
        let status = try await reply.take(1, timeout: 30)
        switch ATFStatus(rawValue: status[status.startIndex]) {
        case .go:
            return u64be(from: try await reply.take(8, timeout: 30))
        case .error:
            throw FastSendError(try await readErrorMessage(reply))
        case .upgrade:
            throw FastSendError(try await readErrorMessage(reply), needsPro: true)
        case .done:
            // 服务端不该在这一步说「收完了」，但真发生了也当成功，
            // 总比把一次成功的传输报成失败强。
            return .max
        case nil:
            throw FastSendError(L("error.closedNoReply"))
        }
    }

    /// 读收尾应答。
    private func readFinal(_ reply: ReplyStream) async throws {
        let status = try await reply.take(1, timeout: 30)
        switch ATFStatus(rawValue: status[status.startIndex]) {
        case .done:
            return
        case .error:
            throw FastSendError(try await readErrorMessage(reply))
        case .upgrade:
            throw FastSendError(try await readErrorMessage(reply), needsPro: true)
        default:
            throw FastSendError(L("error.closedNoReply"))
        }
    }

    private func readErrorMessage(_ reply: ReplyStream) async throws -> String {
        let len = u32be(from: try await reply.take(4, timeout: 10))
        guard len > 0, len <= 4096 else { return L("error.closedNoReply") }
        let body = try await reply.take(Int(len), timeout: 10)
        return String(data: body, encoding: .utf8) ?? L("error.closedNoReply")
    }

    // MARK: - 连接

    private func connect() async throws -> NWConnection {
        let opts = NWProtocolTCP.Options()
        // 传输是一股脑推大块数据，Nagle 攒包只会增加延迟
        opts.noDelay = true
        opts.connectionTimeout = Int(connectTimeout)

        let conn = NWConnection(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(integerLiteral: UInt16(port)),
            using: NWParameters(tls: nil, tcp: opts)
        )

        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            // stateUpdateHandler 会被多次调用，continuation 只能恢复一次
            let done = OnceFlag()
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    if done.take() { k.resume() }
                case .failed(let e):
                    if done.take() { k.resume(throwing: FastSendError(Self.describe(e))) }
                case .cancelled:
                    if done.take() { k.resume(throwing: FastSendError(L("error.canceled"))) }
                default:
                    break
                }
            }
            conn.start(queue: .global(qos: .userInitiated))
        }
        return conn
    }

    private func send(_ conn: NWConnection, data: Data) async throws {
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            conn.send(content: data, completion: .contentProcessed { error in
                if let error {
                    k.resume(throwing: FastSendError(Self.describe(error)))
                } else {
                    k.resume()
                }
            })
        }
    }

    private nonisolated func startReceiving(_ conn: NWConnection, into reply: ReplyStream) {
        func loop() {
            conn.receive(minimumIncompleteLength: 1, maximumLength: 512) { data, _, isComplete, error in
                if let data, !data.isEmpty {
                    Task { await reply.append(data) }
                }
                if isComplete || error != nil {
                    Task { await reply.finish() }
                    return
                }
                loop()
            }
        }
        loop()
    }

    private static func describe(_ error: Error) -> String {
        if let e = error as? NWError {
            switch e {
            case .posix(.ECONNREFUSED):
                return L("error.refused")
            case .posix(.ETIMEDOUT):
                return L("error.unreachable")
            case .posix(.ENETUNREACH), .posix(.EHOSTUNREACH):
                return L("error.netUnreachable")
            case .posix(.EPIPE), .posix(.ECONNRESET):
                return L("error.connReset")
            default:
                return e.localizedDescription
            }
        }
        return error.localizedDescription
    }

    /// TCP 端口通不通。桌面端可能因为端口被占没起 9501，
    /// 这时候要回落到 HTTP，而不是让用户干等一次连接超时。
    static func probe(host: String, port: Int = Ports.fastTCP,
                      timeout: TimeInterval = 2) async -> Bool {
        let opts = NWProtocolTCP.Options()
        opts.connectionTimeout = Int(timeout)
        let conn = NWConnection(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(integerLiteral: UInt16(port)),
            using: NWParameters(tls: nil, tcp: opts)
        )
        defer { conn.cancel() }

        return await withCheckedContinuation { (k: CheckedContinuation<Bool, Never>) in
            let done = OnceFlag()
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    if done.take() { k.resume(returning: true) }
                case .failed, .cancelled:
                    if done.take() { k.resume(returning: false) }
                default:
                    break
                }
            }
            conn.start(queue: .global(qos: .utility))
            // Network.framework 的 connectionTimeout 在某些网络下不触发，
            // 自己再兜一层，否则探测会一直挂着
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout + 1) {
                if done.take() { k.resume(returning: false) }
            }
        }
    }
}

// MARK: - 辅助

/// 服务端应答的字节流。
///
/// ATF3 的应答是二进制、长度不定（状态字节 + 可选的偏移量或错误文本），
/// 所以这里按「等够 n 个字节」来取，而不是像以前那样等一个换行 ——
/// 偏移量里出现 0x0A 是完全正常的，按换行切会把一个数字劈成两半。
private actor ReplyStream {
    private var buffer = Data()
    private var closed = false
    /// 每个等待者要多少字节
    private var waiters: [(need: Int, k: CheckedContinuation<Data, Error>)] = []

    var hasBytes: Bool { closed || !buffer.isEmpty }

    func append(_ d: Data) {
        guard !closed else { return }
        buffer.append(d)
        serve()
    }

    func finish() {
        guard !closed else { return }
        closed = true
        serve()
    }

    /// 取 n 个字节，不够就等。连接关了还不够就抛错。
    func take(_ n: Int, timeout: TimeInterval) async throws -> Data {
        if buffer.count >= n { return consume(n) }
        if closed { throw FastSendError(L("error.closedNoReply")) }

        let stream = self
        return try await withThrowingTaskGroup(of: Data.self) { group in
            group.addTask {
                try await withCheckedThrowingContinuation { k in
                    Task { await stream.enqueue(need: n, k: k) }
                }
            }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                throw FastSendError(L("error.replyTimeout"))
            }
            guard let first = try await group.next() else {
                throw FastSendError(L("error.replyTimeout"))
            }
            group.cancelAll()
            return first
        }
    }

    private func enqueue(need: Int, k: CheckedContinuation<Data, Error>) {
        waiters.append((need, k))
        serve()
    }

    private func serve() {
        while let w = waiters.first {
            if buffer.count >= w.need {
                waiters.removeFirst()
                w.k.resume(returning: consume(w.need))
            } else if closed {
                waiters.removeFirst()
                w.k.resume(throwing: FastSendError(L("error.closedNoReply")))
            } else {
                return
            }
        }
    }

    private func consume(_ n: Int) -> Data {
        let out = buffer.prefix(n)
        buffer.removeFirst(min(n, buffer.count))
        return Data(out)
    }
}

/// stateUpdateHandler / 超时兜底可能同时想恢复同一个 continuation，
/// 恢复两次会直接崩溃，所以用它保证只有第一个成功。
private final class OnceFlag: @unchecked Sendable {
    private var used = false
    private let lock = NSLock()

    func take() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if used { return false }
        used = true
        return true
    }
}
