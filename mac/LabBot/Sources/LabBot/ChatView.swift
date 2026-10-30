import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// The open chat: the bot's light behind it, the messages, and the composer floating at the bottom on system blur.
struct ChatView: View {
    @Environment(AppModel.self) private var app
    @Binding var input: String
    var openTerminal: () -> Void
    @State private var awayFromEnd = false

    var body: some View {
        let detail = app.settings.detail
        ZStack(alignment: .bottom) {
            Aurora(color: app.bot.tint).ignoresSafeArea()
            if app.messages.isEmpty {
                EmptyChat(bot: app.bot) { app.send($0) }.padding(.bottom, 120)
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 22) {
                            ForEach(app.messages) { m in
                                let last = m.id == app.messages.last?.id
                                if m.role == "user" {
                                    UserBubble(m: m, color: app.bot.tint, onRetry: app.regenerate, onEdit: { input = app.retract(m.id) })
                                } else {
                                    BotReply(m: m, bot: app.bot, streaming: last && app.streaming, working: last && app.isWorking,
                                             canRegenerate: last && !app.streaming, onRegenerate: app.regenerate)
                                }
                            }
                            if let req = app.approval {
                                ApprovalCard(req: req, botName: app.bot.name, busy: app.answering, onAnswer: app.answer).padding(.leading, 36)
                            }
                            Color.clear.frame(height: 1).id("end")
                        }
                        .frame(maxWidth: 780)
                        .padding(.horizontal, 28).padding(.top, 16).padding(.bottom, 150)
                        .frame(maxWidth: .infinity)
                    }
                    .scrollContentBackground(.hidden)
                    .defaultScrollAnchor(.bottom)
                    .onScrollGeometryChange(for: Bool.self) { g in g.contentSize.height - g.contentOffset.y - g.containerSize.height > 400 } action: { _, far in
                        withAnimation(.spring(duration: 0.35, bounce: 0.4)) { awayFromEnd = far }
                    }
                    .overlay(alignment: .bottom) {
                        if awayFromEnd {
                            Button { withAnimation(.snappy) { proxy.scrollTo("end", anchor: .bottom) } } label: {
                                Image(systemName: "arrow.down").font(.callout.weight(.bold)).frame(width: 36, height: 36)
                                    .background(.regularMaterial, in: Circle()).overlay(Circle().strokeBorder(Color.primary.opacity(0.08)))
                                    .shadow(color: .black.opacity(0.12), radius: 8, y: 3)
                            }
                            .buttonStyle(.plain).padding(.bottom, 150).transition(.scale(scale: 0.4).combined(with: .opacity))
                        }
                    }
                    .onChange(of: app.messages.count) { withAnimation(.snappy) { proxy.scrollTo("end", anchor: .bottom) } }
                    .onChange(of: app.conversationID) { proxy.scrollTo("end", anchor: .bottom) }
                    .onChange(of: app.approval?.id) { withAnimation(.snappy) { proxy.scrollTo("end", anchor: .bottom) } }
                }
            }
            VStack(spacing: 8) {
                if let e = app.error { ErrorStrip(error: e) { app.error = nil } }
                Composer(input: $input).frame(maxWidth: 780)
            }
            .padding(.horizontal, 28).padding(.bottom, 18)
        }
        .environment(\.chatDetail, detail)
        .tint(accentInk(app.bot.tint))
    }
}

/// The bot's colour as soft light behind the chat (still: nothing here redraws while you read).
struct Aurora: View {
    var color: Color
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        if Theme.classic { Color(nsColor: .textBackgroundColor) } else { lights }
    }

    private var lights: some View {
        GeometryReader { g in
            ZStack {
                Color(nsColor: .windowBackgroundColor)
                RadialGradient(colors: [color.opacity(scheme == .dark ? 0.22 : 0.16), .clear], center: UnitPoint(x: 0.15, y: 0.0), startRadius: 0, endRadius: g.size.width * 0.7)
                RadialGradient(colors: [color.opacity(scheme == .dark ? 0.14 : 0.10), .clear], center: UnitPoint(x: 0.95, y: 0.35), startRadius: 0, endRadius: g.size.width * 0.55)
            }
        }
        .animation(.smooth(duration: 0.6), value: color)
    }
}

/// A readable version of a bot colour for text and icons (dark colours are lifted in dark mode).
func accentInk(_ c: Color) -> Color {
    if Theme.classic { return .primary }
    return Color(nsColor: NSColor(name: nil) { appearance in
        let ns = NSColor(c).usingColorSpace(.sRGB) ?? .systemIndigo
        let dark = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        return dark ? (ns.blended(withFraction: 0.45, of: .white) ?? ns) : ns
    })
}

