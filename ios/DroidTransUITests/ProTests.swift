import StoreKitTest
import XCTest

/// 会员这一屏。
///
/// 商品由 SKTestSession 在本地起一个假商店提供，不用先在
/// App Store Connect 建商品也能跑完整流程。
///
/// 不能只靠 scheme 里的 storeKitConfiguration：那个只对 Xcode 的 Run 生效，
/// xcodebuild test 和 simctl launch 都不走它 —— 结果就是界面上一排「—」，
/// 而测试还以为自己在测真东西。
///
/// 这条用例证明的是「界面和 StoreKit 的对接没问题」，不是「上架后能卖」。
/// 后者要等 App Store Connect 上把三个商品建出来。
final class ProTests: XCTestCase {

    private var session: SKTestSession!

    override func setUpWithError() throws {
        try super.setUpWithError()
        continueAfterFailure = false
        session = try SKTestSession(configurationFileNamed: "Products")
        session.resetToDefaultState()
        session.clearTransactions()
        // 自动化里不要弹「确认购买」，否则测试会卡在系统弹窗上
        session.disableDialogs = true
    }

    override func tearDown() {
        session = nil
        super.tearDown()
    }

    private func openMe(_ app: XCUIApplication) throws {
        let me = app.buttons["open-me"]
        if !me.waitForExistence(timeout: 10) {
            let a = XCTAttachment(screenshot: app.screenshot())
            a.name = "找不到我的入口时的界面"
            a.lifetime = .keepAlways
            add(a)
            XCTFail("主界面上没有「我的」入口")
        }
        me.tap()
    }

    /// 已知问题：这条单独跑稳定通过，混在整套里跑必失败。
    ///
    /// 现象是 launchConnected 明明返回了，界面却还停在雷达页（有诊断截图为证）。
    /// 已经排除的：等待时序、许可证/配对状态残留、launch 前强制 terminate。
    /// 怀疑是同一个模拟器上连续跑多个 SKTestSession 之后 StoreKit 的进程状态没清干净，
    /// 但没有证据，所以不写成结论。
    ///
    /// 它测的东西（商品能不能从 StoreKit 取到价格）已经由
    /// testPurchaseRedeemsUniversalLicense 覆盖到 —— 那条在整套里是通过的，
    /// 而且它连兑换许可证都验了。所以先这么放着，不为了让 CI 变绿而删掉断言。
    ///
    ///   单独跑：xcodebuild test -only-testing:DroidTransUITests/ProTests/testPricesLoadFromStoreKit
    func testPricesLoadFromStoreKit() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-no-license"]
        app.launch()
        try openMe(app)

        let pro = app.buttons["open-pro"]
        XCTAssertTrue(pro.waitForExistence(timeout: 5), "「我的」里没有会员入口")
        pro.tap()

        // 三个档位都要出现，而且价格不能是占位的破折号 ——
        // 那说明商品没取到，用户看到的是一排「—」
        for plan in ["year", "years3", "lifetime"] {
            let row = app.buttons["plan-\(plan)"]
            XCTAssertTrue(row.waitForExistence(timeout: 10), "缺少档位 \(plan)")
        }

        let buy = app.buttons["pro-buy"]
        XCTAssertTrue(buy.waitForExistence(timeout: 5))
        // 必须真的有数字。之前这里只断言「不含破折号」，
        // 而商品取不到时按钮写的是「商品暂时取不到」—— 照样不含破折号，
        // 于是一个彻底坏掉的界面把测试骗过去了。
        XCTAssertNotNil(
            buy.label.rangeOfCharacter(from: .decimalDigits),
            "购买按钮上没有价格，商品没从 StoreKit 取到：\(buy.label)"
        )

        // 档位行上的价格同理
        let lifetime = app.buttons["plan-lifetime"]
        XCTAssertNotNil(
            lifetime.label.rangeOfCharacter(from: .decimalDigits),
            "终身档没有价格：\(lifetime.label)"
        )
    }
}

extension ProTests {

    /// 走完一次购买，并且确认它换回了一份三端通用的许可证。
    ///
    /// 光解锁本机是不够的 —— 用户在 Mac 上也该能用同一份授权，
    /// 所以这条用例真正要证明的是「买完之后 LicenseStore 里有东西」。
    ///
    /// 需要一个接受 Xcode 测试凭证的授权服务：
    ///   ALLOW_STOREKIT_TEST=1 PORT=8798 HOST=0.0.0.0 node dist/server.mjs
    ///   TEST_RUNNER_LICENSING_BASE=http://<Mac 的局域网地址>:8798 xcodebuild test …
    func testPurchaseRedeemsUniversalLicense() throws {
        let env = ProcessInfo.processInfo.environment
        guard let base = env["TEST_RUNNER_LICENSING_BASE"] ?? env["LICENSING_BASE"] else {
            throw XCTSkip("没给 LICENSING_BASE，跳过内购兑换")
        }

        let app = try launchConnected(["-licensing-base", base, "-uitest-fresh", "-uitest-no-license"])
        try openMe(app)
        let pro = app.buttons["open-pro"]
        XCTAssertTrue(pro.waitForExistence(timeout: 5))
        pro.tap()

        let lifetime = app.buttons["plan-lifetime"]
        XCTAssertTrue(lifetime.waitForExistence(timeout: 10))
        lifetime.tap()

        let buy = app.buttons["pro-buy"]
        XCTAssertTrue(buy.waitForExistence(timeout: 5))
        buy.tap()

        // 买完这一屏会换成「已激活」。换不过来说明要么购买没成功，
        // 要么凭证没换到许可证 —— 后者更要紧，那意味着用户在 Mac 上拿不到码。
        //
        // 用 identifier 而不是文案定位：NSLocalizedString 在测试 bundle 里查，
        // 那儿没有 App 的 strings，取回来的是 key 本身，
        // 于是断言永远找不到元素 —— 产品明明是好的，测试却红着。
        let activated = app.staticTexts["pro-activated"]
        XCTAssertTrue(
            activated.waitForExistence(timeout: 30),
            "买完了但没变成已激活 —— 交易没换回许可证"
        )
    }
}
