import XCTest
@testable import DroidTrans

/// 手机当接收方那一半。
///
/// 这些用例会真的把 PeerServer 跑起来、真的用 URLSession 打它 ——
/// 因为这一半最容易错的地方不在纯函数里，而在「HTTP 头解析对不对」
/// 「不带码的请求会不会挂住」这种只有真跑一遍才暴露的地方。
///
/// 端口用 Ports.peer，和线上一致。CI 上并行跑测试时可能撞端口，
/// 所以每个用例跑完都 stop()。
@MainActor
final class PeerServerTests: XCTestCase {

    private var server: PeerServer!

    override func setUp() async throws {
        server = PeerServer.shared
        server.start()
        try await waitUntilUp()
    }

    override func tearDown() async throws {
        server.stop()
        server = nil
    }

    private var base: String { "http://127.0.0.1:\(Ports.peer)" }

    /// 监听是异步就绪的，起来之前打它会连接被拒。
    ///
    /// 等得比直觉长：上一个用例刚 stop()，同一个端口要过一会儿才能再绑上，
    /// 等太短的话「测试之间互相干扰」会被误读成「服务端起不来」。
    private func waitUntilUp() async throws {
        for _ in 0..<200 {
            if server.running { return }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTFail("PeerServer 没起来：\(server.lastError ?? "无错误信息")")
    }

    // MARK: - 免配对的那几个口

    func testHealthIdentifiesItselfAsDroidTrans() async throws {
        let j = try await get("/api/health")
        XCTAssertEqual(j["ok"] as? Bool, true)
        // 发送方就靠这两个字段确认「那头是卓传」，改了它们所有客户端都连不上
        XCTAssertEqual(j["app"] as? String, "droidtrans")
        XCTAssertEqual(j["engine"] as? String, "swift")
    }

    /// 这条是整个「不用输码」体验的开关：发送方看到 approve 才会走敲门，
    /// 看不到就退回让用户抄六位码。
    func testInfoAdvertisesApprovePairing() async throws {
        let j = try await get("/api/wifi/info")
        XCTAssertEqual(j["pairing_mode"] as? String, "approve")
        XCTAssertEqual(j["pairing_required"] as? Bool, true)
        XCTAssertEqual(j["port"] as? Int, Ports.peer)
        // 手机这侧只实现了 HTTP PUT，多报一条通道会让发送方走上死路
        XCTAssertEqual(j["prefer"] as? [String], ["http_put"])
    }

    // MARK: - 令牌

    func testProtectedPathRejectsMissingToken() async throws {
        let (j, code) = try await request("/api/outbox", method: "GET", token: nil)
        XCTAssertEqual(code, 403)
        XCTAssertEqual(j["pairing_required"] as? Bool, true)
    }

    func testWrongCodeGetsNoToken() async throws {
        let (j, code) = try await pair(code: "000000")
        XCTAssertEqual(code, 403)
        XCTAssertNil(j["token"])
    }

    func testRightCodeStillWorksAsFallback() async throws {
        // 码没被废掉，只是退成兜底：手输地址、组播被拦时还得靠它
        let (j, code) = try await pair(code: server.pairingCode)
        XCTAssertEqual(code, 200)
        XCTAssertFalse((j["token"] as? String ?? "").isEmpty)
    }

    // MARK: - 敲门

    /// 不带码的请求应该挂住，等这台点头，然后发令牌。
    func testKnockIsApprovedByTap() async throws {
        async let response = pair(code: "")

        try await waitForKnock()
        XCTAssertEqual(server.knock?.name, "对面那台")
        server.approve()

        let (j, code) = try await response
        XCTAssertEqual(code, 200)
        let token = j["token"] as? String ?? ""
        XCTAssertFalse(token.isEmpty)

        // 发出去的令牌得真的能用，否则发送方下一步就 403
        let (_, outboxCode) = try await request("/api/outbox", method: "GET", token: token)
        XCTAssertEqual(outboxCode, 200)
    }

    func testKnockIsRefusedByTap() async throws {
        async let response = pair(code: "")

        try await waitForKnock()
        server.deny()

        let (j, code) = try await response
        XCTAssertEqual(code, 403)
        XCTAssertNil(j["token"])
    }

    /// 一次只招呼一个人：两个弹窗叠在一起，用户分不清自己在给谁开门。
    func testSecondKnockIsRefusedWhileOneIsPending() async throws {
        async let first = pair(code: "")
        try await waitForKnock()

        let (_, secondCode) = try await pair(code: "")
        XCTAssertEqual(secondCode, 403)

        server.approve()
        let (_, firstCode) = try await first
        XCTAssertEqual(firstCode, 200)
    }

    private func waitForKnock() async throws {
        for _ in 0..<60 {
            if server.knock != nil { return }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTFail("等不到敲门")
    }

    // MARK: - 收文件

    func testPutWritesFileVerbatim() async throws {
        let (pj, _) = try await pair(code: server.pairingCode)
        let token = pj["token"] as? String ?? ""

        // 挑一个跨过缓冲区边界的大小：头读完时缓冲里会留一截 body，
        // 那段接不上的话文件就会少几个字节，而且只在大文件上才看得出来
        let payload = Data((0..<(200 * 1024)).map { UInt8($0 % 251) })

        var req = URLRequest(url: URL(string: base + "/api/fast/put")!)
        req.httpMethod = "PUT"
        req.setValue(token, forHTTPHeaderField: kTokenHeader)
        req.setValue("v.bin", forHTTPHeaderField: "X-Relative-Path")
        req.setValue("\(payload.count)", forHTTPHeaderField: "X-File-Size")
        let (data, resp) = try await URLSession.shared.upload(for: req, from: payload)

        XCTAssertEqual((resp as? HTTPURLResponse)?.statusCode, 200)
        let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        XCTAssertEqual(j["success"] as? Bool, true)

        let got = server.received.last
        XCTAssertEqual(got?.size, Int64(payload.count))
        XCTAssertEqual(try Data(contentsOf: XCTUnwrap(got?.url)), payload)
    }

    /// 对面传 "../../x" 过来，不拦的话就写到 Inbox 外面去了
    func testPathTraversalIsFlattened() async throws {
        let (pj, _) = try await pair(code: server.pairingCode)
        let token = pj["token"] as? String ?? ""

        var req = URLRequest(url: URL(string: base + "/api/fast/put")!)
        req.httpMethod = "PUT"
        req.setValue(token, forHTTPHeaderField: kTokenHeader)
        req.setValue("../../escaped.txt", forHTTPHeaderField: "X-Relative-Path")
        req.setValue("4", forHTTPHeaderField: "X-File-Size")
        _ = try await URLSession.shared.upload(for: req, from: Data("evil".utf8))

        let url = try XCTUnwrap(server.received.last?.url)
        // 名字里不能再有路径分隔符。同名时后面会接一个 (2)，那是另一条规则，
        // 所以这里只断言前缀，别把「不覆盖同名文件」的行为也一起锁死。
        XCTAssertTrue(url.lastPathComponent.hasPrefix("escaped"))
        XCTAssertFalse(url.lastPathComponent.contains("/"))
        // 真正要守住的是这一条：落点必须还在 Inbox 里，没跑到上层去
        XCTAssertEqual(url.deletingLastPathComponent().standardized,
                       server.inboxDir.standardized)
    }

    // MARK: - 打点

    private func get(_ path: String) async throws -> [String: Any] {
        try await request(path, method: "GET", token: nil).0
    }

    private func pair(code: String) async throws -> ([String: Any], Int) {
        var req = URLRequest(url: URL(string: base + "/api/pair")!)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: [
            "code": code,
            "device_id": "test",
            "device_name": "对面那台",
        ])
        req.timeoutInterval = 60
        let (data, resp) = try await URLSession.shared.data(for: req)
        let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        return (j, (resp as? HTTPURLResponse)?.statusCode ?? 0)
    }

    private func request(_ path: String, method: String,
                         token: String?) async throws -> ([String: Any], Int) {
        var req = URLRequest(url: URL(string: base + path)!)
        req.httpMethod = method
        if let token { req.setValue(token, forHTTPHeaderField: kTokenHeader) }
        req.timeoutInterval = 20
        let (data, resp) = try await URLSession.shared.data(for: req)
        let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        return (j, (resp as? HTTPURLResponse)?.statusCode ?? 0)
    }
}
