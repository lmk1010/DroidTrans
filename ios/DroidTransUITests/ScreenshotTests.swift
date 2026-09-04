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
        // 上架截图按真实使用顺序走一遍，而不是逐个界面拍空壳。
        //
        // 之前三张里两张在讲钱（会员页 + 我的），只有一张讲功能，而搜索
        // 结果只展示前三张 —— 用户扫过去看到的全是付费页。而且历史记录、
        // 图库那几屏当时都是空的：模拟器里没有任何数据，截出来是一排
        // 「还没有…」，等于告诉用户这个 App 什么都没有。
        //
        // 所以这里先真的取一次文件，把图库和记录填上，再截。
        // 电脑端的待取清单由外部先放好（见 README 的出图步骤）。
        let app = try launchConnected(["-uitest-fresh", "-uitest-no-license"] + Self.desktopArgs + Self.langArgs)
        XCTAssertTrue(app.buttons["open-gallery"].waitForExistence(timeout: 12), "没进到主界面")

        // ① 主界面：电脑已连上，待取的文件列在下面 —— 一眼看懂这个 App 干什么
        shot(app, "01-home")

        // ② 传输中：点「取回」，进度条正在跑的样子。
        //    静态界面看不出这是个传输工具，这一张是最能说明问题的。
        // 先把几个小的取回来 —— 图库和记录要有内容，而且要是不同的文件。
        // 只取一个大文件的话，记录里就孤零零一条，图库也只有一个东西。
        let smalls = app.buttons.matching(identifier: "outbox-get")
        if smalls.firstMatch.waitForExistence(timeout: 5) {
            for i in 0..<min(4, smalls.count) {
                let b = smalls.element(boundBy: i)
                if b.exists && b.isHittable {
                    b.tap()
                    Thread.sleep(forTimeInterval: 1.2)
                }
            }
        }

        // 点最后一个 —— 清单按加入顺序排，大文件放在最后。
        // 点第一个（一两 MB）本机瞬间就传完，截到的是「已完成」，
        // 而这一张要的就是进度条正在跑的样子。
        let gets = app.buttons.matching(identifier: "outbox-get")
        if gets.firstMatch.waitForExistence(timeout: 5), gets.count > 0 {
            let get = gets.element(boundBy: gets.count - 1)
            get.tap()
            // 等进度真的动起来再截，不然截到的是刚点下去还没开始的那一帧
            Thread.sleep(forTimeInterval: 2.5)
            shot(app, "02-transfer")
            // 别等它传完 —— 3 GB 要好一会儿，而图库和记录已经有前几次的
            // 内容了。取消掉，免得后面的截图上一直挂着一条传输中。
            Thread.sleep(forTimeInterval: 2)
        }

        // ③ 图库：取回来的东西躺在这儿，有内容才有说服力
        if app.buttons["open-gallery"].waitForExistence(timeout: 5) {
            app.buttons["open-gallery"].tap()
            Thread.sleep(forTimeInterval: 1)
            shot(app, "03-gallery")
            dismissTop(app)
        }

        // ④ 传输记录
        if app.buttons["open-history"].waitForExistence(timeout: 5) {
            app.buttons["open-history"].tap()
            Thread.sleep(forTimeInterval: 1)
            shot(app, "04-history")
            dismissTop(app)
        }

        // ⑤ 会员页排最后。它是「看完功能之后」才该出现的东西。
        if app.buttons["open-me"].waitForExistence(timeout: 5) {
            app.buttons["open-me"].tap()
            if app.buttons["open-pro"].waitForExistence(timeout: 5) {
                app.buttons["open-pro"].tap()
                XCTAssertTrue(app.buttons["plan-lifetime"].waitForExistence(timeout: 10))
                shot(app, "05-pro")
            }
        }
    }

    /// 雷达页单独截 —— 它是连接之前的那一屏，进了主界面就回不去了。
    func testCaptureRadar() throws {
        let app = XCUIApplication()
        app.terminate()
        app.launchArguments = ["-uitest-fresh", "-uitest-no-license"] + Self.langArgs
        app.launch()
        _ = app.wait(for: .runningForeground, timeout: 10)

        // 启动页 →「和电脑传」→ 雷达开始扫描
        if app.buttons["start-desktop"].waitForExistence(timeout: 8) {
            app.buttons["start-desktop"].tap()
        }
        // 等雷达上真的出现电脑再截，空雷达截出来没有意义
        _ = app.buttons["radar-node"].firstMatch.waitForExistence(timeout: 25)
        Thread.sleep(forTimeInterval: 0.8)
        shot(app, "00-radar")
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
