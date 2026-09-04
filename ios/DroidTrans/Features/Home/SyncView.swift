/// 相册增量同步。Pro 功能。
///
/// 免费版能手动选照片，一张不少；这一屏卖的是「不用自己记哪些传过了」。

import SwiftUI

struct SyncView: View {
    let desktop: Desktop

    @Environment(\.dismiss) private var dismiss
    @StateObject private var sync = PhotoSync()
    @ObservedObject private var license = LicenseStore.shared
    @State private var showPro = false
    @State private var confirmForget = false
    @State private var floating = false

    var body: some View {
        ZStack {
            AppBackground()

            VStack(spacing: 0) {
                bar
                ScrollView(showsIndicators: false) {
                    VStack(spacing: Space.l) {
                        content
                        Color.clear.frame(height: Space.xxl)
                    }
                    .padding(.horizontal, Space.gutter)
                }
            }
        }
        .preferredColorScheme(.dark)
        .task {
            sync.configure(desktop: desktop, token: Store.shared.token(for: desktop))
            if license.isPro { await sync.scan() }
        }
        .sheet(isPresented: $showPro) { ProView() }
        .confirmationDialog(L("sync.forget.confirm"), isPresented: $confirmForget,
                            titleVisibility: .visible) {
            Button(L("sync.forget"), role: .destructive) {
                sync.forgetAll()
                Task { await sync.scan() }
            }
            Button(L("common.cancel"), role: .cancel) {}
        } message: {
            Text(L("sync.forget.hint"))
        }
    }

    private var bar: some View {
        HStack {
            Button(L("common.done")) { dismiss() }
                .foregroundStyle(Color.brand)
                .accessibilityIdentifier("close-sync")
            Spacer()
            Text(L("sync.title"))
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Color.ink)
            Spacer()
            if license.isPro, case .ready = sync.state {
                Button(L("sync.forget.short")) { confirmForget = true }
                    .font(.system(size: 13))
                    .foregroundStyle(Color.ink3)
            } else {
                Button(L("common.done")) {}.opacity(0).disabled(true)
            }
        }
        .padding(.horizontal, Space.gutter)
        .padding(.vertical, Space.m)
    }

    @ViewBuilder
    private var content: some View {
        if !license.isPro {
            locked
        } else {
            switch sync.state {
            case .idle, .scanning:
                busy(L("sync.scanning"))
            case .denied:
                hint(title: L("sync.denied"), detail: L("sync.denied.hint"), action: openSettings)
            case .failed(let m):
                hint(title: L("sync.failed"), detail: m, action: nil)
            case .ready(let count):
                ready(count)
            case .syncing(let done, let total):
                syncing(done: done, total: total)
            case .finished(let sent, let failed):
                finished(sent: sent, failed: failed)
            }
        }
    }

    // MARK: - 未解锁

    private var locked: some View {
        VStack(spacing: Space.l) {
            ArtIcon(art: .shield, size: 88)
            Text(L("sync.locked"))
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(Color.ink)
            Text(L("sync.locked.hint"))
                .font(.system(size: 14))
                .foregroundStyle(Color.ink2)
                .multilineTextAlignment(.center)
                .lineSpacing(3)

            VStack(spacing: Space.s) {
                Perk(L("sync.perk.incremental"))
                Perk(L("sync.perk.original"))
                Perk(L("sync.perk.free"))
            }
            .padding(.top, Space.s)

            Button(L("sync.unlock")) { showPro = true }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityIdentifier("sync-unlock")
                .padding(.top, Space.m)
        }
        .padding(.top, Space.xl)
    }

    // MARK: - 已解锁的各种状态

    private func busy(_ text: String) -> some View {
        VStack(spacing: Space.m) {
            ProgressView().tint(Color.ink2)
            Text(text)
                .font(.system(size: 14))
                .foregroundStyle(Color.ink2)
        }
        .padding(.top, Space.xxl)
    }

    private func ready(_ count: Int) -> some View {
        VStack(spacing: Space.l) {
            ArtIcon(art: .sync, size: 120)
                .padding(.top, Space.m)

            if count == 0 {
                Text(L("sync.upToDate"))
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Color.ink)
                Text(L("sync.upToDate.hint", "\(sync.syncedCount)"))
                    .font(.system(size: 13.5))
                    .foregroundStyle(Color.ink3)
                    .multilineTextAlignment(.center)
            } else {
                Text(L("sync.found", "\(count)"))
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(Color.ink)
                Text(L("sync.found.hint"))
                    .font(.system(size: 13.5))
                    .foregroundStyle(Color.ink3)
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)

                // 超额的在按下「开始」之前就说，别等传到那一张才报错
                if sync.overQuota > 0 {
                    Text(L("sync.overQuota", "\(sync.overQuota)"))
                        .font(.system(size: 12.5))
                        .foregroundStyle(.orange)
                        .multilineTextAlignment(.center)
                        .lineSpacing(2)
                        .padding(.horizontal, Space.m)
                }

                Button(L("sync.start", "\(count)")) { sync.start() }
                    .buttonStyle(PrimaryButtonStyle())
                    .accessibilityIdentifier("sync-start")
                    .padding(.top, Space.s)
            }
        }
    }

    private func syncing(done: Int, total: Int) -> some View {
        VStack(spacing: Space.l) {
            // 让它轻轻浮起来。不是装饰 —— 传几百张要跑好几分钟，
            // 静止画面配一个几乎不动的进度条，用户会怀疑是不是卡死了。
            ArtIcon(art: .sync, size: 100)
                .offset(y: floating ? -6 : 0)
                .animation(.easeInOut(duration: 1.2).repeatForever(autoreverses: true),
                           value: floating)
                .onAppear { floating = true }
                .onDisappear { floating = false }
                .padding(.top, Space.m)

            Text("\(done) / \(total)")
                .font(.system(size: 26, weight: .bold, design: .rounded))
                .foregroundStyle(Color.ink)

            ProgressView(value: total > 0 ? Double(done) / Double(total) : 0)
                .tint(.brand)

            Text(L("sync.keepOpen"))
                .font(.system(size: 12.5))
                .foregroundStyle(Color.ink3)
                .multilineTextAlignment(.center)

            Button(L("common.cancel")) { sync.cancel() }
                .buttonStyle(GhostButtonStyle())
                .padding(.top, Space.s)
        }
    }

    private func finished(sent: Int, failed: Int) -> some View {
        VStack(spacing: Space.m) {
            ArtIcon(art: .done, size: 88)
                .padding(.top, Space.xl)
            Text(L("sync.done", "\(sent)"))
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(Color.ink)
            if failed > 0 {
                Text(L("sync.someFailed", "\(failed)"))
                    .font(.system(size: 13))
                    .foregroundStyle(.orange)
            }
        }
    }

    private func hint(title: String, detail: String, action: (() -> Void)?) -> some View {
        VStack(spacing: Space.m) {
            ArtIcon(art: .inbox, size: 72).padding(.top, Space.xl)
            Text(title)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(Color.ink)
            Text(detail)
                .font(.system(size: 13.5))
                .foregroundStyle(Color.ink3)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
            if let action {
                Button(L("scan.openSettings"), action: action)
                    .buttonStyle(GhostButtonStyle())
                    .padding(.top, Space.s)
            }
        }
    }

    private func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }
}

private struct Perk: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        HStack(spacing: Space.s) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 14))
                .foregroundStyle(Color.brand)
            Text(text)
                .font(.system(size: 13.5))
                .foregroundStyle(Color.ink2)
            Spacer()
        }
    }
}
