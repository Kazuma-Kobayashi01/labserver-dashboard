import AppKit
import SwiftUI

/// Cards floating over the right of the chat (as in Claude's app): how much of the model's context the chat fills, what
/// the bot made and what was sent to it, and where this chat runs (bot, model, server, sandbox).
struct InspectorView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                ContextCard(stats: ContextStats(app.messages))
                OutputsCard()
                EnvironmentCard()
            }
            .padding(.vertical, 10).padding(.horizontal, 2)
        }
        .scrollIndicators(.never)
        .scrollBounceBehavior(.basedOnSize)
    }
}

/// A rounded group with a small heading, like the panels on the right of Claude's window.
private struct Card<Content: View>: View {
    var title: String
    var trailing: String? = nil
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(title).font(.callout.weight(.semibold))
                Spacer()
                if let trailing { Text(trailing).font(.caption).foregroundStyle(.secondary).monospacedDigit() }
            }
            content
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
            if Theme.classic { shape.fill(Color(nsColor: .controlBackgroundColor)) } else { shape.fill(.regularMaterial) }
        }
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Color.primary.opacity(0.08)))
        .shadow(color: .black.opacity(0.14), radius: 14, y: 6)
    }
}

// MARK: - context

/// A rough count of the tokens the open chat sends (the last 24 messages, as the request does): CJK characters count
/// about one each, other text about four characters to a token, a picture about a thousand.
struct ContextStats {
    struct Part: Identifiable { var id: String { key }; var key: String; var tokens: Int; var color: Color }
    var parts: [Part]
    var total: Int { parts.reduce(0) { $0 + $1.tokens } }
    static let window = 128_000

    init(_ messages: [ChatMessage]) {
        let sent = messages.suffix(24)
        var user = 0, reply = 0, thinking = 0, tools = 0, files = 0
        for m in sent {
            if m.role == "user" { user += Self.tokens(m.content) } else { reply += Self.tokens(m.content) }
            thinking += Self.tokens(m.reasoning)
            for t in m.tools { tools += Self.tokens(t.command) + Self.tokens(t.output) }
            for a in m.attachments {
                switch a.kind {
                case .image: files += 1000
                case .text: files += a.size / 3
                case .file: files += 30
                }
            }
        }
        parts = [
            Part(key: "user", tokens: user, color: .blue), Part(key: "reply", tokens: reply, color: .purple),
            Part(key: "thinking", tokens: thinking, color: .orange), Part(key: "tools", tokens: tools, color: .teal),
            Part(key: "files", tokens: files, color: .pink),
        ]
    }

    static func tokens(_ s: String) -> Int {
        var cjk = 0, other = 0
        for u in s.unicodeScalars { if u.value >= 0x2E80 { cjk += 1 } else { other += 1 } }
        return cjk + (other + 3) / 4
    }

    static func short(_ n: Int) -> String { n >= 1000 ? String(format: "%.1fk", Double(n) / 1000) : "\(n)" }
}

private struct ContextCard: View {
    var stats: ContextStats

    var body: some View {
        let used = Double(stats.total) / Double(ContextStats.window)
        Card(title: L.t("inspector.context"), trailing: "≈ \(ContextStats.short(stats.total)) / \(ContextStats.short(ContextStats.window))") {
            HStack {
                Text(L.f("inspector.used", max(used * 100, stats.total > 0 ? 0.1 : 0))).font(.callout.weight(.medium)).monospacedDigit()
                Spacer()
            }
            // one bar, split by what fills it
            GeometryReader { g in
                HStack(spacing: 1) {
                    ForEach(stats.parts.filter { $0.tokens > 0 }) { p in
                        Rectangle().fill(Theme.classic ? Color.primary.opacity(0.6) : p.color)
                            .frame(width: max(2, g.size.width * CGFloat(p.tokens) / CGFloat(ContextStats.window)))
                    }
                    Spacer(minLength: 0)
                }
                .background(Color.primary.opacity(0.08))
                .clipShape(Capsule())
            }
            .frame(height: 6)
            VStack(spacing: 6) {
                ForEach(stats.parts) { p in
                    HStack(spacing: 8) {
                        RoundedRectangle(cornerRadius: 2).fill(Theme.classic ? Color.primary.opacity(0.6) : p.color).frame(width: 8, height: 8)
                        Text(L.t("inspector.\(p.key)")).font(.callout)
                        Spacer()
                        Text(ContextStats.short(p.tokens)).font(.callout).foregroundStyle(.secondary).monospacedDigit()
                        Text(stats.total == 0 ? "–" : String(format: "%.0f%%", Double(p.tokens) / Double(max(stats.total, 1)) * 100))
                            .font(.callout).foregroundStyle(.tertiary).monospacedDigit().frame(width: 38, alignment: .trailing)
                    }
                }
            }
            Text(L.t("inspector.estimate")).font(.caption).foregroundStyle(.tertiary)
        }
    }
}