// MARK: - empty chat

/// The hero moment, as on the phone: the orb inside soft rings that breathe out a few times (then rest, so an idle
/// window draws nothing), the greeting painted in the bot's colours, and four suggestion tiles rising in one after another.
private struct EmptyChat: View {
    var bot: Bot
    var onSuggest: (String) -> Void
    @State private var ring: CGFloat = 0
    @State private var shown = false

    private let icons = ["hand.wave", "sparkles", "lightbulb", "calendar"]

    var body: some View {
        VStack(spacing: 12) {
            ZStack {
                Circle().fill(RadialGradient(colors: [bot.tint.opacity(0.32), .clear], center: .center, startRadius: 0, endRadius: 125)).frame(width: 250, height: 250)
                ForEach(0..<3) { k in
                    let f = (ring + CGFloat(k) / 3).truncatingRemainder(dividingBy: 1)
                    Circle().stroke(bot.tint.opacity(0.22 * (1 - f)), lineWidth: 2).frame(width: 140 + 110 * f, height: 140 + 110 * f)
                }
                Orb(color: bot.tint, size: 120, alive: true)
            }
            .frame(height: 250)
            Text(L.f("hello", bot.name)).font(.system(size: 30, weight: .heavy, design: .rounded))
                .foregroundStyle(LinearGradient(colors: [.primary, accentInk(bot.tint)], startPoint: .leading, endPoint: .trailing))
            Text(bot.role.isEmpty ? L.t("hello.sub") : bot.role).font(.title3).foregroundStyle(.secondary).padding(.bottom, 18)
            LazyVGrid(columns: [GridItem(.fixed(260), spacing: 10), GridItem(.fixed(260), spacing: 10)], spacing: 10) {
                ForEach(1...4, id: \.self) { i in
                    let s = L.t("suggest.\(i)")
                    SuggestionTile(icon: icons[i - 1], text: s, color: bot.tint) { onSuggest(s) }
                        .opacity(shown ? 1 : 0).offset(y: shown ? 0 : 24)
                        .animation(.spring(duration: 0.55, bounce: 0.3).delay(0.09 * Double(i)), value: shown)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: bot.id) {
            shown = false
            try? await Task.sleep(for: .milliseconds(30))
            shown = true
            ring = 0
            withAnimation(.linear(duration: 3.6).repeatCount(3, autoreverses: false)) { ring = 1 }
        }
    }
}

private struct SuggestionTile: View {
    var icon: String
    var text: String
    var color: Color
    var action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 10) {
                Image(systemName: icon).font(.callout.weight(.semibold)).foregroundStyle(accentInk(color))
                    .frame(width: 32, height: 32).background(accentInk(color).opacity(0.15), in: Circle())
                Text(text).font(.callout.weight(.semibold)).multilineTextAlignment(.leading).lineLimit(2)
            }
            .frame(maxWidth: .infinity, minHeight: 64, alignment: .topLeading)
            .padding(14)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(hover ? color.opacity(0.5) : Color.primary.opacity(0.06)))
            .shadow(color: color.opacity(hover ? 0.25 : 0.08), radius: hover ? 14 : 6, y: 4)
            .scaleEffect(hover ? 1.02 : 1)
            .animation(.spring(duration: 0.3, bounce: 0.4), value: hover)
            .contentShape(RoundedRectangle(cornerRadius: 20))
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
    }
}

// MARK: - messages

private struct UserBubble: View {
    var m: ChatMessage
    var color: Color
    var onRetry: () -> Void
    var onEdit: () -> Void
    @State private var hover = false

    var body: some View {
        VStack(alignment: .trailing, spacing: 4) {
            HStack(alignment: .center, spacing: 8) {
                Spacer(minLength: 80)
                if m.failed {
                    Button(action: onRetry) {
                        Image(systemName: "exclamationmark.circle.fill").font(.title3).foregroundStyle(.white, .red)
                    }
                    .buttonStyle(.plain).help(L.t("sendFailed"))
                }
                VStack(alignment: .leading, spacing: 8) {
                    SentFiles(files: m.attachments)
                    if !m.content.isEmpty { MarkdownView(text: m.content, color: ink) }
                }
                .padding(.horizontal, 15).padding(.vertical, 10)
                .background(bubble, in: UnevenRoundedRectangle(topLeadingRadius: 20, bottomLeadingRadius: 20, bottomTrailingRadius: 6, topTrailingRadius: 20))
                .shadow(color: Theme.classic ? .clear : color.opacity(0.3), radius: 8, y: 3)
                .tint(ink)
            }
            HStack(spacing: 2) {
                IconButton(symbol: "doc.on.doc", help: L.t("copy")) { copy(m.content) }
                IconButton(symbol: "pencil", help: L.t("retract"), action: onEdit)
            }
            .opacity(hover ? 1 : 0)
        }
        .onHover { hover = $0 }
    }

