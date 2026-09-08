/// 扫到的码里带着 SSID 和密码，就替用户把网连上。
///
/// 没有这一步，「扫一下就能传」在没有路由器的场合只是句空话：用户得退出 App、
/// 去「设置 → 无线局域网」里找那个热点、手输一串系统随机生成的密码，
/// 回来还要重新扫一次码。
///
/// NEHotspotConfiguration 需要 **Hotspot Configuration** 能力（entitlement），
/// 在 Apple 开发者后台给 App ID 勾上它，Xcode 工程里的
/// `com.apple.developer.networking.HotspotConfiguration` 才签得进去。
/// 没有这个能力时系统只会回一个错误，App 不会崩 —— 那时候退回「请手动连接 XXX」，
/// 至少把名字告诉用户。
///
/// 加进去的配置是 `joinOnce`：只为这一次传输连，断开就不会再自动回去，
/// 用户原来的 Wi-Fi 列表不会被这个 App 搅乱。

import Foundation
import NetworkExtension

enum HotspotJoin {

    enum Failure: Error, LocalizedError {
        case unsupported(String)
        case failed(String, String)   // (原因, ssid)

        var errorDescription: String? {
            switch self {
            case .unsupported(let ssid):
                return L("hotspot.manual", ssid)
            case .failed(_, let ssid):
                return L("hotspot.manual", ssid)
            }
        }
    }

    /// 连上返回；连不上抛错，错误里带着「请手动连接 XXX」这句能救场的话。
    static func join(ssid: String, password: String) async throws {
        guard !ssid.isEmpty else { return }

        let config: NEHotspotConfiguration
        if password.isEmpty {
            config = NEHotspotConfiguration(ssid: ssid)
        } else {
            config = NEHotspotConfiguration(ssid: ssid, passphrase: password, isWEP: false)
        }
        // 只连这一次。设成 false 的话，用户以后每次路过这个热点都会被自动拉进去，
        // 而它是一个没有互联网的临时网络。
        config.joinOnce = true

        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            NEHotspotConfigurationManager.shared.apply(config) { error in
                guard let error = error as NSError? else {
                    k.resume()
                    return
                }
                // 已经连着同一个热点：系统把它当错误报，但对我们来说这就是成功
                if error.domain == NEHotspotConfigurationErrorDomain,
                   error.code == NEHotspotConfigurationError.alreadyAssociated.rawValue {
                    k.resume()
                    return
                }
                k.resume(throwing: Failure.failed(error.localizedDescription, ssid))
            }
        }
    }
}
