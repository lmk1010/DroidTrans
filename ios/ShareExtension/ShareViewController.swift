/// 分享扩展。
///
/// 这是 iOS 上最短的一条路：在相册、浏览器、微信里点「分享 → 卓传」，
/// 东西就直接到电脑上了 —— 不用先打开 App、再选照片、再找那张照片。
///
/// 扩展是独立进程，内存和运行时间都被系统卡得很死，所以这里只做一件事：
/// 读出分享进来的东西，走已经配好的那台电脑发出去，然后关掉。
/// 选电脑、配对这些统统不做，那是主 App 的事。

import SwiftUI
import UIKit
import UniformTypeIdentifiers

final class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()

        let root = ShareRootView(
            items: extensionContext?.inputItems as? [NSExtensionItem] ?? [],
            onFinish: { [weak self] in
                self?.extensionContext?.completeRequest(returningItems: nil)
            },
            onCancel: { [weak self] in
                self?.extensionContext?.cancelRequest(
                    withError: NSError(domain: "life.mkstore.droidtrans.share",
                                       code: 0, userInfo: nil))
            }
        )

        let host = UIHostingController(rootView: root)
        host.view.backgroundColor = .clear
        addChild(host)
        view.addSubview(host.view)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])
        host.didMove(toParent: self)
    }
}

// MARK: - 界面

private struct ShareRootView: View {
    let items: [NSExtensionItem]
    var onFinish: () -> Void
    var onCancel: () -> Void

    @StateObject private var model = ShareModel()

    var body: some View {
        ZStack {
            AppBackground()

            VStack(spacing: Space.l) {
                HStack {
                    Button(L("common.cancel")) { onCancel() }
                        .foregroundStyle(Color.ink2)
                    Spacer()
                    Text(L("share.title"))
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Color.ink)
                    Spacer()
                    Button(L("common.cancel")) {}.opacity(0).disabled(true)
                }

                content
                Spacer(minLength: 0)
            }
            .padding(Space.gutter)
        }
        .preferredColorScheme(.dark)
        .task {
            await model.prepare(items)
            await model.send()
            if model.state == .done {
                // 成功了就自己退场，不要再让用户点一次「完成」
                try? await Task.sleep(nanoseconds: 900_000_000)
                onFinish()
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.state {
        case .noDesktop:
            hint(art: .link, title: L("share.notPaired"), detail: L("share.notPaired.hint"))
        case .empty:
            hint(art: .files, title: L("share.nothing"), detail: "")
        case .failed(let message):
            hint(art: .files, title: L("share.failed"), detail: message)
        case .preparing, .sending, .done:
            transferring
        }
    }

    private var transferring: some View {
        VStack(spacing: Space.l) {
            HStack(spacing: Space.m) {
                ArtIcon(art: .laptop, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.peerName)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Color.ink)
                        .lineLimit(1)
                    Text(model.statusText)
                        .font(.system(size: 12.5))
                        .foregroundStyle(model.state == .done ? .green : Color.ink2)
                }
                Spacer()
                if model.state == .done {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 22))
                        .foregroundStyle(.green)
                } else {
                    ProgressView().tint(Color.ink2)
                }
            }
            .padding(Space.l)
            .glass()

            if model.total > 1 {
                ProgressView(value: model.progress).tint(.brand)
            }

            VStack(spacing: Space.s) {
                ForEach(model.names, id: \.self) { name in
                    HStack(spacing: Space.m) {
                        Text(name)
                            .font(.system(size: 13.5))
                            .foregroundStyle(Color.ink2)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Spacer()
                    }
                    .padding(.horizontal, Space.m)
                    .padding(.vertical, Space.s)
                    .glass(radius: Radius.tile)
                }
            }
        }
    }

    private func hint(art: Art, title: String, detail: String) -> some View {
        VStack(spacing: Space.m) {
            ArtIcon(art: art, size: 64)
            Text(title)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(Color.ink)
            if !detail.isEmpty {
                Text(detail)
                    .font(.system(size: 13.5))
                    .foregroundStyle(Color.ink3)
                    .multilineTextAlignment(.center)
            }
            Button(L("common.done")) { onCancel() }
                .buttonStyle(GhostButtonStyle())
                .padding(.top, Space.s)
        }
        .padding(.top, Space.xl)
    }
}

// MARK: - 逻辑

@MainActor
private final class ShareModel: ObservableObject {
    enum State: Equatable {
        case preparing
        case sending
        case done
        case failed(String)
        /// 还没在主 App 里配过任何一台电脑
        case noDesktop
        /// 分享进来的东西一个都读不出来
        case empty
    }

    @Published var state: State = .preparing
    @Published var names: [String] = []
    @Published var sentCount = 0
    @Published var total = 0

