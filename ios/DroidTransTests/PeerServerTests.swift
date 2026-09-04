import XCTest
@testable import DroidTrans

/// 手机当接收方那一半。
///
/// 这些用例会真的把 PeerServer 跑起来、真的用 URLSession 打它 ——
/// 因为这一半最容易错的地方不在纯函数里，而在「HTTP 头解析对不对」
/// 「不带码的请求会不会挂住」这种只有真跑一遍才暴露的地方。
///
/// 每个用例让系统分配空闲端口。线上仍固定 9600；测试没必要和别的进程
/// 争同一个端口，否则前一个 listener 刚关闭就会让下一个用例随机失败。
@MainActor
final class PeerServerTests: XCTestCase {

    private var server: PeerServer!

    override func setUp() async throws {
        server = PeerServer(port: 0, advertiseService: false)
        server.start()
        try await waitUntilUp()
    }

    override func tearDown() async throws {
        server.stop()
        server = nil
    }

    private var base: String { "http://127.0.0.1:\(server.boundPort)" }

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
        XCTAssertEqual(j["port"] as? Int, server.boundPort)
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

    /// 收件箱是模拟器里真实的目录，跨次运行不会清。
    ///
    /// 固定文件名的话，上一轮留下的那份完整文件会让这一轮一开始就被判成
    /// 「已经收全了」—— 报出来的是「续传偏移量不对」，看着像产品坏了，
    /// 其实是上一轮的残留。所以每次跑都用一个新名字，跑完删掉。
    private func uniqueName(_ stem: String) -> String {
        let name = "t-\(UUID().uuidString.prefix(8))-\(stem)"
        addTeardownBlock { [server] in
            guard let dir = await server?.inboxDir else { return }
            for f in (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
            where f.contains(name) {
                try? FileManager.default.removeItem(at: dir.appendingPathComponent(f))
            }
        }
        return name
    }

    /// 手机互传也要能断点续传。
    ///
    /// 这条路走的是 HTTP PUT，原来每次从头写、断了就把半个文件删掉重来 ——
    /// 而对比表、官网、README 都写着「断点续传」，那条承诺在这条路上不成立。
    func testPutResumesFromOffset() async throws {
        let (pj, _) = try await pair(code: server.pairingCode)
        let token = pj["token"] as? String ?? ""

        let payload = Data((0..<(200 * 1024)).map { UInt8($0 % 251) })
        let name = uniqueName("resume.bin")
        let half = payload.count / 2

        // 第一段：只发前一半，声明的总大小是完整的 —— 服务端应当认定没收全
        var first = URLRequest(url: URL(string: base + "/api/fast/put")!)
        first.httpMethod = "PUT"
        first.setValue(token, forHTTPHeaderField: kTokenHeader)
        first.setValue(name, forHTTPHeaderField: "X-Relative-Path")
        first.setValue("\(payload.count)", forHTTPHeaderField: "X-File-Size")
        let (d1, r1) = try await URLSession.shared.upload(for: first, from: payload.prefix(half))
        XCTAssertEqual((r1 as? HTTPURLResponse)?.statusCode, 400,
                       "只发了一半却报成功：\(String(decoding: d1, as: UTF8.self))")

        // 收件箱里绝不能出现半个文件
        let inbox = server.inboxDir
        let visible = (try? FileManager.default.contentsOfDirectory(atPath: inbox.path)) ?? []
        XCTAssertFalse(visible.contains(name),
                       "传了一半，收件箱里却已经有这个文件了 —— 用户会以为它是好的")

        // 问一下已经收到多少
        let q = base + "/api/fast/offset?name=\(name)&size=\(payload.count)"
        var ask = URLRequest(url: URL(string: q)!)
        ask.setValue(token, forHTTPHeaderField: kTokenHeader)
        let (d2, _) = try await URLSession.shared.data(for: ask)
        let oj = (try? JSONSerialization.jsonObject(with: d2)) as? [String: Any] ?? [:]
        XCTAssertEqual((oj["offset"] as? NSNumber)?.intValue, half,
                       "续传偏移量不对，会从头重传")

        // 第二段：只补剩下的
        var second = URLRequest(url: URL(string: base + "/api/fast/put")!)
        second.httpMethod = "PUT"
        second.setValue(token, forHTTPHeaderField: kTokenHeader)
        second.setValue(name, forHTTPHeaderField: "X-Relative-Path")
        second.setValue("\(payload.count)", forHTTPHeaderField: "X-File-Size")
        second.setValue("\(half)", forHTTPHeaderField: "X-Start-Offset")
        let (_, r2) = try await URLSession.shared.upload(for: second, from: payload.suffix(from: half))
        XCTAssertEqual((r2 as? HTTPURLResponse)?.statusCode, 200)

        let got = server.received.last
        XCTAssertEqual(got?.size, Int64(payload.count))
        XCTAssertEqual(try Data(contentsOf: XCTUnwrap(got?.url)), payload,
                       "续出来的内容和原文件对不上")
    }

    /// 已经完整收过的文件，再问偏移量应当直接说「不用传了」。
    func testOffsetReportsCompleteFile() async throws {
        let (pj, _) = try await pair(code: server.pairingCode)
        let token = pj["token"] as? String ?? ""
        let payload = Data(repeating: 7, count: 4096)
        let name = uniqueName("already.bin")

        var req = URLRequest(url: URL(string: base + "/api/fast/put")!)
        req.httpMethod = "PUT"
        req.setValue(token, forHTTPHeaderField: kTokenHeader)
        req.setValue(name, forHTTPHeaderField: "X-Relative-Path")
        req.setValue("\(payload.count)", forHTTPHeaderField: "X-File-Size")
        _ = try await URLSession.shared.upload(for: req, from: payload)

        var ask = URLRequest(url: URL(string: base + "/api/fast/offset?name=\(name)&size=\(payload.count)")!)
        ask.setValue(token, forHTTPHeaderField: kTokenHeader)
        let (d, _) = try await URLSession.shared.data(for: ask)
        let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] ?? [:]
        XCTAssertEqual(j["complete"] as? Bool, true, "同一个文件已经收全了，不该再传一遍")
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

// MARK: - 报哪个网卡的地址

/// 开了个人热点时，对方连的是 bridge100（172.20.10.1），不是 en0。
///
/// 而热点恰恰是在没有 Wi-Fi 的场合开的 —— 那时 en0 干脆没地址。
/// 只认 en0 的话，用户开了热点、对面也连上了，这边却显示「先连上 Wi-Fi」，
/// 手输地址那条兜底路直接断掉，而且不会有任何报错。
final class InterfaceRankTests: XCTestCase {

    func testHotspotOutranksWiFi() throws {
        let bridge = try XCTUnwrap(PeerServer.interfaceRank("bridge100"))
        let wifi = try XCTUnwrap(PeerServer.interfaceRank("en0"))
        XCTAssertLessThan(bridge, wifi, "同时开着热点和 Wi-Fi 时，报出去的必须是热点那个地址")
    }

    func testCellularIsNeverReported() {
        // 蜂窝地址对面根本连不过来，报出去等于给用户一个死地址
        XCTAssertNil(PeerServer.interfaceRank("pdp_ip0"))
        XCTAssertNil(PeerServer.interfaceRank("utun0"))
        XCTAssertNil(PeerServer.interfaceRank("lo0"))
    }

    func testWiFiStillPreferredOverSecondary() throws {
        let en0 = try XCTUnwrap(PeerServer.interfaceRank("en0"))
        let en1 = try XCTUnwrap(PeerServer.interfaceRank("en1"))
        XCTAssertLessThan(en0, en1)
    }
}
