/// 真机联调用的配置。默认全空 —— 空的时候那些用例自动跳过。
///
/// 为什么不用环境变量或宿主机上的开关文件：真机上都读不到。
/// xcodebuild 的 `TEST_RUNNER_XXX` 只喂给 UI 测试的 runner，
/// 而 App 内跑的单测在设备上也看不到宿主机的文件系统。
/// 所以联调时用脚本把这个文件写一遍再构建，跑完改回空值。
enum Interop {
    /// 电脑的地址，例如 "192.168.10.15:9500"
    static let desktop = ""
    /// 电脑上显示的六位配对码
    static let desktopCode = ""
    /// 对面那台手机的地址，例如 "192.168.10.14:9600"
    static let peer = ""
    /// 这台当接收端等对面连过来
    static let serve = false
}
