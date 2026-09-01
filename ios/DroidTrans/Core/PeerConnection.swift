/// 接收方的 HTTP 那一层：一条连接从收到第一个字节到回完应答。
///
/// 为什么自己写而不是拉个 HTTP 库：要实现的只有六个口，而其中最要紧的
/// PUT /api/fast/put 是「一边收一边往磁盘写」—— 手机内存装不下一个 4GB 的视频，
/// 大多数现成的轻量库都会先把 body 收进内存再交给你。
///
/// 只说 HTTP/1.1，一条连接处理一个请求就关（Connection: close）。
/// URLSession 那侧完全接受这种做法，省掉一整套 keep-alive 的状态机。

import Foundation
import Network

final class PeerConnection {
    private let nw: NWConnection
    private weak var server: PeerServer?
    private let queue: DispatchQueue

    /// 已收到但还没消费掉的字节。头读完之后，剩下的就是 body 的开头。
    private var buf = Data()
    private var closed = false

    init(nw: NWConnection, server: PeerServer, queue: DispatchQueue) {
        self.nw = nw
        self.server = server
        self.queue = queue
    }

    func start() {
        nw.start(queue: queue)
        Task { await run() }
    }

    func cancel() {
        guard !closed else { return }
        closed = true
        nw.cancel()
    }

    // MARK: - 主流程

    private func run() async {
        defer {
            cancel()
            if let server {
                Task { @MainActor in server.drop(self) }
            }
        }

        do {
            guard let head = try await readHead() else { return }
            try await handle(head)
        } catch {
            // 对面拔线、超时、格式不对 —— 一条连接的事，不影响监听继续跑
        }
    }

    /// 读到 \r\n\r\n 为止。超过 64KB 还没读完就当它不是正经请求。
    private func readHead() async throws -> Head? {
        while true {
            if let r = buf.range(of: Data("\r\n\r\n".utf8)) {
                let raw = buf.subdata(in: buf.startIndex..<r.lowerBound)
                buf.removeSubrange(buf.startIndex..<r.upperBound)
                return Head(raw: raw)
            }
            guard buf.count < 64 * 1024, let more = try await recv() else { return nil }
            buf.append(more)
        }
    }

    private func handle(_ h: Head) async throws {
        guard let server else { return }

        // 不在开放名单里的口，先验令牌。和 Go 端 openPath() 一一对应。
        if !kOpenPaths.contains(h.path) {
            let tok = h.header(kTokenHeader) ?? ""
            let ok = await MainActor.run { server.valid(tok) }
            guard ok else {
                try await sendJSON(403, ["success": false, "error": L("pair.required"),
                                         "pairing_required": true])
                return
            }
        }

        switch (h.method, h.path) {
        case ("GET", "/api/health"):
            let name = await MainActor.run { server.deviceName }
            try await sendJSON(200, ["ok": true, "app": "droidtrans",
                                     "engine": "swift", "name": name,
                                     "version": appVersion])

        case ("GET", "/api/wifi/info"), ("GET", "/api/fast/caps"):
            try await sendJSON(200, await info())

        case ("POST", "/api/pair"):
            let body = try await readBody(h)
            let j = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] ?? [:]
            let code = (j["code"] as? String ?? "").trimmingCharacters(in: .whitespaces)
            let who = j["device_name"] as? String ?? L("peer.someone")

            // 不带码 = 敲门。这条连接就挂在这儿，等这台的主人点「同意」。
            // 对面那侧看到的是「正在等对方确认」，不是一个要填的输入框。
            let tok: String?
            if code.isEmpty {
                tok = await server.requestApproval(from: who)
            } else {
                tok = await MainActor.run { server.pair(code: code) }
            }

            if let tok {
                try await sendJSON(200, ["success": true, "token": tok])
            } else {
                try await sendJSON(403, ["success": false, "error": L("pair.wrongCode")])
            }

        case ("GET", "/api/outbox"):
            // 这一侧只收不发，清单永远是空的。
            // 发送方拿这个口验令牌还好不好用，所以它必须存在且回 200。
            try await sendJSON(200, ["success": true, "items": [],
                                     "count": 0, "total_size": 0])

        case ("PUT", "/api/fast/put"):
            try await receiveFile(h)

        case ("POST", "/api/inbox/text"):
            let body = try await readBody(h)
            let j = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] ?? [:]
            let text = j["text"] as? String ?? ""
            try await saveText(text)
            try await sendJSON(200, ["success": true])

