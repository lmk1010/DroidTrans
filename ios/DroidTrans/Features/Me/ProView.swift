/// 开通 Pro。
///
/// 两条路并存：
///   · 在这里内购（iOS 上卖东西只能走 App Store）
///   · 输入在 macOS / Android 上买的激活码
///
/// 这一屏只有一件事要做对：**让用户看得出这笔钱买到了什么**。
///
/// 光罗列 Pro 有什么是不够的 —— 没有参照系，用户没法判断值不值。
/// 所以做成免费 / Pro 两列对照：免费那一列给得很足（局域网互传不限量、
/// 断点续传、4 GB 以内随便传），这既是事实，也让 Pro 那一列的差额一目了然。
///
/// 表里每一行都必须对应真实的门控。之前写过一条「原始画质」，
/// 可免费版走 PHPicker 的 .current 模式拿到的本来就是原图 ——
/// 把免费就有的东西列成付费权益，是这类页面最容易犯也最伤信任的错。

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
                        compare
                        plans
                        actions
                    }

                    Color.clear.frame(height: Space.xl)
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

    /// 已激活的样子。
    ///
    /// 之前这里只有一个盾牌图标加一行「已激活」——把最该强化价值的时刻
    /// 浪费掉了：付了钱的人打开来，看不到自己比免费版多拿了什么。
    /// 现在对照表照样摆着，Pro 那一列标成「你的」，
    /// 免费列里被解除的额度划掉，一眼看到「这几行我赚到了」。
    private var activated: some View {
        VStack(spacing: Space.l) {
            VStack(spacing: Space.s) {
                ArtIcon(art: .shield, size: 64)
                Text(L("license.active"))
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(Color.ink)
                    .accessibilityIdentifier("pro-activated")
                Text(L("pro.owned.summary", "\(CompareRow.gainCount)"))
                    .font(.system(size: 13))
                    .foregroundStyle(Color.ok)
            }

            compare

            if let lic = license.license {
                Text(lic.isLifetime ? L("license.plan.lifetime") : lic.code)
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundStyle(Color.ink3)
            }
            Button(L("license.manage")) { showCode = true }
                .buttonStyle(GhostButtonStyle())
        }
        .padding(.top, Space.m)
    }

    // MARK: - 卖点

    /// 标题区。图标用 sync 而不是 shield —— 盾牌讲的是「安全」，
    /// 但这一屏卖的是相册同步，图得指向功能本身。
    private var pitch: some View {
        VStack(spacing: Space.s) {
            ArtIcon(art: .sync, size: 60)

            Text(L("pro.headline"))
                .font(.system(size: 22, weight: .bold))
                .foregroundStyle(Color.ink)
                .multilineTextAlignment(.center)

            Text(L("pro.sub"))
                .font(.system(size: 13.5))
                .foregroundStyle(Color.ink2)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
        }
    }

    /// 免费 / Pro 对照表。
    ///
    /// 两列并排，差额自己说话。免费列不是摆设 —— 局域网互传不限量、
    /// 断点续传全都在免费列里，那是我们对 LocalSend 的正面回应，
    /// 也是这张表可信的前提：一张只写「Pro 有、免费没有」的表，
    /// 用户第一反应是被阉割了。
    private var compare: some View {
        VStack(spacing: 0) {
            CompareHeader(owned: license.isPro)

            CompareSection(L("pro.section.phone"))
            CompareRow(owned: license.isPro, title: L("pro.row.lan"),
                       free: L("pro.row.lan.both"), pro: L("pro.row.lan.both"))
            CompareRow(owned: license.isPro, title: L("pro.row.resume"),
                       free: L("pro.compare.yes"), pro: L("pro.compare.yes"))
            CompareRow(owned: license.isPro, title: L("pro.row.filesize"),
                       free: L("pro.row.filesize.free"), pro: L("pro.compare.unlimited"))
            CompareRow(owned: license.isPro, title: L("pro.row.sync"),
                       free: L("pro.compare.no"), pro: L("pro.compare.yes"))
            CompareRow(owned: license.isPro, title: L("pro.row.live"),
                       free: L("pro.row.live.free"), pro: L("pro.row.live.pro"))

            CompareSection(L("pro.section.mac"))
            CompareRow(owned: license.isPro, title: L("pro.row.export"),
                       free: L("pro.row.export.free"), pro: L("pro.compare.unlimited"))
            CompareRow(owned: license.isPro, title: L("pro.row.usb"),
                       free: L("pro.compare.no"), pro: L("pro.compare.yes"))
            CompareRow(owned: license.isPro, title: L("pro.row.dedupe"),
                       free: L("pro.compare.no"), pro: L("pro.compare.yes"), last: true)
        }
        .glass()
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

            // 免费版没有时间限制，这一点要说死 ——
            // 「试用版」的联想会让人根本不下手用。
            Text(L("pro.compare.note"))
                .font(.system(size: 12))
                .foregroundStyle(Color.ink3)
                .multilineTextAlignment(.center)
                .lineSpacing(2)
                .padding(.top, Space.s)

            legal
        }
    }

    /// 付费页必须能点到条款和隐私政策，审核会看。
    /// 我们没有自己的 EULA，用 Apple 的标准版就是合规的。
    private var legal: some View {
        VStack(spacing: Space.s) {
            Text(L("pro.legal"))
                .font(.system(size: 11.5))
                .foregroundStyle(Color.ink3)
                .multilineTextAlignment(.center)
                .lineSpacing(2)

            HStack(spacing: Space.m) {
                Link(L("pro.terms"),
                     destination: URL(string: "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/")!)
                Text("·").foregroundStyle(Color.ink3)
                Link(L("pro.privacy"),
                     destination: URL(string: "https://droidtrans.mkstore.life/privacy.html")!)
            }
            .font(.system(size: 11.5))
            .foregroundStyle(Color.ink2)
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

/// 表头。两列的宽度在这里定死，下面每一行都用同一个常量，
/// 否则各行的数字对不齐，一眼就显得糙。
private let compareColumn: CGFloat = 76

private struct CompareHeader: View {
    var owned = false

    var body: some View {
        HStack(spacing: 0) {
            Spacer(minLength: 0)
            Text(L("pro.compare.free"))
                .frame(width: compareColumn)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(Color.ink3)
            Text(owned ? L("pro.compare.mine") : L("pro.compare.pro"))
                .frame(width: compareColumn)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(owned ? Color.ok : Color.brand)
        }
        .padding(.horizontal, Space.l)
        .padding(.top, Space.l)
        .padding(.bottom, Space.s)
    }
}

private struct CompareSection: View {
    let title: String
    init(_ t: String) { title = t }

    var body: some View {
        HStack {
            Text(title)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(Color.ink3)
                .textCase(.uppercase)
            Spacer()
        }
        .padding(.horizontal, Space.l)
        .padding(.top, Space.m)
        .padding(.bottom, 4)
    }
}

private struct CompareRow: View {
    var owned = false
    let title: String
    let free: String
    let pro: String
    var last = false

    /// 免费和 Pro 不一样的行数 —— 也就是「买到了几项」。
    /// 写死在这里而不是数组里数，是因为这张表本身就是写死的；
    /// 加行的时候记得同步，否则「已解锁 N 项」会对不上。
    static let gainCount = 5

    /// 这一行有没有差额。没差额的（局域网互传、断点续传）不该划掉免费列。
    private var gain: Bool { free != pro }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                Text(title)
                    .font(.system(size: 13.5))
                    .foregroundStyle(Color.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: Space.s)
                freeValue
                proValue
            }
            .padding(.horizontal, Space.l)
            .padding(.vertical, 9)

            if !last {
                Divider().overlay(Color.strokeSoft).padding(.leading, Space.l)
            } else {
                Color.clear.frame(height: Space.m)
            }
        }
    }

    /// 免费那一列。已购买且这一行有差额时划掉 —— 那是「你已经不受的限制」。
    private var freeValue: some View {
        // strikethrough 必须直接接在 Text 上：接在 .foregroundStyle 之后
        // 会解析成 View 那个重载，那个要 iOS 16，而部署目标是 15.5。
        Text(free)
            .strikethrough(owned && gain, color: Color.ink3.opacity(0.6))
            .font(.system(size: 12.5))
            .foregroundStyle(Color.ink3)
            .multilineTextAlignment(.center)
            .frame(width: compareColumn)
    }

    /// Pro 那一列稍微亮一点。不用背景色块 —— 一整列底色会把表格切成两半，
    /// 反而看不出是在比较同一行。
    private var proValue: some View {
        Text(pro)
            .font(.system(size: 12.5, weight: .semibold))
            .foregroundStyle(owned ? Color.ok : Color.ink)
            .multilineTextAlignment(.center)
            .frame(width: compareColumn)
    }
}

private struct PlanRow: View {
    let plan: ProPlan
    let product: Product?
    let selected: Bool
    let owned: Bool
    var onTap: () -> Void

    /// 三年档写成「约 $x / 年」，让「便宜多少」不用心算。
    /// 单价从 StoreKit 的实际价格算，不写死 —— 各地区货币和定价不一样。
    private var note: String? {
        switch plan {
        case .lifetime:
            return L("pro.forever")
        case .years3:
            guard let product else { return nil }
            return L("pro.perYear", (product.price / 3).formatted(product.priceFormatStyle))
        case .year:
            return nil
        }
    }

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
                    } else if let note {
                        Text(note)
                            .font(.system(size: 12))
                            .foregroundStyle(Color.ink3)
                    }
                }

                Spacer()

                Text(product?.displayPrice ?? "—")
                    .font(.system(size: 17, weight: .semibold, design: .rounded))
                    .foregroundStyle(Color.ink)
            }
            .padding(.horizontal, Space.l)
            .padding(.vertical, Space.m)
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
