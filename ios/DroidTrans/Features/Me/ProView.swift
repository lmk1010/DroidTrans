/// 开通 Pro。
///
/// 两条路并存：
///   · 在这里内购（iOS 上卖东西只能走 App Store）
///   · 输入在 macOS / Android 上买的激活码
///
/// 基础功能一律免费，这一屏解锁的只是高级功能 —— 文案上必须说清楚，
/// 不然用户会以为传个文件都要付费。

import StoreKit
import SwiftUI

struct ProView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var license = LicenseStore.shared
    @StateObject private var iap = IAP.shared

    @State private var selected: ProPlan = .lifetime
    @State private var buying = false
    @State private var showCode = false

    var body: some View {
        ZStack {
            AppBackground()

            ScrollView(showsIndicators: false) {
                VStack(spacing: Space.l) {
                    bar

                    if license.isPro {
                        activated
                    } else {
                        pitch
                        plans
                        actions
                    }

                    Color.clear.frame(height: Space.xxl)
                }
                .padding(.horizontal, Space.gutter)
            }
        }
        .preferredColorScheme(.dark)
        .task { await iap.load() }
        .sheet(isPresented: $showCode) { LicenseView() }
        .alert(L("license.failed"), isPresented: .constant(iap.error != nil)) {
            Button(L("common.ok")) { iap.error = nil }
        } message: {
            Text(iap.error ?? "")
        }
    }

    private var bar: some View {
        HStack {
            Button(L("common.done")) { dismiss() }
                .foregroundStyle(Color.brand)
                .accessibilityIdentifier("close-pro")
            Spacer()
            Text(L("license.title"))
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Color.ink)
            Spacer()
            Button(L("common.done")) {}.opacity(0).disabled(true)
        }
        .padding(.vertical, Space.m)
    }

    // MARK: - 已开通

    private var activated: some View {
        VStack(spacing: Space.l) {
            ArtIcon(art: .shield, size: 88)
            Text(L("license.active"))
                .font(.system(size: 22, weight: .bold))
                .foregroundStyle(Color.ink)
                .accessibilityIdentifier("pro-activated")
            if let lic = license.license {
                Text(lic.isLifetime ? L("license.plan.lifetime") : lic.code)
                    .font(.system(size: 14, design: .monospaced))
                    .foregroundStyle(Color.ink2)
            }
            Button(L("license.manage")) { showCode = true }
                .buttonStyle(GhostButtonStyle())
        }
        .padding(.top, Space.xl)
    }

    // MARK: - 卖点

    private var pitch: some View {
        VStack(spacing: Space.l) {
            ArtIcon(art: .shield, size: 88)

            VStack(spacing: Space.s) {
                Text(L("pro.headline"))
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(Color.ink)
                    .multilineTextAlignment(.center)
                Text(L("pro.sub"))
                    .font(.system(size: 14))
                    .foregroundStyle(Color.ink2)
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)
            }

            VStack(spacing: Space.s) {
                Perk(text: L("pro.perk.free"))
                Perk(text: L("pro.perk.threePlatforms"))
                Perk(text: L("pro.perk.oneTime"))
            }
        }
        .padding(.top, Space.s)
    }

    // MARK: - 档位

    private var plans: some View {
        VStack(spacing: Space.s) {
            ForEach(ProPlan.allCases) { plan in
                PlanRow(
                    plan: plan,
                    product: iap.products.first { $0.id == plan.rawValue },
                    selected: selected == plan,
                    owned: iap.owned.contains(plan.rawValue)
                ) {
                    selected = plan
                }
            }

            if iap.loading && iap.products.isEmpty {
                ProgressView().tint(Color.ink2).padding(.vertical, Space.m)
            }
        }
    }

    private var actions: some View {
        VStack(spacing: Space.m) {
            Button(buying ? L("pro.buying") : buyTitle) {
                Task { await buy() }
            }
            .buttonStyle(PrimaryButtonStyle(enabled: canBuy && !buying))
            .disabled(!canBuy || buying)
            .accessibilityIdentifier("pro-buy")

            HStack(spacing: Space.l) {
                Button(L("pro.restore")) { Task { await iap.restore() } }
                Button(L("pro.haveCode")) { showCode = true }
            }
            .font(.system(size: 13.5))
            .foregroundStyle(Color.ink2)

            Text(L("pro.legal"))
                .font(.system(size: 11.5))
                .foregroundStyle(Color.ink3)
                .multilineTextAlignment(.center)
                .lineSpacing(2)
                .padding(.top, Space.s)
        }
    }

    private var canBuy: Bool { iap.products.contains { $0.id == selected.rawValue } }

    private var buyTitle: String {
        guard let p = iap.products.first(where: { $0.id == selected.rawValue }) else {
            return L("pro.unavailable")
        }
        return L("pro.buy", p.displayPrice)
    }

    private func buy() async {
        guard let product = iap.products.first(where: { $0.id == selected.rawValue }) else { return }
        buying = true
        defer { buying = false }
        _ = await iap.buy(product)
    }
}

// MARK: - 组件

private struct Perk: View {
    let text: String

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
        .padding(.horizontal, Space.m)
    }
}

private struct PlanRow: View {
    let plan: ProPlan
    let product: Product?
    let selected: Bool
    let owned: Bool
    var onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: Space.m) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .font(.system(size: 20))
                    .foregroundStyle(selected ? Color.brand : Color.ink3)

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(plan.title)
                            .font(.system(size: 16, weight: .medium))
                            .foregroundStyle(Color.ink)
                        if plan == .lifetime {
                            Text(L("pro.best"))
                                .font(.system(size: 10, weight: .bold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Capsule().fill(Color.brand))
                        }
                    }
                    if owned {
                        Text(L("pro.owned"))
                            .font(.system(size: 12))
                            .foregroundStyle(.green)
                    }
                }

                Spacer()

                Text(product?.displayPrice ?? "—")
                    .font(.system(size: 17, weight: .semibold, design: .rounded))
                    .foregroundStyle(Color.ink)
            }
            .padding(Space.l)
            .background(
                RoundedRectangle(cornerRadius: Radius.card, style: .continuous)
                    .fill(.ultraThinMaterial)
                    .environment(\.colorScheme, .dark)
            )
            .overlay(
                RoundedRectangle(cornerRadius: Radius.card, style: .continuous)
                    .strokeBorder(selected ? Color.brand : Color.stroke,
                                  lineWidth: selected ? 1.6 : 0.7)
            )
            .clipShape(RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("plan-\(plan.serverPlan)")
    }
}