// MARK: - outputs

/// What the chat produced and carried: pictures the bot made (a click opens them in Preview), files sent to it, and the
/// commands it ran.
private struct OutputsCard: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        let pictures = app.messages.filter { $0.role == "assistant" }.flatMap { m in
            ReplyParts.split(m.content).compactMap { p -> String? in if case .image(let src, _) = p { return src }; return nil }
        }
        let sent = app.messages.flatMap(\.attachments)
        let commands = app.messages.flatMap(\.tools).filter(\.isCommand)
        Card(title: L.t("inspector.outputs"), trailing: "\(pictures.count + sent.count)") {
            if pictures.isEmpty && sent.isEmpty && commands.isEmpty {
                Text(L.t("inspector.noOutputs")).font(.callout).foregroundStyle(.secondary)
            }
            ForEach(Array(pictures.enumerated()), id: \.offset) { _, src in
                OutputRow(symbol: "photo", name: (src as NSString).lastPathComponent) { open(picture: src) }
            }
            ForEach(sent) { a in
                OutputRow(symbol: a.kind == .image ? "photo.on.rectangle" : "doc", name: a.name) { NSWorkspace.shared.open(URL(fileURLWithPath: a.local)) }
            }
            if !commands.isEmpty {
                HStack(spacing: 8) {
                    Image(systemName: "terminal").foregroundStyle(.secondary).frame(width: 16)
                    Text(L.f("inspector.commands", commands.count)).font(.callout)
                    Spacer()
                    let failed = commands.filter(\.failed).count
                    if failed > 0 { Text(L.f("inspector.failed", failed)).font(.caption).foregroundStyle(.red) }
                }
            }
        }
    }

    private func open(picture src: String) {
        Task {
            guard src.hasPrefix("media:") else { if let u = URL(string: src) { NSWorkspace.shared.open(u) }; return }
            let path = String(src.dropFirst(6))
            guard let data = try? await Manager.current.media(profile: app.bot.profile, path: path) else { return }
            let url = Disk.dir("tmp").appendingPathComponent((path as NSString).lastPathComponent)
            try? data.write(to: url)
            NSWorkspace.shared.open(url)
        }
    }
}

private struct OutputRow: View {
    var symbol: String
    var name: String
    var action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: symbol).foregroundStyle(.secondary).frame(width: 16)
                Text(name).font(.callout).lineLimit(1).truncationMode(.middle)
                Spacer()
                Image(systemName: "arrow.up.right").font(.caption2).foregroundStyle(.tertiary).opacity(hover ? 1 : 0)
            }
            .padding(.vertical, 3).contentShape(Rectangle())
        }
        .buttonStyle(.plain).onHover { hover = $0 }
    }
}

// MARK: - environment

/// Where this chat runs: the bot and what kind it is, the model, the server and whether it answers, the sandbox.
private struct EnvironmentCard: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        let b = app.bot
        let kind = b.id == defaultBotID ? L.t("inspector.kindAdmin") : b.hasSandbox ? L.t("inspector.kindSandbox") : L.t("inspector.kindChat")
        Card(title: L.t("inspector.environment")) {
            HStack(spacing: 10) {
                Orb(color: b.tint, size: 30)
                VStack(alignment: .leading, spacing: 1) {
                    Text(b.name).font(.system(.callout, design: .rounded, weight: .bold))
                    Text(kind).font(.caption).foregroundStyle(.secondary)
                }
            }
            Divider()
            Row(L.t("inspector.model"), app.catalog.label(app.effectivePick))
            Row(L.t("inspector.server"), URL(string: app.settings.baseURL)?.host ?? app.settings.baseURL)
            HStack {
                Text(L.t("inspector.connection")).font(.callout).foregroundStyle(.secondary)
                Spacer()
                Circle().fill(app.serverError == nil ? Color.green : Color.orange).frame(width: 7, height: 7)
                Text(app.serverError == nil ? L.t("inspector.online") : L.t("offline.banner")).font(.callout)
            }
            if b.isServerBot {
                Row(L.t("inspector.sandbox"), b.hasSandbox ? (app.running.contains(b.id) ? L.t("inspector.running") : L.t("inspector.idle")) : L.t("inspector.none"))
            } else {
                Row(L.t("inspector.reach"), L.t("inspector.reachHost"))
            }
            Row(L.t("settings.display"), L.t("detail.\(app.settings.detail.rawValue)"))
        }
    }

    private func Row(_ k: String, _ v: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(k).font(.callout).foregroundStyle(.secondary)
            Spacer(minLength: 12)
            Text(v).font(.callout).lineLimit(1).truncationMode(.middle)
        }
    }
}
