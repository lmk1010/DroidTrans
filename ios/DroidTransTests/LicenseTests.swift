import XCTest
@testable import DroidTrans

/// 许可证验签的跨端一致性。
///
/// 向量是授权服务（Node）真实签发的那一份，Go 桌面端也验过同一份。
/// Swift 这边自己造一份来验自己没有意义 —— 要保证的是
/// 「Node 签的东西，Go 和 Swift 都认」。
final class LicenseTests: XCTestCase {

    private func vector() throws -> String {
        let bundle = Bundle(for: type(of: self))
        guard let url = bundle.url(forResource: "license_vector", withExtension: "txt"),
              let s = try? String(contentsOf: url, encoding: .utf8) else {
            throw XCTSkip("没有向量文件")
        }
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    func testVerifiesRealLicense() throws {
        let token = try vector()
        // 向量放久了会先撞上「太久没回连」，那不影响签名是否正确 ——
        // 这条用例要证明的是签名和字段解析，所以把时效状态单独接住。
        do {
            let lic = try LicenseVerifier.verify(token)
            XCTAssertEqual(lic.product, "droidtrans-pro")
            XCTAssertFalse(lic.code.isEmpty)
        } catch LicenseError.stale(let lic) {
            XCTAssertEqual(lic.product, "droidtrans-pro")
            XCTAssertFalse(lic.code.isEmpty)
        } catch LicenseError.expired(let lic) {
            XCTAssertEqual(lic.product, "droidtrans-pro")
        }
    }

    func testRejectsTamperedLicense() throws {
        let token = try vector()
        var chars = Array(token)
        // 动载荷里的一个字符，签名必须失效
        let i = chars.count / 4
        chars[i] = chars[i] == "A" ? "B" : "A"

        XCTAssertThrowsError(try LicenseVerifier.verify(String(chars))) { err in
            if case LicenseError.expired = err {
                XCTFail("改过的许可证竟然只是被判成过期，签名没起作用")
            }
            if case LicenseError.stale = err {
                XCTFail("改过的许可证竟然只是被判成过期，签名没起作用")
            }
        }
    }

    func testRejectsGarbage() {
        XCTAssertThrowsError(try LicenseVerifier.verify(""))
        XCTAssertThrowsError(try LicenseVerifier.verify("no-dot-here"))
        XCTAssertThrowsError(try LicenseVerifier.verify(".onlysig"))
        XCTAssertThrowsError(try LicenseVerifier.verify("onlybody."))
    }

    /// 服务端签的时间戳带毫秒。默认的 ISO8601 formatter 认不了它，
    /// 会把每个日期都解析成 nil —— 于是「终身版」和「已过期」都判不出来。
    func testParsesFractionalSecondTimestamps() throws {
        let token = try vector()
        let lic = (try? LicenseVerifier.verify(token)) ?? {
            if case let LicenseError.stale(l)? = (try? LicenseVerifier.verify(token)) as Any as? LicenseError { return l }
            return nil
        }()
        guard let lic else { throw XCTSkip("向量已失效") }
        XCTAssertNotNil(lic.issuedAt, "issued 解析不出来，时效判断会全部失灵")
    }
}