    /// Modern: the bot's colour; classic: a plain dark (or, in dark mode, light) bubble.
    private var bubble: AnyShapeStyle {
        Theme.classic ? AnyShapeStyle(Color.primary.opacity(0.88))
            : AnyShapeStyle(LinearGradient(colors: [color.opacity(0.95), color.mix(.purple, 0.25)], startPoint: .topLeading, endPoint: .bottomTrailing))
    }
    private var ink: Color { Theme.classic ? Color(nsColor: .textBackgroundColor) : .white }
}

private struct SentFiles: View {
    var files: [Attachment]
    var body: some View {
        if !files.isEmpty {
            HStack(spacing: 6) {
                ForEach(files) { a in
                    if a.kind == .image, let img = NSImage(contentsOfFile: a.local) {
                        Image(nsImage: img).resizable().aspectRatio(contentMode: .fill).frame(width: files.count == 1 ? 180 : 96, height: files.count == 1 ? 140 : 96)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    } else {
                        Label(a.name, systemImage: a.kind == .text ? "doc.text" : "doc").font(.callout).lineLimit(1)
                            .padding(.horizontal, 10).padding(.vertical, 6).background(.white.opacity(0.18), in: Capsule())
                    }
                }
            }
        }
    }
}

private struct BotReply: View {
    var m: ChatMessage
    var bot: Bot
    var streaming: Bool
    var working: Bool
    var canRegenerate: Bool
    var onRegenerate: () -> Void
    @Environment(\.chatDetail) private var detail
    @State private var hover = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Orb(color: bot.tint, size: 26, mood: bot.mood(streaming: streaming, working: working, hasText: !m.content.isEmpty))
                Text(bot.name).font(.system(.callout, design: .rounded, weight: .bold))
            }
            VStack(alignment: .leading, spacing: 10) {
                if !m.reasoning.isEmpty && detail.showThoughts {
                    Thoughts(text: m.reasoning, ms: m.thinkingMs, streaming: streaming && m.content.isEmpty, color: bot.tint)
                }
                ForEach(Array(ReplyParts.pieces(m.content, m.tools, group: detail.groupTools).enumerated()), id: \.offset) { _, p in
                    switch p {
                    case .text(let s): MarkdownView(text: s)
                    case .image(let src, _): ReplyImage(src: src, profile: bot.profile)
                    case .tool(let t): ToolCard(run: t)
                    case .tools(let ts): ToolGroup(runs: ts)
                    }
                }
                if streaming && (m.content.isEmpty || m.content.count <= (m.tools.map(\.at).max() ?? -1)) {
                    Shimmer(text: L.t(working ? "working" : "thinking"))
                }
                if !streaming && !m.content.isEmpty {
                    HStack(spacing: 2) {
                        IconButton(symbol: "doc.on.doc", help: L.t("copy")) { copy(ReplyParts.plain(m.content)) }
                        if canRegenerate { IconButton(symbol: "arrow.clockwise", help: L.t("regenerate"), action: onRegenerate) }
                    }
                    .opacity(hover ? 1 : 0.35)
                }
            }
            .padding(.leading, 34)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onHover { hover = $0 }
    }
}

/// The bot's thinking, folded to one line ("Thought for 12 s") unless the display level opens it.
private struct Thoughts: View {
    var text: String
    var ms: Int
    var streaming: Bool
    var color: Color
    @Environment(\.chatDetail) private var detail
    @State private var open: Bool? = nil

