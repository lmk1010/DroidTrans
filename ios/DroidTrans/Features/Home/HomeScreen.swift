/// 连上电脑之后的主界面。
///
/// 一屏放下所有事：连着哪台电脑、要发什么、电脑那边有什么等着取、正在传的进度。
///
/// 原来是三个 tab（发送 / 接收 / 设置），每个 tab 里又是一列平铺的入口。
/// 那样用户想知道「东西传完了没有」得先切一个 tab，
/// 想知道「电脑给我发东西了吗」还得再切一个 —— 每件事都多两步。
/// 传文件本来就是几秒钟的事，不该为它设计一套需要导航的界面。

import SwiftUI

struct HomeScreen: View {
    let desktop: Desktop

    @EnvironmentObject private var app: AppState
    @StateObject private var transfers = TransferManager()
    @ObservedObject private var license = LicenseStore.shared

    @State private var outbox: [OutboxItem] = []
    @State private var loadingOutbox = false
    @State private var downloading: Set<String> = []
    @State private var dlProgress: [String: Double] = [:]

    @State private var showPhotos = false
    @State private var showFiles = false
    @State private var showText = false
    @State private var showMe = false
    @State private var showHistory = false
    @State private var showGallery = false
    @State private var showSync = false
    @State private var toast: String?
    @State private var error: String?

    var body: some View {
        ZStack {
            AppBackground()

            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: Space.xl) {
                    topBar
                    connectionCard
                    sendSection
                    if !outbox.isEmpty { inboxSection }
                    if !transfers.jobs.isEmpty { transferSection }
                    // 两边都空的时候下面是一大片黑，看着像没加载完。
                    // 与其留白，不如把「接下来能干什么」画出来。
                    if outbox.isEmpty && transfers.jobs.isEmpty { idleHint }
                    Color.clear.frame(height: Space.xxl)
                }
                .padding(.horizontal, Space.gutter)
                .padding(.top, Space.s)
            }
            .refreshable { await loadOutbox() }

