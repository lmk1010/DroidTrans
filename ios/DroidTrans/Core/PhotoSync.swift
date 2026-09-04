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
    /// 原始文件有多大。用来在传之前就判断超没超对面的额度。
    var byteSize: Int64 {
        PHAssetResource.assetResources(for: asset)
            .compactMap { $0.value(forKey: "fileSize") as? Int64 }
            .max() ?? 0
    }
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
    /// 因为超过对面免费额度而传不了的张数。
    ///
    /// 扫描时就算出来，界面能在用户按下「开始」之前提醒 ——
    /// 传到那一张才报错，前面等的时间就白花了。
    @Published private(set) var overQuota = 0

    private var desktop: Desktop?
    private var token = ""
    private var useTCP = true
    private var task: Task<Void, Never>?

    /// 已经传过的资产。存在 App Group 里，分享扩展将来也能读。
    private let storeKey = "synced_assets"
    private let defaults = UserDefaults(suiteName: kAppGroup) ?? .standard

    /// 常驻内存的一份。
    ///
    /// 之前这里是个计算属性，每次读写都过一趟 UserDefaults ——
    /// 传一张就「把整个已传数组读出来、插一个、再整个写回去」。
    /// 相册里攒了几千张之后，那是每张照片都要序列化一遍几千个字符串的
    /// plist，同步到后面会肉眼可见地越来越慢。这是 O(n²)。
    ///
    /// 现在只在启动时读一次，落盘攒着批量写。
    private lazy var syncedCache: Set<String> = Set(defaults.stringArray(forKey: storeKey) ?? [])
    /// 距上次落盘又记了多少张
    private var dirty = 0

    private var synced: Set<String> { syncedCache }

    var syncedCount: Int { syncedCache.count }

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

        // 超额的挑出来单独报，不混进待传列表里 ——
        // 混进去的话进度会卡在某一张上反复失败，看着像坏了
        if let desktop, desktop.maxFileSize > 0 {
            var ok: [SyncCandidate] = []
            var over = 0
            for c in found {
                if desktop.exceedsQuota(c.byteSize) {
                    over += 1
                } else {
                    ok.append(c)
                }
            }
            found = ok
            overQuota = over
        } else {
            overQuota = 0
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
            self.flush()
            self.state = .finished(sent: sent, failed: failed)
            // 重新扫一遍，把已传的从列表里去掉
            await self.scan()
        }
    }

    func cancel() {
        task?.cancel()
        task = nil
        flush()
    }

    /// 记一笔「这张传过了」。
    ///
    /// 每 20 张落一次盘，而不是每张都落。中途被杀进程最多丢 19 条记录，
    /// 代价是下次同步重传这 19 张 —— 比每张都写 plist 划算得多。
    /// 反过来「漏记」只会重传，不会丢文件，所以这个方向的容错是安全的。
    private func markSynced(_ id: String) {
        syncedCache.insert(id)
        dirty += 1
        if dirty >= 20 { flush() }
    }

    /// 把攒着的记录写进 UserDefaults。同步结束、取消、退到后台都要调。
    private func flush() {
        guard dirty > 0 else { return }
        defaults.set(Array(syncedCache), forKey: storeKey)
        dirty = 0
    }

    /// 忘掉所有记录，下次会把整个相册重新当成「没传过」。
    func forgetAll() {
        syncedCache = []
        dirty = 0
        defaults.removeObject(forKey: storeKey)
    }

    // MARK: - 单张

    private func send(_ item: SyncCandidate, to desktop: Desktop) async throws {
        let files = try await export(item.asset, includeLive: LicenseStore.shared.isPro)
        // 导出的是临时副本，传完就删 —— 一次同步几百张的话，
        // 留着能把手机磁盘吃光。实况照片会导出两份，目录一起删。
        defer {
            for dir in Set(files.map { $0.deletingLastPathComponent() }) {
                try? FileManager.default.removeItem(at: dir)
            }
        }

        for url in files {
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
    }

    /// 把相册资产导成临时文件。
    ///
    /// 走 PHAssetResourceManager 而不是 requestImageData：前者拿的是原始文件
    /// （HEIC 还是 HEIC），后者会按当前设置转码，用户传到电脑上会发现
    /// 「怎么和相册里不一样」。
    ///
    /// 实况照片在系统里是**两个文件**：一张静态图，加一段 3 秒的 MOV。
    /// 只导静态图的话，那段动态就永久丢了 —— 而用户往往是在几年后
    /// 翻到这张照片时才发现它不动了，那时候手机上的原件早没了。
    /// 所以 includeLive 打开时两份一起导，落盘用同一个主文件名，
    /// 将来导回相册系统能重新把它们配成一张实况照片。
    ///
    /// includeLive 由 Pro 决定：免费版导出的是能看的普通照片，
    /// 不是残缺的东西 —— 这条界线必须站得住，不然就是把功能弄坏了再卖。
    private func export(_ asset: PHAsset, includeLive: Bool) async throws -> [URL] {
        let resources = PHAssetResource.assetResources(for: asset)
        // 优先原始资源；编辑过的照片会有多个，挑主的那个
        let wanted: [PHAssetResourceType] = asset.mediaType == .video
            ? [.video, .fullSizeVideo]
            : [.photo, .fullSizePhoto]
        guard let main = wanted.compactMap({ t in resources.first { $0.type == t } }).first
                ?? resources.first else {
            throw ApiError(L("sync.err.export"))
        }

        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        var out = [try await write(main, into: dir, named: main.originalFilename)]

        if includeLive, asset.mediaSubtypes.contains(.photoLive),
           let paired = pairedVideo(in: resources) {
            // 和主图同名、只换扩展名。散成两个不相干的文件名，
            // 用户在电脑上根本看不出它俩是一对。
            let stem = (main.originalFilename as NSString).deletingPathExtension
            let ext = (paired.originalFilename as NSString).pathExtension
            let name = ext.isEmpty ? stem + ".MOV" : stem + "." + ext
            // 动态部分导失败不该让整张照片失败 —— 静态图已经传成功了，
            // 报错会让这张被当成没传过，下次整张重来。
            if let url = try? await write(paired, into: dir, named: name) {
                out.append(url)
            }
        }
        return out
    }

    /// 编辑过的实况照片会有 fullSizePairedVideo，优先用它。
    private func pairedVideo(in resources: [PHAssetResource]) -> PHAssetResource? {
        resources.first { $0.type == .fullSizePairedVideo }
            ?? resources.first { $0.type == .pairedVideo }
    }

    private func write(_ res: PHAssetResource, into dir: URL, named: String) async throws -> URL {
        let dest = dir.appendingPathComponent(named)
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
