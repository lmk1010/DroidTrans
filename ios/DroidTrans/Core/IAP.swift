/// 内购。
///
/// iOS 上卖东西只能走 App Store —— 引导用户去站外付款会被拒审。
/// 但反过来是允许的：在别的平台（macOS / Android）买的激活码，
/// 可以在这里输入激活，所以两条路并存。
///
/// 商品 ID 与 Resources/Products.storekit 一致，上架前要在
/// App Store Connect 里建同名商品：1 年/3 年是非续期订阅，终身是非消耗型。

import Foundation
import StoreKit

enum ProPlan: String, CaseIterable, Identifiable {
    case year = "life.mkstore.droidtrans.pro.year"
    case years3 = "life.mkstore.droidtrans.pro.years3"
    case lifetime = "life.mkstore.droidtrans.pro.lifetime"

    var id: String { rawValue }

    /// 与授权服务那边的 plan 字段对齐，换许可证时要用
    var serverPlan: String {
        switch self {
        case .year: return "year"
        case .years3: return "years3"
        case .lifetime: return "lifetime"
        }
    }

    var title: String {
        switch self {
        case .year: return L("license.plan.year")
        case .years3: return L("license.plan.years3")
        case .lifetime: return L("license.plan.lifetime")
        }
    }

    /// 非续期订阅的有效期由 App 自己判断；终身档没有到期时间。
    var entitlementDays: Int? {
        switch self {
        case .year: return 365
        case .years3: return 365 * 3
        case .lifetime: return nil
        }
    }

    func isActive(purchasedAt: Date, now: Date = Date()) -> Bool {
        guard let days = entitlementDays else { return true }
        let expires = purchasedAt.addingTimeInterval(TimeInterval(days) * 24 * 3600)
        return expires > now
    }
}

@MainActor
final class IAP: ObservableObject {
    static let shared = IAP()

    @Published private(set) var products: [Product] = []
    @Published private(set) var owned: Set<String> = []

    /// 测试要从「还没买过」开始时，连 StoreKit 已持有的交易也当作没有。
    ///
    /// -uitest-no-license 原来只清许可证文件，但 isPro 还看 localPurchase ——
    /// 而 StoreKit 测试环境里的交易是跨次持久的。上一轮跑过购买用例之后，
    /// 下一轮的「免费版应该被挡住」就会失败，报的是「门禁没生效」，
    /// 看着像产品 bug，其实是上一轮的状态没清干净。
    ///
    /// 只作用于启动时恢复历史交易那一步。**当次购买必须照常走完** ——
    /// 在购买回调里提前 return 会跳过兑换和 t.finish()，
    /// 交易不 finish 就会一直重放，而且「购买能换到通用许可证」也就测不了了。
    private var pretendNotPurchased: Bool {
        ProcessInfo.processInfo.arguments.contains("-uitest-no-license")
    }
    @Published private(set) var loading = false
    @Published var error: String?

    /// 买过任意一档就算 Pro。
    /// 注意这只代表「这个 Apple ID 买过」，跨端授权还得靠许可证。
    var hasPurchase: Bool { !owned.isEmpty }

    private var updates: Task<Void, Never>?

    private init() {
        // 交易可能在 App 之外完成（比如家人共享、或者上次没走完的购买），
        // 所以要一直挂着监听，不能只在购买时等返回值
        updates = Task.detached { [weak self] in
            for await result in Transaction.updates {
                guard let self else { return }
                await self.handle(result)
            }
        }
    }

    deinit { updates?.cancel() }

    func load() async {
        guard products.isEmpty else { return }
        loading = true
        defer { loading = false }
        do {
            let list = try await Product.products(for: ProPlan.allCases.map(\.rawValue))
            // 按价格排，界面上从便宜到贵
            products = list.sorted { $0.price < $1.price }
        } catch {
            self.error = error.localizedDescription
        }
        await refreshOwned()
    }

    /// 当前这个 Apple ID 仍在有效期内的权益。
    func refreshOwned() async {
        var found: Set<String> = []
        var redeemJWS: String?
        for await result in Transaction.currentEntitlements {
            guard case .verified(let t) = result,
                  t.revocationDate == nil,
                  let plan = ProPlan(rawValue: t.productID),
                  plan.isActive(purchasedAt: t.purchaseDate) else { continue }
            found.insert(t.productID)
            if redeemJWS == nil || plan == .lifetime {
                redeemJWS = result.jwsRepresentation
            }
        }
        if pretendNotPurchased {
            // 假装没买过：本机权益和后续的兑换都跳过，
            // 不然 isPro 还是 true，「免费版该被挡住」的用例就永远过不了。
            owned = []
            LicenseStore.shared.setLocalPurchase(false)
            return
        }
        owned = found
        LicenseStore.shared.setLocalPurchase(!found.isEmpty)

        // 重装或换机后 currentEntitlements 能恢复本机权益，也要顺便把三端通用
        // 许可证取回来；否则 iPhone 显示已购买，Mac/Android 却没有可用激活码。
        if !found.isEmpty,
           LicenseStore.shared.token == nil || LicenseStore.shared.problem != nil,
           let redeemJWS {
            await redeem(redeemJWS)
        }
    }

    func buy(_ product: Product) async -> Bool {
        do {
            let result = try await product.purchase()
            switch result {
            case .success(let verification):
                await handle(verification)
                return true
            case .userCancelled:
                return false
            case .pending:
                // 需要家长同意之类，交易会在之后通过 Transaction.updates 回来
                error = L("iap.pending")
                return false
            @unknown default:
                return false
            }
        } catch {
            self.error = error.localizedDescription
            return false
        }
    }

    /// 换了手机、重装之后用这个把已买的恢复回来。
    func restore() async {
        loading = true
        defer { loading = false }
        try? await AppStore.sync()
        await refreshOwned()
        if owned.isEmpty { error = L("iap.nothingToRestore") }
    }

    private func handle(_ result: VerificationResult<Transaction>) async {
        // 未通过校验的交易一律不认。StoreKit 2 已经替我们验过签名，
        // 走到 .unverified 说明这笔东西不可信。
        guard case .verified(let t) = result else { return }
        guard let plan = ProPlan(rawValue: t.productID) else {
            await t.finish()
            return
        }
        if t.revocationDate != nil || !plan.isActive(purchasedAt: t.purchaseDate) {
            owned.remove(t.productID)
            LicenseStore.shared.setLocalPurchase(!owned.isEmpty)
            await t.finish()
            return
        }
        owned.insert(t.productID)
        LicenseStore.shared.setLocalPurchase(true)

        // 换一份三端通用的许可证。只在本机解锁是不够的 ——
        // 用户在 Mac 上也该能用同一份授权。
        await redeem(result.jwsRepresentation)

        // 必须 finish，否则这笔交易会一直重放
        await t.finish()
    }

    /// 拿交易凭证去服务端换许可证。
    ///
    /// 换不到不影响本机使用（owned 已经记下了），但要告诉用户 ——
    /// 否则他在 Mac 上找不到自己的码，会以为白买了。
    private func redeem(_ jws: String) async {
        do {
            let result = try await LicenseAPI.redeemApple(jws: jws)
            if case .failure(let e) = LicenseStore.shared.save(result.license) {
                error = e.localizedDescription
            }
        } catch {
            self.error = (error as? ApiError)?.message ?? error.localizedDescription
        }
    }
}
