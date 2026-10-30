import Foundation
import SwiftUI

/// The app's own bot: the server's admin, reached as the `labbot` profile. Every other bot is kept by the bot-manager.
let defaultBotID = "lab"
let defaultProfile = "labbot"

/// A bot to talk to. The default one lives here; the others come from the bot-manager, which also keeps their prompt.
struct Bot: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var role: String = ""
    var systemPrompt: String = ""
    /// ARGB of the orb.
    var color: UInt32 = 0xFF4B3FD8
    var modelProvider: String = ""
    var modelId: String = ""
    var reasoning: String = ""
    /// A server bot made with a sandbox: commands, files and a terminal. Chosen once, when it is made.
    var sandboxed: Bool = true

    var isServerBot: Bool { id.range(of: #"^b[0-9a-f]{10}$"#, options: .regularExpression) != nil }
    var hasSandbox: Bool { isServerBot && sandboxed }
    /// Who gets a terminal: the default bot (the admin) and the bots with a sandbox.
    var hasTerminal: Bool { id == defaultBotID || hasSandbox }
    /// The name the bot-manager knows it by.
    var profile: String { isServerBot ? id : defaultProfile }
    var pick: ModelPick { ModelPick(provider: modelProvider, model: modelId, reasoning: reasoning) }
    /// The bot's colour, or graphite in the classic look.
    var tint: Color { Theme.classic ? Theme.mono : Color(argb: color) }

    /// The admin bot; only its colour can be changed (kept on this Mac).
    static var lab: Bot {
        Bot(id: defaultBotID, name: "Lab bot", role: L.t("bot.defaultRole"),
            color: UInt32(truncatingIfNeeded: UserDefaults.standard.object(forKey: "labColor") as? Int ?? 0xFF4B3FD8), sandboxed: false)
    }
}

enum BotColors {
    static let all: [UInt32] = [0xFF4B3FD8, 0xFF8E5CF6, 0xFFE8589B, 0xFFF2724D, 0xFFF2B63D, 0xFF2FBF8F, 0xFF3B9BE8, 0xFF2B2B3A]
}

/// A model and thinking level picked for a chat or as a bot's default; empty = the server's default.
struct ModelPick: Codable, Hashable {
    var provider: String = ""
    var model: String = ""
    var reasoning: String = ""
    /// The provider's priority tier (Fast), for the models that have one.
    var fast: Bool = false
    var isDefault: Bool { provider.isEmpty || model.isEmpty }

    init(provider: String = "", model: String = "", reasoning: String = "", fast: Bool = false) {
        self.provider = provider; self.model = model; self.reasoning = reasoning; self.fast = fast
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        provider = try c.decodeIfPresent(String.self, forKey: .provider) ?? ""
        model = try c.decodeIfPresent(String.self, forKey: .model) ?? ""
        reasoning = try c.decodeIfPresent(String.self, forKey: .reasoning) ?? ""
        fast = try c.decodeIfPresent(Bool.self, forKey: .fast) ?? false
    }
}

/// What the server offers: providers, their models and the thinking levels each takes.
struct Catalog: Codable, Hashable {
    struct Model: Codable, Hashable, Identifiable {
        var id: String; var name: String; var efforts: [String] = []
        /// Whether it has a fast mode (the provider's priority tier).
        var fast: Bool = false

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decode(String.self, forKey: .id)
            name = try c.decodeIfPresent(String.self, forKey: .name) ?? id
            efforts = try c.decodeIfPresent([String].self, forKey: .efforts) ?? []
            fast = try c.decodeIfPresent(Bool.self, forKey: .fast) ?? false
        }
    }
    struct Provider: Codable, Hashable, Identifiable { var id: String; var name: String; var models: [Model] }
    var providers: [Provider] = []

    func label(_ p: ModelPick) -> String {
        guard !p.isDefault else { return L.t("model.default") }
        let m = providers.first { $0.id == p.provider }?.models.first { $0.id == p.model }
        let name = (p.fast && m?.fast == true ? "⚡ " : "") + (m?.name ?? p.model)
        if p.reasoning.isEmpty { return name }
        // the model's deepest level is "Ultra"
        let top = effortOrder.last { m?.efforts.contains($0) == true }
        return (m?.efforts.count ?? 0) > 1 && p.reasoning == top ? "\(name) Ultra" : "\(name) · \(L.t("effort.\(p.reasoning)"))"
    }
}

