import AppKit
import Foundation
import SwiftUI
import UniformTypeIdentifiers

/// A file picked for the message being written, while it is read (and uploaded) and once ready.
struct Pending: Identifiable, Hashable {
    enum State: Hashable { case working, ready, failed(String) }
    var id = UUID().uuidString
    var name: String
    var state: State = .working
    var attachment: Attachment? = nil
}

/// Everything the window shows: the bots, the open bot's chats, the open chat and the replies coming in.
@MainActor
@Observable
final class AppModel {
    let settings = AppSettings.shared

    var bots: [Bot] = [Bot.lab]
    var limit = 5
    var running: Set<String> = []
    var catalog = Catalog()
    /// Why the server could not be reached on the last refresh (the lists then show what was cached).
    var serverError: ServerError? = nil

    var bot: Bot = Bot.lab
    var conversations: [Conversation] = []
    var conversationID = UUID().uuidString
    var messages: [ChatMessage] = []
    /// Conversations (of any bot) whose reply is still coming in.
    var busy: Set<String> = []
    /// Which bot each busy conversation belongs to.
    private var busyOwner: [String: String] = [:]
    /// Bots with a reply coming in (their orb thinks in the side panel).
    var busyBots: Set<String> { Set(busyOwner.values) }
    /// Conversations where the bot is using a tool right now.
    var working: Set<String> = []
    /// Guarded actions waiting for an answer, by conversation.
    var waiting: [String: ApprovalRequest] = [:]
    var answering = false
    var error: ServerError? = nil
    var pending: [Pending] = []
    var pick = ModelPick()

    private var tasks: [String: Task<Void, Never>] = [:]
    /// The latest state of replies being written, by conversation, so a chat opened again shows them.
    private var live: [String: [ChatMessage]] = [:]

    var conversation: Conversation? { conversations.first { $0.id == conversationID } }
    /// No key yet, or this Mac cannot look up the server's name: the window shows the setup steps instead of a chat.
    var needsSetup: Bool { settings.managerKey.isEmpty || serverError?.isNameMissing == true }
    var streaming: Bool { busy.contains(conversationID) }
    var isWorking: Bool { working.contains(conversationID) }
    var approval: ApprovalRequest? { waiting[conversationID] }
    var effectivePick: ModelPick { pick.isDefault ? bot.pick : pick }
    var canSend: Bool { !pending.contains { $0.state == .working } }

    init() {
        if let cached = Disk.load([Bot].self, Disk.root.appendingPathComponent("bots.json")) { bots = [Bot.lab] + cached }
        if let c = Disk.load(Catalog.self, Disk.root.appendingPathComponent("catalog.json")) { catalog = c }
        select(settings.currentBotID)
        Task { await refresh() }
    }

    // MARK: bots

    func refresh() async {
        let m = Manager.current
        do {
            let l = try await m.list()
            bots = [Bot.lab] + l.bots
            limit = l.limit
            running = l.running
            serverError = nil
            Disk.save(l.bots, Disk.root.appendingPathComponent("bots.json"))
            if let c = try? await m.models() {
                catalog = c
                Disk.save(c, Disk.root.appendingPathComponent("catalog.json"))
            }
            // the open bot may have been changed (or removed) elsewhere
            if let b = bots.first(where: { $0.id == bot.id }) { bot = b } else { select(defaultBotID) }
        } catch let e as ServerError {
            serverError = e
        } catch {
            serverError = .offline(error.localizedDescription)
        }
    }

    func select(_ id: String) {
        let b = bots.first { $0.id == id } ?? Bot.lab
        bot = b
        settings.currentBotID = b.id
        pick = settings.pick(b.id)
        pending = []
        error = nil
        conversations = sorted(Disk.conversations(b.id))
        let last = settings.lastConversation(b.id)
        if conversations.contains(where: { $0.id == last }) { open(last) } else { newConversation() }
    }

    func createBot(_ b: Bot) async throws {
        let made = try await Manager.current.create(b)
        await refresh()
        select(made.id)
    }

    func updateBot(_ b: Bot) async throws {
        _ = try await Manager.current.update(b)
        await refresh()
    }

    func trashBot(_ id: String) async throws {
        try await Manager.current.trash(id)
        if bot.id == id { select(defaultBotID) }
        await refresh()
    }

    func setPick(_ p: ModelPick) {
        pick = p
        settings.setPick(bot.id, p)
    }

