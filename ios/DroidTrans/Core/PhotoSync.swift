/// 相册增量同步。
///
/// 这是 Pro 最主要的付费理由：每周往电脑倒照片的人，
/// 和「打开相册手动挑一遍、还得记住上次挑到哪」是天壤之别。
///
/// 免费版能做的是手动选照片（PHPicker），一张不少；
/// Pro 买的是「不用自己记哪些传过了」。
///
/// 已传记录按 PHAsset 的 localIdentifier 存 —— 它在同一台设备上稳定，
/// 比文件名可靠得多（相册里同名文件遍地都是）。

import Foundation
import Photos

struct SyncCandidate: Identifiable {
    let asset: PHAsset
    var id: String { asset.localIdentifier }
    var isVideo: Bool { asset.mediaType == .video }
    var createdAt: Date { asset.creationDate ?? .distantPast }
}

@MainActor
final class PhotoSync: ObservableObject {
    enum State: Equatable {
        case idle
        case scanning
        /// 扫完了，有这么多张没传过
        case ready(count: Int)
        case syncing(done: Int, total: Int)
        case finished(sent: Int, failed: Int)
        case denied
        case failed(String)
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var candidates: [SyncCandidate] = []

    private var desktop: Desktop?
    private var token = ""
    private var useTCP = true
    private var task: Task<Void, Never>?

    /// 已经传过的资产。存在 App Group 里，分享扩展将来也能读。
    private let storeKey = "synced_assets"
    private let defaults = UserDefaults(suiteName: kAppGroup) ?? .standard

    private var synced: Set<String> {
        get { Set(defaults.stringArray(forKey: storeKey) ?? []) }
        set { defaults.set(Array(newValue), forKey: storeKey) }
    }

    var syncedCount: Int { synced.count }

    func configure(desktop: Desktop, token: String) {
        self.desktop = desktop
        self.token = token
    }

    // MARK: - 扫描

    func scan() async {
        state = .scanning

        let status = await requestAccess()
        guard status == .authorized || status == .limited else {
            state = .denied
            return
        }

        let opts = PHFetchOptions()
        // 新的在前。用户最关心的是刚拍的那些
        opts.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        let result = PHAsset.fetchAssets(with: opts)

        let done = synced
        var found: [SyncCandidate] = []
        result.enumerateObjects { asset, _, _ in
            guard !done.contains(asset.localIdentifier) else { return }
            found.append(SyncCandidate(asset: asset))
        }

        candidates = found
        state = .ready(count: found.count)
    }

    private func requestAccess() async -> PHAuthorizationStatus {
        let current = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        if current != .notDetermined { return current }
        return await withCheckedContinuation { k in
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { k.resume(returning: $0) }
        }
    }

    // MARK: - 同步

    func start() {
        guard let desktop, !candidates.isEmpty else { return }
        let items = candidates

        task = Task { [weak self] in
            guard let self else { return }
            self.useTCP = await FastSender.probe(
                host: desktop.host, port: desktop.tcpPort ?? Ports.fastTCP)

            var sent = 0
            var failed = 0
            for (i, item) in items.enumerated() {
                if Task.isCancelled { break }
                self.state = .syncing(done: i, total: items.count)

                do {
                    try await self.send(item, to: desktop)
                    // 传成功才记账。失败的下次还要再来一遍 ——
                    // 宁可重传一张，也不能悄悄漏掉一张。
                    self.markSynced(item.id)
                    sent += 1
                } catch is CancellationError {
                    break
                } catch {
                    failed += 1
                }
            }
            self.state = .finished(sent: sent, failed: failed)
            // 重新扫一遍，把已传的从列表里去掉
            await self.scan()
        }
    }

    func cancel() {
        task?.cancel()
        task = nil
    }

    private func markSynced(_ id: String) {
        var s = synced
        s.insert(id)
        synced = s
    }

    /// 忘掉所有记录，下次会把整个相册重新当成「没传过」。
    func forgetAll() {
        defaults.removeObject(forKey: storeKey)
    }

    // MARK: - 单张

    private func send(_ item: SyncCandidate, to desktop: Desktop) async throws {
        let url = try await export(item.asset)
        // 导出的是临时副本，传完就删 —— 一次同步几百张的话，
        // 留着能把手机磁盘吃光
        defer { try? FileManager.default.removeItem(at: url) }

        let name = url.lastPathComponent
        if useTCP {
            let sender = FastSender(host: desktop.host, token: token,
                                    port: desktop.tcpPort ?? Ports.fastTCP)
            try await sender.sendFile(url, remoteName: name)
        } else {
            let api = ApiClient(baseURL: desktop.baseURL, token: token)
            _ = try await api.putFile(url, remoteName: name, deviceId: Store.shared.deviceId)
        }

        let size = (try? FileManager.default
            .attributesOfItem(atPath: url.path)[.size] as? NSNumber)??.int64Value ?? 0
        History.shared.add(HistoryEntry(
            name: name, size: size, direction: .sent, peer: desktop.name))
    }

    /// 把相册资产导成临时文件。
    ///
    /// 走 PHAssetResourceManager 而不是 requestImageData：前者拿的是原始文件
    /// （HEIC 还是 HEIC，实况照片的视频部分也在），后者会按当前设置转码，
    /// 用户传到电脑上会发现「怎么和相册里不一样」。
    private func export(_ asset: PHAsset) async throws -> URL {
        let resources = PHAssetResource.assetResources(for: asset)
        // 优先原始资源；编辑过的照片会有多个，挑主的那个
        let wanted: [PHAssetResourceType] = asset.mediaType == .video
            ? [.video, .fullSizeVideo]
            : [.photo, .fullSizePhoto]
        guard let res = wanted.compactMap({ t in resources.first { $0.type == t } }).first
                ?? resources.first else {
            throw ApiError(L("sync.err.export"))
        }

        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let dest = dir.appendingPathComponent(res.originalFilename)

        let opts = PHAssetResourceRequestOptions()
        // iCloud 照片本机可能只有缩略图，得允许去网上取原图
        opts.isNetworkAccessAllowed = true

        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            PHAssetResourceManager.default().writeData(for: res, toFile: dest, options: opts) { err in
                if let err { k.resume(throwing: err) } else { k.resume() }
            }
        }
        return dest
    }
}
