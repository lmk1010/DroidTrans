import XCTest

/// 手机 ↔ 手机，两台设备之间真的跑一遍。
///
/// 这条路一直只有单元测试覆盖 —— 协议层的字节拼得对不对测过了，但
/// 「一台亮出来、另一台找到它、敲门、同意、把文件推过去」这一整条链路
/// 从没在两台设备之间跑过。而它恰恰是产品对外承诺的三条路之一。
///
/// 两台模拟器共用宿主机的网络栈，所以只有接收方会占 9600 端口，
/// 发送方只做发现，不冲突。
///
/// 怎么跑（两条命令并行，各自指定设备）：
///
///   xcodebuild test -only-testing:DroidTransUITests/PeerToPeerTests/testHoldAsReceiver \
///     -destination 'id=<接收方 UDID>' &
///   xcodebuild test -only-testing:DroidTransUITests/PeerToPeerTests/testSendToPeer \
///     -destination 'id=<发送方 UDID>'
final class PeerToPeerTests: XCTestCase {

    private func launch(_ extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.terminate()
        app.launchArguments = ["-uitest-fresh", "-uitest-no-license"] + extra
        app.launch()
        _ = app.wait(for: .runningForeground, timeout: 15)
        return app
    }

    /// 接收方：亮出来，然后一直等着，中途有人敲门就同意。
    ///
    /// 它不做断言 —— 断言在发送方那条用例里。这条的职责是把 App 停在
    /// 「等待连接」那一屏足够久，让另一台跑完。
    func testHoldAsReceiver() throws {
        let app = launch()
        XCTAssertTrue(app.buttons["start-iphone"].waitForExistence(timeout: 15), "没到启动选择页")
        app.buttons["start-iphone"].tap()

        XCTAssertTrue(app.buttons["peer-recv"].waitForExistence(timeout: 8), "没看到「我要收」")
        app.buttons["peer-recv"].tap()

        // 等它把自己亮出来（地址 + 配对码）
        XCTAssertTrue(app.staticTexts["peer-code"].waitForExistence(timeout: 15),
                      "接收方没有亮出地址和配对码，对面根本找不到")

        // 剩下的时间用来等对面敲门。弹出来就同意。
        let deadline = Date().addingTimeInterval(150)
        var approved = false
        while Date() < deadline {
            // 收到东西就说明整条链路通了，可以收工
            if app.staticTexts["peer-got-count"].exists { break }
            // 敲门弹的是 alert，按钮没有 accessibilityIdentifier，
            // 只能按标题找。中英各一个词。
            let allow = app.buttons.matching(NSPredicate(
                format: "label == %@ OR label == %@", "同意", "Accept")).firstMatch
            if allow.exists && allow.isHittable {
                allow.tap()
                approved = true
            }
            Thread.sleep(forTimeInterval: 1)
        }
        XCTAssertTrue(approved, "整个等待期内没等到敲门 —— 对面没连过来")
        XCTAssertTrue(app.staticTexts["peer-got-count"].waitForExistence(timeout: 60),
                      "连上了但什么都没收到 —— 传输那一段是断的")
    }

    /// 发送方：找到对面那台，连上去。
    func testSendToPeer() throws {
        let app = launch()
        XCTAssertTrue(app.buttons["start-iphone"].waitForExistence(timeout: 15), "没到启动选择页")
        app.buttons["start-iphone"].tap()

        XCTAssertTrue(app.buttons["peer-send"].waitForExistence(timeout: 8), "没看到「我要发」")
        app.buttons["peer-send"].tap()

        // 雷达上应当出现对面那台手机
        let node = app.buttons["radar-node"].firstMatch
        XCTAssertTrue(node.waitForExistence(timeout: 40),
                      "雷达上没找到另一台手机 —— 手机互传这条路在两台设备之间是断的")
        XCTAssertFalse(node.label.isEmpty, "节点上没有设备名，用户分不清连的是哪台")
        node.tap()

        // 敲门之后：先进「正在等对方确认」，对面同意了再进主界面。
        // 别用文案匹配 —— 之前找的是「等待」，而那一屏写的是
        // 「正在等对方确认」，对不上，于是把一次成功的连接报成了失败。
        let waiting = app.staticTexts["approval-waiting"]
        let home = app.buttons["send-photos"]
        let reached = waiting.waitForExistence(timeout: 20) || home.exists
        if !reached {
            add(shot(app, "stuck-after-tap"))
        }
        XCTAssertTrue(reached, "点了对面那台之后既没连上也没进「等待对方确认」")

        // 对面同意之后应当进主界面
        let connected = home.waitForExistence(timeout: 90)
        if !connected {
            add(shot(app, "not-connected"))
        }
        XCTAssertTrue(connected, "对面同意了但这边没进主界面")

        // 连上不等于能传。发一段文字过去 —— 文字不用过系统相册选择器，
        // 是这条链路上最容易在自动化里跑通的一种。
        XCTAssertTrue(app.buttons["send-text"].waitForExistence(timeout: 10), "主界面上没有发文字入口")
        app.buttons["send-text"].tap()

        let editor = app.textViews["text-editor"].firstMatch
        XCTAssertTrue(editor.waitForExistence(timeout: 8), "没打开文字输入")
        editor.tap()
        editor.typeText("Hello from the other phone")

        XCTAssertTrue(app.buttons["send-text-button"].waitForExistence(timeout: 5), "没有发送按钮")
        app.buttons["send-text-button"].tap()
        Thread.sleep(forTimeInterval: 5)
    }

    private func shot(_ app: XCUIApplication, _ name: String) -> XCTAttachment {
        let a = XCTAttachment(screenshot: app.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        return a
    }
}
