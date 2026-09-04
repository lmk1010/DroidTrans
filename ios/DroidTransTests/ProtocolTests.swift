import XCTest
@testable import DroidTrans

/// 线格式本身的正确性由 Go 端的 TestATF3VectorsFromSwift 保证 ——
/// 那边用真正的 readHeader 解析这边生成的字节。
/// 这里只测在 Swift 内部就能测完的部分：边界拒绝、应答解析、地址整理。
final class ProtocolTests: XCTestCase {

    // MARK: - 帧

    func testHeaderLayout() throws {
        let h = try buildATF3Header(name: "a.txt", size: 5, token: "tok")
        // magic(4) + tokenLen(4) + token(3) + nameLen(4) + name(5) + size(8)
        XCTAssertEqual(h.count, 4 + 4 + 3 + 4 + 5 + 8)
        XCTAssertEqual(h.prefix(4), Data("ATF3".utf8))
    }

    func testLengthsAreBytesNotCharacters() throws {
        // emoji 是 4 字节 UTF-8。按字符数写长度的话，服务端会少读几个字节，
        // 然后把文件内容的开头当成 size 去解析 —— 症状是「文件名乱码 + 大小离谱」
        let name = "🏖️.mp4"
        let h = try buildATF3Header(name: name, size: 1, token: "")
        let nameBytes = Array(name.utf8).count
        XCTAssertEqual(h.count, 4 + 4 + 0 + 4 + nameBytes + 8)
    }

    func testRejectsOversizedFields() {
        XCTAssertThrowsError(
            try buildATF3Header(name: "a", size: 1, token: String(repeating: "k", count: 513)),
            "令牌超过 512 字节必须在本地就拒掉，不能发出去等服务端回 ERR"
        )
        XCTAssertThrowsError(
            try buildATF3Header(name: String(repeating: "n", count: 4097), size: 1, token: "t")
        )
        XCTAssertThrowsError(try buildATF3Header(name: "a", size: -1, token: "t"))
    }

    func testAcceptsBoundaryExactly() {
        // 正好卡在上限上必须放行，差一个字节就拒会让 4096 字节的文件名传不了
        XCTAssertNoThrow(
            try buildATF3Header(name: "a", size: 1, token: String(repeating: "k", count: 512))
        )
        XCTAssertNoThrow(
            try buildATF3Header(name: String(repeating: "n", count: 4096), size: 1, token: "t")
        )
    }

    // MARK: - 应答

    func testStatusBytesMatchServer() {
        // 这三个值必须和 desktop/internal/fast/fast.go 里的常量一致。
        // 对不上的表现是「传完了却报失败」或者更糟：把错误当成功。
        XCTAssertEqual(ATFStatus.go.rawValue, 0x00)
        XCTAssertEqual(ATFStatus.error.rawValue, 0x01)
        XCTAssertEqual(ATFStatus.done.rawValue, 0x02)
    }

    func testOffsetDecoding() {
        // 偏移量是 8 字节大端。字节序写反的症状是续传从一个天文数字开始，
        // 然后 seek 失败 —— 只在大文件上出现，小文件测不出来。
        XCTAssertEqual(u64be(from: Data([0, 0, 0, 0, 0, 0, 0, 5])), 5)
        XCTAssertEqual(u64be(from: Data([0, 0, 0, 0, 0, 3, 13, 64])), 200_000)
        XCTAssertEqual(u32be(from: Data([0, 0, 1, 0])), 256)
    }

    func testOffsetSurvivesNewlineByte() {
        // 0x0A 就是换行。上一版应答按换行切分，这样的偏移量会被劈成两半，
        // 而且只在文件大小恰好含 0x0A 时复现 —— 换二进制应答就是为了根除这类 bug。
        XCTAssertEqual(u64be(from: Data([0, 0, 0, 0, 0, 0, 0x0A, 0])), 2560)
    }

    // MARK: - 地址

    func testNormalizeAddress() {
        XCTAssertEqual(normalizeAddress("192.168.1.5"), "http://192.168.1.5:9500")
        XCTAssertEqual(normalizeAddress("192.168.1.5:8080"), "http://192.168.1.5:8080")
        XCTAssertEqual(normalizeAddress("http://192.168.1.5:9500"), "http://192.168.1.5:9500")
        // 用户从二维码或聊天里粘过来常带空格
        XCTAssertEqual(normalizeAddress("  192.168.1.5  "), "http://192.168.1.5:9500")
        XCTAssertNil(normalizeAddress(""))
        XCTAssertNil(normalizeAddress("   "))
    }

    // MARK: - 模型

    func testDesktopIgnoresUnknownProtocols() {
        // 桌面端升级后可能通告 App 还不认识的协议 id。
        // 认不出来的丢掉就行，绝不能让整台电脑变得不可用。
        let d = Desktop.fromWifiInfo(host: "192.168.1.5", json: [
            "name": "MacBook Pro",
            "prefer": ["tcp", "quic-v9", "http_put"],
            "port": 9500,
        ])
        XCTAssertEqual(d.prefer, [.tcp, .httpPut])
        XCTAssertEqual(d.name, "MacBook Pro")
    }

    func testDesktopToleratesMissingFields() {
        // 老版本桌面端不会回新加的字段，缺了就该走默认值而不是解析失败
        let d = Desktop.fromWifiInfo(host: "192.168.1.5", json: [:])
        XCTAssertEqual(d.port, Ports.http)
        XCTAssertEqual(d.name, "192.168.1.5")   // 没名字就拿地址顶上
        XCTAssertTrue(d.pairingRequired)        // 拿不准时按「需要配对」处理，别把门敞着
        XCTAssertEqual(d.outboxCount, 0)
    }

    func testPhoneModelUsesPhoneArtSignals() {
        let onePlus = Desktop.fromWifiInfo(host: "192.168.1.8", json: [
            "name": "PLK110",
            "port": Ports.peer,
            "pairing_mode": "approve",
        ])
        XCTAssertTrue(onePlus.isPhone)

        let laptop = Desktop.fromWifiInfo(host: "192.168.1.9", json: [
            "name": "MacBook Pro",
            "port": Ports.http,
        ])
        XCTAssertFalse(laptop.isPhone)
    }

    func testNumbersMayArriveAsDoubleOrString() {
        // JSON 里的数字反序列化成什么类型取决于它长什么样，
        // 直接 as? Int 会在 9500.0 这种情况下静默变 nil
        XCTAssertEqual(intOf(9500 as NSNumber), 9500)
        XCTAssertEqual(intOf(9500.0 as NSNumber), 9500)
        XCTAssertEqual(intOf("9500"), 9500)
        XCTAssertEqual(int64Of(5_368_709_120 as NSNumber), 5_368_709_120)
    }

    func testOutboxItemFlags() {
        let missing = OutboxItem.fromJSON(["id": "1", "name": "gone.jpg", "size": -1])
        XCTAssertTrue(missing.isMissing, "size 为 -1 表示文件在登记之后被删了")

        let text = OutboxItem.fromJSON(["id": "2", "name": "链接", "text": "https://a.com"])
        XCTAssertTrue(text.isText)

        let taken = OutboxItem.fromJSON(["id": "3", "name": "a.jpg", "size": 10, "taken": 1])
        XCTAssertTrue(taken.isTaken)
        XCTAssertFalse(taken.isText)
        // rel 缺省时要回退到文件名，否则手机上会存成空路径
        XCTAssertEqual(taken.rel, "a.jpg")
    }
}
