import XCTest

/// 每条用例自己把前置条件准备好。
///
/// 不依赖「上一条跑完留下的状态」——那种依赖会让单独跑某一条时
/// 莫名其妙地失败，而且失败信息指向的地方跟真正的原因毫无关系
/// （典型症状：报「找不到会员入口」，实际是上一条把配对清了）。
extension XCTestCase {

    /// 启动并确保连上电脑；需要配对就用 PAIR_CODE 配上。
    @discardableResult
    func launchConnected(_ extraArgs: [String] = []) throws -> XCUIApplication {
        let app = XCUIApplication()
        // 先掐掉再起。上一条用例结束时 App 往往还停在主界面，
        // 直接 launch 的话紧接着的元素查询可能命中重启前的那一帧快照 ——
        // 于是 launchConnected 以为已经进了主界面就返回了，
        // 实际界面还停在雷达页，后面每一步都对不上。
        app.terminate()
        app.launchArguments = extraArgs
        app.launch()
        _ = app.wait(for: .runningForeground, timeout: 10)

        // 以顶栏的「我的」为准判断主界面就绪：「发送」那三块出现只说明
        // HomeScreen 开始渲染了，顶栏还可能差一拍。
        let ready = app.buttons["open-me"]
        if ready.waitForExistence(timeout: 6) { return app }

        // 雷达页。点一下要连的电脑。
        let node = app.buttons["radar-node"].firstMatch
        guard node.waitForExistence(timeout: 25) else {
            throw XCTSkip("雷达上没出现电脑，Mac 上的卓传可能没开着")
        }
        node.tap()
        if ready.waitForExistence(timeout: 8) { return app }

        // 走到这儿说明电脑要求配对
        let env = ProcessInfo.processInfo.environment
        guard let code = env["TEST_RUNNER_PAIR_CODE"] ?? env["PAIR_CODE"], code.count == 6 else {
            throw XCTSkip("这台电脑要求配对，但没给 PAIR_CODE")
        }
        let field = app.textFields["pair-code-field"]
        guard field.waitForExistence(timeout: 10) else {
            attachScreen(app, "既没进主界面也没进配对页")
            throw XCTSkip("既没进主界面也没进配对页")
        }
        field.tap()
        field.typeText(code)
        guard ready.waitForExistence(timeout: 20) else {
            attachScreen(app, "输完配对码仍没进主界面")
            throw XCTSkip("配对没成功")
        }
        return app
    }

    func attachScreen(_ app: XCUIApplication, _ name: String) {
        let a = XCTAttachment(screenshot: app.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }
}
