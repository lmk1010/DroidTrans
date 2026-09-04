/// 文件的上传与下载。
///
/// 和 ApiClient 里那些小接口分开放：这两条都要带进度、都不能受
/// 常规超时约束（传一个 4GB 的视频要几分钟），配置和别的请求不一样。

import Foundation

extension ApiClient {

    /// HTTP PUT 上传，TCP 走不通时的回落通道。
    ///
    /// 桌面端按 X-File-Size 做秒传判断：同名同大小会直接跳过不重传，
    /// 所以这个头必须给准。返回 true 表示电脑上已经有了，没有真的传。
    nonisolated func putFile(
        _ fileURL: URL,
        remoteName: String,
        deviceId: String,
        relativePath: String? = nil,
        onProgress: (@Sendable (Int64, Int64) -> Void)? = nil
    ) async throws -> Bool {
        let size = (try? FileManager.default.attributesOfItem(atPath: fileURL.path)[.size] as? NSNumber)??.int64Value ?? 0

        // 断了重来只补剩下的。
        //
        // 这条路以前是从头重传：手机互传（对面是另一台手机）走的就是它，
        // 而对比表和官网都写着「断点续传」—— 那条承诺在这条路上不成立。
        //
        // 小文件不问：一次往返的代价比重传还大。8 MB 以下直接整份发。
        var offset: Int64 = 0
        if size > 8 << 20 {
            let (o, done) = await resumeOffset(remoteName: relativePath ?? remoteName, size: size)
            if done { return true }
            offset = o
        }

        // offset 为 0 时直接发原文件，不做任何拷贝。
        // 需要续传才切出剩余部分 —— 那部分本来就是还要发的量。
        var bodyURL = fileURL
        var temp: URL?
        if offset > 0, offset < size {
            guard let slice = try? Self.sliceTail(of: fileURL, from: offset) else {
                offset = 0
                return try await putWhole(fileURL, size: size, offset: 0,
                                          remoteName: remoteName, deviceId: deviceId,
                                          relativePath: relativePath, onProgress: onProgress)
            }
            bodyURL = slice
            temp = slice
        }
        defer { if let temp { try? FileManager.default.removeItem(at: temp) } }

        return try await putWhole(bodyURL, size: size, offset: offset,
                                  remoteName: remoteName, deviceId: deviceId,
                                  relativePath: relativePath, onProgress: onProgress)
    }

