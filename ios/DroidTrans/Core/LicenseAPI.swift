/// 授权服务的客户端。
///
/// 三条路都在这儿：激活码换许可证、定期续签、iOS 内购换许可证。

import Foundation

enum LicenseAPI {
    /// 调试时可以用 -licensing-base http://192.168.x.x:8798 指向本地服务，
    /// 免得为了试一次兑换就往线上发一堆测试交易
    static var base: String {
        if let i = ProcessInfo.processInfo.arguments.firstIndex(of: "-licensing-base"),
           i + 1 < ProcessInfo.processInfo.arguments.count {
            return ProcessInfo.processInfo.arguments[i + 1]
        }
        return "https://droidtrans.mkstore.life"
    }

    /// 拿 Apple 的交易凭证换一份三端通用的许可证。
    ///
    /// iOS 内购只解锁 iOS 是不够的 —— 用户在 Mac 上也该能用同一份授权，
    /// 所以购买之后必须来服务端换一次，拿到和 Waffo 那边同格式的许可证。
    static func redeemApple(jws: String) async throws -> (code: String, license: String) {
        guard let url = URL(string: base + "/api/apple/redeem") else {
            throw ApiError(L("error.badAddress", "/api/apple/redeem"))
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: [
            "jws": jws,
            "device_id": await deviceId(),
        ])
        req.timeoutInterval = 25

        let (data, resp) = try await URLSession.shared.data(for: req)
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        guard (200..<300).contains(status),
              let license = json["license"] as? String, !license.isEmpty else {
            let code = json["error"] as? String
            throw ApiError(appleMessage(code, status: status), statusCode: status, code: code)
        }
        return (json["code"] as? String ?? "", license)
    }

    private static func appleMessage(_ code: String?, status: Int) -> String {
        switch code {
        case "bad_transaction": return L("iap.err.badTransaction")
        case "unknown_product": return L("iap.err.unknownProduct")
        case "revoked": return L("license.err.revoked")
        case "too_many_activations": return L("license.err.tooMany")
        case "missing_device_id": return L("license.err.device")
        default: return "HTTP \(status)"
        }
    }

    static func activate(code: String) async throws -> String {
        try await post("/api/activate", ["code": code])
    }

    /// 定期拿旧许可证换一份新的，把「上次回连时间」往前推。
    static func refresh(token: String) async throws -> String {
        try await post("/api/refresh", ["license": token])
    }

    /// 释放服务端的设备名额。成功之后调用方才删除本地许可证。
    static func deactivate(token: String) async throws {
        guard let url = URL(string: base + "/api/deactivate") else {
            throw ApiError(L("error.badAddress", "/api/deactivate"))
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: [
            "license": token,
            "device_id": await deviceId(),
        ])
        req.timeoutInterval = 20

        let (data, resp) = try await URLSession.shared.data(for: req)
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        guard (200..<300).contains(status) else {
            let machineCode = json["error"] as? String
            throw ApiError(message(for: machineCode, status: status),
                           statusCode: status, code: machineCode)
        }
    }

    private static func post(_ path: String, _ body: [String: String]) async throws -> String {
        guard let url = URL(string: base + path) else { throw ApiError(L("error.badAddress", path)) }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        var requestBody = body
        requestBody["device_id"] = await deviceId()
        req.httpBody = try JSONSerialization.data(withJSONObject: requestBody)
        req.timeoutInterval = 20

        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]

        guard (200..<300).contains(code) else {
            // 服务端的 error 是机器可读的短码，翻成人话再给用户看
            let machineCode = json["error"] as? String
            throw ApiError(message(for: machineCode, status: code),
                           statusCode: code, code: machineCode)
        }
        guard let token = json["license"] as? String, !token.isEmpty else {
            throw ApiError(L("license.err.noLicense"))
        }
        return token
    }

    private static func message(for code: String?, status: Int) -> String {
        switch code {
        case "not_found": return L("license.err.notFound")
        case "revoked": return L("license.err.revoked")
        case "too_many_activations": return L("license.err.tooMany")
        case "invalid_code": return L("license.err.invalidCode")
        case "invalid_license", "device_mismatch", "device_deactivated", "missing_device_id":
            return L("license.err.device")
        default: return "HTTP \(status)"
        }
    }

    private static func deviceId() async -> String {
        await MainActor.run { Store.shared.deviceId }
    }
}