    var body: some View {
        let isOpen = open ?? detail.thoughtsOpen
        VStack(alignment: .leading, spacing: 6) {
            Button { withAnimation(.snappy) { open = !isOpen } } label: {
                HStack(spacing: 6) {
                    Image(systemName: "brain").foregroundStyle(accentInk(color))
                    Text(ms >= 1000 ? L.f("thoughtFor", ms / 1000) : L.t("thought")).font(.callout.weight(.medium))
                    Image(systemName: "chevron.down").font(.caption).rotationEffect(.degrees(isOpen ? 180 : 0))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if isOpen {
                Text(text).font(.callout).foregroundStyle(.secondary).textSelection(.enabled).transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
    }
}

/// "Thinking…" with a sheen running across it.
struct Shimmer: View {
    var text: String
    var body: some View {
        TimelineView(.animation) { tl in
            let x = tl.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.6) / 1.6 * 3 - 1
            Text(text).font(.callout).foregroundStyle(
                LinearGradient(stops: [.init(color: .secondary, location: x - 0.3), .init(color: .primary, location: x), .init(color: .secondary, location: x + 0.3)],
                               startPoint: .leading, endPoint: .trailing))
        }
    }
}

struct IconButton: View {
    var symbol: String
    var help: String
    var action: () -> Void
    var body: some View {
        Button(action: action) { Image(systemName: symbol).font(.caption).frame(width: 26, height: 24).contentShape(Rectangle()) }
            .buttonStyle(.borderless).foregroundStyle(.secondary).help(help)
    }
}

func copy(_ s: String) {
    NSPasteboard.general.clearContents()
    NSPasteboard.general.setString(s, forType: .string)
}

// MARK: - commands

/// A command the bot ran, as a terminal window: what it is about, then the command and what it printed. Open while it
/// runs (in the standard level), folded to one line once done; a click opens it again.
struct ToolCard: View {
    var run: ToolRun
    @Environment(\.chatDetail) private var detail
    @State private var open: Bool? = nil
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        if !run.isCommand {
            HStack(spacing: 8) {
                if run.done { Image(systemName: "checkmark").foregroundStyle(.tint) } else { ProgressView().controlSize(.mini) }
                Image(systemName: "globe").foregroundStyle(.secondary)
                Text(run.label.isEmpty ? run.tool : run.label).font(.callout).foregroundStyle(.secondary).lineLimit(1)
            }
            .padding(.horizontal, 12).padding(.vertical, 6).background(Color.primary.opacity(0.06), in: Capsule())
        } else {
            let isOpen = open ?? (run.done ? detail.toolStaysOpen : detail.toolOpenWhileRunning)
            let dark = scheme == .dark
            VStack(alignment: .leading, spacing: 0) {
                Button { withAnimation(.snappy) { open = !isOpen } } label: {
                    HStack(spacing: 8) {
                        Group {
                            if !run.done { ProgressView().controlSize(.small) }
                            else if run.failed { Image(systemName: "xmark").foregroundStyle(.red) }
                            else { Image(systemName: "checkmark").foregroundStyle(.green) }
                        }
                        .frame(width: 18)
                        Image(systemName: "terminal").foregroundStyle(.secondary)
                        Text(run.label.isEmpty || run.label == "terminal" ? L.t("tool.command") : run.label).fontWeight(.semibold).lineLimit(1)
                        Spacer()
                        if run.failed, let c = run.exitCode, c != 0 { Text(L.f("tool.exit", c)).font(.caption).foregroundStyle(.red) }
                        if let ms = run.durationMs { Text(ms < 1000 ? "\(ms)ms" : String(format: "%.1fs", Double(ms) / 1000)).font(.caption).foregroundStyle(.secondary) }
                        Image(systemName: "chevron.down").font(.caption).rotationEffect(.degrees(isOpen ? 180 : 0)).foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 12).padding(.vertical, 9).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .background(dark ? Color(white: 0.15) : Color(white: 0.96))
                if isOpen {
                    VStack(alignment: .leading, spacing: 6) {
                        if !run.command.isEmpty {
                            HStack(alignment: .top, spacing: 4) {
                                Text("$").bold().foregroundStyle(dark ? Color(red: 0.51, green: 0.67, blue: 1) : .blue)
                                Text(run.command).textSelection(.enabled)
                                Spacer()
                                IconButton(symbol: "doc.on.doc", help: L.t("copy")) { copy(run.command) }
                            }
                        }
                        let out = run.output.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? (run.error ?? "") : run.output
                        if !run.done && out.isEmpty { Text(L.t("tool.running")).foregroundStyle(.secondary) }
                        else if !out.isEmpty {
                            ScrollView { Text(out.trimmingCharacters(in: .newlines)).foregroundStyle(run.failed ? .red : .secondary).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading) }
                                .frame(maxHeight: 260).fixedSize(horizontal: false, vertical: true)
                        } else { Text(L.t("tool.noOutput")).foregroundStyle(.tertiary) }
                    }
                    .font(.system(size: 12, design: .monospaced))
                    .padding(12)
                    .transition(.opacity)
                }
            }
            .background(dark ? Color(white: 0.11) : .white)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Color.primary.opacity(0.08)))
        }
    }
}