            if let toast { ToastView(text: toast) }
        }
        .preferredColorScheme(.dark)
        .task {
            await transfers.configure(desktop: desktop, token: Store.shared.token(for: desktop))
            await loadOutbox()
        }
        .sheet(isPresented: $showPhotos) {
            PhotoPicker { enqueue($0) }.ignoresSafeArea()
        }
        .sheet(isPresented: $showFiles) {
            DocumentPicker { enqueue($0) }.ignoresSafeArea()
        }
        .sheet(isPresented: $showText) {
            SendTextSheet(desktop: desktop) { flash(L("home.sentToClipboard")) }
        }
        .sheet(isPresented: $showMe) {
            MeView(desktop: desktop).environmentObject(app)
        }
        .sheet(isPresented: $showHistory) {
            HistoryView()
        }
        .sheet(isPresented: $showGallery) {
            GalleryView()
        }
        .sheet(isPresented: $showSync) {
            SyncView(desktop: desktop)
        }
        .alert(L("home.error"), isPresented: .constant(error != nil)) {
            Button(L("common.ok")) { error = nil }
        } message: {
            Text(error ?? "")
        }
    }

    // MARK: - 顶部

    private var topBar: some View {
        HStack(spacing: Space.s) {
            Text(L("home.title"))
                .font(.system(size: 26, weight: .bold))
                .foregroundStyle(Color.ink)
            Spacer()
            Button { showGallery = true } label: {
                Image(systemName: "photo.on.rectangle")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(Color.ink2)
                    .frame(width: 38, height: 38)
                    .glass(radius: Radius.pill)
            }
            .accessibilityIdentifier("open-gallery")

            Button { showHistory = true } label: {
                Image(systemName: "clock.arrow.circlepath")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(Color.ink2)
                    .frame(width: 38, height: 38)
                    .glass(radius: Radius.pill)
            }
            .accessibilityIdentifier("open-history")

            Button { showMe = true } label: {
                Image(systemName: "person.crop.circle")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(Color.ink2)
                    .frame(width: 38, height: 38)
                    .glass(radius: Radius.pill)
            }
            .accessibilityIdentifier("open-me")
        }
    }

    // MARK: - 连接

    private var connectionCard: some View {
        HStack(spacing: Space.m) {
            ArtIcon(art: desktop.isPhone ? .phone : .laptop, size: 46)

            VStack(alignment: .leading, spacing: 3) {
                Text(desktop.name)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Color.ink)
                    .lineLimit(1)
                HStack(spacing: 6) {
                    Circle().fill(Color.green).frame(width: 6, height: 6)
                    Text("\(desktop.host) · " + L("home.connected"))
                        .font(.system(size: 12.5))
                        .foregroundStyle(Color.ink2)
                }
            }

            Spacer()

            Button {
                app.switchDevice()
            } label: {
                Text(L("home.switch"))
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Color.brand)
                    .padding(.horizontal, Space.m)
                    .padding(.vertical, 7)
                    .background(Color.brand.opacity(0.14), in: Capsule())
            }
        }
        .padding(Space.l)
        .glass()
    }

    // MARK: - 发送

    private var sendSection: some View {
        VStack(alignment: .leading, spacing: Space.m) {
            SectionHeader(L("home.send"))

            HStack(spacing: Space.m) {
                SendTile(art: .photos, title: L("home.send.photos")) { showPhotos = true }
                SendTile(art: .files, title: L("home.send.files")) { showFiles = true }
                SendTile(art: .text, title: L("home.send.text")) { showText = true }
            }

            // 增量同步。带 PRO 角标 —— 免费版点进去看得到它是什么、
            // 为什么值钱，而不是点了没反应或者干脆藏起来。
            Button { showSync = true } label: {
                HStack(spacing: Space.m) {
                    ArtIcon(art: .sync, size: 42)
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            Text(L("sync.title"))
                                .font(.system(size: 15.5, weight: .medium))
                                .foregroundStyle(Color.ink)
                            if !license.isPro {
                                Text("PRO")
                                    .font(.system(size: 9, weight: .black))
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 5)
                                    .padding(.vertical, 2)
                                    .background(Capsule().fill(Color.brand))
                            }
                        }
                        Text(L("sync.subtitle"))
                            .font(.system(size: 12.5))
                            .foregroundStyle(Color.ink2)
                            .lineLimit(1)
                    }
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Color.ink3)
                }
                .padding(Space.l)
                .glass()
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("open-sync")
        }
    }

    private func enqueue(_ files: [PickedFile]) {
        guard !files.isEmpty else { return }
        transfers.enqueue(files.map { ($0.url, $0.name, $0.temporary) })
    }

    // MARK: - 空着的时候

    private var idleHint: some View {
        VStack(spacing: Space.m) {
            ArtIcon(art: .sync, size: 132)
                .padding(.top, Space.l)

            Text(L("home.idle"))
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(Color.ink)

            Text(L("home.idle.hint"))
                .font(.system(size: 13))
                .foregroundStyle(Color.ink3)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
                .padding(.horizontal, Space.l)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, Space.l)
    }

    // MARK: - 电脑发来的

    private var inboxSection: some View {
        VStack(alignment: .leading, spacing: Space.m) {
            SectionHeader(title: L("home.inbox"), count: outbox.count) {
                Button {
                    Task { await loadOutbox() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Color.ink2)
                }
                .disabled(loadingOutbox)
            }

            VStack(spacing: Space.s) {
                ForEach(outbox) { item in
                    InboxRow(
                        item: item,
                        progress: dlProgress[item.id],
                        busy: downloading.contains(item.id),
                        onGet: { Task { await fetch(item) } }
                    )
                }
            }
        }
    }

    // MARK: - 传输中

    private var transferSection: some View {
        VStack(alignment: .leading, spacing: Space.m) {
            SectionHeader(title: L("home.transfers"), count: nil) {
                if transfers.activeCount > 0 {
                    Button(L("common.cancel")) { transfers.cancelAll() }
                        .font(.system(size: 13))
                        .foregroundStyle(Color.ink2)
                } else {
                    Button(L("common.clear")) { transfers.clearFinished() }
                        .font(.system(size: 13))
                        .foregroundStyle(Color.ink2)
                }
            }

            if transfers.activeCount > 0 {
                VStack(alignment: .leading, spacing: 6) {
                    ProgressView(value: transfers.overallProgress)
                        .tint(.brand)
                    Text("\(transfers.doneCount) / \(transfers.jobs.count)")
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(Color.ink3)
                }
            }

            VStack(spacing: Space.s) {
                ForEach(transfers.jobs) { job in
                    JobRow(job: job)
                }
            }
        }
    }

    // MARK: - 动作

    private func loadOutbox() async {
        loadingOutbox = true
        defer { loadingOutbox = false }
        let api = ApiClient(baseURL: desktop.baseURL, token: Store.shared.token(for: desktop))
        do {
            outbox = try await api.outbox()
        } catch {
            // 刷新失败不弹窗：用户可能只是下拉了一下，
            // 一个错误弹窗比「列表没变」烦人得多
            self.outbox = outbox
        }
    }

    private func fetch(_ item: OutboxItem) async {
        if item.isText {
            UIPasteboard.general.string = item.text
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            flash(L("home.copied"))
            return
        }
        if item.isMissing {
            error = L("error.gone")
            return
        }

        downloading.insert(item.id)
        defer {
            downloading.remove(item.id)
            dlProgress[item.id] = nil
        }

        let api = ApiClient(baseURL: desktop.baseURL, token: Store.shared.token(for: desktop))
        // 存进「文件」App 能看到的目录，用户取完自己找得到
        let dest = FileManager.default
            .urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(item.rel.isEmpty ? item.name : item.rel)
        do {
            try await api.download(item, to: dest) { got, total in
                let f = total > 0 ? Double(got) / Double(total) : 0
                Task { @MainActor in dlProgress[item.id] = f }
            }
            History.shared.add(HistoryEntry(
                name: item.name, size: item.size, direction: .received,
                localPath: item.rel.isEmpty ? item.name : item.rel, peer: desktop.name
            ))
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            flash(L("home.savedToFiles"))
            await loadOutbox()
        } catch {
            self.error = (error as? ApiError)?.message ?? error.localizedDescription
        }
    }

    private func flash(_ text: String) {
        withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { toast = text }
        Task {
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            withAnimation(.easeOut(duration: 0.25)) { toast = nil }
        }
    }
}

