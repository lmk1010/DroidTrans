import XCTest
@testable import DroidTrans

/// 对着真实运行的 Mac 桌面端跑一遍传输。
///
/// 和 ProtocolTests 的分工：那边只管字节拼得对不对，这边管
/// 「连上去、握手、推数据、读应答」这一整条链路在真机环境里通不通。
/// 前者全绿而后者失败的情况是存在的 —— 比如令牌没带上、端口探错了。
///
/// 依赖外部环境，所以没给参数时跳过而不是失败：
///
///   TEST_RUNNER_DT_HOST=192.168.31.93 \
///   TEST_RUNNER_DT_CODE=$(curl -s http://127.0.0.1:9500/api/pair/info \
///     | python3 -c "import sys,json;print(json.load(sys.stdin)['code'])") \
///   xcodebuild test -scheme DroidTrans -only-testing:DroidTransTests/LiveTransferTests
final class LiveTransferTests: XCTestCase {

    private func env(_ key: String) -> String? {
        let e = ProcessInfo.processInfo.environment
        return e["TEST_RUNNER_\(key)"] ?? e[key]
    }

    private func requireHost() throws -> String {
        guard let host = env("DT_HOST"), !host.isEmpty else {
            throw XCTSkip("没给 DT_HOST，跳过")
        }
        return host
    }

    /// 拿一个能用的令牌：桌面端要求配对时用配对码换，不要求就空串。
    private func token(host: String) async throws -> String {
        let api = ApiClient(baseURL: "http://\(host):\(Ports.http)")
        let info = try await api.info()
        guard info.pairingRequired else { return "" }

        guard let code = env("DT_CODE"), code.count == 6 else {
            throw XCTSkip("这台电脑要求配对，但没给 DT_CODE")
        }
        return try await api.pair(code: code, deviceId: "ios-integration-test",
                                  deviceName: "iOS 集成测试")
    }

    func testHealthAndInfo() async throws {
        let host = try requireHost()
        let api = ApiClient(baseURL: "http://\(host):\(Ports.http)")

        let ok = await api.health()
        XCTAssertTrue(ok, "\(host) 上没有卓传在跑")

        let info = try await api.info()
        XCTAssertFalse(info.name.isEmpty, "电脑没报自己的名字，界面上会显示成一串 IP")
        XCTAssertFalse(info.prefer.isEmpty, "电脑没报支持哪些通道，客户端无从选择")
    }

    /// 把一个文件真的传过去。
    ///
    /// 内容里带随机标记，跑完在 Mac 上比对落地文件就能确认不是「假成功」。
    func testSendFileOverTCP() async throws {
        let host = try requireHost()
        let tok = try await token(host: host)

        // 探一下快传端口。探不通就没必要往下走了，
        // 那说明桌面端的 9501 没起来 —— 这本身就是要报出来的问题
        let alive = await FastSender.probe(host: host)
        XCTAssertTrue(alive, "TCP 9501 连不上，快传通道是断的")

        let marker = env("DT_MARKER") ?? "ios-\(Int(Date().timeIntervalSince1970))"
        let name = "\(marker).txt"
        let body = "从 iOS 集成测试发出的内容：\(marker)\n"

        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try body.write(to: tmp, atomically: true, encoding: .utf8)
        defer { try? FileManager.default.removeItem(at: tmp) }

        var lastFraction: Double = -1
        let sender = FastSender(host: host, token: tok)
        try await sender.sendFile(tmp, remoteName: name) { p in
            lastFraction = p.fraction
        }

        // 进度回调必须真的被调过。没有进度的传输在界面上就是一条不动的进度条，
        // 用户会以为卡住了
        XCTAssertGreaterThan(lastFraction, 0, "整个传输过程一次进度回调都没有")
    }

    /// 令牌不对时必须拿到服务端说的原因，而不是一句 broken pipe。
    ///
    /// 服务端拒绝时会先回 ERR 再关连接，客户端如果闷头继续写，
    /// 真正的原因就会被写失败的错误盖掉 —— 这个坑在 Dart 端踩过一次。
    func testBadTokenGivesRealReason() async throws {
        let host = try requireHost()
        let api = ApiClient(baseURL: "http://\(host):\(Ports.http)")
        let info = try await api.info()
        try XCTSkipUnless(info.pairingRequired, "这台电脑没开配对，测不了拒绝路径")

        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("bad-token-probe.txt")
        try String(repeating: "x", count: 64 * 1024).write(to: tmp, atomically: true, encoding: .utf8)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let sender = FastSender(host: host, token: "definitely-not-a-valid-token")
        do {
            try await sender.sendFile(tmp, remoteName: "bad-token-probe.txt")
            XCTFail("用错误的令牌竟然传成功了")
        } catch let e as FastSendError {
            let m = e.message.lowercased()
            XCTAssertFalse(
                m.contains("broken pipe") || m.contains("epipe"),
                "拿到的是写失败而不是服务端给的原因，错误被盖掉了：\(e.message)"
            )
        }
    }
}
