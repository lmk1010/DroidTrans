/// 我的。
///
/// 会员状态、这台设备、当前连接、帮助，都在这一屏。
/// 原来这些散在一个叫「设置」的二级页里，会员入口还得再点一层 ——
/// 用户想看「我买没买、还剩多久」得挖两下才找得到。

import SwiftUI

struct MeView: View {
    let desktop: Desktop?

    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var app: AppState
    @ObservedObject private var license = LicenseStore.shared

    @State private var deviceName = Store.shared.deviceName
    @State private var showPro = false
    @FocusState private var nameFocused: Bool

    var body: some View {
        ZStack {
            AppBackground()

            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: Space.xl) {
                    bar
                    proCard
                    deviceSection
                    if let desktop { connectionSection(desktop) }
                    aboutSection
                    Color.clear.frame(height: Space.xxl)
                }
                .padding(.horizontal, Space.gutter)
            }
        }
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showPro) { ProView() }
        .onDisappear { Store.shared.deviceName = deviceName }
    }

    private var bar: some View {
        HStack {
            Button(L("common.done")) {
                Store.shared.deviceName = deviceName
                dismiss()
            }
            .foregroundStyle(Color.brand)
            .accessibilityIdentifier("close-me")
            Spacer()
            Text(L("me.title"))
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Color.ink)
            Spacer()
            Button(L("common.done")) {}.opacity(0).disabled(true)
        }
        .padding(.vertical, Space.m)
    }

    // MARK: - 会员

    private var proCard: some View {
        Button { showPro = true } label: {
            HStack(spacing: Space.m) {
                ArtIcon(art: .shield, size: 48)

                VStack(alignment: .leading, spacing: 3) {
                    Text(license.isPro ? L("license.title") : L("me.free"))
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Color.ink)
                    Text(statusLine)
                        .font(.system(size: 12.5))
                        .foregroundStyle(license.problem != nil ? .orange : Color.ink2)
                        .lineLimit(1)
                }

                Spacer()

                if !license.isPro {
                    Text(L("me.upgrade"))
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, Space.m)
                        .padding(.vertical, 7)
                        .background(
                            Capsule().fill(LinearGradient(colors: [.brand, .brandDeep],
                                                          startPoint: .top, endPoint: .bottom))
                        )
                } else {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Color.ink3)
                }
            }
            .padding(Space.l)
            .glass()
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("open-pro")
    }

    private var statusLine: String {
        if let problem = license.problem {
            return problem.errorDescription ?? ""
        }
        guard let lic = license.license else { return L("me.free.hint") }
        if lic.isLifetime { return L("license.plan.lifetime") }
        if let d = lic.expiresAt {
            let f = DateFormatter()
            f.dateStyle = .medium
            f.timeStyle = .none
            return L("me.validUntil", f.string(from: d))
        }
        return lic.code
    }

    // MARK: - 这台设备

    private var deviceSection: some View {
        VStack(alignment: .leading, spacing: Space.m) {
            SectionHeader(L("me.device"))

            VStack(spacing: 0) {
                HStack {
                    Text(L("settings.name"))
                        .font(.system(size: 15))
                        .foregroundStyle(Color.ink)
                    Spacer()
                    TextField("iPhone", text: $deviceName)
                        .multilineTextAlignment(.trailing)
                        .font(.system(size: 15))
                        .foregroundStyle(Color.ink2)
                        .focused($nameFocused)
                        .onSubmit { Store.shared.deviceName = deviceName }
                }
                .padding(Space.l)
            }
            .glass()

            Text(L("settings.name.hint"))
                .font(.system(size: 12))
                .foregroundStyle(Color.ink3)
                .padding(.horizontal, Space.xs)
        }
    }

    // MARK: - 连接

    private func connectionSection(_ d: Desktop) -> some View {
        VStack(alignment: .leading, spacing: Space.m) {
            SectionHeader(L("settings.connection"))

            VStack(spacing: 0) {
                InfoLine(label: L("settings.computer"), value: d.name)
                Divider().overlay(Color.stroke)
                InfoLine(label: L("settings.address"), value: "\(d.host):\(d.port)")
                Divider().overlay(Color.stroke)
                InfoLine(label: L("settings.channel"), value: channelText(d))
                Divider().overlay(Color.stroke)
                Button {
                    app.disconnect()
                    dismiss()
                } label: {
                    HStack {
                        Text(L("settings.disconnect"))
                            .font(.system(size: 15))
                            .foregroundStyle(.red.opacity(0.9))
                        Spacer()
                    }
                    .padding(Space.l)
                }
            }
            .glass()
        }
    }

    private func channelText(_ d: Desktop) -> String {
        d.prefer.first.map { p in
            switch p {
            case .tcp: return L("settings.channel.tcp")
            case .ftp: return L("settings.channel.ftp")
            case .httpPut, .httpMultipart: return L("settings.channel.http")
            }
        } ?? L("settings.channel.auto")
    }

    // MARK: - 关于

    private var aboutSection: some View {
        VStack(alignment: .leading, spacing: Space.m) {
            SectionHeader(L("settings.about"))

            VStack(spacing: 0) {
                InfoLine(label: L("settings.version"), value: version)
                Divider().overlay(Color.stroke)
                LinkLine(title: L("settings.help"),
                         url: "https://droidtrans.mkstore.life/support.html")
                Divider().overlay(Color.stroke)
                LinkLine(title: L("settings.website"),
                         url: "https://droidtrans.mkstore.life/")
                Divider().overlay(Color.stroke)
                LinkLine(title: L("me.privacy"),
                         url: "https://droidtrans.mkstore.life/privacy.html")
            }
            .glass()
        }
    }

    private var version: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? ""
        return b.isEmpty ? v : "\(v) (\(b))"
    }
}

// MARK: - 行

private struct InfoLine: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
                .font(.system(size: 15))
                .foregroundStyle(Color.ink)
            Spacer()
            Text(value)
                .font(.system(size: 14))
                .foregroundStyle(Color.ink2)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .padding(Space.l)
    }
}

private struct LinkLine: View {
    let title: String
    let url: String

    var body: some View {
        Link(destination: URL(string: url)!) {
            HStack {
                Text(title)
                    .font(.system(size: 15))
                    .foregroundStyle(Color.ink)
                Spacer()
                Image(systemName: "arrow.up.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Color.ink3)
            }
            .padding(Space.l)
        }
    }
}