    /// 问对面已经收到多少字节。失败就当从头传 —— 续传是优化，不该变成新的失败点。
    nonisolated func resumeOffset(remoteName: String, size: Int64) async -> (Int64, Bool) {
        let q = "name=\(remoteName.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? remoteName)&size=\(size)"
        guard let url = URL(string: await baseURL + "/api/fast/offset?" + q) else { return (0, false) }
        var req = URLRequest(url: url)
        let tok = await currentToken()
        if !tok.isEmpty { req.setValue(tok, forHTTPHeaderField: kTokenHeader) }
        req.timeoutInterval = 8
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              (200..<300).contains((resp as? HTTPURLResponse)?.statusCode ?? 0),
              let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return (0, false)
        }
        let off = (j["offset"] as? NSNumber)?.int64Value ?? 0
        return (max(0, min(off, size)), j["complete"] as? Bool == true)
    }

    /// 把 fileURL 整个发出去，声明的总大小是 size、起点是 offset。
    private nonisolated func putWhole(
        _ fileURL: URL,
        size: Int64,
        offset: Int64,
        remoteName: String,
        deviceId: String,
        relativePath: String?,
        onProgress: (@Sendable (Int64, Int64) -> Void)?
    ) async throws -> Bool {
        guard let url = URL(string: await baseURL + "/api/fast/put") else {
            throw ApiError(L("error.badAddress", "/api/fast/put"))
        }
        var req = URLRequest(url: url)
        req.httpMethod = "PUT"
        let tok = await currentToken()
        if !tok.isEmpty { req.setValue(tok, forHTTPHeaderField: kTokenHeader) }
        // 文件名可能有中文和空格，必须转义；桌面端会解回来
        req.setValue(remoteName.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? remoteName,
                     forHTTPHeaderField: "X-Filename")
        req.setValue(relativePath ?? remoteName, forHTTPHeaderField: "X-Relative-Path")
        req.setValue(deviceId, forHTTPHeaderField: "X-Device-Id")
        req.setValue("\(size)", forHTTPHeaderField: "X-File-Size")
        if offset > 0 { req.setValue("\(offset)", forHTTPHeaderField: "X-Start-Offset") }
        let bodyLen = (try? FileManager.default.attributesOfItem(atPath: fileURL.path)[.size] as? NSNumber)??.int64Value ?? size
        req.setValue("\(bodyLen)", forHTTPHeaderField: "Content-Length")
        // 大文件不能受常规超时约束
        req.timeoutInterval = 3600

        let reporter = ProgressReporter(onProgress: onProgress)
        let session = URLSession(configuration: .ephemeral, delegate: reporter, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }

        let (data, resp) = try await session.upload(for: req, fromFile: fileURL)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]

        guard (200..<300).contains(code) else {
            throw ApiError(json["error"] as? String ?? "HTTP \(code)", statusCode: code)
        }
        if json["success"] as? Bool == false {
            throw ApiError(json["error"] as? String ?? "HTTP \(code)", statusCode: code)
        }
        return json["skipped"] as? Bool == true
    }

    /// 把文件从 offset 之后的部分切成一个临时文件。
    ///
    /// URLSession 的 upload(fromFile:) 只能整份发，要续传就得先把剩余部分
    /// 单独拿出来。用 Data(mappedIfSafe:) 而不是整份读进内存 ——
    /// 4 GB 的视频读进内存会直接被系统杀掉。
    static func sliceTail(of url: URL, from offset: Int64) throws -> URL {
        let src = try FileHandle(forReadingFrom: url)
        defer { try? src.close() }
        try src.seek(toOffset: UInt64(offset))

        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("dt-resume-\(UUID().uuidString)")
        FileManager.default.createFile(atPath: tmp.path, contents: nil)
        let dst = try FileHandle(forWritingTo: tmp)
        defer { try? dst.close() }

        while let chunk = try src.read(upToCount: 4 << 20), !chunk.isEmpty {
            try dst.write(contentsOf: chunk)
        }
        return tmp
    }

    /// 从电脑取一条。
    ///
    /// 落盘纪律和发送方向一致：字节先进 `.dtpart` 分片，收全了才改名到目标位置。
    /// 目标路径上永远不会出现半个文件 —— 用户在「文件」App 里看到它，
    /// 它就是完整的。
    ///
    /// 断了再取时自动从分片的末尾接着要（HTTP Range）。调用方不用自己算偏移量：
    /// 之前 resumeFrom 是个参数，结果一个调用方都没传过，
    /// 「两个方向都支持断点续传」这句话在取回方向上其实是空的。
    ///
    /// 注意：桌面端只在「不带 Range 的完整请求」结束后才把条目标记为已取走，
    /// 所以续传下来的文件要另外调 removeOutbox 收尾，否则它会一直挂在清单上。
    nonisolated func download(
        _ item: OutboxItem,
        to dest: URL,
        onProgress: (@Sendable (Int64, Int64) -> Void)? = nil
    ) async throws {
        let fm = FileManager.default
        try? fm.createDirectory(at: dest.deletingLastPathComponent(),
                                withIntermediateDirectories: true)

        // 大小写进分片名：同名不同大小是两个文件，续错了内容会静默损坏
        let part = dest.deletingLastPathComponent()
            .appendingPathComponent(".\(dest.lastPathComponent).\(item.size).dtpart")

        var resumeFrom = (try? fm.attributesOfItem(atPath: part.path))
            .flatMap { ($0[.size] as? NSNumber)?.int64Value } ?? 0
        if item.size > 0, resumeFrom > item.size {
            // 分片比文件还长，说明它根本不是这个文件的，重来
            try? fm.removeItem(at: part)
            resumeFrom = 0
        }
        if item.size > 0, resumeFrom == item.size {
            // 上次其实已经收全了，只是没来得及改名
            try? fm.removeItem(at: dest)
            try fm.moveItem(at: part, to: dest)
            onProgress?(item.size, item.size)
            return
        }

        let encoded = item.id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? item.id
        guard let url = URL(string: await baseURL + "/api/outbox/file/" + encoded) else {
            throw ApiError(L("error.badAddress", "/api/outbox/file"))
        }
        var req = URLRequest(url: url)
        let tok = await currentToken()
        if !tok.isEmpty { req.setValue(tok, forHTTPHeaderField: kTokenHeader) }
        if resumeFrom > 0 {
            req.setValue("bytes=\(resumeFrom)-", forHTTPHeaderField: "Range")
        }
        req.timeoutInterval = 3600

        let sink = DownloadSink(part: part, resumeFrom: resumeFrom,
                                whole: item.size, onProgress: onProgress)
        let session = URLSession(configuration: .ephemeral, delegate: sink, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }

        let code: Int
        do {
            code = try await sink.run(session: session, request: req)
        } catch {
            // 连接断了。已经落到分片里的字节留着，下次从那儿接着要 ——
            // 这正是「断点续传」在这个方向上唯一能成立的方式。
            throw error
        }

        if code == 410 { throw ApiError(L("error.gone"), statusCode: code) }
        if code == 404 { throw ApiError(L("error.notInList"), statusCode: code) }
        guard (200..<300).contains(code) else { throw ApiError("HTTP \(code)", statusCode: code) }

        let have = (try? fm.attributesOfItem(atPath: part.path))
            .flatMap { ($0[.size] as? NSNumber)?.int64Value } ?? 0
        if item.size > 0, have != item.size {
            // 分片留着，下次接着取。这里删掉就等于让用户从头再来一遍。
            throw ApiError(L("peer.err.truncated"))
        }
        try? fm.removeItem(at: dest)
        try fm.moveItem(at: part, to: dest)
    }
}

