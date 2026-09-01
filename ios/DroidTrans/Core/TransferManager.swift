/// 上传队列。
///
/// 一次选十几张照片是常态，所以必须是队列而不是「一个一个等」。
/// 但也不能全都并发 —— 手机的上行带宽就那么多，同时开十条只会让每一条都慢，
/// 而且进度条会一起爬到 99% 然后集体卡住，看起来像死了。

import Foundation
import SwiftUI

@MainActor
final class TransferManager: ObservableObject {
    struct Job: Identifiable, Equatable {
        enum State: Equatable {
            case waiting
            case sending(Double)   // 0…1
            case done
            case failed(String)
            /// 电脑上已经有同名同大小的文件，跳过了
            case skipped
        }

        let id = UUID()
        let name: String
        let size: Int64
        /// 传完要删掉的临时文件（相册导出的副本）。用户自己的文件不能删。
        let temporary: Bool
        var state: State = .waiting

        var isFinished: Bool {
            switch state {
            case .done, .failed, .skipped: return true
            case .waiting, .sending: return false
            }
        }
    }

    @Published private(set) var jobs: [Job] = []
    @Published private(set) var running = false

    /// 传输用哪条通道。连上电脑时探一次，别每个文件都探。
    private var useTCP = true
    private var desktop: Desktop?
    private var peerName = ""

    private var token = ""
    private var queue: [(Job, URL)] = []
    private var current: Task<Void, Never>?

    var activeCount: Int { jobs.filter { !$0.isFinished }.count }
    var doneCount: Int { jobs.filter { $0.isFinished }.count }

    /// 总进度。传输中的那个算它自己的比例，不然进度条会一格一格地跳。
    var overallProgress: Double {
        guard !jobs.isEmpty else { return 0 }
        let sum = jobs.reduce(0.0) { acc, j in
            switch j.state {
            case .done, .skipped: return acc + 1
            case .sending(let p): return acc + p
            case .waiting, .failed: return acc
            }
        }
        return sum / Double(jobs.count)
    }

    // MARK: - 配置

    func configure(desktop: Desktop, token: String) async {
        self.desktop = desktop
        self.peerName = desktop.name
        self.token = token
        // 桌面端可能因为端口被占没起 9501。先探一次，
        // 免得每个文件都去撞一次连接超时。
        self.useTCP = await FastSender.probe(host: desktop.host, port: desktop.tcpPort ?? Ports.fastTCP)
    }

    // MARK: - 入队

    func enqueue(_ items: [(url: URL, name: String, temporary: Bool)]) {
        for item in items {
            let size = (try? FileManager.default.attributesOfItem(atPath: item.url.path)[.size] as? NSNumber)??.int64Value ?? 0
            let job = Job(name: item.name, size: size, temporary: item.temporary)
            jobs.append(job)
            queue.append((job, item.url))
        }
        pump()
    }

    func clearFinished() {
        jobs.removeAll { $0.isFinished }
    }

    func cancelAll() {
        current?.cancel()
        current = nil
        queue.removeAll()
        running = false
        for i in jobs.indices where !jobs[i].isFinished {
            jobs[i].state = .failed(L("job.canceled"))
        }
    }

    // MARK: - 执行

    private func pump() {
        guard !running, let desktop else { return }
        guard !queue.isEmpty else { return }

        running = true
        let (job, url) = queue.removeFirst()

        current = Task { [weak self] in
            guard let self else { return }
            await self.send(job: job, url: url, desktop: desktop)
            self.running = false
            self.current = nil
            self.pump()
        }
    }

    private func send(job: Job, url: URL, desktop: Desktop) async {
        update(job.id, .sending(0))

        // 相册导出的是沙盒外的临时文件，读之前要先拿到访问权限
        let scoped = url.startAccessingSecurityScopedResource()
        defer {
            if scoped { url.stopAccessingSecurityScopedResource() }
            if job.temporary { try? FileManager.default.removeItem(at: url) }
        }

        do {
            if useTCP {
                let sender = FastSender(
                    host: desktop.host,
                    token: token,
                    port: desktop.tcpPort ?? Ports.fastTCP
                )
                try await sender.sendFile(url, remoteName: job.name) { [weak self] p in
                    Task { @MainActor in self?.update(job.id, .sending(p.fraction)) }
                }
                update(job.id, .done)
                record(job)
            } else {
                let api = ApiClient(baseURL: desktop.baseURL, token: token)
                let skipped = try await api.putFile(
                    url, remoteName: job.name, deviceId: Store.shared.deviceId
                ) { [weak self] sent, total in
                    let f = total > 0 ? Double(sent) / Double(total) : 0
                    Task { @MainActor in self?.update(job.id, .sending(f)) }
                }
                update(job.id, skipped ? .skipped : .done)
                record(job)
            }
        } catch is CancellationError {
            update(job.id, .failed(L("job.canceled")))
        } catch {
            let msg = (error as? FastSendError)?.message
                ?? (error as? ApiError)?.message
                ?? error.localizedDescription
            update(job.id, .failed(msg))
        }
    }

    /// 传成功了就记一笔。失败的不记 —— 历史是「传过什么」，不是操作日志。
    private func record(_ job: Job) {
        History.shared.add(HistoryEntry(
            name: job.name, size: job.size, direction: .sent, peer: peerName
        ))
    }

    private func update(_ id: UUID, _ state: Job.State) {
        guard let i = jobs.firstIndex(where: { $0.id == id }) else { return }
        jobs[i].state = state
    }
}
