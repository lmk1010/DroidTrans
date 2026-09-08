/// 真机对电脑：连、配对、传一个大文件，把速率量出来。
///
/// 默认跳过；要跑就把地址和六位码填进 InteropConfig.swift 再构建。
/// 走的是生产代码那条路：先 /api/wifi/info 问能力，再用 FastSender 发 ATF3 裸流，
/// 和用户在 App 里点一下发文件是同一条链路。

import XCTest
@testable import DroidTrans

final class DesktopSpeedTests: XCTestCase {

    func testUploadsToDesktopAndReportsSpeed() async throws {
        guard !Interop.desktop.isEmpty else {
            throw XCTSkip("真机对电脑，只在显式要求时跑（见 InteropConfig.swift）")
        }
        let addr = Interop.desktop
        let code = Interop.desktopCode

        let desktop = try await DesktopDiscovery.verify(addr)
        XCTAssertFalse(desktop.name.isEmpty)
        // 电脑该报出快传通道；只剩 http 的话速率会差一大截，那是另一个问题
        XCTAssertTrue(desktop.prefer.contains(.tcp), "电脑没报 TCP 快传：\(desktop.prefer)")

        let client = ApiClient(baseURL: desktop.baseURL)
        let token = try await client.pair(code: code, deviceId: "ios-speed",
                                         deviceName: "iPhone 真机")
        XCTAssertFalse(token.isEmpty)

        // 128 MB，内容是重复的字节：量的是链路，不是磁盘
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("ios-speed-128m.bin")
        try? FileManager.default.removeItem(at: tmp)
        FileManager.default.createFile(atPath: tmp.path, contents: nil)
        let handle = try FileHandle(forWritingTo: tmp)
        let block = Data(repeating: 0x5A, count: 1 << 20)
        for _ in 0..<128 { handle.write(block) }
        try handle.close()
        defer { try? FileManager.default.removeItem(at: tmp) }

        let sender = FastSender(host: desktop.host, token: token,
                                port: desktop.tcpPort ?? Ports.fastTCP)
        let t0 = Date()
        try await sender.sendFile(tmp, remoteName: "ios-speed-128m.bin")
        let sec = Date().timeIntervalSince(t0)

        let mbps = 128.0 / sec
        print(String(format: "RESULT ATF3 iOS→电脑 128 MB / %.1f s = %.1f MB/s (%.0f Mbps)",
                     sec, mbps, mbps * 8))
        XCTAssertGreaterThan(sec, 0)
    }

    /// 同一台机器、同一个文件，换 HTTP PUT 那条通道再传一次。
    ///
    /// 两条路差不多，就说明瓶颈在链路而不在我们的实现；差很多，那才是代码的问题。
    func testUploadsOverHttpForComparison() async throws {
        guard !Interop.desktop.isEmpty else {
            throw XCTSkip("真机对电脑，只在显式要求时跑")
        }
        let desktop = try await DesktopDiscovery.verify(Interop.desktop)
        let client = ApiClient(baseURL: desktop.baseURL)
        let token = try await client.pair(code: Interop.desktopCode, deviceId: "ios-speed-http",
                                         deviceName: "iPhone 真机")
        await client.setToken(token)

        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("ios-speed-http-32m.bin")
        try? FileManager.default.removeItem(at: tmp)
        FileManager.default.createFile(atPath: tmp.path, contents: nil)
        let handle = try FileHandle(forWritingTo: tmp)
        let block = Data(repeating: 0x5A, count: 1 << 20)
        for _ in 0..<32 { handle.write(block) }
        try handle.close()
        defer { try? FileManager.default.removeItem(at: tmp) }

        let t0 = Date()
        _ = try await client.putFile(tmp, remoteName: "ios-speed-http-32m.bin",
                                     deviceId: "ios-speed-http")
        let sec = Date().timeIntervalSince(t0)
        let mbps = 32.0 / sec
        print(String(format: "RESULT HTTP iOS→电脑 32 MB / %.1f s = %.1f MB/s (%.0f Mbps)",
                     sec, mbps, mbps * 8))
    }

}
