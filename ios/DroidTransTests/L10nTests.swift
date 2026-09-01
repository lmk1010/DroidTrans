import XCTest
@testable import DroidTrans

/// 两份文案必须严格对齐。
///
/// 少一条 key 的后果不是崩溃，而是那个语言下界面上直接显示
/// "home.send.photos" 这种东西 —— 而且只有切到那个语言才看得见，
/// 平时开发根本撞不到。
final class L10nTests: XCTestCase {

    private func keys(of lang: String) throws -> Set<String> {
        let bundle = Bundle(for: type(of: self))
        guard let url = bundle.url(forResource: "Localizable", withExtension: "strings",
                                   subdirectory: nil, localization: lang)
                ?? Bundle.main.url(forResource: "Localizable", withExtension: "strings",
                                   subdirectory: nil, localization: lang),
              let dict = NSDictionary(contentsOf: url) as? [String: String] else {
            throw XCTSkip("读不到 \(lang) 的文案，可能没打进测试 bundle")
        }
        return Set(dict.keys)
    }

    func testChineseAndEnglishHaveSameKeys() throws {
        let en = try keys(of: "en")
        let zh = try keys(of: "zh-Hans")

        XCTAssertFalse(en.isEmpty, "英文文案是空的")

        let missingInZh = en.subtracting(zh).sorted()
        let missingInEn = zh.subtracting(en).sorted()

        XCTAssertTrue(missingInZh.isEmpty, "中文缺这些 key：\(missingInZh)")
        XCTAssertTrue(missingInEn.isEmpty, "英文缺这些 key：\(missingInEn)")
    }

    /// 带参数的文案，两边的占位符数量要一致，
    /// 否则 String(format:) 会在其中一个语言下崩掉或者少显示内容。
    func testPlaceholdersMatch() throws {
        let bundle = Bundle.main
        func dict(_ lang: String) -> [String: String]? {
            guard let url = bundle.url(forResource: "Localizable", withExtension: "strings",
                                       subdirectory: nil, localization: lang) else { return nil }
            return NSDictionary(contentsOf: url) as? [String: String]
        }
        guard let en = dict("en"), let zh = dict("zh-Hans") else {
            throw XCTSkip("读不到文案")
        }
        for (key, value) in en {
            let a = value.components(separatedBy: "%@").count
            let b = (zh[key] ?? "").components(separatedBy: "%@").count
            XCTAssertEqual(a, b, "\(key) 的占位符数量对不上：en=\(a - 1) zh=\(b - 1)")
        }
    }
}
