/// 本机身份与各台电脑的配对令牌。

import Foundation
import UIKit

/// 主 App 和分享扩展是两个进程，各自的 UserDefaults.standard 互不相通。
/// 配对状态必须放进共享容器，否则扩展永远是「没配对过」的状态。
let kAppGroup = "group.life.mkstore.droidtrans"

@MainActor
final class Store: ObservableObject {
    static let shared = Store()

    /// App Group 没配好时退回 standard —— 宁可扩展用不了，
    /// 也不能让主 App 因为读不到偏好设置直接不可用
    private let defaults = UserDefaults(suiteName: kAppGroup) ?? .standard
    private enum Key {
        static let deviceName = "device_name"
        static let deviceId = "device_id"
        static let tokens = "tokens"
        static let tokensByName = "tokens_by_name"
        static let lastHost = "last_host"
        static let lastName = "last_name"
    }

    @Published var lastHost: String? {
        didSet { defaults.set(lastHost, forKey: Key.lastHost) }
    }

    /// 上次那台电脑叫什么。
    ///
    /// 光记地址不够：家里路由器重启一次、换个网段，地址就作废了，
    /// 而那台电脑还好端端地在雷达上。名字才是认得出它的那一样东西。
    @Published var lastName: String? {
        didSet { defaults.set(lastName, forKey: Key.lastName) }
    }

    private var tokens: [String: String] {
        didSet { defaults.set(tokens, forKey: Key.tokens) }
    }

    /// 同一个令牌再按名字存一份。
    ///
    /// 令牌本来只按 "host:port" 存，电脑一换 IP 就等于没配过 ——
    /// 用户什么也没做，却被要求重新输六位码。
    private var tokensByName: [String: String] {
        didSet { defaults.set(tokensByName, forKey: Key.tokensByName) }
    }

    private init() {
        // 早先的版本把这些存在 standard 里。装过旧版的用户升上来时搬一次，
        // 不然他会发现自己「莫名其妙又要重新配对」。
        Self.migrateFromStandardIfNeeded(into: defaults)

        tokens = defaults.dictionary(forKey: Key.tokens) as? [String: String] ?? [:]
        tokensByName = defaults.dictionary(forKey: Key.tokensByName) as? [String: String] ?? [:]
        lastHost = defaults.string(forKey: Key.lastHost)
        lastName = defaults.string(forKey: Key.lastName)
    }

    private static func migrateFromStandardIfNeeded(into shared: UserDefaults) {
        guard shared !== UserDefaults.standard else { return }
        guard shared.dictionary(forKey: Key.tokens) == nil else { return }

        let std = UserDefaults.standard
        if let old = std.dictionary(forKey: Key.tokens) {
            shared.set(old, forKey: Key.tokens)
            std.removeObject(forKey: Key.tokens)
        }
        if let host = std.string(forKey: Key.lastHost) {
            shared.set(host, forKey: Key.lastHost)
            std.removeObject(forKey: Key.lastHost)
        }
        if let name = std.string(forKey: Key.deviceName) {
            shared.set(name, forKey: Key.deviceName)
        }
    }

    /// 本机 ID。桌面端拿它区分设备、把上传归到同一批次里，
    /// 所以一旦生成就不能变 —— 存 Keychain 而不是 UserDefaults，
    /// 后者一卸载就没了，用户重装后电脑上会凭空多出一台「新手机」。
    var deviceId: String {
        if let existing = Keychain.get("device_id") {
            // 扩展读不到主 App 的钥匙串（除非配 access group），
            // 所以在共享容器里留一份镜像给它用
            if defaults.string(forKey: Key.deviceId) == nil {
                defaults.set(existing, forKey: Key.deviceId)
            }
            return existing
        }
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        let id = bytes.map { String(format: "%02x", $0) }.joined()
        Keychain.set("device_id", id)
        defaults.set(id, forKey: Key.deviceId)
        return id
    }

    /// 显示给电脑看的名字，默认取系统里的设备名。
    var deviceName: String {
        get {
            let saved = defaults.string(forKey: Key.deviceName) ?? ""
            if !saved.isEmpty { return saved }
            let sys = UIDevice.current.name
            return sys.isEmpty ? "iPhone" : sys
        }
        set { defaults.set(newValue, forKey: Key.deviceName) }
    }

    // MARK: - 令牌

    /// 令牌是按「哪台电脑」存的：同一部手机可以同时配对家里和公司的电脑。
    /// 先按地址找，找不到再按名字找 —— 后者是电脑换了 IP 时的退路。
    /// 拿错了也不要紧：电脑那边会拒，App 照样退回配对页，
    /// 只是绝大多数情况下用户不用再输一次码。
    func token(for desktop: Desktop) -> String {
        tokens[desktop.id] ?? tokensByName[desktop.name] ?? ""
    }

    func setToken(_ token: String, for desktop: Desktop) {
        tokens[desktop.id] = token
        tokensByName[desktop.name] = token
    }

    /// 电脑那边撤销了这台手机的授权时调用，免得一直拿废令牌去撞 403。
    func clearToken(for desktop: Desktop) {
        tokens.removeValue(forKey: desktop.id)
        tokensByName.removeValue(forKey: desktop.name)
    }

    /// 只给 UI 测试用：把配对状态清干净，让每次跑都从「刚装好」开始。
    ///
    /// 不清的话，装过一次之后 App 会自动连回上次那台电脑，
    /// 雷达页压根不出现，所有走雷达的用例都会以「没找到电脑」失败 ——
    /// 那是个假故障，产品其实是好的。
    func resetForTesting() {
        tokens = [:]
        tokensByName = [:]
        lastHost = nil
        lastName = nil
    }
}

// MARK: - Keychain

/// 只用来存一个设备 ID，所以不做成通用封装。
enum Keychain {
    private static let service = "life.mkstore.droidtrans"

    static func get(_ key: String) -> String? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func set(_ key: String, _ value: String) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = Data(value.utf8)
        // 设备 ID 不涉及隐私，但也没必要在锁屏时可读；
        // ThisDeviceOnly 保证它不会跟着 iCloud 备份跑到另一台手机上
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }
}