/// Several commands as one line ("Ran 3 commands"), for the minimal display; a click lists them.
struct ToolGroup: View {
    var runs: [ToolRun]
    @State private var open = false

    var body: some View {
        let busy = runs.contains { !$0.done }
        let failed = runs.contains(where: \.failed)
        VStack(alignment: .leading, spacing: 8) {
            Button { withAnimation(.snappy) { open.toggle() } } label: {
                HStack(spacing: 8) {
                    if busy { ProgressView().controlSize(.mini) } else { Image(systemName: failed ? "xmark" : "checkmark").foregroundStyle(failed ? .red : .green) }
                    Image(systemName: "terminal").foregroundStyle(.secondary)
                    Text(L.f(busy ? "tool.groupRunning" : "tool.groupDone", runs.count)).font(.callout).foregroundStyle(.secondary)
                    Image(systemName: "chevron.down").font(.caption).rotationEffect(.degrees(open ? 180 : 0)).foregroundStyle(.secondary)
                }
                .padding(.horizontal, 12).padding(.vertical, 6).background(Color.primary.opacity(0.06), in: Capsule()).contentShape(Capsule())
            }
            .buttonStyle(.plain)
            if open { ForEach(runs) { ToolCard(run: $0) }.environment(\.chatDetail, .standard) }
        }
    }
}

// MARK: - approvals, errors

/// The bot wants to do something guarded: what kind, the exact command, the answers, and how long until it is refused.
struct ApprovalCard: View {
    var req: ApprovalRequest
    var botName: String
    var busy: Bool
    var onAnswer: (String) -> Void

    var body: some View {
        let (symbol, key): (String, String) = switch req.kind {
        case "download": ("arrow.down.circle", "approval.download")
        case "install": ("shippingbox", "approval.install")
        case "delete": ("trash", "approval.delete")
        case "system": ("server.rack", "approval.system")
        default: ("shield", "approval.other")
        }
        let danger = ["delete", "system", "danger"].contains(req.kind)
        let accent: Color = danger ? .red : .accentColor
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: symbol).foregroundStyle(accent).font(.title3)
                Text(L.f(key, botName)).font(.headline)
            }
            Text(req.command).font(.system(size: 12, design: .monospaced)).textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading).padding(10)
                .background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 8))
            HStack {
                ForEach(req.choices.filter { $0 != "deny" }, id: \.self) { c in
                    Button(L.t("approval.\(c)")) { onAnswer(c) }.buttonStyle(.borderedProminent).tint(c == "once" ? accent : .secondary)
                }
                Button(L.t("approval.deny"), role: .cancel) { onAnswer("deny") }.buttonStyle(.bordered)
                Spacer()
                TimelineView(.periodic(from: .now, by: 1)) { tl in
                    let left = max(0, Int(req.timeout - tl.date.timeIntervalSince(req.at)))
                    Text(L.f("approval.left", left)).font(.caption).foregroundStyle(.secondary).monospacedDigit()
                }
            }
            .disabled(busy)
        }
        .padding(14)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(accent.opacity(0.45)))
    }
}

private struct ErrorStrip: View {
    var error: ServerError
    var onDismiss: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            VStack(alignment: .leading, spacing: 6) {
                if error.isNameMissing {
                    Text(L.t("err.hosts")).font(.callout)
                    HostsLine()
                } else {
                    Text(error.message).font(.callout)
                }
            }
            Spacer()
            Button(action: onDismiss) { Image(systemName: "xmark") }.buttonStyle(.borderless)
        }
        .padding(12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
        .frame(maxWidth: 780)
    }
}

/// The line that teaches this Mac the bot-manager's name (it follows the LabLink tunnel like the other site names).
struct HostsLine: View {
    static let command = #"sudo sh -c 'grep -q bot.labserver.internal /etc/hosts || sed -i "" "/ labserver\.internal/ s/$/ bot.labserver.internal/" /etc/hosts'"#
    var body: some View {
        HStack {
            Text(Self.command).font(.system(size: 11, design: .monospaced)).textSelection(.enabled).lineLimit(2)
            IconButton(symbol: "doc.on.doc", help: L.t("copy")) { copy(Self.command) }
        }
        .padding(8).background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 8))
    }
}

extension Color {
    func mix(_ other: Color, _ f: Double) -> Color {
        let a = NSColor(self).usingColorSpace(.sRGB) ?? .systemIndigo, b = NSColor(other).usingColorSpace(.sRGB) ?? .systemPurple
        return Color(nsColor: a.blended(withFraction: f, of: b) ?? a)
    }
}
