/// 接收端二维码的格式，以及 AWDL 那种链路本地地址进 URL 的写法。
///
/// 二维码这套格式 Android 端 network/PeerLink.java 有一份一模一样的实现，
/// 两边对不上的表现是「扫了没反应」—— 最难查的那类问题，所以逐字段钉住。

import XCTest
@testable import DroidTrans

final class PeerLinkTests: XCTestCase {

    func testRoundTripsHotspotCredentials() throws {
        let qr = PeerLink.encode(host: "192.168.49.1", port: 9600, code: "123456",
                                 name: "iPhone 15", ssid: "DroidTrans-3f2",
                                 password: "8a91 c07b")
        let link = try XCTUnwrap(PeerLink.parse(qr))

        XCTAssertEqual(link.host, "192.168.49.1")
        XCTAssertEqual(link.port, 9600)
        XCTAssertEqual(link.code, "123456")
        XCTAssertEqual(link.name, "iPhone 15")
        XCTAssertEqual(link.ssid, "DroidTrans-3f2")
        // 密码里的空格要原样回来，差一个字符就连不上那个热点
        XCTAssertEqual(link.password, "8a91 c07b")
        XCTAssertTrue(link.hasHotspot)
        XCTAssertEqual(link.baseURL, "http://192.168.49.1:9600")
    }

    func testNoHotspotFieldsWhenNotSharing() throws {
        let link = try XCTUnwrap(PeerLink.parse(
            PeerLink.encode(host: "10.0.0.8", port: 9600, code: "654321", name: "iPhone")))
        XCTAssertFalse(link.hasHotspot)
        XCTAssertEqual(link.ssid, "")
    }

    /// 电脑端一直发的就是这个形状。新字段是加出来的，老码必须照常能用。
    func testAcceptsDesktopQR() throws {
        let link = try XCTUnwrap(PeerLink.parse("http://192.168.1.5:9500/?c=187931"))
        XCTAssertEqual(link.host, "192.168.1.5")
        XCTAssertEqual(link.port, Ports.http)
        XCTAssertEqual(link.code, "187931")
        XCTAssertFalse(link.hasHotspot)
    }

    func testBareAddressFallsBackToDesktopPort() throws {
        let link = try XCTUnwrap(PeerLink.parse("192.168.1.5"))
        XCTAssertEqual(link.port, Ports.http)
    }

    func testRejectsGarbage() {
        XCTAssertNil(PeerLink.parse(""))
        XCTAssertNil(PeerLink.parse("   "))
    }

    // MARK: - AWDL 的链路本地地址

    /// 少了方括号和 %25，URLSession 直接判这条地址不合法 ——
    /// 两台 iPhone 不经路由器直连时，能拿到的只有这种地址。
    func testLinkLocalIPv6BecomesUsableURL() {
        XCTAssertEqual(urlHost("fe80::14ab:5cff:fe12:1%awdl0"),
                       "[fe80::14ab:5cff:fe12:1%25awdl0]")
        XCTAssertEqual(plainHost("[fe80::14ab:5cff:fe12:1%25awdl0]"),
                       "fe80::14ab:5cff:fe12:1%awdl0")
        // IPv4 一个字都不该动
        XCTAssertEqual(urlHost("192.168.1.5"), "192.168.1.5")

        let d = Desktop(host: "fe80::1%awdl0", port: Ports.peer, name: "iPhone")
        XCTAssertEqual(d.baseURL, "http://[fe80::1%25awdl0]:9600")
        XCTAssertNotNil(URL(string: d.baseURL))
    }
}
