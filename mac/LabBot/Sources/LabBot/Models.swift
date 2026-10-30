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
    var isDefault: Bool { provider.isEmpty || model.isEmpty }
}

/// What the server offers: providers, their models and the thinking levels each takes.
struct Catalog: Codable, Hashable {
    struct Model: Codable, Hashable, Identifiable { var id: String; var name: String; var efforts: [String] = [] }
    struct Provider: Codable, Hashable, Identifiable { var id: String; var name: String; var models: [Model] }
    var providers: [Provider] = []

    func label(_ p: ModelPick) -> String {
        guard !p.isDefault else { return L.t("model.default") }
        let m = providers.first { $0.id == p.provider }?.models.first { $0.id == p.model }
        let name = m?.name ?? p.model
        return p.reasoning.isEmpty ? name : "\(name) · \(L.t("effort.\(p.reasoning)"))"
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
