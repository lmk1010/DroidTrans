/// 生成 ATF3 帧的跨语言对拍向量。
///
///   cd ios/Tools/atf2vec && ./run.sh
///
/// 输出落到 desktop/internal/fast/testdata/atf3_vectors.json，
/// 由 Go 端的 TestATF3VectorsFromSwift 用真正的 readHeader 解析回来。
///
/// 为什么要这么绕：Swift 的测试断言只能证明「Swift 和 Swift 自己一致」。
/// 真正要保证的是 Swift 发出的字节，Go 服务端能原样读回来 ——
/// 字段错位在真机上表现为「传到一半失败」，是最难查的一类问题。

import Foundation

struct Vector: Encodable {
    let desc: String
    let name: String
    let size: Int64
    let token: String
    let hex: String
}

func hex(_ d: Data) -> String {
    d.map { String(format: "%02x", $0) }.joined()
}

var vectors: [Vector] = []

func add(_ desc: String, name: String, size: Int64, token: String) {
    do {
        let h = try buildATF3Header(name: name, size: size, token: token)
        vectors.append(Vector(desc: desc, name: name, size: size, token: token, hex: hex(h)))
    } catch {
        FileHandle.standardError.write("生成失败 [\(desc)]: \(error)\n".data(using: .utf8)!)
        exit(1)
    }
}

add("最普通的一次传输", name: "photo.jpg", size: 1024, token: "abc123")
add("中文文件名", name: "照片 2026.jpg", size: 2048, token: "tok")
// emoji 是 4 字节 UTF-8，长度按字节算不按字符算，这里专门盯这一点
add("emoji 文件名", name: "假期🏖️.mp4", size: 999, token: "tok")
add("带路径分隔符（服务端 SafeJoin 负责兜底）", name: "DCIM/100APPLE/IMG_0001.HEIC", size: 5, token: "t")
add("空文件", name: "empty.txt", size: 0, token: "tok")
// size 是 uint64，超过 4GB 的视频必须还原得回来 —— 用 uint32 就会在这里翻车
add("超过 4GB 的大文件", name: "movie.mov", size: 5_368_709_120, token: "tok")
add("空令牌", name: "a.txt", size: 1, token: "")
// 正好卡在服务端 readStr 的上限上
add("令牌 512 字节（上限）", name: "a.txt", size: 1, token: String(repeating: "k", count: 512))
add("文件名 4096 字节（上限）", name: String(repeating: "n", count: 4092) + ".txt", size: 1, token: "tok")

// 超限的必须在客户端就被拒，不能让它发出去
do {
    _ = try buildATF3Header(name: "a.txt", size: 1, token: String(repeating: "k", count: 513))
    FileHandle.standardError.write("令牌 513 字节竟然通过了\n".data(using: .utf8)!)
    exit(1)
} catch {}

do {
    _ = try buildATF3Header(name: String(repeating: "n", count: 4097), size: 1, token: "t")
    FileHandle.standardError.write("文件名 4097 字节竟然通过了\n".data(using: .utf8)!)
    exit(1)
} catch {}

let enc = JSONEncoder()
enc.outputFormatting = [.prettyPrinted, .sortedKeys]
let data = try enc.encode(vectors)

let out = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "-"
if out == "-" {
    FileHandle.standardOutput.write(data)
} else {
    try data.write(to: URL(fileURLWithPath: out))
    print("写入 \(vectors.count) 条向量 → \(out)")
}
