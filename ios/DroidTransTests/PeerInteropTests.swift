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
    ///     InteropConfig.swift 里把 serve 改成 true 再构建
    func testServesAnExternalSender() async throws {
        try XCTSkipUnless(Interop.serve, "跨平台对跑，只在显式要求时跑（见 InteropConfig.swift）")

        // 广播打开：对面要能在真的局域网上把这台发现出来，
        // 那正是模拟器之间对跑测不到、而真机能测的一段
        let server = PeerServer(port: UInt16(Ports.peer), advertiseService: true)
        server.start()
        defer { server.stop() }

        for _ in 0..<200 where !server.running {
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTAssertTrue(server.running, "接收端没起来：\(server.lastError ?? "无错误信息")")
        let fm = FileManager.default
        let docs = fm.urls(for: .documentDirectory, in: .userDomainMask)[0]
        var probe = "Documents=\(docs.path) 存在=\(fm.fileExists(atPath: docs.path)) 可写=\(fm.isWritableFile(atPath: docs.path))"
        do {
            try fm.createDirectory(at: docs.appendingPathComponent("probe"),
                                   withIntermediateDirectories: true)
            probe += " 建目录=成功"
            try? fm.removeItem(at: docs.appendingPathComponent("probe"))
        } catch {
            probe += " 建目录失败=\(error.localizedDescription)"
        }
        print("RESULT 沙盒 \(probe) HOME=\(NSHomeDirectory())")
        print("RESULT 接收端就绪 \(server.localIP ?? "无地址"):\(server.boundPort) 码 \(server.pairingCode)")

        var reported = false
        // 最多等十分钟：真机那头要装包、起 instrumentation、还要人点几下，
        // 两分钟经常不够，而超时之后这头就不在了，对面只会看到「连不上」
        for _ in 0..<6000 {
            if server.knock != nil {
                server.approve()
            }
            if let got = server.received.first, !reported {
                XCTAssertGreaterThan(got.size, 0, "收到的文件是空的")
                XCTAssertTrue(FileManager.default.fileExists(atPath: got.url.path))
                print("RESULT 收到 \(got.name) \(got.size) 字节")
                reported = true
                // 收完一个不退出：联调时要连着测好几轮，
                // 退出的话对面下一次连过来只会得到「连不上」
            }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTAssertTrue(reported, "没等到对面把文件传过来")
    }

    /// 反过来：这台当发送端，连外面那台真的接收端（安卓）。
    ///
    ///     InteropConfig.swift 里填上 peer = "192.168.10.14:9600"
    func testSendsToAnExternalPeer() async throws {
        guard !Interop.peer.isEmpty else {
            throw XCTSkip("跨平台对跑，只在显式要求时跑（见 InteropConfig.swift）")
        }
        let peer = try await DesktopDiscovery.verify(Interop.peer)
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

}
