import AppKit
import SwiftTerm
import SwiftUI

/// A terminal in a bot's own machine, in its own window: the bot's sandbox, or the home server itself for Lab bot (the
/// admin). It connects to the bot-manager's `/bots/{id}/shell` WebSocket with the manager key; there is no login of its own.
struct TerminalWindow: View {
    var botID: String
    @Environment(AppModel.self) private var app
    @State private var shell = BotShell()

    var body: some View {
        let bot = app.bots.first { $0.id == botID } ?? Bot.lab
        VStack(spacing: 0) {
            ZStack(alignment: .bottom) {
                TerminalHost(shell: shell).padding(6).background(Color(red: 0.08, green: 0.08, blue: 0.11))
                if case .closed(let why) = shell.state {
                    HStack(spacing: 12) {
                        Text(why).foregroundStyle(.white)
                        Button(L.t("terminal.reconnect")) { shell.connect(profile: bot.profile) }.buttonStyle(.borderedProminent).tint(accentInk(bot.tint))
                    }
                    .padding(12).background(.ultraThickMaterial, in: RoundedRectangle(cornerRadius: 12)).environment(\.colorScheme, .dark).padding(16)
                } else if shell.state == .connecting {
                    ProgressView().controlSize(.large).padding(.bottom, 40)
                }
            }
        }
        .navigationTitle(bot.name)
        .navigationSubtitle(L.t(bot.isServerBot ? "terminal.sandbox" : "terminal.host"))
        .toolbar {
            ToolbarItem(placement: .navigation) { Orb(color: bot.tint, size: 22) }
            ToolbarItem {
                Button { shell.connect(profile: bot.profile) } label: { Label(L.t("terminal.reconnect"), systemImage: "arrow.clockwise") }
                    .disabled(shell.state == .open || shell.state == .connecting)
            }
        }
        .onAppear { shell.connect(profile: bot.profile) }
        .onDisappear { shell.close() }
    }
}

/// The WebSocket to a bot's shell: bytes both ways as binary frames, `{"resize": [cols, rows]}` as text.
@MainActor
@Observable
final class BotShell {
    enum State: Equatable { case connecting, open, closed(String) }
    var state: State = .connecting
    @ObservationIgnored weak var view: TerminalView?
    @ObservationIgnored private var task: URLSessionWebSocketTask?
    @ObservationIgnored private var size = (cols: 100, rows: 30)

    func connect(profile: String) {
        task?.cancel(with: .goingAway, reason: nil)
        state = .connecting
        let m = Manager.current
        guard !m.key.isEmpty else { state = .closed(L.t("err.noKey")); return }
        guard var c = URLComponents(string: "\(m.base)/bots/\(profile)/shell") else { state = .closed(L.t("err.badURL")); return }
        c.scheme = c.scheme == "https" ? "wss" : "ws"
        c.queryItems = [URLQueryItem(name: "cols", value: "\(size.cols)"), URLQueryItem(name: "rows", value: "\(size.rows)")]
        var r = URLRequest(url: c.url!)
        r.setValue("Bearer \(m.key)", forHTTPHeaderField: "Authorization")
        let t = URLSession.shared.webSocketTask(with: r)
        task = t
        t.resume()
        receive(t)
        // the first message proves the line is open (the shell prints its prompt); the size follows
        sendSize()
    }

    private func receive(_ t: URLSessionWebSocketTask) {
        t.receive { [weak self] result in
            Task { @MainActor in
                guard let self, self.task === t else { return }
                switch result {
                case .success(let msg):
                    if self.state != .open { self.state = .open }
                    switch msg {
                    case .data(let d): self.view?.feed(byteArray: [UInt8](d)[...])
                    case .string(let s): self.view?.feed(text: s)
                    @unknown default: break
                    }
                    self.receive(t)
                case .failure:
                    let code = (t.response as? HTTPURLResponse)?.statusCode
                    let why: String = switch code {
                    case 401: L.t("err.auth")
                    case 403: L.t("terminal.noTerminal")
                    case 404: L.t("err.notFound")
                    case 409: L.t("terminal.noSandbox")
                    default: self.state == .open ? L.t("terminal.ended") : L.t("terminal.failed")
                    }
                    self.state = .closed(why)
                }
            }
        }
    }

    func send(_ bytes: ArraySlice<UInt8>) {
        task?.send(.data(Data(bytes))) { _ in }
    }

    func resize(cols: Int, rows: Int) {
        guard cols > 0, rows > 0 else { return }
        size = (cols, rows)
        sendSize()
    }

    private func sendSize() {
        task?.send(.string(#"{"resize":[\#(size.cols),\#(size.rows)]}"#)) { _ in }
    }

    func close() {
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
    }
}

/// SwiftTerm's terminal view, wired to the shell.
struct TerminalHost: NSViewRepresentable {
    var shell: BotShell

    func makeCoordinator() -> Coordinator { Coordinator(shell: shell) }

    func makeNSView(context: Context) -> TerminalView {
        let v = TerminalView(frame: .zero)
        v.terminalDelegate = context.coordinator
        v.font = NSFont.monospacedSystemFont(ofSize: 13, weight: .regular)
        v.nativeBackgroundColor = NSColor(red: 0.08, green: 0.08, blue: 0.11, alpha: 1)
        v.nativeForegroundColor = NSColor(red: 0.89, green: 0.89, blue: 0.94, alpha: 1)
        v.caretColor = NSColor(red: 0.51, green: 0.67, blue: 1, alpha: 1)
        v.optionAsMetaKey = true
        shell.view = v
        DispatchQueue.main.async { v.window?.makeFirstResponder(v) }
        return v
    }

    func updateNSView(_ v: TerminalView, context: Context) {}

    final class Coordinator: NSObject, TerminalViewDelegate {
        let shell: BotShell
        init(shell: BotShell) { self.shell = shell }

        func send(source: TerminalView, data: ArraySlice<UInt8>) { Task { @MainActor in shell.send(data) } }
        func sizeChanged(source: TerminalView, newCols: Int, newRows: Int) { Task { @MainActor in shell.resize(cols: newCols, rows: newRows) } }
        func setTerminalTitle(source: TerminalView, title: String) {}
        func hostCurrentDirectoryUpdate(source: TerminalView, directory: String?) {}
        func scrolled(source: TerminalView, position: Double) {}
        func clipboardCopy(source: TerminalView, content: Data) {
            if let s = String(data: content, encoding: .utf8) { LabBot.copy(s) }
        }
        func bell(source: TerminalView) { NSSound.beep() }
        func rangeChanged(source: TerminalView, startY: Int, endY: Int) {}
        func requestOpenLink(source: TerminalView, link: String, params: [String: String]) {
            if let u = URL(string: link) { NSWorkspace.shared.open(u) }
        }
    }
}