    // MARK: conversations

    private func sorted(_ list: [Conversation]) -> [Conversation] {
        list.sorted { ($0.pinned ? 1 : 0, $0.updated) > ($1.pinned ? 1 : 0, $1.updated) }
    }

    func newConversation() {
        conversationID = UUID().uuidString
        messages = []
        error = nil
    }

    func open(_ id: String) {
        conversationID = id
        messages = live[id] ?? Disk.messages(bot.id, id)
        settings.setLastConversation(bot.id, id)
        error = nil
    }

    func rename(_ id: String, _ title: String) {
        guard let i = conversations.firstIndex(where: { $0.id == id }) else { return }
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        conversations[i].title = t.isEmpty ? titleFrom(Disk.messages(bot.id, id)) : t
        conversations[i].renamed = !t.isEmpty
        Disk.saveConversations(bot.id, conversations)
    }

    func setPinned(_ id: String, _ on: Bool) {
        guard let i = conversations.firstIndex(where: { $0.id == id }) else { return }
        conversations[i].pinned = on
        conversations = sorted(conversations)
        Disk.saveConversations(bot.id, conversations)
    }

    func deleteConversation(_ id: String) {
        tasks[id]?.cancel()
        conversations.removeAll { $0.id == id }
        Disk.saveConversations(bot.id, conversations)
        Disk.deleteConversation(bot.id, id)
        if conversationID == id { newConversation() }
    }

    /// A chat as Markdown, for saving or sharing.
    func exportMarkdown(_ id: String) -> String {
        let c = conversations.first { $0.id == id }
        var out = "# \(c?.title.isEmpty == false ? c!.title : bot.name)\n\n"
        for m in Disk.messages(bot.id, id) {
            out += "**\(m.role == "user" ? (L.zh ? "你" : "You") : bot.name)**\n\n\(m.content)\n\n"
        }
        return out
    }

    /// Searches every chat of the open bot for [query]: its title or any message.
    func search(_ query: String) -> Set<String> {
        let q = query.lowercased()
        return Set(conversations.filter { c in
            c.title.lowercased().contains(q) || Disk.messages(bot.id, c.id).contains { $0.content.lowercased().contains(q) }
        }.map(\.id))
    }

    private func touch(_ botID: String, _ convID: String, _ list: [ChatMessage]) {
        guard !list.isEmpty else { return }
        var index = botID == bot.id ? conversations : Disk.conversations(botID)
        var c = index.first { $0.id == convID } ?? Conversation(id: convID)
        if !c.renamed { c.title = titleFrom(list) }
        c.updated = Date()
        index.removeAll { $0.id == convID }
        index.append(c)
        index = sorted(index)
        Disk.saveConversations(botID, index)
        if botID == bot.id { conversations = index }
        settings.setLastConversation(botID, convID)
    }

    // MARK: sending

    func send(_ text: String) {
        let ready = pending.compactMap { $0.state == .ready ? $0.attachment : nil }
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (!t.isEmpty || !ready.isEmpty), !streaming, canSend else { return }
        pending = []
        let msg = ChatMessage(role: "user", content: t, attachments: ready)
        start(history: messages + [msg])
    }

    /// Asks again for the last answer (or sends a message that did not get through).
    func regenerate() {
        guard !streaming else { return }
        var h = messages
        while let last = h.last, last.role == "assistant" { h.removeLast() }
        guard var last = h.last else { return }
        last.failed = false
        h[h.count - 1] = last
        start(history: h)
    }

    /// Takes a message back: it and what followed go, and its text is handed back for editing.
    func retract(_ id: String) -> String {
        guard !streaming, let i = messages.firstIndex(where: { $0.id == id }) else { return "" }
        let text = messages[i].content
        messages = Array(messages[..<i])
        Disk.saveMessages(bot.id, conversationID, messages)
        if messages.isEmpty { deleteConversation(conversationID) } else { touch(bot.id, conversationID, messages) }
        return text
    }

    func stop() { tasks[conversationID]?.cancel() }

