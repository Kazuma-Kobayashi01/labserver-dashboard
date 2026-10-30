import Foundation

/// Why a call to the server did not work.
enum ServerError: Error, Equatable {
    case noKey, badURL, offline(String), unauthorized, notFound, limit(String), http(Int, String)

    var message: String {
        switch self {
        case .noKey: L.t("err.noKey")
        case .badURL: L.t("err.badURL")
        case .offline(let d): L.t("err.offline") + (d.isEmpty ? "" : "\n\(d)")
        case .unauthorized: L.t("err.auth")
        case .notFound: L.t("err.notFound")
        case .limit(let m): m
        case .http(let c, let m): "HTTP \(c) \(m)"
        }
    }

    /// The name of the bot-manager's host could not be looked up: the one line missing from /etc/hosts.
    var isNameMissing: Bool {
        if case .offline(let d) = self { return d.contains("hostname could not be found") || d.contains("找不到") }
        return false
    }
}

/// Bot lists, the server's lists of bots and models, uploads, approvals, pictures: everything the bot-manager offers.
/// Talks over the Mac's own network (the LabLink tunnel or the home LAN), with the manager key.
struct Manager {
    var base: String
    var key: String

    @MainActor static var current: Manager { Manager(base: AppSettings.shared.baseURL, key: AppSettings.shared.managerKey) }