/// One command (or other tool) the bot used while answering. [at] is where in the reply's text it happened.
struct ToolRun: Codable, Hashable, Identifiable {
    var id: String
    var tool: String = "terminal"
    var label: String = ""
    var command: String = ""
    var output: String = ""
    var exitCode: Int? = nil
    var error: String? = nil
    var durationMs: Int? = nil
    var done: Bool = false
    var at: Int = 0

    var failed: Bool { done && ((exitCode ?? 0) != 0 || !(error ?? "").isEmpty) }
    var isCommand: Bool { tool == "terminal" || !command.isEmpty }
}

/// A file sent with a message: a picture goes along inline, a text file is pasted in, anything else is uploaded.
struct Attachment: Codable, Hashable, Identifiable {
    enum Kind: String, Codable { case image, text, file }
    var id: String = UUID().uuidString
    var name: String
    var mime: String
    var size: Int
    var kind: Kind
    /// Where the copy is kept on this Mac (inside the app's folder).
    var local: String
    /// Where the bot can find it on the server (uploaded files only).
    var remotePath: String = ""
}

extension Attachment {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? UUID().uuidString
        name = try c.decode(String.self, forKey: .name)
        mime = try c.decodeIfPresent(String.self, forKey: .mime) ?? "application/octet-stream"
        size = try c.decodeIfPresent(Int.self, forKey: .size) ?? 0
        kind = Kind(rawValue: (try c.decodeIfPresent(String.self, forKey: .kind) ?? "file").lowercased()) ?? .file
        local = try c.decodeIfPresent(String.self, forKey: .local) ?? ""
        remotePath = try c.decodeIfPresent(String.self, forKey: .remotePath) ?? ""
    }
}

struct ChatMessage: Codable, Hashable, Identifiable {
    var id: String = UUID().uuidString
    var role: String   // "user" | "assistant"
    var content: String
    var reasoning: String = ""
    var time: Date = Date()
    var attachments: [Attachment] = []
    /// A user message that never reached the bot; shown with a red mark and sent again on a click.
    var failed: Bool = false
    var thinkingMs: Int = 0
    var tools: [ToolRun] = []
}

extension ChatMessage {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? UUID().uuidString
        role = try c.decode(String.self, forKey: .role)
        content = try c.decodeIfPresent(String.self, forKey: .content) ?? ""
        reasoning = try c.decodeIfPresent(String.self, forKey: .reasoning) ?? ""
        time = try c.decodeIfPresent(Date.self, forKey: .time) ?? Date()
        attachments = try c.decodeIfPresent([Attachment].self, forKey: .attachments) ?? []
        failed = try c.decodeIfPresent(Bool.self, forKey: .failed) ?? false
        thinkingMs = try c.decodeIfPresent(Int.self, forKey: .thinkingMs) ?? 0
        tools = try c.decodeIfPresent([ToolRun].self, forKey: .tools) ?? []
    }
}

extension ToolRun {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        tool = try c.decodeIfPresent(String.self, forKey: .tool) ?? "terminal"
        label = try c.decodeIfPresent(String.self, forKey: .label) ?? ""
        command = try c.decodeIfPresent(String.self, forKey: .command) ?? ""
        output = try c.decodeIfPresent(String.self, forKey: .output) ?? ""
        exitCode = try c.decodeIfPresent(Int.self, forKey: .exitCode)
        error = try c.decodeIfPresent(String.self, forKey: .error)
        durationMs = try c.decodeIfPresent(Int.self, forKey: .durationMs)
        done = try c.decodeIfPresent(Bool.self, forKey: .done) ?? false
        at = try c.decodeIfPresent(Int.self, forKey: .at) ?? 0
    }
}

/// A chat as the apps share it through the bot-manager: times in milliseconds, attachments by name and kind only (the
/// file stays on the device that sent it).
enum ChatWire {
    private static let enc: JSONEncoder = { let e = JSONEncoder(); e.dateEncodingStrategy = .millisecondsSince1970; return e }()
    private static let dec: JSONDecoder = { let d = JSONDecoder(); d.dateDecodingStrategy = .millisecondsSince1970; return d }()

