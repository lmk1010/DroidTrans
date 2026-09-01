import XCTest

/// 真的把东西传到电脑上。
///
/// 这条用例的验证不在测试进程里 —— 手机发出去的文字会落到 Mac 的剪贴板，
/// 跑完之后在 Mac 上 `pbpaste` 一比就知道到底通没通。
/// 测试里只能断言「界面走完了」，那不等于「电脑收到了」。
///
///   TEST_RUNNER_SEND_TEXT="droidtrans-e2e-<时间戳>" \
///   xcodebuild test -scheme DroidTrans \
///     -only-testing:DroidTransUITests/TransferTests/testSendTextToDesktop
///   pbpaste    # 应该就是刚才那串
final class TransferTests: XCTestCase {

    func testSendTextToDesktop() throws {
        guard let payload = ProcessInfo.processInfo.environment["TEST_RUNNER_SEND_TEXT"]
                ?? ProcessInfo.processInfo.environment["SEND_TEXT"] else {
            throw XCTSkip("没给 SEND_TEXT，跳过")
        }

        let app = try launchConnected(["-uitest-fresh"])

        let entry = app.buttons["send-text"]
        XCTAssertTrue(entry.waitForExistence(timeout: 5), "找不到「文字或链接」入口")
        entry.tap()

        let editor = app.textViews["text-editor"]
        XCTAssertTrue(editor.waitForExistence(timeout: 5), "文字输入框没出来")
        editor.tap()
        editor.typeText(payload)

        let send = app.buttons["send-text-button"]
        XCTAssertTrue(send.waitForExistence(timeout: 3))
        send.tap()

        // 发完会关掉这一屏回到发送页。还停在原地就是没发出去。
        XCTAssertTrue(
            entry.waitForExistence(timeout: 15),
            "点了发送但没有回到发送页 —— 请求多半失败了"
        )
    }
}
