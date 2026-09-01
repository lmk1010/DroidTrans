/// 传输历史。
///
/// 「我刚才那张图传过去了吗」「昨天从电脑拿的那个文件在哪」——
/// 这两个问题没有历史记录就答不了，用户只能重传一次。
///
/// 存本地 JSON：条目是每笔传输一条，写入频率极低，
/// 上数据库只是多一个要维护、会挂的东西。

import Foundation

struct HistoryEntry: Identifiable, Codable, Equatable {
    enum Direction: String, Codable {
        case sent      // 手机 → 电脑
        case received  // 电脑 → 手机
    }

    let id: UUID
    let name: String
    let size: Int64
    let direction: Direction
    let at: Date
    /// 收到的文件在手机上的相对路径，用来「在文件里打开」。发出去的没有。
    var localPath: String?
    /// 对方是哪台电脑，多台设备时才分得清
    var peer: String

    init(name: String, size: Int64, direction: Direction,
         localPath: String? = nil, peer: String) {
        self.id = UUID()
        self.name = name
        self.size = size
        self.direction = direction
        self.at = Date()
        self.localPath = localPath
        self.peer = peer
    }
}

@MainActor
final class History: ObservableObject {
    static let shared = History()

    @Published private(set) var entries: [HistoryEntry] = []

    /// 最多留这么多条。传输记录的价值随时间掉得很快，
    /// 留着几千条只会让列表变慢、让文件变大。
    private let limit = 300

    private let url: URL = {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory,
                                           in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("history.json")
    }()

    private init() {
        load()
    }

    private func load() {
        guard let data = try? Data(contentsOf: url),
              let list = try? JSONDecoder().decode([HistoryEntry].self, from: data) else {
            entries = []
            return
        }
        entries = list
    }

    private func save() {
        // 临时文件 + rename：进程在写一半时被杀，也不会留下半个文件
        guard let data = try? JSONEncoder().encode(entries) else { return }
        let tmp = url.appendingPathExtension("tmp")
        do {
            try data.write(to: tmp)
            _ = try FileManager.default.replaceItemAt(url, withItemAt: tmp)
        } catch {
            try? FileManager.default.removeItem(at: tmp)
        }
    }

    func add(_ entry: HistoryEntry) {
        entries.insert(entry, at: 0)
        if entries.count > limit {
            entries.removeLast(entries.count - limit)
        }
        save()
    }

    func clear() {
        entries = []
        save()
    }

    /// 按天分组，界面直接拿去渲染。
    var grouped: [(day: Date, items: [HistoryEntry])] {
        let cal = Calendar.current
        let dict = Dictionary(grouping: entries) { cal.startOfDay(for: $0.at) }
        return dict.keys.sorted(by: >).map { ($0, dict[$0]!.sorted { $0.at > $1.at }) }
    }
}