// MARK: - 发送入口

private struct SendTile: View {
    let art: Art
    let title: String
    var action: () -> Void

    @State private var pressed = false

    var body: some View {
        Button(action: action) {
            VStack(spacing: Space.s) {
                ArtIcon(art: art, size: 52)
                Text(title)
                    .font(.system(size: 13.5, weight: .medium))
                    .foregroundStyle(Color.ink)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, Space.l)
            .glass()
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("send-\(art.rawValue)")
        .scaleEffect(pressed ? 0.96 : 1)
        .animation(.spring(response: 0.3, dampingFraction: 0.7), value: pressed)
        .simultaneousGesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in pressed = true }
                .onEnded { _ in pressed = false }
        )
    }
}

// MARK: - 行

private struct InboxRow: View {
    let item: OutboxItem
    let progress: Double?
    let busy: Bool
    var onGet: () -> Void

    var body: some View {
        HStack(spacing: Space.m) {
            ArtIcon(art: item.isText ? .text : art, size: 34)

            VStack(alignment: .leading, spacing: 3) {
                Text(item.isText ? (item.text ?? "") : item.name)
                    .font(.system(size: 14.5, weight: .medium))
                    .foregroundStyle(Color.ink)
                    .lineLimit(1)
                    .truncationMode(.middle)

                if let progress {
                    ProgressView(value: progress).tint(.brand)
                } else {
                    Text(detail)
                        .font(.system(size: 12))
                        .foregroundStyle(item.isMissing ? .red : Color.ink3)
                }
            }

            Spacer()

            if busy {
                ProgressView().controlSize(.small).tint(Color.ink2)
            } else {
                Button(action: onGet) {
                    Text(item.isText ? L("common.copy") : L("home.get"))
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(item.isMissing ? Color.ink3 : Color.brand)
                        .padding(.horizontal, Space.m)
                        .padding(.vertical, 6)
                        .background(Color.brand.opacity(item.isMissing ? 0 : 0.14), in: Capsule())
                }
                .disabled(item.isMissing)
            }
        }
        .padding(Space.m)
        .glass(radius: Radius.tile)
    }

    /// 按扩展名挑素材。认不出来就用文件那张。
    private var art: Art {
        let ext = (item.name as NSString).pathExtension.lowercased()
        switch ext {
        case "jpg", "jpeg", "png", "heic", "gif", "webp", "mp4", "mov": return .photos
        default: return .files
        }
    }

    private var detail: String {
        if item.isMissing { return L("item.missing") }
        if item.isText { return L("item.text") }
        var s = humanSize(item.size)
        if item.isTaken { s += " · " + L("item.taken") }
        return s
    }
}

private struct JobRow: View {
    let job: TransferManager.Job

    var body: some View {
        HStack(spacing: Space.m) {
            statusIcon.frame(width: 20)

            VStack(alignment: .leading, spacing: 3) {
                Text(job.name)
                    .font(.system(size: 14))
                    .foregroundStyle(Color.ink)
                    .lineLimit(1)
                    .truncationMode(.middle)

                if case .sending(let p) = job.state {
                    ProgressView(value: p).tint(.brand)
                } else {
                    Text(detail)
                        .font(.system(size: 12))
                        .foregroundStyle(isFailed ? .red : Color.ink3)
                        .lineLimit(1)
                }
            }
            Spacer()
        }
        .padding(Space.m)
        .glass(radius: Radius.tile)
    }

    private var isFailed: Bool {
        if case .failed = job.state { return true }
        return false
    }

    private var detail: String {
        switch job.state {
        case .waiting: return L("job.waiting") + " · \(humanSize(job.size))"
        case .sending: return humanSize(job.size)
        case .done: return L("job.done") + " · \(humanSize(job.size))"
        case .skipped: return L("job.skipped")
        case .failed(let m): return m
        }
    }

    @ViewBuilder
    private var statusIcon: some View {
        switch job.state {
        case .waiting:
            Image(systemName: "clock").foregroundStyle(Color.ink3)
        case .sending:
            ProgressView().controlSize(.small).tint(Color.brand)
        case .done:
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
        case .skipped:
            Image(systemName: "checkmark.circle").foregroundStyle(Color.ink3)
        case .failed:
            Image(systemName: "exclamationmark.circle.fill").foregroundStyle(.red)
        }
    }
}

// MARK: - 提示

private struct ToastView: View {
    let text: String

    var body: some View {
        VStack {
            Spacer()
            Text(text)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(Color.ink)
                .padding(.horizontal, Space.l)
                .padding(.vertical, Space.m)
                .glass(radius: Radius.pill)
                .padding(.bottom, Space.xxl)
        }
        .transition(.move(edge: .bottom).combined(with: .opacity))
        .allowsHitTesting(false)
    }
}
