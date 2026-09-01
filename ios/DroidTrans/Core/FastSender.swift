/// ATF2 快传：直连桌面端 TCP 9501，把文件裸流推过去。
///
/// 走这条路而不是 HTTP multipart，是因为大文件（几十 GB 的视频）
/// 在 multipart 上会多一层编码和内存拷贝，速度差得很明显。

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
    var errorDescription: String? { message }
    init(_ m: String) { message = m }
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

        let header = try buildATF2Header(name: name, size: size, token: token)

        let conn = try await connect()
        // 无论成败都要关，不然连接会挂到超时
        defer { conn.cancel() }

        let reply = ReplyWatcher()
        // 应答要在写之前就开始收：服务端校验失败时会立刻回 ERR 并关连接，
        // 等写完再读的话，这中间的写入会先炸成 broken pipe，真正的原因就丢了。
        startReceiving(conn, into: reply)

        try await send(conn, data: header)

        guard let handle = try? FileHandle(forReadingFrom: fileURL) else {
            throw FastSendError(L("error.cantOpenFile", name))
        }
        defer { try? handle.close() }

        var sent: Int64 = 0
        onProgress?(SendProgress(sent: 0, total: size))

        while true {
            try Task.checkCancellation()

            // 服务端在传输中途回话只有一种情况：它拒绝了（令牌不对、路径非法…）。
            // 它回完就关连接，这时候继续写只会拿到 broken pipe，
            // 把真正的原因盖掉。停下来去读那句 ERR。
            if await reply.hasReply { break }

            let chunk = handle.readData(ofLength: Self.chunkSize)
            if chunk.isEmpty { break }

            do {
                try await send(conn, data: chunk)
            } catch {
                // 连接已经断了。服务端在断开前说过话的话，那句话才是真原因。
                if await reply.hasReply { break }
                throw error
            }
            sent += Int64(chunk.count)
            onProgress?(SendProgress(sent: sent, total: size))
        }

        let raw = try await reply.wait(timeout: 30)
        if let err = parseATFReply(raw) {
            throw FastSendError(err)
        }
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

    private nonisolated func startReceiving(_ conn: NWConnection, into reply: ReplyWatcher) {
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

/// 服务端的应答。收到换行就算一条完整的回复。
private actor ReplyWatcher {
    private var buffer = Data()
    private var done = false
    private var waiters: [CheckedContinuation<String, Never>] = []

    var hasReply: Bool { done || buffer.contains(0x0A) }

    func append(_ d: Data) {
        guard !done else { return }
        buffer.append(d)
        if buffer.contains(0x0A) { flush() }
    }

    func finish() {
        guard !done else { return }
        flush()
    }

    private func flush() {
        done = true
        let text = String(data: buffer, encoding: .utf8) ?? ""
        for w in waiters { w.resume(returning: text) }
        waiters.removeAll()
    }

    func wait(timeout: TimeInterval) async throws -> String {
        if done { return String(data: buffer, encoding: .utf8) ?? "" }

        let watcher = self
        return try await withThrowingTaskGroup(of: String.self) { group in
            group.addTask {
                await withCheckedContinuation { (k: CheckedContinuation<String, Never>) in
                    Task { await watcher.enqueue(k) }
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

    private func enqueue(_ k: CheckedContinuation<String, Never>) {
        if done {
            k.resume(returning: String(data: buffer, encoding: .utf8) ?? "")
        } else {
            waiters.append(k)
        }
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