    static func encode(_ c: Conversation, _ messages: [ChatMessage]) -> Data? {
        guard let data = try? enc.encode(messages), var list = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return nil }
        for i in list.indices {
            // whole milliseconds, as the phone reads them
            if let t = list[i]["time"] as? Double { list[i]["time"] = Int64(t.rounded()) }
            if var atts = list[i]["attachments"] as? [[String: Any]] {
                for j in atts.indices { atts[j]["local"] = nil }
                list[i]["attachments"] = atts
            }
        }
        let conv: [String: Any] = ["id": c.id, "title": c.title, "created": ms(c.created), "updated": ms(c.updated), "pinned": c.pinned, "renamed": c.renamed]
        return try? JSONSerialization.data(withJSONObject: ["conversation": conv, "messages": list])
    }

    static func decode(_ data: Data) -> (Conversation, [ChatMessage])? {
        guard let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any], let c = o["conversation"] as? [String: Any],
              let conv = conversation(c), let raw = try? JSONSerialization.data(withJSONObject: o["messages"] ?? []),
              let messages = try? dec.decode([ChatMessage].self, from: raw) else { return nil }
        return (conv, messages)
    }

    static func conversation(_ c: [String: Any]) -> Conversation? {
        guard let id = c["id"] as? String else { return nil }
        func date(_ k: String) -> Date { Date(timeIntervalSince1970: Double((c[k] as? NSNumber)?.int64Value ?? 0) / 1000) }
        return Conversation(id: id, title: c["title"] as? String ?? "", created: date("created"), updated: date("updated"),
                            pinned: c["pinned"] as? Bool ?? false, renamed: c["renamed"] as? Bool ?? false)
    }

    static func ms(_ d: Date) -> Int64 { Int64((d.timeIntervalSince1970 * 1000).rounded()) }
}

struct Conversation: Codable, Hashable, Identifiable {
    var id: String = UUID().uuidString
    var title: String = ""
    var created: Date = Date()
    var updated: Date = Date()
    var pinned: Bool = false
    var renamed: Bool = false
}

/// A guarded action waiting for the user (download, install, delete, system change...).
struct ApprovalRequest: Hashable, Identifiable {
    var id: String { runId + requestId }
    var runId: String
    var requestId: String
    var kind: String
    var command: String
    var choices: [String]
    var botProfile: String = ""
    var at: Date = Date()
    var timeout: TimeInterval = 300

    /// Reads Hermes' `approval.request` event.
    static func parse(_ data: String) -> ApprovalRequest? {
        guard let o = try? JSONSerialization.jsonObject(with: Data(data.utf8)) as? [String: Any],
              let run = o["run_id"] as? String, !run.isEmpty else { return nil }
        let pattern = o["pattern_key"] as? String ?? ""
        let kind = pattern.contains("lablink-") ? String(pattern[pattern.range(of: "lablink-")!.upperBound...]) : "danger"
        let desc = o["description"] as? String ?? ""
        let command = pattern.hasPrefix("plugin_rule:") ? desc : ((o["command"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? desc)
        return ApprovalRequest(runId: run, requestId: o["request_id"] as? String ?? "", kind: kind, command: command,
                               choices: o["choices"] as? [String] ?? ["once", "deny"])
    }
}

/// How much of a bot's work replies show, from everything to just the answer.
enum ChatDetail: String, CaseIterable, Codable, Identifiable {
    case full, standard, compact, minimal
    var id: String { rawValue }
    var thoughtsOpen: Bool { self == .full }
    var showThoughts: Bool { self != .minimal }
    var toolOpenWhileRunning: Bool { self == .full || self == .standard }
    var toolStaysOpen: Bool { self == .full }
    var groupTools: Bool { self == .minimal }
    var codeFoldLines: Int { switch self { case .full, .standard: .max; case .compact: 14; case .minimal: 6 } }
}

/// Where a chat falls in the history list.
enum Period: Int, CaseIterable {
    case pinned, today, yesterday, week, month, older

    static func of(_ c: Conversation, now: Date = Date()) -> Period {
        if c.pinned { return .pinned }
        let cal = Calendar.current
        if cal.isDateInToday(c.updated) { return .today }
        if cal.isDateInYesterday(c.updated) { return .yesterday }
        let days = cal.dateComponents([.day], from: c.updated, to: now).day ?? 0
        if days < 7 { return .week }
        if days < 30 { return .month }
        return .older
    }

    var label: String { L.t("period.\(self)") }
}

/// A short title from the first thing said.
func titleFrom(_ messages: [ChatMessage]) -> String {
    guard let first = messages.first(where: { $0.role == "user" }) else { return "" }
    let line = first.content.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "#>-* ")) }
        .first { !$0.isEmpty } ?? first.attachments.first?.name ?? ""
    let squeezed = line.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
    return squeezed.count <= 40 ? squeezed : String(squeezed.prefix(39)).trimmingCharacters(in: .whitespaces) + "…"
}

extension Color {
    init(argb: UInt32) {
        self.init(.sRGB, red: Double((argb >> 16) & 0xFF) / 255, green: Double((argb >> 8) & 0xFF) / 255,
                  blue: Double(argb & 0xFF) / 255, opacity: Double((argb >> 24) & 0xFF) / 255)
    }
}
