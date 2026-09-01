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

    private func shot(_ app: XCUIApplication, _ name: String) {
        let a = XCTAttachment(screenshot: app.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }

    func testCaptureKeyScreens() throws {
        // 截的是「还没买过」的样子 —— 那才是新用户看到的界面。
        // 不清的话上一次购买留下的许可证会让 Pro 页变成「已激活」。
        let app = try launchConnected(["-uitest-fresh", "-uitest-no-license"])
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
}
