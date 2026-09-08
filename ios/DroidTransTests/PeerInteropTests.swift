/// 跨平台对跑：一端跑在 iOS 模拟器里，另一端是外面真的另一台（安卓模拟器）。
///
/// 平时跑整套用例时这两条都跳过 —— 它们需要外面有一台在等着。
/// 手动跑法见 android/手机互传功能说明.md 里的「跨平台对跑」。
///
/// 开关走宿主机 home 下的标记文件，不是环境变量：xcodebuild 的
/// `TEST_RUNNER_XXX` 只喂给 UI 测试的 runner 进程，App 内跑的单测收不到。
///
/// 为什么值得有：协议有三份实现（Go / Java / Swift），字节或流程差一点，
/// 单端测试里全是绿的，只有真的让两端说话才暴露。

import XCTest
@testable import DroidTrans

@MainActor
final class PeerInteropTests: XCTestCase {

    /// 这台当接收端，等外面的发送端连过来传一个文件。敲门自动点「同意」。
    ///
    ///     echo > ~/.droidtrans-interop-serve
    ///     xcodebuild test ... -only-testing:DroidTransTests/PeerInteropTests
    func testServesAnExternalSender() async throws {
        try XCTSkipUnless(Self.flag("serve") != nil, "跨平台对跑，只在显式要求时跑")

        let server = PeerServer(port: UInt16(Ports.peer), advertiseService: false)
        server.start()
        defer { server.stop() }

        for _ in 0..<200 where !server.running {
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTAssertTrue(server.running, "接收端没起来：\(server.lastError ?? "无错误信息")")

        // 最多等两分钟：对面要装 APK、起 instrumentation，比本机用例慢得多
        for _ in 0..<1200 {
            if server.knock != nil {
                server.approve()
            }
            if let got = server.received.first {
                XCTAssertGreaterThan(got.size, 0, "收到的文件是空的")
                XCTAssertTrue(FileManager.default.fileExists(atPath: got.url.path))
                return
            }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTFail("没等到对面把文件传过来")
    }

    /// 反过来：这台当发送端，连外面那台真的接收端（安卓）。
    ///
    ///     echo 127.0.0.1:9600 > ~/.droidtrans-interop-peer
    func testSendsToAnExternalPeer() async throws {
        // XCTUnwrap 拿不到值是**失败**不是跳过 —— 平时跑整套用例时这条会变红
        guard let addr = Self.flag("peer") else {
            throw XCTSkip("跨平台对跑，只在显式要求时跑")
        }

        let peer = try await DesktopDiscovery.verify(addr)
        // 对面是台手机：它该说「点一下同意」，而不是让用户去抄六位码
        XCTAssertTrue(peer.approvesByTap, "对面没报 approve，发送端会退回抄码那条路")
        XCTAssertTrue(peer.isPhone)
        // 手机那侧只有 HTTP PUT，多报一条通道会把发送方引上死路
        XCTAssertEqual(peer.prefer, [.httpPut])

        let client = ApiClient(baseURL: peer.baseURL)
        let token = try await client.pair(code: "", deviceId: "ios-interop",
                                         deviceName: "iPhone 模拟器")
        XCTAssertFalse(token.isEmpty)
        await client.setToken(token)

        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("from-ios.txt")
        let payload = Data("从 iPhone 传给安卓的一段字节".utf8)
        try payload.write(to: tmp)
        defer { try? FileManager.default.removeItem(at: tmp) }

        // 返回值是「秒传了没有」：true = 对面本来就有这个文件、一个字节都没发。
        // 这里是第一次传，必须是 false —— 传不成的话上面那句会直接抛错。
        let skipped = try await client.putFile(tmp, remoteName: "from-ios.txt",
                                               deviceId: "ios-interop")
        XCTAssertFalse(skipped, "第一次传就报秒传，说明对面根本没在收")
    }

    /// 宿主机 home 下的 `.droidtrans-interop-<名字>`；不存在返回 nil。
    ///
    /// 模拟器进程读得到宿主机的文件系统，宿主机 home 的位置由模拟器自己
    /// 通过 SIMULATOR_HOST_HOME 告诉进程 —— 不能用 NSUserName() 去拼，
    /// 那在模拟器里得到的不是宿主机那个用户名。
    private static func flag(_ name: String) -> String? {
        let env = ProcessInfo.processInfo.environment
        let home = env["SIMULATOR_HOST_HOME"] ?? NSHomeDirectory()
        guard let s = try? String(contentsOfFile: "\(home)/.droidtrans-interop-\(name)",
                                 encoding: .utf8) else { return nil }
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
