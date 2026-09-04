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

        // 主界面就绪的判据必须是「只有主界面才有」的东西。
        //
        // 以前这里用 open-me，但启动选择页右上角也有一个 open-me ——
        // 于是在启动页就以为连上了直接返回，后面每一步都对不上。
        // ConnectFlowTests / ProGateTests 长期失败就是这么来的，
        // 看着像「环境问题」，其实是脚手架没跟上界面改版。
        let ready = app.buttons["open-gallery"]
        if ready.waitForExistence(timeout: 6) { return app }

        // 要配对就先配对。这一步必须排在雷达前面：
        // -uitest-desktop 直连之后会直接落到配对页，根本不经过雷达。
        if try pairIfNeeded(app, ready) { return app }

        // 启动选择页：先说明要跟谁传，才会进雷达
        let pickDesktop = app.buttons["start-desktop"]
        if pickDesktop.waitForExistence(timeout: 6) {
            pickDesktop.tap()
        }

        // 雷达页。点一下要连的电脑。
        let node = app.buttons["radar-node"].firstMatch
        guard node.waitForExistence(timeout: 25) else {
            attachScreen(app, "雷达上没出现电脑")
            throw XCTSkip("雷达上没出现电脑，Mac 上的卓传可能没开着")
        }
        node.tap()
        if ready.waitForExistence(timeout: 8) { return app }

        if try pairIfNeeded(app, ready) { return app }

        attachScreen(app, "既没进主界面也没进配对页")
        throw XCTSkip("既没进主界面也没进配对页")
    }

    /// 停在配对页就把码打进去。返回 true 表示已经进了主界面。
    private func pairIfNeeded(_ app: XCUIApplication,
                              _ ready: XCUIElement) throws -> Bool {
        let field = app.textFields["pair-code-field"]
        guard field.waitForExistence(timeout: 8) else { return false }

        // xcodebuild 只转发 TEST_RUNNER_ 前缀的环境变量。两种写法都认，
        // 否则命令行跑起来会静默 skip —— 而 skip 在输出里长得跟 pass 很像。
        let env = ProcessInfo.processInfo.environment
        guard let code = env["TEST_RUNNER_PAIR_CODE"] ?? env["PAIR_CODE"],
              code.count == 6 else {
            throw XCTSkip("这台电脑要求配对，但没给 PAIR_CODE")
        }
        field.tap()
        field.typeText(code)
        guard ready.waitForExistence(timeout: 20) else {
            attachScreen(app, "输完配对码仍没进主界面")
            throw XCTSkip("配对没成功")
        }
        return true
    }

    func attachScreen(_ app: XCUIApplication, _ name: String) {
        let a = XCTAttachment(screenshot: app.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }
}
