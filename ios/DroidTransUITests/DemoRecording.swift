import StoreKitTest
import XCTest

/// 录审核演示视频用的一条「慢动作」流程。
///
/// 提交审核时最大的风险是审核指南 2.1：卓传是配套 App，审核员拿一台
/// iPhone 打开只看到空雷达，很容易判「无法审核」。备注里放一段完整流程的
/// 录屏，比让他自己去装 Mac 端可靠得多。
///
/// 和 ScreenshotTests 的区别：那边只要拿到画面，这边每一步都刻意停顿，
/// 让看视频的人跟得上 —— 所以它不是测试，别在 CI 里跑。
final class DemoRecording: XCTestCase {

    private var session: SKTestSession!

    override func setUpWithError() throws {
        try super.setUpWithError()
        // 必须显式建会话并指向当前的 Products.storekit。
        //
        // 不建的话读到的是模拟器里缓存的旧配置 —— 第一次录出来的视频上
        // 印着「1 year $1.99 / 3 years $3.99 / Lifetime $9.99」，而商店里
        // 只卖 $14.99 的终身档。这段视频是要交给 Apple 审核员的，
        // 价格和档位对不上等于自己送把柄。
        session = try SKTestSession(configurationFileNamed: "Products")
        session.resetToDefaultState()
        session.clearTransactions()
        session.disableDialogs = true
    }

    override func tearDown() {
        session = nil
        super.tearDown()
    }

    private func beat(_ s: Double = 1.6) { Thread.sleep(forTimeInterval: s) }

    /// 关掉最上面那层。ScreenshotTests 里那个是 private，不跨文件共享 ——
    /// 这条流程只在录视频时手动跑，复制一份比为它去改公共代码干净。
    private func closeTop(_ app: XCUIApplication) {
        for id in ["close-history", "close-gallery", "close-text", "close-sync", "common-done"] {
            if app.buttons[id].exists {
                app.buttons[id].tap()
                return
            }
        }
        app.swipeDown(velocity: .fast)
    }

    func testWalkThroughForReviewers() throws {
        // 走 launchConnected，别自己拼启动参数：它会在需要时用 PAIR_CODE
        // 完成配对。之前手写一遍，结果卡在配对页 —— 主界面根本没进去，
        // 录出来的是一段配对界面的静止画面。
        let env = ProcessInfo.processInfo.environment
        let host = env["TEST_RUNNER_DT_DESKTOP"] ?? env["DT_DESKTOP"] ?? ""
        let desktop = host.isEmpty ? [] : ["-uitest-desktop", host]
        let lang = (env["TEST_RUNNER_SHOT_LANG"] ?? env["SHOT_LANG"] ?? "").isEmpty
            ? [] : ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        let app = try launchConnected(["-uitest-fresh", "-uitest-no-license"] + desktop + lang)
        beat(3)

        guard app.buttons["open-gallery"].waitForExistence(timeout: 20) else {
            XCTFail("没进到主界面，录不了")
            return
        }
        beat(3)

        let gets = app.buttons.matching(identifier: "outbox-get")
        if gets.firstMatch.waitForExistence(timeout: 5), gets.count > 0 {
            gets.element(boundBy: 0).tap()
            beat(3)
        }
        if gets.count > 1 {
            gets.element(boundBy: gets.count - 1).tap()
            beat(5)
        }

        if app.buttons["open-gallery"].waitForExistence(timeout: 5) {
            app.buttons["open-gallery"].tap()
            beat(3.5)
            closeTop(app)
            beat(1)
        }

        if app.buttons["open-history"].waitForExistence(timeout: 5) {
            app.buttons["open-history"].tap()
            beat(3)
            closeTop(app)
            beat(1)
        }

        if app.buttons["open-me"].waitForExistence(timeout: 5) {
            app.buttons["open-me"].tap()
            beat(2)
            if app.buttons["open-pro"].waitForExistence(timeout: 5) {
                app.buttons["open-pro"].tap()
                beat(4)
            }
        }
        beat(2)
    }
}
