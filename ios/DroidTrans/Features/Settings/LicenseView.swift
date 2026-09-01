/// 激活 Pro。
///
/// 一个激活码三端通用 —— 在 macOS 或 Android 上买的码，输进来就能用。
/// 基础功能一律免费，这一屏解锁的只是高级功能。

import SwiftUI

struct LicenseView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var store = LicenseStore.shared

    @State private var code = ""
    @State private var busy = false
    @State private var error: String?
    @FocusState private var focused: Bool

    var body: some View {
        ZStack {
            AppBackground()

            ScrollView(showsIndicators: false) {
                VStack(spacing: Space.l) {
                    bar

                    if let lic = store.license {
                        activated(lic)
                    } else {
                        activate
                    }

                    Color.clear.frame(height: Space.xxl)
                }
                .padding(.horizontal, Space.gutter)
            }
        }
        .preferredColorScheme(.dark)
        .alert(L("license.failed"), isPresented: .constant(error != nil)) {
            Button(L("common.ok")) { error = nil }
        } message: {
            Text(error ?? "")
        }
    }

    private var bar: some View {
        HStack {
            Button(L("common.done")) { dismiss() }
                .foregroundStyle(Color.brand)
            Spacer()
            Text(L("license.title"))
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Color.ink)
            Spacer()
            Button(L("common.done")) {}.opacity(0).disabled(true)
        }
        .padding(.vertical, Space.m)
    }

    // MARK: - 已激活

    private func activated(_ lic: License) -> some View {
        VStack(spacing: Space.l) {
            VStack(spacing: Space.m) {
                ArtIcon(art: .shield, size: 76)
                Text(store.problem == nil ? L("license.active") : L("license.attention"))
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(Color.ink)
                Text(planText(lic))
                    .font(.system(size: 14))
                    .foregroundStyle(Color.ink2)
                    .multilineTextAlignment(.center)
            }
            .padding(.vertical, Space.l)

            if let problem = store.problem {
                VStack(spacing: Space.s) {
                    Text(problem.errorDescription ?? "")
                        .font(.system(size: 13.5))
                        .foregroundStyle(.orange)
                        .multilineTextAlignment(.center)
                    Button(L("license.refresh")) { Task { await refresh() } }
                        .buttonStyle(GhostButtonStyle())
                        .disabled(busy)
                }
                .padding(Space.l)
                .glass()
            }

            VStack(spacing: 0) {
                InfoRow(label: L("license.code"), value: lic.code)
                Divider().overlay(Color.stroke)
                InfoRow(label: L("license.email"), value: lic.email)
                if let d = lic.expiresAt {
                    Divider().overlay(Color.stroke)
                    InfoRow(label: L("license.expires"), value: dateText(d))
                }
            }
            .glass()

            Button(L("license.remove"), role: .destructive) {
                Task { await deactivate() }
            }
            .font(.system(size: 14))
            .foregroundStyle(.red.opacity(0.9))
            .padding(.top, Space.s)
            .disabled(busy)
        }
    }

    // MARK: - 未激活

    private var activate: some View {
        VStack(spacing: Space.l) {
            VStack(spacing: Space.m) {
                ArtIcon(art: .shield, size: 76)
                Text(L("license.free"))
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(Color.ink)
                Text(L("license.free.hint"))
                    .font(.system(size: 14))
                    .foregroundStyle(Color.ink2)
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)
            }
            .padding(.vertical, Space.l)

            VStack(alignment: .leading, spacing: Space.s) {
                SectionHeader(L("license.enterCode"))
                TextField("", text: $code,
                          prompt: Text("DT-XXXX-XXXX-XXXX").foregroundColor(Color.ink3))
                    .font(.system(size: 17, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.ink)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.characters)
                    .focused($focused)
                    .accessibilityIdentifier("license-code-field")
                    .padding(Space.l)
                    .glass(radius: Radius.tile)

                Button(busy ? L("license.activating") : L("license.activate")) {
                    Task { await activateCode() }
                }
                .buttonStyle(PrimaryButtonStyle(enabled: !trimmed.isEmpty && !busy))
                .disabled(trimmed.isEmpty || busy)
                .accessibilityIdentifier("license-activate")
            }

            Link(destination: URL(string: "https://droidtrans.mkstore.life/pricing.html")!) {
                Text(L("license.buy"))
                    .font(.system(size: 14))
                    .foregroundStyle(Color.brand)
            }
            .padding(.top, Space.s)
        }
    }

    // MARK: - 动作

    private var trimmed: String {
        code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    private func activateCode() async {
        busy = true
        defer { busy = false }
        do {
            let token = try await LicenseAPI.activate(code: trimmed)
            if case .failure(let e) = store.save(token) {
                error = e.localizedDescription
            }
        } catch {
            self.error = (error as? ApiError)?.message ?? error.localizedDescription
        }
    }

    private func refresh() async {
        guard let token = store.token else { return }
        busy = true
        defer { busy = false }
        do {
            let fresh = try await LicenseAPI.refresh(token: token)
            if case .failure(let e) = store.save(fresh) {
                error = e.localizedDescription
            }
        } catch {
            self.error = (error as? ApiError)?.message ?? error.localizedDescription
        }
    }

    private func deactivate() async {
        guard let token = store.token else {
            store.remove()
            return
        }
        busy = true
        defer { busy = false }
        do {
            try await LicenseAPI.deactivate(token: token)
            store.remove()
            code = ""
        } catch {
            self.error = (error as? ApiError)?.message ?? error.localizedDescription
        }
    }

    private func planText(_ lic: License) -> String {
        switch lic.plan {
        case "lifetime": return L("license.plan.lifetime")
        case "years3": return L("license.plan.years3")
        default: return L("license.plan.year")
        }
    }

    private func dateText(_ d: Date) -> String {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .none
        return f.string(from: d)
    }
}

private struct InfoRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
                .font(.system(size: 14))
                .foregroundStyle(Color.ink2)
            Spacer()
            Text(value)
                .font(.system(size: 14, design: .monospaced))
                .foregroundStyle(Color.ink)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .padding(Space.l)
    }
}
