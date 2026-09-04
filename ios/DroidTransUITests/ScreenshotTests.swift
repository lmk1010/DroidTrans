import StoreKitTest
import XCTest

/// 把几个关键界面截下来存成附件，方便肉眼过一遍。
///
///   xcodebuild test -only-testing:DroidTransUITests/ScreenshotTests \
///     -resultBundlePath /tmp/shots.xcresult
///
/// 不做断言 —— 它的作用是「让人看见」，不是「验证」。
final class ScreenshotTests: XCTestCase {

    private var session: SKTestSession!

    /// 模拟器上的 Bonjour 不可靠，直接把电脑地址喂给 App。
    /// 环境变量 DT_DESKTOP 由 scheme 或命令行传入，没有就退回自动发现。
    static var desktopArgs: [String] {
        // xcodebuild 只把 TEST_RUNNER_ 前缀的环境变量转发给测试进程，
        // 两种写法都认一下，否则命令行跑起来会静默跳过 ——
        // 而 skip 在输出里长得跟 pass 很像，很容易误以为验过了。
        let env = ProcessInfo.processInfo.environment
        let host = env["TEST_RUNNER_DT_DESKTOP"] ?? env["DT_DESKTOP"] ?? ""
        guard !host.isEmpty else { return [] }
        return ["-uitest-desktop", host]
    }

    override func setUpWithError() throws {
        try super.setUpWithError()
        // 没有它，Pro 页截出来的价格全是「—」
        session = try SKTestSession(configurationFileNamed: "Products")
        session.resetToDefaultState()
        session.clearTransactions()
        session.disableDialogs = true
    }

    override func tearDown() {
        session = nil
        super.tearDown()
    }

    /// 出英文版商店截图时把 App 的语言顶成英文。
    ///
    /// 商店截图必须中英各一套，而模拟器整机切语言慢且会污染后面的用例；
    /// 用启动参数只影响这一次启动，跑完就没了。
    ///
    ///   TEST_RUNNER_SHOT_LANG=en xcodebuild test -only-testing:…/ScreenshotTests
    static var langArgs: [String] {
        let env = ProcessInfo.processInfo.environment
        let lang = env["TEST_RUNNER_SHOT_LANG"] ?? env["SHOT_LANG"] ?? ""
        guard !lang.isEmpty else { return [] }
        return ["-AppleLanguages", "(\(lang))", "-AppleLocale", lang == "en" ? "en_US" : lang]
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        let a = XCTAttachment(screenshot: app.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }

    func testCaptureKeyScreens() throws {
        // 截的是「还没买过」的样子 —— 那才是新用户看到的界面。
        // 不清的话上一次购买留下的许可证会让 Pro 页变成「已激活」。
        let app = try launchConnected(["-uitest-fresh", "-uitest-no-license"] + Self.desktopArgs + Self.langArgs)
        let me = app.buttons["open-me"]
        XCTAssertTrue(me.waitForExistence(timeout: 10))
        shot(app, "01-home")

        me.tap()
        XCTAssertTrue(app.buttons["open-pro"].waitForExistence(timeout: 5))
        shot(app, "02-me")

        app.buttons["open-pro"].tap()
        XCTAssertTrue(app.buttons["plan-lifetime"].waitForExistence(timeout: 10))
        shot(app, "03-pro")
    }

    /// 把剩下的界面也截出来。
    ///
    /// 只截三屏是验收不了的：出问题的往往是分支状态 —— 没权限、
    /// 传完了、被 Pro 挡住、列表是空的。那些界面平时看不见，
    /// 也就最容易一直烂着没人管。
    func testCaptureRemainingScreens() throws {
        let app = try launchConnected(["-uitest-fresh", "-uitest-no-license"] + Self.desktopArgs + Self.langArgs)
        // open-me 在启动页和主界面上都有，拿它判断「已进主界面」是错的 ——
        // 结果就是下面每个 if 都静默跳过，测试绿着却一张图都没截到。
        // 用只有主界面才有的 open-gallery 来判定。
        // 卡住时先把当前这一屏截下来 —— 不然只知道「没进主界面」，
        // 不知道停在配对页、雷达页还是别的地方，只能瞎猜
        if !app.buttons["open-gallery"].waitForExistence(timeout: 12) {
            shot(app, "00-stuck")
            XCTFail("没进到主界面，附件 00-stuck 是当时的界面")
            return
        }

        // 相册同步：免费版看到的是被 Pro 挡住的那一屏
        XCTAssertTrue(app.buttons["open-sync"].waitForExistence(timeout: 5), "主界面上没有相册同步入口")
        do {
            app.buttons["open-sync"].tap()
            XCTAssertTrue(app.buttons["sync-unlock"].waitForExistence(timeout: 6),
                          "相册同步没有显示 Pro 引导")
            shot(app, "04-sync-locked")
            app.buttons["close-sync"].tap()
        }

        // 传输记录。空列表也要有像样的空态，不能是一片黑
        XCTAssertTrue(app.buttons["open-history"].waitForExistence(timeout: 5), "主界面上没有传输记录入口")
        do {
            app.buttons["open-history"].tap()
            sleep(1)
            shot(app, "05-history")
            dismissTop(app)
        }

        // 电脑上还没放东西时的收件箱
        XCTAssertTrue(app.buttons["open-gallery"].waitForExistence(timeout: 5), "主界面上没有收件箱入口")
        do {
            app.buttons["open-gallery"].tap()
            sleep(1)
            shot(app, "06-gallery")
            dismissTop(app)
        }

        // 发文字。发送那三块的标识是 send-<art>，见 HomeScreen 的 SendTile
        XCTAssertTrue(app.buttons["send-text"].waitForExistence(timeout: 5), "主界面上没有发文字入口")
        do {
            app.buttons["send-text"].tap()
            sleep(1)
            shot(app, "07-text")
            dismissTop(app)
        }
    }

    /// 关掉最上面那层。有「完成」按钮就点，没有就往下拖。
    private func dismissTop(_ app: XCUIApplication) {
        for id in ["close-history", "close-gallery", "close-text", "close-sync", "common-done"] {
            if app.buttons[id].exists {
                app.buttons[id].tap()
                return
            }
        }
        app.swipeDown(velocity: .fast)
    }
}
