import XCTest
@testable import DroidTrans

/// 取回方向的断点续传。
///
/// README、对比表、官网都写着「断点续传，两个方向都支持」。发送方向是真的，
/// 取回方向原来只是 download 上挂了个 resumeFrom 参数 —— 全项目没有一个
/// 调用方传过它，所以那句话在这个方向上是空的。
///
/// 这里起一个真的 HTTP 服务，只支持最小的 Range 语义，把整条链路跑一遍。
final class DownloadResumeTests: XCTestCase {

    private var server: RangeServer!
    private var dir: URL!

    override func setUp() async throws {
        dir = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("dl-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        server = try RangeServer()
    }

    override func tearDown() async throws {
        server?.stop()
        try? FileManager.default.removeItem(at: dir)
    }

    private func client() -> ApiClient {
        ApiClient(baseURL: "http://127.0.0.1:\(server.port)", token: "")
    }

    private func item(size: Int) -> OutboxItem {
        OutboxItem(id: "x", name: "video.mp4", size: Int64(size),
                   rel: "video.mp4", text: nil, taken: 0, addedAt: nil)
    }

    /// 主线：断在中途，再取一次只补剩下的，内容完整。
    func testResumesInsteadOfStartingOver() async throws {
        let payload = Data((0..<(300 * 1024)).map { UInt8($0 % 251) })
        server.body = payload
        let dest = dir.appendingPathComponent("video.mp4")

        // 第一次：服务端只给前 100KB 就断开
        server.cutAfter = 100 * 1024
        do {
            try await client().download(item(size: payload.count), to: dest)
            XCTFail("传了一半却报成功")
        } catch {
            // 断了是预期的
        }

        // 目标路径上绝不能出现半个文件
        XCTAssertFalse(FileManager.default.fileExists(atPath: dest.path),
                       "只取到一半，目标位置却已经有文件了 —— 用户会以为它是好的")

        // 第二次：完整供货，应当只要剩下的那段
        server.cutAfter = nil
        try await client().download(item(size: payload.count), to: dest)

        XCTAssertEqual(server.lastRangeStart, 100 * 1024,
                       "第二次没带 Range，或者偏移量不对 —— 那就是从头重取")
        XCTAssertEqual(try Data(contentsOf: dest), payload, "续出来的内容和原文件对不上")

        let leftovers = try FileManager.default.contentsOfDirectory(atPath: dir.path)
            .filter { $0.hasSuffix(".dtpart") }
        XCTAssertTrue(leftovers.isEmpty, "转正之后分片文件应该消失")
    }

    /// 服务端不认 Range（回 200 整份）时，不能把整份接到半截后面 ——
    /// 那样文件会比原件还长，而且中间一段是重复的。
    func testFallsBackCleanlyWhenServerIgnoresRange() async throws {
        let payload = Data(repeating: 0xAB, count: 64 * 1024)
        server.body = payload
        server.ignoreRange = true
        let dest = dir.appendingPathComponent("video.mp4")

        server.cutAfter = 20 * 1024
        _ = try? await client().download(item(size: payload.count), to: dest)
        server.cutAfter = nil
        try await client().download(item(size: payload.count), to: dest)

        XCTAssertEqual(try Data(contentsOf: dest), payload)
    }

    /// 进度要从已有的字节接着报。从 0 重爬会让用户以为又从头传了。
    func testProgressContinuesFromWhatWeAlreadyHave() async throws {
        let payload = Data(repeating: 7, count: 200 * 1024)
        server.body = payload
        let dest = dir.appendingPathComponent("video.mp4")

        server.cutAfter = 80 * 1024
        _ = try? await client().download(item(size: payload.count), to: dest)

        // 断点具体落在第几个字节由 socket 缓冲决定，不能写死 ——
        // 要断言的是「从已有的地方接着报」，不是「恰好断在 80KB」
        let already = try XCTUnwrap(
            FileManager.default.contentsOfDirectory(atPath: dir.path)
                .first { $0.hasSuffix(".dtpart") }
                .map { dir.appendingPathComponent($0) }
                .flatMap { try? FileManager.default.attributesOfItem(atPath: $0.path) }
                .flatMap { ($0[.size] as? NSNumber)?.int64Value },
            "第一次断开后什么都没留下 —— 那就根本无从续起")
        XCTAssertGreaterThan(already, 0)

        server.cutAfter = nil
        let first = UnsafeProgressBox()
        try await client().download(item(size: payload.count), to: dest) { got, _ in
            first.record(got)
        }
        XCTAssertGreaterThanOrEqual(first.firstValue ?? 0, already,
                                    "续传时进度从 0 重新开始了")
    }
}

/// 回调在别的线程上来，用个小盒子接住第一次的值。
private final class UnsafeProgressBox: @unchecked Sendable {
    private let lock = NSLock()
    private(set) var firstValue: Int64?
    func record(_ v: Int64) {
        lock.lock(); defer { lock.unlock() }
        if firstValue == nil { firstValue = v }
    }
}

/// 一个只够用的 HTTP 服务：支持 `Range: bytes=N-`，可以故意在中途断开。
private final class RangeServer: @unchecked Sendable {
    private let listener: Int32
    let port: UInt16

