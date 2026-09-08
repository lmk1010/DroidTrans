/// 桌面端 HTTP 接口客户端。
///
/// 路由表见 desktop/internal/app/app.go 的 routes()。

import Foundation

struct ApiError: Error, LocalizedError {
    let message: String
    let statusCode: Int?
    let code: String?

    init(_ message: String, statusCode: Int? = nil, code: String? = nil) {
        self.message = message
        self.statusCode = statusCode
        self.code = code
    }

    /// 配对被拒：令牌无效，或者已经在电脑上被撤销了
    var needsPairing: Bool { statusCode == 403 }

    var errorDescription: String? { message }
}

actor ApiClient {
    let baseURL: String
    private var token: String
    private let session: URLSession

    init(baseURL: String, token: String = "") {
        self.baseURL = baseURL
        self.token = token
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 8
        // 局域网直连，没有中间缓存的余地，缓存只会带来「删了还在」这种怪现象
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        cfg.waitsForConnectivity = false
        self.session = URLSession(configuration: cfg)
    }

    func setToken(_ t: String) { token = t }
    func currentToken() -> String { token }

    // MARK: - 底层

    private func request(_ path: String, method: String, body: Any? = nil,
                         query: [String: String] = [:],
                         timeout: TimeInterval? = nil) throws -> URLRequest {
        guard var comps = URLComponents(string: baseURL + path) else {
            throw ApiError(L("error.badAddress", path))
        }
        if !query.isEmpty {
            comps.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        guard let url = comps.url else { throw ApiError(L("error.badAddress", path)) }

        var req = URLRequest(url: url)
        req.httpMethod = method
        if !token.isEmpty { req.setValue(token, forHTTPHeaderField: kTokenHeader) }
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        // 默认 8 秒是给「问一句就该有答案」的接口定的。要等人动手指的那种
        // （敲门），必须自己把这个数字放宽，见 pair()。
        if let timeout { req.timeoutInterval = timeout }
        return req
    }

    private func send(_ req: URLRequest) async throws -> [String: Any] {
        let data: Data
        let resp: URLResponse
        do {
            (data, resp) = try await session.data(for: req)
        } catch {
            throw ApiError(describe(error))
        }
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]

        if (200..<300).contains(code) {
            // 有些接口 HTTP 200 但 success:false，真正的原因在 body 里
            if json["success"] as? Bool == false {
                throw ApiError(json["error"] as? String ?? "HTTP \(code)",
                               statusCode: code)
            }
            return json
        }
        throw ApiError(json["error"] as? String ?? "HTTP \(code)", statusCode: code)
    }

    private nonisolated func describe(_ error: Error) -> String {
        let e = error as NSError
        guard e.domain == NSURLErrorDomain else { return e.localizedDescription }
        switch e.code {
        case NSURLErrorTimedOut:
            return L("error.timeout")
        case NSURLErrorCannotConnectToHost, NSURLErrorNetworkConnectionLost,
             NSURLErrorNotConnectedToInternet, NSURLErrorCannotFindHost:
            return L("error.unreachable")
        case NSURLErrorCancelled:
            return L("error.canceled")
        default:
            return e.localizedDescription
        }
    }

    // MARK: - 发现与握手（这几个不需要配对）

    func health() async -> Bool {
        do {
            let j = try await send(try request("/api/health", method: "GET"))
            return j["ok"] as? Bool == true && j["app"] as? String == "droidtrans"
        } catch {
            return false
        }
    }

    /// 电脑的全量信息：名字、要不要配对、快传能力、待取件数量。
    func info(deviceId: String? = nil, deviceName: String? = nil) async throws -> Desktop {
        var q: [String: String] = [:]
        if let deviceId { q["device_id"] = deviceId }
        if let deviceName { q["device_name"] = deviceName }
        let j = try await send(try request("/api/wifi/info", method: "GET", query: q))
        let host = plainHost(URL(string: baseURL)?.host ?? baseURL)
        return Desktop.fromWifiInfo(host: host, json: j)
    }

    /// 拿配对码换长期令牌。换到之后要自己存起来并调 setToken。
    ///
    /// 码留空 = 敲门：对面会把这条请求挂起来，弹框问它的主人同不同意。
    /// 那要等一个人看到通知、拿起手机、点一下，**8 秒根本不够** ——
    /// 用默认超时的话，对面点了同意这边早就报「连不上」了，
    /// 而弹窗还开着，用户看到的是「明明点了同意，还是连不上」。
    /// 对面最多挂 45 秒（两端 PeerServer 都是这个数），这里给到 60 秒兜住它。
    func pair(code: String, deviceId: String, deviceName: String) async throws -> String {
        let knocking = code.trimmingCharacters(in: .whitespaces).isEmpty
        let j = try await send(try request("/api/pair", method: "POST", body: [
            "code": code,
            "device_id": deviceId,
            "device_name": deviceName,
        ], timeout: knocking ? 60 : nil))
        guard let t = j["token"] as? String, !t.isEmpty else {
            throw ApiError(L("error.noToken"))
        }
        return t
    }

    // MARK: - 手机 → 电脑

    /// 发一段文字/链接，落到电脑剪贴板。
    func sendText(_ text: String) async throws {
        _ = try await send(try request("/api/inbox/text", method: "POST", body: ["text": text]))
    }

    // MARK: - 电脑 → 手机

    /// 电脑上等着被取走的清单。
    func outbox() async throws -> [OutboxItem] {
        let j = try await send(try request("/api/outbox", method: "GET"))
        let items = j["items"] as? [Any] ?? []
        return items.compactMap { ($0 as? [String: Any]).map(OutboxItem.fromJSON) }
    }

    /// 从清单里删掉一条。
    ///
    /// id 必须放在路径段上：不带 id 的 /api/outbox/remove 是「清空整个清单」，
    /// 写成 ?id=xxx 会静默走到清空那条路由上去，把用户还没取的东西全抹掉。
    func removeOutbox(id: String) async throws {
        let encoded = id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id
        _ = try await send(try request("/api/outbox/remove/\(encoded)", method: "POST", body: [:]))
    }

    /// 一键清掉所有已被取走的条目。
    func clearTaken() async throws {
        _ = try await send(try request("/api/outbox/clear_taken", method: "POST", body: [:]))
    }
}
