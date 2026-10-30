import AppKit
import SwiftUI

@main
struct LabBotApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @State private var app = AppModel()

    var body: some Scene {
        // one chat window, always shown at launch (and back from the Window menu or the Dock once closed)
        Window("Lab bot", id: "main") {
            RootView()
                .environment(app)
                .preferredColorScheme(app.settings.colorScheme)
                .frame(minWidth: 760, minHeight: 520)
        }
        .defaultSize(width: 1120, height: 760)
        .defaultLaunchBehavior(.presented)
        .commands {
            CommandGroup(replacing: .newItem) {
                Button(L.t("newChat")) { app.newConversation() }.keyboardShortcut("n")
            }
        }

        // a bot's terminal opens in a window of its own
        WindowGroup(L.t("terminal"), id: "terminal", for: String.self) { $botID in
            TerminalWindow(botID: botID ?? defaultBotID).environment(app).preferredColorScheme(app.settings.colorScheme)
        }
        .defaultSize(width: 860, height: 540)
        .restorationBehavior(.disabled)
        .defaultLaunchBehavior(.suppressed)

        Settings { SettingsView().environment(app).preferredColorScheme(app.settings.colorScheme) }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
}

/// The window, laid out like Claude's: bots and their chats on the left (system translucency), the chat in the middle
/// with no bar over it, and the inspector (context, outputs, environment) on the right. The few actions sit at the top
/// right: a new chat, the bot's terminal, and the inspector's toggle.
struct RootView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.openWindow) private var openWindow
    @State private var input = ""
    @State private var editing: Bot? = nil
    @State private var creating = false
    @AppStorage("inspector") private var showInspector = true

    var body: some View {
        NavigationSplitView {
            Sidebar(onNewBot: { creating = true }, onEditBot: { editing = $0 }, onOpenTerminal: openTerminal)
                .navigationSplitViewColumnWidth(min: 240, ideal: 270, max: 360)
        } detail: {
            Group {
                if app.needsSetup { SetupView() } else {
                    // the cards float over the chat's light on the right; the chat keeps clear of them
                    ChatView(input: $input, openTerminal: openTerminal)
                        .safeAreaInset(edge: .trailing, spacing: 0) { if showInspector { Color.clear.frame(width: 300) } }
                        .overlay(alignment: .topTrailing) {
                            if showInspector {
                                InspectorView().frame(width: 284).padding(.trailing, 14)
                                    .transition(.move(edge: .trailing).combined(with: .opacity))
                            }
                        }
                }
            }
            .animation(.smooth, value: app.needsSetup)
            .toolbar {
                // the few actions, at the top right of the chat (no bar or title over it)
                ToolbarItemGroup(placement: .primaryAction) {
                    Spacer()
                    Button { app.newConversation() } label: { Label(L.t("newChat"), systemImage: "square.and.pencil") }
                        .help(L.t("newChat")).disabled(app.messages.isEmpty || app.needsSetup)
                    Button(action: openTerminal) { Label(L.t("terminal.open"), systemImage: "terminal") }
                        .help(app.bot.hasTerminal ? L.t("terminal.open") : L.t("terminal.noTerminal"))
                        .disabled(!app.bot.hasTerminal || app.needsSetup)
                    Button { withAnimation(.spring(duration: 0.4, bounce: 0.2)) { showInspector.toggle() } } label: {
                        Label(L.t("inspector.toggle"), systemImage: showInspector ? "rectangle.righthalf.inset.filled" : "rectangle.split.2x1")
                    }
                    .help(L.t("inspector.toggle")).disabled(app.needsSetup)
                }
            }
        }
        .navigationTitle("Lab bot")
        .toolbar(removing: .title)
        .toolbarBackgroundVisibility(.hidden, for: .windowToolbar)
        // a change of look rebuilds the views, which read it as they draw
        .id(app.settings.theme)
        .sheet(item: $editing) { b in BotEditor(initial: b) }
        .sheet(isPresented: $creating) { BotEditor(initial: nil) }
        .onChange(of: app.bot.id) { input = "" }
    }

    private func openTerminal() { openWindow(id: "terminal", value: app.bot.id) }
}
