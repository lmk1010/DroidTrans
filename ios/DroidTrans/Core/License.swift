/// 授权。
///
/// 许可证串的形式是 base64url(JSON) + "." + base64url(签名)。
/// 签名覆盖的是**前半段那串字符本身**，不是解码后的 JSON ——
/// 三端必须一致，否则 Node 签出来的东西这里永远验不过。
///
/// 对齐 desktop/internal/license/license.go，改动要几边一起改。

import CryptoKit
import Foundation

struct License: Codable, Equatable {
    let v: Int
    let product: String
    let plan: String          // year / years3 / lifetime
    let code: String
    /// 新许可证绑定到签发时的设备；nil 兼容早期 v2 许可证。
    let deviceId: String?
    let email: String
    let issued: String
    /// 终身版是 null
    let expires: String?
    let maxVersion: String

    var isLifetime: Bool { expires == nil }

    var expiresAt: Date? {
        guard let expires else { return nil }
        return ISO8601DateFormatter.dt.date(from: expires)
    }

    var issuedAt: Date? { ISO8601DateFormatter.dt.date(from: issued) }

    var expired: Bool {
        guard let d = expiresAt else { return false }
        return d < Date()
    }

    /// 太久没回连服务端。不是立刻失效，只是该续签一次了。
    var stale: Bool {
        guard let d = issuedAt else { return false }
        return Date().timeIntervalSince(d) > LicenseStore.staleAfter
    }

    var needsRefresh: Bool {
        guard let d = issuedAt else { return false }
        return Date().timeIntervalSince(d) > LicenseStore.refreshAfter
    }
}

enum LicenseError: Error, LocalizedError {
    case malformed
    case badSignature
    case wrongProduct
    case deviceMismatch
    case expired(License)
    case stale(License)

    var errorDescription: String? {
        switch self {
        case .malformed: return L("license.err.malformed")
        case .badSignature: return L("license.err.signature")
        case .wrongProduct: return L("license.err.product")
        case .deviceMismatch: return L("license.err.device")
        case .expired: return L("license.err.expired")
        case .stale: return L("license.err.stale")
        }
    }
}

enum LicenseVerifier {
    /// 签发方的 Ed25519 公钥。
    ///
    /// 私钥在授权服务那边，只有它能签出有效许可证。
    /// 这里放的是公钥，泄漏无所谓 —— 它只能验证，不能签发。
    /// 与 desktop/internal/license/key.go 是同一把。
    private static let publicKeyRaw = Data(base64Encoded:
        "1DoaCefyZVtNEp7mzFstWOPezLYPU6LPuUkIv1R5hqo=")!

    static func verify(_ token: String, expectedDeviceId: String? = nil) throws -> License {
        guard let dot = token.firstIndex(of: "."), dot != token.startIndex,
              token.index(after: dot) != token.endIndex else {
            throw LicenseError.malformed
        }
        let body = String(token[token.startIndex..<dot])
        let sigPart = String(token[token.index(after: dot)...])

        guard let sig = Data(base64URL: sigPart), sig.count == 64,
              let raw = Data(base64URL: body) else {
            throw LicenseError.malformed
        }

        let key = try Curve25519.Signing.PublicKey(rawRepresentation: publicKeyRaw)
        // 验的是 body 那串字符，不是它解码出来的字节
        guard key.isValidSignature(sig, for: Data(body.utf8)) else {
            throw LicenseError.badSignature
        }

        guard let lic = try? JSONDecoder().decode(License.self, from: raw) else {
            throw LicenseError.malformed
        }
        guard lic.v == 2, lic.product == "droidtrans-pro" else {
            throw LicenseError.wrongProduct
        }
        if let boundDeviceId = lic.deviceId,
           let expectedDeviceId,
           boundDeviceId != expectedDeviceId {
            throw LicenseError.deviceMismatch
        }

        // 过期要排在「太久没回连」前面判断：过期是更根本的状态，
        // 联网续签也救不回来（服务端会签发同样已过期的到期时间），
        // 用户需要的是「续费」而不是「联网一次」。顺序反了会给出误导性的提示。
        if lic.expired { throw LicenseError.expired(lic) }
        if lic.stale { throw LicenseError.stale(lic) }
        return lic
    }
}

