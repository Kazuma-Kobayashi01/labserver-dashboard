import Foundation
import SwiftUI

/// App settings. The manager key is kept in a file only this user can read (no Keychain prompts on every new build);
/// the rest in UserDefaults.
@MainActor
@Observable
final class AppSettings {
    static let shared = AppSettings()
    private let d = UserDefaults.standard

    /// The bot-manager's address; every bot is reached through it.
    var managerURL: String { didSet { d.set(managerURL, forKey: "managerURL") } }
    var detail: ChatDetail { didSet { d.set(detail.rawValue, forKey: "detail") } }
    var markdown: Bool { didSet { d.set(markdown, forKey: "markdown") } }
    var currentBotID: String { didSet { d.set(currentBotID, forKey: "currentBot") } }
    /// The look: "modern" (each bot's colour lights the window) or "classic" (plain black and white, no coloured light).
    var theme: String { didSet { d.set(theme, forKey: "theme"); Theme.classic = theme == "classic" } }
    /// "system", "light" or "dark".
    var appearance: String { didSet { d.set(appearance, forKey: "appearance") } }
    var colorScheme: ColorScheme? { appearance == "light" ? .light : appearance == "dark" ? .dark : nil }
    var managerKey: String { didSet { SecretFile.write(managerKey) } }

    static let defaultManagerURL = "http://bot.labserver.internal/m/v1"

    private init() {
        managerURL = d.string(forKey: "managerURL") ?? AppSettings.defaultManagerURL
        detail = ChatDetail(rawValue: d.string(forKey: "detail") ?? "") ?? .standard
        markdown = d.object(forKey: "markdown") as? Bool ?? true
        currentBotID = d.string(forKey: "currentBot") ?? defaultBotID
        appearance = d.string(forKey: "appearance") ?? "system"
        managerKey = SecretFile.read()
        let t = d.string(forKey: "theme") ?? "modern"
        theme = t
        Theme.classic = t == "classic"
    }

    var baseURL: String { managerURL.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/")) }

    func pick(_ botID: String) -> ModelPick {
        guard let data = d.data(forKey: "pick.\(botID)") else { return ModelPick() }
        return (try? JSONDecoder().decode(ModelPick.self, from: data)) ?? ModelPick()
    }

    func setPick(_ botID: String, _ p: ModelPick) {
        if p.isDefault { d.removeObject(forKey: "pick.\(botID)") } else { d.set(try? JSONEncoder().encode(p), forKey: "pick.\(botID)") }
    }

    func lastConversation(_ botID: String) -> String { d.string(forKey: "conv.\(botID)") ?? "" }
    func setLastConversation(_ botID: String, _ id: String) { d.set(id, forKey: "conv.\(botID)") }
}

/// The manager key in `~/Library/Application Support/Lab bot/manager.key`, readable by this user only (mode 600).
enum SecretFile {
    private static var url: URL { Disk.root.appendingPathComponent("manager.key") }

    static func read() -> String {
        (try? String(contentsOf: url, encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    static func write(_ value: String) {
        let v = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !v.isEmpty else { try? FileManager.default.removeItem(at: url); return }
        FileManager.default.createFile(atPath: url.path, contents: Data(v.utf8), attributes: [.posixPermissions: 0o600])
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }
}

/// Chats on disk: `~/Library/Application Support/Lab bot/<bot>/index.json` lists them, `<conversation>.json` holds each one's
/// messages; attachments are copied into `files/`; the last bot list is cached so the sidebar shows something offline.
enum Disk {
    static let root: URL = {
        let u = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Lab bot", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }()
    static var files: URL { dir("files") }

    static func dir(_ name: String) -> URL {
        let u = root.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    private static let enc: JSONEncoder = { let e = JSONEncoder(); e.dateEncodingStrategy = .millisecondsSince1970; return e }()
    private static let dec: JSONDecoder = { let d = JSONDecoder(); d.dateDecodingStrategy = .millisecondsSince1970; return d }()

    static func load<T: Decodable>(_ type: T.Type, _ url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? dec.decode(type, from: data)
    }

    static func save<T: Encodable>(_ value: T, _ url: URL) {
        guard let data = try? enc.encode(value) else { return }
        try? data.write(to: url, options: .atomic)
    }

    static func conversations(_ botID: String) -> [Conversation] {
        load([Conversation].self, dir(botID).appendingPathComponent("index.json")) ?? []
    }

    static func saveConversations(_ botID: String, _ list: [Conversation]) {
        save(list, dir(botID).appendingPathComponent("index.json"))
    }

    static func messages(_ botID: String, _ convID: String) -> [ChatMessage] {
        load([ChatMessage].self, dir(botID).appendingPathComponent("\(convID).json")) ?? []
    }

    static func saveMessages(_ botID: String, _ convID: String, _ list: [ChatMessage]) {
        save(list, dir(botID).appendingPathComponent("\(convID).json"))
    }

    static func deleteConversation(_ botID: String, _ convID: String) {
        try? FileManager.default.removeItem(at: dir(botID).appendingPathComponent("\(convID).json"))
    }
}

/// The look the views paint with. Mirrors the setting (views are rebuilt when it changes, see RootView).
enum Theme {
    nonisolated(unsafe) static var classic = false
    /// The graphite every bot wears in the classic look.
    static let mono = Color(.sRGB, red: 0.30, green: 0.30, blue: 0.33)
}
