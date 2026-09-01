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
        req.setValue("\(size)", forHTTPHeaderField: "Content-Length")
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

    /// 从电脑取一条。resumeFrom 大于 0 时带 Range 续传。
    ///
    /// 注意：桌面端只在「不带 Range 的完整请求」结束后才把条目标记为已取走，
    /// 所以续传下来的文件要另外调 removeOutbox 收尾，否则它会一直挂在清单上。
    nonisolated func download(
        _ item: OutboxItem,
        to dest: URL,
        resumeFrom: Int64 = 0,
        onProgress: (@Sendable (Int64, Int64) -> Void)? = nil
    ) async throws {
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

        let reporter = ProgressReporter(onProgress: onProgress)
        let session = URLSession(configuration: .ephemeral, delegate: reporter, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }

        let (tmp, resp) = try await session.download(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0

        if code == 410 { throw ApiError(L("error.gone"), statusCode: code) }
        if code == 404 { throw ApiError(L("error.notInList"), statusCode: code) }
        guard (200..<300).contains(code) else { throw ApiError("HTTP \(code)", statusCode: code) }

        let fm = FileManager.default
        try? fm.createDirectory(at: dest.deletingLastPathComponent(),
                                withIntermediateDirectories: true)

        if resumeFrom > 0, code == 206, fm.fileExists(atPath: dest.path) {
            // 服务端认了 Range，把新收到的接到已有文件后面
            let handle = try FileHandle(forWritingTo: dest)
            defer { try? handle.close() }
            try handle.seekToEnd()
            try handle.write(contentsOf: Data(contentsOf: tmp))
            try? fm.removeItem(at: tmp)
        } else {
            // 服务端忽略了 Range（回 200），那就是整个文件，覆盖掉半截的旧数据
            try? fm.removeItem(at: dest)
            try fm.moveItem(at: tmp, to: dest)
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
