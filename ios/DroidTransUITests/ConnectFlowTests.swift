import XCTest

/// 连接与配对的端到端流程，跑在模拟器上，对着真实的 Mac 桌面端。
///
/// 这一套不是为了覆盖率，是为了回答一个问题：
/// 「手机开机之后，普通用户能不能靠自己连上电脑并把文件传过去。」
/// 单测里那些帧编码、JSON 解析全对，这条路照样可能是断的 ——
/// Bonjour 的 SRV 记录写错过一次，就是所有单测全绿而自动发现全平台失效。
///
/// 跑之前要满足：
///   1. Mac 上的卓传开着
///   2. 配对码用环境变量传进来。注意必须带 TEST_RUNNER_ 前缀 ——
///      xcodebuild 只把带这个前缀的变量转发给测试进程，
///      而且转发时会把前缀去掉，所以测试里读的是 PAIR_CODE 而不是
///      TEST_RUNNER_PAIR_CODE。两个都读一遍最省心。
///      直接写 PAIR_CODE=… 则根本传不进去，用例会被静默跳过：
///
///        TEST_RUNNER_PAIR_CODE=$(curl -s http://127.0.0.1:9500/api/pair/info \
///          | python3 -c "import sys,json;print(json.load(sys.stdin)['code'])") \
///        xcodebuild test -scheme DroidTrans -only-testing:DroidTransUITests
final class ConnectFlowTests: XCTestCase {

    private var app: XCUIApplication!

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        app = XCUIApplication()
        // 每次都从「刚装好」的状态开始。不加这个的话，上一次跑完留下的配对
        // 会让 App 直接连回电脑、跳过雷达页，用例会以「没找到电脑」假失败
        app.launchArguments = ["-uitest-fresh"]
        app.launch()
    }

    /// 雷达要能在合理时间内把电脑扫出来。
    ///
    /// 给到 25 秒：mDNS 本身要几秒，NetService 解析还要一轮往返。
    /// 真机上通常 2～3 秒就出来了，这里放宽是为了不让 CI 因为网络抖动误报。
    func testRadarFindsDesktop() {
        let node = app.buttons["radar-node"].firstMatch
        XCTAssertTrue(
            node.waitForExistence(timeout: 25),
            "雷达上没出现任何电脑。检查：Mac 上的卓传开着吗？两边在同一个网络吗？"
        )
        XCTAssertFalse(node.label.isEmpty, "节点上没有设备名，用户看不出这是哪台机器")
    }

    /// 点了节点应该进到配对页（桌面端要求配对时）。
    func testTappingNodeLeadsToPairing() {
        let node = app.buttons["radar-node"].firstMatch
        guard node.waitForExistence(timeout: 25) else {
            XCTFail("雷达上没出现电脑")
            return
        }
        node.tap()

        // 要么进配对页，要么直接连上（桌面端关了配对，或这台手机之前配过）
        let pairTitle = app.staticTexts["pair-title"]
        let home = app.buttons["send-photos"]
        let arrived = pairTitle.waitForExistence(timeout: 15) || home.waitForExistence(timeout: 3)
        XCTAssertTrue(arrived, "点了电脑之后既没进配对页也没连上，卡在原地了")
    }

    /// 完整走一遍：发现 → 配对 → 进主界面。
    ///
    /// 配对码每次桌面端重启都会变，所以从环境变量取；没给就跳过，
    /// 而不是让这条用例红着 —— 它失败与否取决于外部状态，不该拖垮整个套件。
    func testPairAndReachMainScreen() throws {
        guard let code = ProcessInfo.processInfo.environment["PAIR_CODE"], code.count == 6 else {
            throw XCTSkip("没给 PAIR_CODE，跳过。取码：curl -s http://127.0.0.1:9500/api/pair/info")
        }

        let node = app.buttons["radar-node"].firstMatch
        guard node.waitForExistence(timeout: 25) else {
            XCTFail("雷达上没出现电脑")
            return
        }
        node.tap()

        let home = app.buttons["send-photos"]
        if home.waitForExistence(timeout: 6) {
            // 已经配过了，直接就进来了
            return
        }

        let field = app.textFields["pair-code-field"]
        XCTAssertTrue(field.waitForExistence(timeout: 10), "没进到配对页")
        field.tap()
        field.typeText(code)

        XCTAssertTrue(
            home.waitForExistence(timeout: 15),
            "配对码输完了但没进主界面 —— 码不对，或者配对请求失败了"
        )
    }
}

/// 配对之后重启 App，应该自动连回去，不该再让用户点一次雷达。
///
/// 分两段跑：第一段带 -uitest-fresh 从零配对，第二段不带、直接重启，
/// 断言落到主界面。这是「配对是一次性的事」这个承诺的唯一验证方式。
final class ReconnectTests: XCTestCase {

    func testReconnectsAfterRelaunch() throws {
        let env = ProcessInfo.processInfo.environment
        guard let code = env["TEST_RUNNER_PAIR_CODE"] ?? env["PAIR_CODE"], code.count == 6 else {
            throw XCTSkip("没给 PAIR_CODE，跳过")
        }

        // 第一段：干净状态下完成配对
        let first = XCUIApplication()
        first.launchArguments = ["-uitest-fresh"]
        first.launch()

        let home = first.buttons["send-photos"]
        if !home.waitForExistence(timeout: 4) {
            let node = first.buttons["radar-node"].firstMatch
            guard node.waitForExistence(timeout: 25) else {
                throw XCTSkip("雷达上没出现电脑")
            }
            node.tap()

            if !home.waitForExistence(timeout: 6) {
                let field = first.textFields["pair-code-field"]
                XCTAssertTrue(field.waitForExistence(timeout: 10), "没进到配对页")
                field.tap()
                field.typeText(code)
                XCTAssertTrue(home.waitForExistence(timeout: 15), "配对没成功")
            }
        }
        first.terminate()

        // 第二段：重启，这次不清状态
        let second = XCUIApplication()
        second.launchArguments = []
        second.launch()

        XCTAssertTrue(
            second.buttons["send-photos"].waitForExistence(timeout: 25),
            "重启后没有自动连回电脑，停在了雷达页 —— 配对等于白配了"
        )
    }
}