/// 边收边落盘。
///
/// 不能用 `URLSession.download(for:)`：连接中途断掉时它会把已下载的临时文件
/// 直接丢弃，一个字节都不留下 —— 那「断点续传」在取回方向上就永远只是
/// 「从头再来一遍」。所以自己拿 dataTask，收到多少写多少。
///
/// 顺带解决了内存：原来续传是把剩余部分整个 `Data(contentsOf:)` 读进来再追加，
/// 取一个 4 GB 的视频时手机会直接被系统杀掉 —— 而大文件恰恰是 Pro 卖的能力。
private final class DownloadSink: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let part: URL
    private let resumeFrom: Int64
    private let whole: Int64
    private let onProgress: (@Sendable (Int64, Int64) -> Void)?

    private let lock = NSLock()
    private var handle: FileHandle?
    private var written: Int64 = 0
    private var status = 0
    private var cont: CheckedContinuation<Int, Error>?
    private var finished = false

    init(part: URL, resumeFrom: Int64, whole: Int64,
         onProgress: (@Sendable (Int64, Int64) -> Void)?) {
        self.part = part
        self.resumeFrom = resumeFrom
        self.whole = whole
        self.onProgress = onProgress
    }

    func run(session: URLSession, request: URLRequest) async throws -> Int {
        try await withCheckedThrowingContinuation { c in
            lock.lock()
            cont = c
            lock.unlock()
            session.dataTask(with: request).resume()
        }
    }

    private func settle(_ result: Result<Int, Error>) {
        lock.lock()
        let c = cont
        cont = nil
        let already = finished
        finished = true
        try? handle?.close()
        handle = nil
        lock.unlock()
        guard !already, let c else { return }
        c.resume(with: result)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                    didReceive response: URLResponse) async -> URLSession.ResponseDisposition {
        status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            // 404/410 之类：不要碰分片，让上层去解释状态码
            return .allow
        }

        let fm = FileManager.default
        do {
            if !fm.fileExists(atPath: part.path) {
                fm.createFile(atPath: part.path, contents: nil)
            }
            let h = try FileHandle(forWritingTo: part)
            if status == 206, resumeFrom > 0 {
                // 服务端认了 Range，接到分片后面
                try h.seek(toOffset: UInt64(resumeFrom))
                try h.truncate(atOffset: UInt64(resumeFrom))
                written = resumeFrom
            } else {
                // 回的是整份，盖掉半截的旧分片 —— 直接追加会让文件比原件还长，
                // 中间一段是重复的，内容静默损坏
                try h.truncate(atOffset: 0)
                written = 0
            }
            lock.lock(); handle = h; lock.unlock()
        } catch {
            settle(.failure(error))
            return .cancel
        }
        return .allow
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        let h = handle
        lock.unlock()
        guard let h else { return }
        do {
            try h.write(contentsOf: data)
            written += Int64(data.count)
            onProgress?(written, whole > 0 ? whole : written)
        } catch {
            settle(.failure(error))
            dataTask.cancel()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error {
            settle(.failure(error))
        } else {
            settle(.success(status))
        }
    }
}


/// URLSession 的进度回调只有 delegate 这一条路。
private final class ProgressReporter: NSObject, URLSessionTaskDelegate, URLSessionDownloadDelegate {
    private let onProgress: (@Sendable (Int64, Int64) -> Void)?

    init(onProgress: (@Sendable (Int64, Int64) -> Void)?) {
        self.onProgress = onProgress
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    didSendBodyData bytesSent: Int64,
                    totalBytesSent: Int64,
                    totalBytesExpectedToSend: Int64) {
        onProgress?(totalBytesSent, totalBytesExpectedToSend)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64,
                    totalBytesExpectedToWrite: Int64) {
        onProgress?(totalBytesWritten, totalBytesExpectedToWrite)
    }

    /// async download(for:) 自己会处理落地，这里什么都不用做，
    /// 但不实现这个方法的话 URLSessionDownloadDelegate 不合规。
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didFinishDownloadingTo location: URL) {}
}