// MARK: - 存放

@MainActor
final class LicenseStore: ObservableObject {
    static let shared = LicenseStore()

    /// 超过这个时长就该找服务端换一份新的
    static let refreshAfter: TimeInterval = 14 * 24 * 3600
    /// 超过这个时长还没回连过，就不再当有效授权
    static let staleAfter: TimeInterval = 45 * 24 * 3600

    @Published private(set) var license: License?
    /// 过期或太久没回连时，仍然把内容留着，界面才能提示「续期」而不是一句「无效」
    @Published private(set) var problem: LicenseError?
    /// App Store 已验证的本机权益。
    ///
    /// 通用许可证兑换依赖网络；购买已经由 StoreKit 验过时，兑换服务暂时不可用
    /// 不能反过来把这台 iPhone 锁回免费版。
    @Published private(set) var localPurchase = false

    private let defaults = UserDefaults(suiteName: kAppGroup) ?? .standard
    private let key = "license_token"
    private var refreshing = false

    var isPro: Bool { (license != nil && problem == nil) || localPurchase }

    private init() {
        // 内购的用例要从「还没买过」开始。不清的话，上一次跑留下的许可证
        // 会让 Pro 页直接显示「已激活」，档位行压根不渲染 ——
        // 测试报的是「缺少档位」，看起来像界面坏了，其实是状态没清干净。
        //
        // 只清许可证，不动配对：那是另一个开关（-uitest-fresh）的事。
        if ProcessInfo.processInfo.arguments.contains("-uitest-no-license") {
            defaults.removeObject(forKey: key)
        }
        reload()
    }

    func reload() {
        guard let token = defaults.string(forKey: key) else {
            license = nil
            problem = nil
            return
        }
        do {
            license = try LicenseVerifier.verify(token, expectedDeviceId: Store.shared.deviceId)
            problem = nil
        } catch let e as LicenseError {
            switch e {
            case .expired(let l), .stale(let l):
                license = l
            default:
                license = nil
            }
            problem = e
        } catch {
            license = nil
            problem = .malformed
        }
    }

    /// 存之前先验一遍。验不过的串不该落盘 ——
    /// 否则每次启动都要重新失败一次，用户还以为自己已经激活了。
    @discardableResult
    func save(_ token: String) -> Result<License, Error> {
        do {
            let lic = try LicenseVerifier.verify(token, expectedDeviceId: Store.shared.deviceId)
            defaults.set(token, forKey: key)
            license = lic
            problem = nil
            return .success(lic)
        } catch {
            return .failure(error)
        }
    }

    func remove() {
        defaults.removeObject(forKey: key)
        license = nil
        problem = nil
    }

    var token: String? { defaults.string(forKey: key) }

    func setLocalPurchase(_ owned: Bool) {
        localPurchase = owned
    }

    /// 启动时静默续签。联网失败保留当前离线授权，不打断用户传文件。
    func refreshIfNeeded() async {
        guard !refreshing, let token,
              let license,
              license.needsRefresh || {
                  if case .stale = problem { return true }
                  return false
              }() else { return }

        refreshing = true
        defer { refreshing = false }
        do {
            let fresh = try await LicenseAPI.refresh(token: token)
            _ = save(fresh)
        } catch {
            if let api = error as? ApiError,
               api.code == "revoked" || api.code == "device_deactivated" {
                remove()
            }
            // 仍在 45 天宽限期内就继续离线可用；过期状态留给授权页提示用户重试。
        }
    }
}

// MARK: - 工具

extension Data {
    /// base64url 没有 padding，且用 -_ 代替 +/
    init?(base64URL s: String) {
        var t = s.replacingOccurrences(of: "-", with: "+")
                 .replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        guard let d = Data(base64Encoded: t) else { return nil }
        self = d
    }
}

extension ISO8601DateFormatter {
    /// 服务端签的时间戳带毫秒，默认的 formatter 认不了，会全部解析成 nil
    static let dt: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
}
