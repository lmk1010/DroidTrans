import XCTest

/// Pro 功能的门禁。
///
/// 这一套要守住两件事，方向相反但同样要紧：
///   1. 没买的人点进去看得到「这是什么、为什么值钱」，而不是点了没反应
///   2. 免费功能一个都不能被锁上 —— 官网白纸黑字承诺了基础功能永久免费，
///      锁错一个就是欺骗
final class ProGateTests: XCTestCase {

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    /// 没有授权时，相册同步应该展示升级引导，而不是直接开干。
    func testSyncIsGatedWithoutLicense() throws {
        let app = try launchConnected(["-uitest-fresh", "-uitest-no-license"])

        let entry = app.buttons["open-sync"]
        XCTAssertTrue(entry.waitForExistence(timeout: 10), "主界面上没有相册同步入口")
        entry.tap()

        // 该看到解锁引导，不该看到「开始传」
        let unlock = app.buttons["sync-unlock"]
        XCTAssertTrue(unlock.waitForExistence(timeout: 8),
                      "没授权却没有显示升级引导 —— 用户不知道这是 Pro 功能")
        XCTAssertFalse(app.buttons["sync-start"].exists,
                       "没授权居然能直接开始同步，门禁没生效")
    }

    /// 免费功能必须一个不少。
    ///
    /// 这条是防自己人手滑的：哪天顺手把「照片」也加上 PRO 角标，
    /// 官网的承诺就成了空话，而这种改动在代码 review 里很容易滑过去。
    func testFreeFeaturesAreNeverGated() throws {
        let app = try launchConnected(["-uitest-fresh", "-uitest-no-license"])

        for id in ["send-photos", "send-files", "send-text"] {
            let tile = app.buttons[id]
            XCTAssertTrue(tile.waitForExistence(timeout: 8), "缺少免费入口 \(id)")
            XCTAssertFalse(tile.label.uppercased().contains("PRO"),
                           "\(id) 是免费功能，不该挂 PRO 角标：\(tile.label)")
        }

        // 图库和历史也是免费的，点开必须能用
        app.buttons["open-gallery"].tap()
        XCTAssertTrue(app.buttons["open-sync"].waitForExistence(timeout: 8)
                      || app.staticTexts.count > 0, "图库打不开")
    }
}

extension ProGateTests {

    /// 有授权时，同步能真的把相册里的新照片传到电脑。
    ///
    /// 这条要跑通需要：模拟器相册里有照片（xcrun simctl addmedia）、
    /// 一个能签发许可证的授权服务、以及 Mac 上的桌面端开着。
    func testSyncActuallyTransfers() throws {
        // xcodebuild 只转发带 TEST_RUNNER_ 前缀的变量，而且转发时会把前缀去掉 ——
        // 所以测试进程里读到的是 LICENSING_BASE。两个都试一遍最稳妥。
        let env = ProcessInfo.processInfo.environment
        guard let base = env["LICENSING_BASE"] ?? env["TEST_RUNNER_LICENSING_BASE"] else {
            throw XCTSkip("没给 LICENSING_BASE")
        }

        // 先买一份，把 Pro 打开
        let app = try launchConnected(["-licensing-base", base, "-uitest-fresh", "-uitest-no-license"])
        app.buttons["open-me"].tap()
        XCTAssertTrue(app.buttons["open-pro"].waitForExistence(timeout: 5))
        app.buttons["open-pro"].tap()
        XCTAssertTrue(app.buttons["plan-lifetime"].waitForExistence(timeout: 10))
        app.buttons["plan-lifetime"].tap()
        app.buttons["pro-buy"].tap()
        XCTAssertTrue(app.staticTexts["pro-activated"].waitForExistence(timeout: 30),
                      "没买成，后面测不了")

        // 关掉两层 sheet 回主界面。
        //
        // 用 identifier 而不是 NSLocalizedString("common.done") ——
        // 后者在测试 bundle 里查不到 App 的文案，返回的是 key 本身，
        // 于是永远点不到那个按钮。这个坑我踩过两次了。
        app.buttons["close-pro"].tap()
        app.buttons["close-me"].tap()

        let sync = app.buttons["open-sync"]
        XCTAssertTrue(sync.waitForExistence(timeout: 10), "回不到主界面")
        sync.tap()

        // 相册权限用 simctl 预授权，别在测试里等弹窗：
        //   xcrun simctl privacy <device> grant photos life.mkstore.droidtrans
        // addUIInterruptionMonitor 只在「有交互发生」时才会去检查弹窗，
        // 时序很难对上，跑十次能过三次。
        let start = app.buttons["sync-start"]
        guard start.waitForExistence(timeout: 30) else {
            let a = XCTAttachment(screenshot: app.screenshot())
            a.name = "同步页当时的状态"
            a.lifetime = .keepAlways
            add(a)
            throw XCTSkip("相册里没有待同步的照片，或者权限没给")
        }
        start.tap()

        // 传完之后回到「都已经传过了」
        let idle = app.buttons["sync-start"]
        let finished = NSPredicate(format: "exists == false")
        expectation(for: finished, evaluatedWith: idle, handler: nil)
        waitForExpectations(timeout: 90)
    }
}