    private static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 120   // a streamed reply sends a keepalive every 10 s
        c.timeoutIntervalForResource = 3600
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    func request(_ method: String, _ path: String, body: Data? = nil, contentType: String = "application/json") throws -> URLRequest {
        guard !key.isEmpty else { throw ServerError.noKey }
        guard let url = URL(string: base + path), url.scheme?.hasPrefix("http") == true else { throw ServerError.badURL }
        var r = URLRequest(url: url)
        r.httpMethod = method
        r.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        if let body { r.httpBody = body; r.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        return r
    }

    private func send(_ r: URLRequest) async throws -> Data {
        let data: Data, resp: URLResponse
        do { (data, resp) = try await Manager.session.data(for: r) } catch { throw ServerError.offline(error.localizedDescription) }
        try Manager.check(resp, data)
        return data
    }

    static func check(_ resp: URLResponse, _ data: Data) throws {
        guard let h = resp as? HTTPURLResponse else { throw ServerError.offline("") }
        guard !(200..<300).contains(h.statusCode) else { return }
        let msg = ((try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? [String: Any])?["message"] as? String ?? ""
        switch h.statusCode {
        case 401, 403: throw ServerError.unauthorized
        case 404: throw ServerError.notFound
        case 409: throw ServerError.limit(msg)
        default: throw ServerError.http(h.statusCode, msg)
        }
    }

    private func json(_ data: Data) -> [String: Any] { (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:] }

    // MARK: bots

    struct Listing { var bots: [Bot]; var limit: Int; var running: Set<String> }

    func list() async throws -> Listing {
        let o = json(try await send(request("GET", "/bots")))
        let raw = o["bots"] as? [[String: Any]] ?? []
        return Listing(bots: raw.map(Manager.bot), limit: o["limit"] as? Int ?? 5,
                       running: Set(raw.filter { $0["sandbox"] as? String == "running" }.compactMap { $0["id"] as? String }))
    }

    static func bot(_ o: [String: Any]) -> Bot {
        Bot(id: o["id"] as? String ?? "", name: o["name"] as? String ?? "", role: o["role"] as? String ?? "",
            systemPrompt: o["systemPrompt"] as? String ?? "", color: UInt32(truncatingIfNeeded: (o["color"] as? Int) ?? 0xFF4B3FD8),
            modelProvider: o["modelProvider"] as? String ?? "", modelId: o["modelId"] as? String ?? "",
            reasoning: o["reasoning"] as? String ?? "", sandboxed: o["sandboxed"] as? Bool ?? true)
    }

    private func spec(_ b: Bot) -> Data {
        let o: [String: Any] = ["name": b.name, "role": b.role, "systemPrompt": b.systemPrompt, "color": Int(b.color),
                                "modelProvider": b.modelProvider, "modelId": b.modelId, "reasoning": b.reasoning, "sandboxed": b.sandboxed]
        return try! JSONSerialization.data(withJSONObject: o)
    }

    func create(_ b: Bot) async throws -> Bot { Manager.bot(json(try await send(request("POST", "/bots", body: spec(b))))) }
    func update(_ b: Bot) async throws -> Bot { Manager.bot(json(try await send(request("PATCH", "/bots/\(b.id)", body: spec(b))))) }
    func trash(_ id: String) async throws { _ = try await send(request("DELETE", "/bots/\(id)")) }

    func models() async throws -> Catalog {
        try JSONDecoder().decode(Catalog.self, from: try await send(request("GET", "/models")))
    }

    // MARK: files, approvals, pictures

    /// Puts a file where the bot can read it; returns the path it sees it at.
    func upload(profile: String, name: String, data: Data) async throws -> String {
        var r = try request("POST", "/bots/\(profile)/files", body: data, contentType: "application/octet-stream")
        r.setValue(name.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "file", forHTTPHeaderField: "X-Filename")
        return json(try await send(r))["path"] as? String ?? ""
    }

    func answer(_ req: ApprovalRequest, choice: String) async throws {
        var o: [String: Any] = ["choice": choice]
        if !req.requestId.isEmpty { o["request_id"] = req.requestId }
        _ = try await send(request("POST", "/bots/\(req.botProfile)/runs/\(req.runId)/approval", body: try JSONSerialization.data(withJSONObject: o)))
    }

    func media(profile: String, path: String) async throws -> Data {
        var c = URLComponents()
        c.queryItems = [URLQueryItem(name: "bot", value: profile), URLQueryItem(name: "path", value: path)]
        return try await send(request("GET", "/media" + (c.percentEncodedQuery.map { "?" + $0 } ?? "")))
    }

    // MARK: chats shared with the other apps

    struct RemoteChat { var id: String; var updated: Int64; var deleted: Bool }

    func chatList(profile: String) async throws -> [RemoteChat] {
        let o = json(try await send(request("GET", "/chats/\(profile)")))
        return (o["conversations"] as? [[String: Any]] ?? []).compactMap { c in
            guard let id = c["id"] as? String else { return nil }
            return RemoteChat(id: id, updated: (c["updated"] as? NSNumber)?.int64Value ?? 0, deleted: c["deleted"] as? Bool ?? false)
        }
    }

    func chatGet(profile: String, id: String) async throws -> Data { try await send(request("GET", "/chats/\(profile)/\(id)")) }

    func chatPut(profile: String, id: String, body: Data) async throws { _ = try await send(request("PUT", "/chats/\(profile)/\(id)", body: body)) }

    func chatDelete(profile: String, id: String) async throws { _ = try await send(request("DELETE", "/chats/\(profile)/\(id)")) }

    // MARK: chat

    enum Event {
        case content(String), reasoning(String), working
        case toolStart(id: String, tool: String, label: String), toolEnd(id: String)
        case toolDetail(id: String, command: String, done: Bool, output: String?, exitCode: Int?, error: String?, durationMs: Int?)
        case approval(ApprovalRequest)
    }

    /// Streams a reply from [profile]. Returns normally when the server ended the stream with [DONE]; throws when it
    /// failed or the connection dropped first.
    func stream(profile: String, messages: [[String: Any]], pick: ModelPick, markdown: Bool, onEvent: @escaping @MainActor (Event) -> Void) async throws {
        var body: [String: Any] = ["model": "lab-bot", "stream": true, "messages": messages]
        if !pick.isDefault {
            body["modelProvider"] = pick.provider
            body["modelId"] = pick.model
            if !pick.reasoning.isEmpty { body["reasoning"] = pick.reasoning }
            if pick.fast { body["fast"] = true }
        }
        if markdown { body["markdown"] = true }
        var r = try request("POST", "/bots/\(profile)/chat/completions", body: try JSONSerialization.data(withJSONObject: body))
        r.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        let bytes: URLSession.AsyncBytes, resp: URLResponse
        do { (bytes, resp) = try await Manager.session.bytes(for: r) } catch { throw ServerError.offline(error.localizedDescription) }
        if let h = resp as? HTTPURLResponse, !(200..<300).contains(h.statusCode) {
            var data = Data()
            for try await b in bytes.prefix(4000) { data.append(b) }
            try Manager.check(resp, data)
        }
        var event = ""
        do {
            // Hermes sends every event as one data line, so each is handled as soon as it arrives
            for try await line in bytes.lines {
                if line.hasPrefix("event:") { event = line.dropFirst(6).trimmingCharacters(in: .whitespaces); continue }
                guard line.hasPrefix("data:") else { continue }
                let data = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
                defer { event = "" }
                if data == "[DONE]" { return }
                for ev in Manager.parse(event: event, data: data) { await onEvent(ev) }
            }
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw ServerError.offline(error.localizedDescription)
        }
        throw ServerError.offline(L.t("err.streamEnded"))
    }

    static func parse(event: String, data: String) -> [Event] {
        let o = (try? JSONSerialization.jsonObject(with: Data(data.utf8))) as? [String: Any] ?? [:]
        switch event {
        case "hermes.tool.progress":
            var out: [Event] = [.working]
            if let id = o["toolCallId"] as? String, !id.isEmpty {
                switch o["status"] as? String {
                case "running": out.append(.toolStart(id: id, tool: o["tool"] as? String ?? "", label: o["label"] as? String ?? ""))
                case "completed": out.append(.toolEnd(id: id))
                default: break
                }
            }
            return out
        case "lablink.tool":
            guard let id = o["toolCallId"] as? String, !id.isEmpty else { return [] }
            return [.toolDetail(id: id, command: o["command"] as? String ?? "", done: o["status"] as? String == "completed",
                                output: o["output"] as? String, exitCode: o["exitCode"] as? Int, error: o["error"] as? String,
                                durationMs: o["durationMs"] as? Int)]
        case "approval.request":
            return ApprovalRequest.parse(data).map { [.approval($0)] } ?? []
        default:
            guard let delta = ((o["choices"] as? [[String: Any]])?.first)?["delta"] as? [String: Any] else { return [] }
            var out: [Event] = []
            if let r = delta["reasoning_content"] as? String, !r.isEmpty { out.append(.reasoning(r)) }
            if let c = delta["content"] as? String, !c.isEmpty { out.append(.content(c)) }
            return out
        }
    }
}
