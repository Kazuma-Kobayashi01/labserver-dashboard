import AppKit
import SwiftUI

@main
struct LabLinkApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @ObservedObject private var settings = AppSettings.shared

    var body: some Scene {
        WindowGroup("LabLink") {
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

        // ⌘, opens this automatically
        Settings { SettingsView() }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
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
            w.backgroundColor = .windowBackgroundColor
        }
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {}
}