    private var files: [(url: URL, name: String)] = []
    private var text: String?
    private var desktop: Desktop?

    var peerName: String { desktop?.name ?? "" }
    var progress: Double { total > 0 ? Double(sentCount) / Double(total) : 0 }

    var statusText: String {
        switch state {
        case .done: return L("share.sent")
        case .sending where total > 1: return "\(sentCount) / \(total)"
        default: return L("share.sending")
        }
    }

    /// 把分享进来的东西读成本地文件。
    ///
    /// loadFileRepresentation 给的 URL 在闭包返回后就失效了，必须当场拷走。
    func prepare(_ items: [NSExtensionItem]) async {
        // 上次连的那台。扩展里不做设备选择 —— 没配过就让用户回主 App 配，
        // 在这个只有半屏的界面里塞一套配对流程只会更糟。
        guard let host = Store.shared.lastHost,
              let d = try? await DesktopDiscovery.verify(host) else {
            state = .noDesktop
            return
        }
        desktop = d

        for item in items {
            for provider in item.attachments ?? [] {
                if let f = await Self.load(provider) {
                    files.append(f)
                } else if let s = await Self.loadText(provider) {
                    text = (text.map { $0 + "\n" } ?? "") + s
                }
            }
        }

        names = files.map(\.name)
        if let text, files.isEmpty {
            names = [text.count > 60 ? String(text.prefix(60)) + "…" : text]
        }
        total = files.isEmpty ? (text == nil ? 0 : 1) : files.count

        if total == 0 { state = .empty }
    }

    func send() async {
        guard state == .preparing, let desktop else { return }
        state = .sending

        let token = Store.shared.token(for: desktop)

        // 纯文字：直接进电脑剪贴板
        if files.isEmpty, let text {
            let api = ApiClient(baseURL: desktop.baseURL, token: token)
            do {
                try await api.sendText(text)
                sentCount = 1
                state = .done
            } catch {
                state = .failed((error as? ApiError)?.message ?? error.localizedDescription)
            }
            return
        }

        let useTCP = await FastSender.probe(host: desktop.host,
                                            port: desktop.tcpPort ?? Ports.fastTCP)
        for f in files {
            do {
                if useTCP {
                    let sender = FastSender(host: desktop.host, token: token,
                                            port: desktop.tcpPort ?? Ports.fastTCP)
                    try await sender.sendFile(f.url, remoteName: f.name)
                } else {
                    let api = ApiClient(baseURL: desktop.baseURL, token: token)
                    _ = try await api.putFile(f.url, remoteName: f.name,
                                              deviceId: Store.shared.deviceId)
                }
                sentCount += 1
                History.shared.add(HistoryEntry(
                    name: f.name,
                    size: (try? FileManager.default.attributesOfItem(atPath: f.url.path)[.size] as? NSNumber)??.int64Value ?? 0,
                    direction: .sent, peer: desktop.name
                ))
            } catch {
                state = .failed((error as? FastSendError)?.message
                                ?? (error as? ApiError)?.message
                                ?? error.localizedDescription)
                return
            }
            try? FileManager.default.removeItem(at: f.url)
        }
        state = .done
    }

    // MARK: - 读取分享内容

    private static func load(_ provider: NSItemProvider) async -> (URL, String)? {
        let types: [UTType] = [.movie, .image, .pdf, .item]
        guard let type = types.first(where: {
            provider.hasItemConformingToTypeIdentifier($0.identifier)
        }) else { return nil }

        return await withCheckedContinuation { k in
            provider.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, _ in
                guard let url else { return k.resume(returning: nil) }
                let name = url.lastPathComponent
                let dest = FileManager.default.temporaryDirectory
                    .appendingPathComponent(UUID().uuidString, isDirectory: true)
                    .appendingPathComponent(name)
                do {
                    try FileManager.default.createDirectory(
                        at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
                    try FileManager.default.copyItem(at: url, to: dest)
                    k.resume(returning: (dest, name))
                } catch {
                    k.resume(returning: nil)
                }
            }
        }
    }

    private static func loadText(_ provider: NSItemProvider) async -> String? {
        for type in [UTType.url, UTType.plainText, UTType.text] {
            guard provider.hasItemConformingToTypeIdentifier(type.identifier) else { continue }
            let value: String? = await withCheckedContinuation { k in
                provider.loadItem(forTypeIdentifier: type.identifier) { item, _ in
                    if let u = item as? URL { k.resume(returning: u.absoluteString) }
                    else if let s = item as? String { k.resume(returning: s) }
                    else { k.resume(returning: nil) }
                }
            }
            if let value { return value }
        }
        return nil
    }
}
