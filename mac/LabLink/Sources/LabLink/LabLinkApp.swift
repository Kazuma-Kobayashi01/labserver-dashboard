import AppKit
import SwiftUI
import UserNotifications

@main
struct LabLinkApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @ObservedObject private var settings = AppSettings.shared
    @ObservedObject private var model = TunnelModel.shared

    var body: some Scene {
        WindowGroup("LabLink", id: "main") {
            ContentView()
                .frame(minWidth: 300, idealWidth: 320, maxWidth: 440, minHeight: 520, idealHeight: 680, maxHeight: 900)
                .background(WindowConfigurator())
                .preferredColorScheme(settings.scheme)
        }
        .windowStyle(.hiddenTitleBar)
        .windowResizability(.contentSize) // min/max come from the content frame
        .commands {
            CommandGroup(replacing: .newItem) {}
            CommandGroup(replacing: .windowSize) {}
        }

        // Menu bar icon: stays after the window is closed (⌘W) so the app can be reopened or quit from here.
        MenuBarExtra {
            MenuBarMenu()
        } label: {
            Image(systemName: model.phase == .on ? "network.badge.shield.half.filled" : "network")
        }

        // ⌘, opens this automatically
        Settings { SettingsView() }
    }
}

private struct MenuBarMenu: View {
    @Environment(\.openWindow) private var openWindow
    @ObservedObject private var m = TunnelModel.shared

    var body: some View {
        Text(m.phase == .on ? "10.77.0.3 · \(L10n.t("foot.up"))" : L10n.t("foot.down"))
        Divider()
        Button(L10n.t("menu.show")) { showMainWindow() }
        Button(L10n.t("menu.quit")) { NSApp.terminate(nil) }
    }

    private func showMainWindow() {
        NSApp.activate(ignoringOtherApps: true)
        // A window closed with ⌘W is kept by SwiftUI; bring it back, or open a fresh one if there is none.
        if let w = NSApp.windows.first(where: { $0.title == "LabLink" && $0.styleMask.contains(.titled) }) {
            w.makeKeyAndOrderFront(nil)
        } else {
            openWindow(id: "main")
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate {
    private static let quitHoldSeconds: TimeInterval = 1.0

    private var keyMonitor: Any?
    private var quitTimer: Timer?
    private var quitHint: NSPanel?

    // ⌘W only closes the window: the app keeps running in the background (Dock icon stays, click it to reopen).
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    func applicationDidFinishLaunching(_ notification: Notification) {
        if Bundle.main.bundleIdentifier != nil { UNUserNotificationCenter.current().delegate = self }
        Notifier.requestAuthorization()
        // ⌘Q must be held to quit. A plain press only shows the hint; auto-repeat events are swallowed too.
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard event.modifierFlags.intersection(.deviceIndependentFlagsMask) == .command,
                  event.charactersIgnoringModifiers?.lowercased() == "q" else { return event }
            if !event.isARepeat { self?.beginQuitHold(keyCode: CGKeyCode(event.keyCode)) }
            return nil
        }
    }

    // Show banners even while the app is in front.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }

    private func beginQuitHold(keyCode: CGKeyCode) {
        guard quitTimer == nil else { return }
        showQuitHint()
        quitTimer = Timer.scheduledTimer(withTimeInterval: Self.quitHoldSeconds, repeats: false) { [weak self] _ in
            guard let self else { return }
            self.quitTimer = nil
            self.hideQuitHint()
            let stillHeld = NSEvent.modifierFlags.contains(.command)
                && CGEventSource.keyState(.combinedSessionState, key: keyCode)
            if stillHeld { NSApp.terminate(nil) }
        }
    }

    private func showQuitHint() {
        let host = NSHostingView(rootView: QuitHintView())
        host.setFrameSize(host.fittingSize)
        let panel = NSPanel(contentRect: NSRect(origin: .zero, size: host.frame.size),
                            styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.contentView = host
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.level = .floating
        panel.ignoresMouseEvents = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        let area = (NSApp.keyWindow?.frame ?? NSScreen.main?.visibleFrame) ?? .zero
        panel.setFrameOrigin(NSPoint(x: area.midX - panel.frame.width / 2, y: area.midY - panel.frame.height / 2))
        panel.orderFrontRegardless()
        quitHint = panel
    }

    private func hideQuitHint() {
        quitHint?.orderOut(nil)
        quitHint = nil
    }
}

private struct QuitHintView: View {
    var body: some View {
        Text(L10n.t("quit.hold"))
            .font(.system(size: 15, weight: .semibold))
            .padding(.horizontal, 22).padding(.vertical, 14)
            .background(.ultraThickMaterial, in: RoundedRectangle(cornerRadius: 14))
            .padding(8)
    }
}

/// Panel window: resizable within the content frame limits, but no zoom button and no full screen.
struct WindowConfigurator: NSViewRepresentable {
    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        DispatchQueue.main.async {
            guard let w = view.window else { return }
            w.styleMask.insert(.resizable)
            w.styleMask.insert(.fullSizeContentView)
            w.collectionBehavior = [.fullScreenNone, .moveToActiveSpace]
            w.standardWindowButton(.zoomButton)?.isEnabled = false
            w.titlebarAppearsTransparent = true
            w.titleVisibility = .hidden
            w.isMovableByWindowBackground = true
            // transparent window so the header's blur can see the desktop; the body paints its own solid colours
            w.isOpaque = false
            w.backgroundColor = .clear
        }
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {}
}
