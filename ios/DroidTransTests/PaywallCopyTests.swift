import XCTest
@testable import DroidTrans

/// 付费页文案和真实门控必须对得上。
///
/// 出过两次事：
///   · 「原始画质」被列成 Pro 权益，可免费版走 PHPicker 的 .current
///     拿到的本来就是原图 —— 等于卖一个免费就有的东西。
///   · 免费导出额度从 200 调到 1000，桌面端和官网都改了、iOS 漏了，
///     同一件事三端给出两个数字。
///
/// 这类错误肉眼很难发现（要同时开三个平台的文案对着看），
/// 但用户一眼就看得出来，而且伤的是信任。交给测试盯。
final class PaywallCopyTests: XCTestCase {

    /// 免费额度的数字必须和服务端一致。
    ///
    /// 服务端的值在 desktop/internal/app（FreeMaxFileSize / FreePhotosExport），
    /// 这里写死一份做对照 —— 改额度时两边都会被迫过一遍。
    func testQuotaNumbersMatchServer() {
        XCTAssertTrue(L("pro.row.filesize.free").contains("4 GB"),
                      "单文件额度和服务端 FreeMaxFileSize(4 GB) 对不上：\(L("pro.row.filesize.free"))")
        XCTAssertTrue(L("pro.row.export.free").contains("1000"),
                      "导出额度和服务端 FreePhotosExport(1000) 对不上：\(L("pro.row.export.free"))")
    }

    /// 对比表里每一行都得有文案，不能把 key 本身显示给用户。
    func testEveryCompareRowHasCopy() {
        let keys = [
            "pro.row.lan", "pro.row.lan.both",
            "pro.row.resume", "pro.row.filesize", "pro.row.filesize.free",
            "pro.row.sync", "pro.row.live", "pro.row.live.free", "pro.row.live.pro",
            "pro.row.export", "pro.row.export.free",
            "pro.row.usb", "pro.row.dedupe",
            "pro.compare.free", "pro.compare.pro", "pro.compare.mine",
            "pro.compare.yes", "pro.compare.no", "pro.compare.unlimited",
            "pro.section.phone", "pro.section.mac",
            "pro.limit.title", "pro.limit.body", "pro.limit.upgrade",
            "license.remove.confirm", "license.remove.hint",
        ]
        for k in keys {
            XCTAssertNotEqual(L(k), k, "少了文案：\(k)")
            XCTAssertFalse(L(k).isEmpty, "文案是空的：\(k)")
        }
    }

    /// 免费那一列不能全是「没有」。
    ///
    /// 一张只写「Pro 有、免费没有」的表，用户第一反应是被阉割了。
    /// 局域网互传和断点续传本来就免费，必须实实在在写在免费列里。
    func testFreeColumnIsNotAllDashes() {
        XCTAssertNotEqual(L("pro.row.lan.both"), L("pro.compare.no"),
                          "局域网互传在免费列里被写成了「—」，但它本来就免费")
    }
}