    /// 要供的内容
    var body = Data()
    /// 非 nil 时只发这么多字节就断开，模拟传到一半掉线
    var cutAfter: Int?
    /// 装作不认 Range，永远回 200 整份
    var ignoreRange = false
    /// 最后一次请求要的起始偏移量
    private(set) var lastRangeStart: Int = 0

    private var running = true

    init() throws {
        // 先用局部变量把 socket 建好，最后再赋给属性 ——
        // 属性还没全部初始化完就在闭包里碰 self，编译器不让过
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        var yes: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = 0
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        let ok = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard ok == 0, listen(fd, 8) == 0 else {
            close(fd)
            throw NSError(domain: "RangeServer", code: 1)
        }
        var got = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &got) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(fd, $0, &len)
            }
        }
        listener = fd
        port = got.sin_port.bigEndian
        Thread.detachNewThread { [weak self] in self?.loop() }
    }

    func stop() {
        running = false
        close(listener)
    }

    private func loop() {
        while running {
            let fd = accept(listener, nil, nil)
            if fd < 0 { return }
            serve(fd)
            close(fd)
        }
    }

    private func serve(_ fd: Int32) {
        var head = ""
        var buf = [UInt8](repeating: 0, count: 1)
        while !head.hasSuffix("\r\n\r\n") {
            let n = read(fd, &buf, 1)
            if n <= 0 { return }
            head.append(Character(UnicodeScalar(buf[0])))
            if head.count > 8192 { return }
        }

        var start = 0
        if !ignoreRange, let r = head.range(of: "Range: bytes=") {
            let tail = head[r.upperBound...].prefix { $0.isNumber }
            start = Int(tail) ?? 0
        }
        lastRangeStart = start

        let slice = body.suffix(from: min(start, body.count))
        let sendLen = min(cutAfter ?? slice.count, slice.count)
        let header: String
        if start > 0 && !ignoreRange {
            header = """
            HTTP/1.1 206 Partial Content\r
            Content-Length: \(slice.count)\r
            Content-Range: bytes \(start)-\(body.count - 1)/\(body.count)\r
            \r\n
            """
        } else {
            header = "HTTP/1.1 200 OK\r\nContent-Length: \(body.count)\r\n\r\n"
        }
        _ = header.utf8CString.withUnsafeBufferPointer {
            write(fd, $0.baseAddress, strlen($0.baseAddress!))
        }
        let out = [UInt8](slice.prefix(sendLen))
        _ = out.withUnsafeBufferPointer { write(fd, $0.baseAddress, out.count) }
        // cutAfter 时故意不发完就关，客户端应当认定没收全
    }
}