    private func start(history: [ChatMessage]) {
        let b = bot, convID = conversationID, sendPick = effectivePick, markdown = settings.markdown
        let reply = ChatMessage(role: "assistant", content: "")
        Disk.saveMessages(b.id, convID, history)
        touch(b.id, convID, history)
        messages = history + [reply]
        error = nil
        busy.insert(convID)
        busyOwner[convID] = b.id
        let payload = Self.payload(history)

        tasks[convID] = Task { @MainActor [weak self] in
            guard let self else { return }
            var content = "", reasoning = "", tools: [String: ToolRun] = [:], order: [String] = []
            var failed = false
            let started = Date()
            var answeredAt: Date? = nil
            var lastUI = Date.distantPast
            @MainActor func push(force: Bool = false) {
                guard force || Date().timeIntervalSince(lastUI) > 0.05 else { return }
                lastUI = Date()
                var r = reply
                r.content = content; r.reasoning = reasoning; r.tools = order.compactMap { tools[$0] }
                let list = history + [r]
                self.live[convID] = list
                if self.conversationID == convID && self.bot.id == b.id { self.messages = list }
            }
            var attempt = 0
            while true {
                do {
                    try await Manager.current.stream(profile: b.profile, messages: payload, pick: sendPick, markdown: markdown) { ev in
                        switch ev {
                        case .content(let s):
                            if answeredAt == nil { answeredAt = Date() }
                            content += s; self.working.remove(convID); push()
                        case .reasoning(let s): reasoning += s; push()
                        case .working: self.working.insert(convID)
                        case .toolStart(let id, let tool, let label):
                            if tools[id] == nil { order.append(id); tools[id] = ToolRun(id: id, tool: tool, label: label, at: content.count) }
                            else { tools[id]!.tool = tool; tools[id]!.label = label }
                            push(force: true)
                        case .toolEnd(let id): tools[id]?.done = true; push(force: true)
                        case .toolDetail(let id, let command, let done, let output, let exitCode, let err, let ms):
                            if tools[id] == nil { order.append(id); tools[id] = ToolRun(id: id, at: content.count) }
                            var t = tools[id]!
                            if !command.isEmpty { t.command = command }
                            t.done = t.done || done
                            if let output { t.output = output }
                            if let exitCode { t.exitCode = exitCode }
                            if let err { t.error = err }
                            if let ms { t.durationMs = ms }
                            tools[id] = t
                            push(force: true)
                        case .approval(var req):
                            req.botProfile = b.profile
                            push(force: true)
                            self.waiting[convID] = req
                        }
                    }
                    break
                } catch is CancellationError {
                    break
                } catch let e as ServerError {
                    // a connection that drops before anything arrived is tried again a couple of times
                    if case .offline = e, content.isEmpty, reasoning.isEmpty, attempt < 2, !Task.isCancelled {
                        attempt += 1
                        try? await Task.sleep(for: .milliseconds(1500 * attempt))
                        continue
                    }
                    failed = true
                    if self.conversationID == convID { self.error = e }
                    break
                } catch {
                    failed = true
                    if self.conversationID == convID { self.error = .offline(error.localizedDescription) }
                    break
                }
            }
            // finished, stopped or failed: keep what came, in the conversation it belongs to
            var r = reply
            r.content = content; r.reasoning = reasoning
            r.tools = order.compactMap { tools[$0] }.map { var t = $0; t.done = true; return t }
            r.thinkingMs = reasoning.isEmpty ? 0 : Int(((answeredAt ?? Date()).timeIntervalSince(started)) * 1000)
            let empty = r.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && r.reasoning.isEmpty && r.tools.isEmpty
            var final = history
            if !empty { final.append(r) } else if failed, var last = final.popLast() { last.failed = true; final.append(last) }
            Disk.saveMessages(b.id, convID, final)
            self.touch(b.id, convID, final)
            self.live[convID] = nil
            self.tasks[convID] = nil
            self.busy.remove(convID)
            self.busyOwner[convID] = nil
            self.working.remove(convID)
            self.waiting[convID] = nil
            if self.conversationID == convID && self.bot.id == b.id { self.messages = final }
        }
    }

    /// The chat as the server wants it: the last messages, pictures inline in the latest few, text files pasted in.
    nonisolated static func payload(_ history: [ChatMessage]) -> [[String: Any]] {
        let sent = Array(history.filter { !$0.content.isEmpty || !$0.attachments.isEmpty }.suffix(24))
        let pictureTurns = Set(sent.indices.filter { i in sent[i].attachments.contains { $0.kind == .image } }.suffix(3))
        return sent.enumerated().map { i, m in ["role": m.role, "content": content(m, images: pictureTurns.contains(i))] }
    }

