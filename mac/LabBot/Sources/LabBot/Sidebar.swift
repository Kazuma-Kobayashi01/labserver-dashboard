import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// The side panel, as on the phone: each bot is a card with its orb; the open bot's chats sit nested under it, grouped by
/// when they were last used, with a search box. New bot and Settings wait at the bottom. A right click renames, pins,
/// exports or deletes a chat.
struct Sidebar: View {
    @Environment(AppModel.self) private var app
    @Environment(\.openSettings) private var openSettings
    var onNewBot: () -> Void
    var onEditBot: (Bot) -> Void
    var onOpenTerminal: () -> Void
    @State private var query = ""
    @State private var found: Set<String>? = nil
    @State private var renaming: Conversation? = nil
    @State private var newTitle = ""
    @State private var deleting: Conversation? = nil

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(app.bots) { b in
                        let open = b.id == app.bot.id
                        BotCard(bot: b, selected: open, running: app.running.contains(b.id), busy: app.busyBots.contains(b.id),
                                onTerminal: onOpenTerminal, onEdit: { onEditBot(b) })
                            .onTapGesture { if !open { withAnimation(.snappy) { app.select(b.id) } } }
                            .contextMenu { Button(L.t("editor.edit")) { onEditBot(b) } }
                        if open {
                            chats.transition(.opacity.combined(with: .move(edge: .top)))
                        }
                    }
                }
                .padding(.horizontal, 10).padding(.top, 6).padding(.bottom, 12)
                .animation(.snappy, value: app.bot.id)
            }
            if let e = app.serverError, !app.needsSetup { OfflineRow(error: e) { Task { await app.refresh() } } }
            HStack(spacing: 8) {
                Button(action: onNewBot) {
                    Label(L.t("newBot"), systemImage: "plus").font(.callout.weight(.semibold))
                        .padding(.horizontal, 12).padding(.vertical, 7)
                        .background(.regularMaterial, in: Capsule())
                }
                .buttonStyle(.plain).disabled(app.bots.count - 1 >= app.limit)
                Spacer()
                Button { openSettings() } label: {
                    Image(systemName: "gearshape").font(.callout).frame(width: 30, height: 30).background(.regularMaterial, in: Circle())
                }
                .buttonStyle(.plain).help(L.t("settings.title"))
            }
            .padding(10)
        }
        .alert(L.t("chat.rename"), isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField(L.t("chat.renameHint"), text: $newTitle)
            Button(L.t("save")) { if let c = renaming { app.rename(c.id, newTitle) }; renaming = nil }
            Button(L.t("cancel"), role: .cancel) { renaming = nil }
        }
        .confirmationDialog(deleting.map { L.f("chat.deleteConfirm", $0.title.isEmpty ? L.t("newChat") : $0.title) } ?? "",
                            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button(L.t("delete"), role: .destructive) { if let c = deleting { app.deleteConversation(c.id) }; deleting = nil }
        }
    }

    /// The open bot's chats: a new-chat row, a search box once there are a few, then the chats by period.
    private var chats: some View {
        VStack(alignment: .leading, spacing: 2) {
            ChatRowShell(selected: app.messages.isEmpty, action: { app.newConversation() }) {
                Image(systemName: "square.and.pencil").foregroundStyle(.secondary)
                Text(L.t("newChat"))
            }
            if app.conversations.count > 3 || !query.isEmpty {
                HStack(spacing: 6) {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    TextField(L.t("search"), text: $query).textFieldStyle(.plain)
                    if !query.isEmpty { Button { query = "" } label: { Image(systemName: "xmark.circle.fill") }.buttonStyle(.plain).foregroundStyle(.secondary) }
                }
                .font(.callout).padding(.horizontal, 10).padding(.vertical, 6)
                .background(Color.primary.opacity(0.06), in: Capsule()).padding(.vertical, 4)
            }
            let shown = found.map { ids in app.conversations.filter { ids.contains($0.id) } } ?? app.conversations
            ForEach(Period.allCases, id: \.self) { p in
                let group = shown.filter { Period.of($0) == p }
                if !group.isEmpty {
                    Text(p.label).font(.caption.weight(.medium)).foregroundStyle(.tertiary).padding(.leading, 10).padding(.top, 8).padding(.bottom, 2)
                    ForEach(group) { c in chatRow(c) }
                }
            }
        }
        .padding(.leading, 18).padding(.vertical, 4)
        .task(id: query) {
            guard !query.isEmpty else { found = nil; return }
            try? await Task.sleep(for: .milliseconds(250))
            found = app.search(query)
        }
    }

    private func chatRow(_ c: Conversation) -> some View {
        ChatRowShell(selected: c.id == app.conversationID && !app.messages.isEmpty, action: { app.open(c.id) }) {
            if c.pinned { Image(systemName: "pin.fill").font(.caption2).foregroundStyle(.tint) }
            Text(c.title.isEmpty ? L.t("newChat") : c.title).lineLimit(1)
            Spacer(minLength: 4)
            if app.busy.contains(c.id) { PulseDot(color: accentInk(app.bot.tint)) }
        }
        .contextMenu {
            Button(L.t("chat.rename")) { newTitle = c.title; renaming = c }
            Button(L.t(c.pinned ? "chat.unpin" : "chat.pin")) { app.setPinned(c.id, !c.pinned) }
            Button(L.t("chat.share")) { export(c) }
            Divider()
            Button(L.t("chat.delete"), role: .destructive) { deleting = c }
        }
    }

    private func export(_ c: Conversation) {
        let panel = NSSavePanel()
        panel.allowedContentTypes = [UTType(filenameExtension: "md") ?? .plainText]
        panel.nameFieldStringValue = (c.title.isEmpty ? app.bot.name : c.title) + ".md"
        if panel.runModal() == .OK, let url = panel.url { try? app.exportMarkdown(c.id).write(to: url, atomically: true, encoding: .utf8) }
    }
}

