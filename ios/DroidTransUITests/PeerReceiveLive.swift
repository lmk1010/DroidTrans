import XCTest

/// 真机联调用：把 iPhone 的界面真的点进「和安卓机传 → 收」，然后待在那儿。
///
/// 和 PeerInteropTests 里那条无界面的接收端不一样 —— 这一条走的是**用户真会看到的界面**：
/// 屏幕上会显示「等待连接」、二维码、对面敲门时会弹「XXX 想连过来」，
/// 这个用例会替人点「同意」，收到文件后界面上的计数也会跟着涨。
///
/// 默认跳过；联调时把 InteropConfig.swift 里的 serve 改成 true 再构建。
/// 它最多待命 10 分钟，期间对面可以连着传好几轮。
final class PeerReceiveLive: XCTestCase {

    func testStaysInReceiveModeForRealSender() throws {
        try XCTSkipUnless(Interop.serve, "真机联调，只在显式要求时跑")

        let app = XCUIApplication()
        app.launch()

        // 首页：跟谁传 → 和安卓机传
        let android = app.buttons["start-android"]
        XCTAssertTrue(android.waitForExistence(timeout: 15), "首页没出来")
        android.tap()

        // 这一屏问「你是发还是收」
        let recv = app.buttons["peer-recv"]
        XCTAssertTrue(recv.waitForExistence(timeout: 10), "没进到发/收那一屏")
        recv.tap()

        // 从这里开始就是用户看到的接收界面：等待连接、二维码、配对码
        let code = app.staticTexts["peer-code"]
        XCTAssertTrue(code.waitForExistence(timeout: 20), "接收界面没起来")
        print("RESULT 接收界面就绪 \(code.label)")

        // 待命：对面敲门就点「同意」，收到文件就把计数打出来
        let allow = app.buttons["同意"]
        let got = app.staticTexts["peer-got-count"]
        var lastCount = ""
        for _ in 0..<600 {
            if allow.exists {
                allow.tap()
                print("RESULT 已同意对面的连接请求")
            }
            if got.exists, got.label != lastCount {
                lastCount = got.label
                print("RESULT 界面上的已收计数：\(lastCount)")
            }
            // 本地网络权限那个系统弹窗如果冒出来，也替用户点掉
            let systemAllow = XCUIApplication(bundleIdentifier: "com.apple.springboard")
                .buttons["好"]
            if systemAllow.exists {
                systemAllow.tap()
            }
            Thread.sleep(forTimeInterval: 1)
        }
    }
}