    nonisolated static func content(_ m: ChatMessage, images: Bool) -> Any {
        var text = m.content
        var parts: [[String: Any]] = []
        func add(_ s: String) { text += (text.isEmpty ? "" : "\n\n") + s }
        for a in m.attachments {
            switch a.kind {
            case .text:
                let body = (try? String(contentsOfFile: a.local, encoding: .utf8)) ?? "(the file could not be read)"
                add("<file name=\"\(a.name)\">\n\(body.trimmingCharacters(in: .newlines))\n</file>")
            case .file:
                add("[attached file: \(a.name) (\(ByteCountFormatter.string(fromByteCount: Int64(a.size), countStyle: .file)))"
                    + (a.remotePath.isEmpty ? "" : ", on the server at \(a.remotePath)") + "]")
            case .image:
                if images, let data = FileManager.default.contents(atPath: a.local) {
                    parts.append(["type": "image_url", "image_url": ["url": "data:\(a.mime);base64,\(data.base64EncodedString())"]])
                } else {
                    add("[an image was attached here: \(a.name)]")
                }
            }
        }
        if parts.isEmpty { return text }
        return (text.isEmpty ? [] : [["type": "text", "text": text]]) + parts
    }

    // MARK: approvals

    func answer(_ choice: String) {
        guard let req = approval, !answering else { return }
        let convID = conversationID
        answering = true
        Task {
            do {
                try await Manager.current.answer(req, choice: choice)
                if waiting[convID]?.id == req.id { waiting[convID] = nil }
            } catch let e as ServerError {
                // anything but "could not reach it" means Hermes no longer waits
                if case .offline = e { error = e } else if waiting[convID]?.id == req.id { waiting[convID] = nil }
            } catch {}
            answering = false
        }
    }

    // MARK: attachments

    static let maxImage = 8 * 1024 * 1024
    static let maxFile = 25 * 1024 * 1024

    func attach(_ urls: [URL]) {
        let b = bot
        for url in urls {
            let p = Pending(name: url.lastPathComponent)
            pending.append(p)
            Task {
                let result = await Self.prepare(url, bot: b)
                guard let i = pending.firstIndex(where: { $0.id == p.id }) else { return }
                switch result {
                case .success(let a): pending[i].state = .ready; pending[i].attachment = a
                case .failure(let e): pending[i].state = .failed(e.localizedDescription)
                }
            }
        }
    }

    func attach(image: NSImage) {
        guard let tiff = image.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff),
              let png = rep.representation(using: .png, properties: [:]) else { return }
        let url = Disk.dir("tmp").appendingPathComponent("pasted-\(Int(Date().timeIntervalSince1970)).png")
        try? png.write(to: url)
        attach([url])
    }

    func removePending(_ id: String) { pending.removeAll { $0.id == id } }

    struct AttachError: LocalizedError { var errorDescription: String? }

    static func prepare(_ url: URL, bot: Bot) async -> Result<Attachment, Error> {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else { return .failure(AttachError(errorDescription: "unreadable")) }
        let type = UTType(filenameExtension: url.pathExtension) ?? .data
        let mime = type.preferredMIMEType ?? "application/octet-stream"
        let kind: Attachment.Kind
        if type.conforms(to: .image), ["image/png", "image/jpeg", "image/gif", "image/webp"].contains(mime) {
            guard data.count <= maxImage else { return .failure(AttachError(errorDescription: "too large")) }
            kind = .image
        } else if (type.conforms(to: .text) || type.conforms(to: .sourceCode) || ["json", "yaml", "yml", "toml", "md", "conf", "ini", "log", "csv"].contains(url.pathExtension.lowercased())),
                  data.count <= 200_000, String(data: data, encoding: .utf8) != nil {
            kind = .text
        } else {
            guard data.count <= maxFile else { return .failure(AttachError(errorDescription: "too large")) }
            kind = .file
        }
        let local = Disk.files.appendingPathComponent(UUID().uuidString + "-" + url.lastPathComponent)
        do { try data.write(to: local) } catch { return .failure(error) }
        var a = Attachment(name: url.lastPathComponent, mime: mime, size: data.count, kind: kind, local: local.path)
        // anything else goes where the bot can open it (a bot without a sandbox only gets its name)
        if kind == .file, bot.hasSandbox || bot.id == defaultBotID {
            do { a.remotePath = try await Manager.current.upload(profile: bot.profile, name: a.name, data: data) } catch { return .failure(error) }
        }
        return .success(a)
    }
}