/// One row of the chat list: a soft pill when it is the open one, a hint of it under the pointer.
private struct ChatRowShell<Content: View>: View {
    var selected: Bool
    var action: () -> Void
    @ViewBuilder var content: Content
    @State private var hover = false

    var body: some View {
        HStack(spacing: 6) { content }
            .font(.callout.weight(selected ? .semibold : .regular))
            .padding(.horizontal, 10).padding(.vertical, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.primary.opacity(selected ? 0.09 : hover ? 0.05 : 0), in: Capsule())
            .contentShape(Capsule())
            .onTapGesture(perform: action)
            .onHover { hover = $0 }
    }
}

/// A bot as a card: its orb, name, a tag (admin / sandbox) and role; the open one glows in its colour.
private struct BotCard: View {
    var bot: Bot
    var selected: Bool
    var running: Bool
    var busy: Bool
    var onTerminal: () -> Void
    var onEdit: () -> Void
    @State private var hover = false

    var body: some View {
        HStack(spacing: 10) {
            Orb(color: bot.tint, size: 34, mood: busy ? .thinking : .idle)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 5) {
                    Text(bot.name).font(.system(.body, design: .rounded, weight: .bold)).lineLimit(1)
                    if bot.id == defaultBotID { Tag(text: L.t("admin"), color: accentInk(bot.tint)) }
                    else if bot.hasSandbox { Tag(text: L.t("sandbox"), color: .secondary) }
                    if running { Circle().fill(.green).frame(width: 6, height: 6).help("sandbox running") }
                }
                if !bot.role.isEmpty { Text(bot.role).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
            }
            Spacer(minLength: 0)
            // the open bot's own actions: its terminal (the admin and the bots with a sandbox) and its settings
            if selected || hover {
                HStack(spacing: 2) {
                    CardButton(symbol: "slider.horizontal.3", help: L.t("editor.edit"), action: onEdit)
                }
                .transition(.opacity)
            }
        }
        .padding(.horizontal, 10).padding(.vertical, 8)
        .background {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(selected ? AnyShapeStyle(LinearGradient(colors: [bot.tint.opacity(0.22), bot.tint.opacity(0.08)], startPoint: .leading, endPoint: .trailing))
                               : AnyShapeStyle(Color.primary.opacity(hover ? 0.05 : 0)))
        }
        .overlay { if selected { RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(bot.tint.opacity(0.35)) } }
        .contentShape(RoundedRectangle(cornerRadius: 14))
        .onHover { hover = $0 }
    }
}

private struct CardButton: View {
    var symbol: String
    var help: String
    var action: () -> Void
    @State private var hover = false
    var body: some View {
        Button(action: action) {
            Image(systemName: symbol).font(.caption.weight(.semibold)).frame(width: 26, height: 26)
                .background(Color.primary.opacity(hover ? 0.12 : 0.06), in: Circle())
        }
        .buttonStyle(.plain).focusable(false).help(help).onHover { hover = $0 }
    }
}

private struct Tag: View {
    var text: String
    var color: Color
    var body: some View {
        Text(text).font(.system(size: 9, weight: .bold)).padding(.horizontal, 6).padding(.vertical, 2)
            .foregroundStyle(color).background(color.opacity(0.14), in: Capsule())
    }
}

/// A status light that breathes while a reply is coming in.
struct PulseDot: View {
    var color: Color
    @State private var on = false
    var body: some View {
        ZStack {
            Circle().fill(color.opacity(on ? 0 : 0.35)).frame(width: on ? 16 : 7, height: on ? 16 : 7)
            Circle().fill(color).frame(width: 7, height: 7)
        }
        .frame(width: 16, height: 16)
        .onAppear { withAnimation(.easeOut(duration: 1.2).repeatForever(autoreverses: false)) { on = true } }
    }
}

/// One quiet line when the server cannot be reached (the cached bots and chats stay usable); the reason shows on hover.
private struct OfflineRow: View {
    var error: ServerError
    var onRetry: () -> Void
    @State private var spinning = false

    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(.orange).frame(width: 7, height: 7)
            Text(L.t("offline.banner")).font(.caption.weight(.medium)).foregroundStyle(.secondary)
            Spacer()
            Button {
                withAnimation(.linear(duration: 0.6)) { spinning.toggle() }
                onRetry()
            } label: {
                Image(systemName: "arrow.clockwise").font(.caption.weight(.semibold)).rotationEffect(.degrees(spinning ? 360 : 0))
            }
            .buttonStyle(.plain).foregroundStyle(.secondary).help(L.t("retry"))
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(Color.primary.opacity(0.05), in: Capsule())
        .padding(.horizontal, 10)
        .help(error.message)
    }
}