        default:
            try await sendJSON(404, ["success": false, "error": "not found"])
        }
    }

    // MARK: - 各个口

    private func info() async -> [String: Any] {
        guard let server else { return [:] }
        let (name, ip) = await MainActor.run { (server.deviceName, server.localIP) }
        return [
            "success": true,
            "name": name,
            "ip": ip ?? "",
            "ips": [ip].compactMap { $0 },
            "port": Ports.peer,
            "pairing_required": true,
            // 告诉对面「点一下同意就行，别让人输码」。
            // 桌面端不发这个字段，所以老流程完全不受影响。
            "pairing_mode": "approve",
            // 手机这侧只实现了 HTTP PUT。ATF2 裸流和 FTP 是桌面端才有的加速，
            // 少报一个通道，发送方会自己降到 PUT，不会失败。
            "prefer": [FastProtocol.httpPut.rawValue],
            "outbox_count": 0,
            "outbox_size": 0,
            "on_hotspot": false,
            "device_count": 0,
            "connected_devices": [],
        ]
    }

    /// 收文件。一边收一边落盘，不在内存里攒。
    private func receiveFile(_ h: Head) async throws {
        guard let server else { return }

        let raw = h.header("X-Relative-Path") ?? h.header("X-Filename") ?? "file"
        let name = sanitize(raw.removingPercentEncoding ?? raw)
        let total = Int64(h.header("X-File-Size") ?? "") ?? h.contentLength

        let dir = await MainActor.run { server.inboxDir }
        let dest = uniquePath(in: dir, name: name)

        FileManager.default.createFile(atPath: dest.path, contents: nil)
        guard let fh = try? FileHandle(forWritingTo: dest) else {
            try await sendJSON(500, ["success": false, "error": "cannot open file"])
            return
        }
        defer { try? fh.close() }

        var written: Int64 = 0
        // 头读完时 buf 里往往已经躺着 body 的开头，先把它写掉
        if !buf.isEmpty {
            let take = min(Int64(buf.count), total)
            try fh.write(contentsOf: buf.prefix(Int(take)))
            buf.removeFirst(Int(take))
            written += take
        }
        while written < total {
            guard let chunk = try await recv() else { break }
            let take = min(Int64(chunk.count), total - written)
            try fh.write(contentsOf: chunk.prefix(Int(take)))
            written += take
        }

        guard written == total else {
            try? FileManager.default.removeItem(at: dest)
            try await sendJSON(400, ["success": false, "error": L("peer.err.truncated")])
            return
        }

        let done = PeerServer.ReceivedFile(name: dest.lastPathComponent, size: written, url: dest)
        await MainActor.run { server.note(done) }
        try await sendJSON(200, ["success": true, "skipped": false, "size": written])
    }

    private func saveText(_ text: String) async throws {
        guard let server, !text.isEmpty else { return }
        let dir = await MainActor.run { server.inboxDir }
        let dest = uniquePath(in: dir, name: "text-\(Int(Date().timeIntervalSince1970)).txt")
        try? text.data(using: .utf8)?.write(to: dest)
        let done = PeerServer.ReceivedFile(name: dest.lastPathComponent,
                                           size: Int64(text.utf8.count), url: dest)
        await MainActor.run { server.note(done) }
    }

    // MARK: - 收发

    private func readBody(_ h: Head) async throws -> Data {
        let need = Int(h.contentLength)
        while buf.count < need {
            guard let more = try await recv() else { break }
            buf.append(more)
        }
        let n = min(need, buf.count)
        let out = buf.prefix(n)
        buf.removeFirst(n)
        return Data(out)
    }

    private func sendJSON(_ status: Int, _ obj: [String: Any]) async throws {
        let body = (try? JSONSerialization.data(withJSONObject: obj)) ?? Data("{}".utf8)
        var head = "HTTP/1.1 \(status) \(reason(status))\r\n"
        head += "Content-Type: application/json; charset=utf-8\r\n"
        head += "Content-Length: \(body.count)\r\n"
        head += "Connection: close\r\n\r\n"
        try await send(Data(head.utf8) + body)
    }

    private func reason(_ s: Int) -> String {
        switch s {
        case 200: return "OK"
        case 400: return "Bad Request"
        case 403: return "Forbidden"
        case 404: return "Not Found"
        default:  return "Internal Server Error"
        }
    }

    private func recv() async throws -> Data? {
        try await withCheckedThrowingContinuation { k in
            nw.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, done, err in
                if let err { k.resume(throwing: err); return }
                if let data, !data.isEmpty { k.resume(returning: data); return }
                k.resume(returning: done ? nil : Data())
            }
        }
    }

    private func send(_ d: Data) async throws {
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            nw.send(content: d, completion: .contentProcessed { err in
                if let err { k.resume(throwing: err) } else { k.resume() }
            })
        }
    }

    // MARK: - 小工具

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
    }

    /// 文件名不能带路径分隔符 —— 对面传一个 "../../x" 过来，
    /// 不拦的话就写到 Inbox 外面去了。
    private func sanitize(_ s: String) -> String {
        let flat = s.replacingOccurrences(of: "\\", with: "/")
            .split(separator: "/").last.map(String.init) ?? "file"
        let bad = CharacterSet(charactersIn: ":\0")
        let cleaned = flat.components(separatedBy: bad).joined()
        return cleaned.isEmpty || cleaned == "." || cleaned == ".." ? "file" : cleaned
    }

    /// 同名文件不覆盖，接一个 (2)。收东西的场景里，
    /// 悄悄盖掉用户上一次收到的文件是不可接受的。
    private func uniquePath(in dir: URL, name: String) -> URL {
        let fm = FileManager.default
        var candidate = dir.appendingPathComponent(name)
        guard fm.fileExists(atPath: candidate.path) else { return candidate }

        let ext = candidate.pathExtension
        let stem = candidate.deletingPathExtension().lastPathComponent
        var i = 2
        repeat {
            let n = ext.isEmpty ? "\(stem) (\(i))" : "\(stem) (\(i)).\(ext)"
            candidate = dir.appendingPathComponent(n)
            i += 1
        } while fm.fileExists(atPath: candidate.path)
        return candidate
    }
}

// MARK: - 请求头

/// 一个请求的起始行加头部。body 不在这里，由调用方按 Content-Length 去读。
struct Head {
    let method: String
    let path: String
    private let fields: [String: String]

    init(raw: Data) {
        let text = String(decoding: raw, as: UTF8.self)
        var lines = text.components(separatedBy: "\r\n")
        let request = lines.isEmpty ? "" : lines.removeFirst()
        let parts = request.split(separator: " ", maxSplits: 2).map(String.init)

        method = parts.first ?? ""
        // 查询串这边一个口都用不上，切掉，路由只看路径
        let target = parts.count > 1 ? parts[1] : "/"
        path = target.split(separator: "?", maxSplits: 1).first.map(String.init) ?? "/"

        var f: [String: String] = [:]
        for line in lines {
            guard let i = line.firstIndex(of: ":") else { continue }
            let k = line[line.startIndex..<i].trimmingCharacters(in: .whitespaces).lowercased()
            let v = line[line.index(after: i)...].trimmingCharacters(in: .whitespaces)
            f[k] = v
        }
        fields = f
    }

    func header(_ name: String) -> String? { fields[name.lowercased()] }

    var contentLength: Int64 { Int64(header("Content-Length") ?? "") ?? 0 }
}
