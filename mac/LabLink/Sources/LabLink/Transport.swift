import Foundation

/// How the tunnel reaches the relay. The relay's UDP replies do not arrive on the networks this is used on, so the
/// WireGuard packets travel over TCP: the app starts a local wstunnel client (bundled in the app, configured by
/// `~/.config/lablink/wstunnel.env`) that carries them over a WebSocket; the WireGuard config's Endpoint must be
/// `127.0.0.1:51821`. Without the file or the binary the app refuses to connect.
///
///   ~/.config/lablink/wstunnel.env   (mode 600)
///   URL=wss://<relay>:9443
///   PREFIX=<secret path prefix configured on the relay's wstunnel server>
enum TCPTransport {
    static let localPort = 51821
    private static var process: Process?

    static var envPath: String { NSHomeDirectory() + "/.config/lablink/wstunnel.env" }
    static var binary: String { (Bundle.main.resourcePath ?? "") + "/wstunnel" }
    private static var pattern: String { "wstunnel client -L udp://127.0.0.1:\(localPort):" }

    private static var settings: (url: String, prefix: String)? {
        guard let text = try? String(contentsOfFile: envPath, encoding: .utf8) else { return nil }
        var values: [String: String] = [:]
        for line in text.split(separator: "\n") {
            let parts = line.split(separator: "=", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
            if parts.count == 2 { values[parts[0]] = parts[1] }
        }
        guard let url = values["URL"], url.hasPrefix("wss://"), let prefix = values["PREFIX"], !prefix.isEmpty else { return nil }
        return (url, prefix)
    }

    /// True when the app is set up to use the TCP transport.
    static var enabled: Bool { FileManager.default.isExecutableFile(atPath: binary) && settings != nil }

    static func isRunning() async -> Bool {
        await Shell.run("/usr/bin/pgrep", ["-f", pattern], timeout: 3).status == 0
    }

    /// Starts the local client unless one is already running. It keeps running after the app quits, like the tunnel.
    static func start() async -> Bool {
        guard let s = settings else { return false }
        if await isRunning() { return true }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: binary)
        p.arguments = [
            "client", "-L", "udp://127.0.0.1:\(localPort):127.0.0.1:51820?timeout_sec=0",
            "--http-upgrade-path-prefix", s.prefix, s.url,
        ]
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        do { try p.run() } catch { return false }
        process = p
        for _ in 0..<20 {
            try? await Task.sleep(nanoseconds: 150_000_000)
            if !p.isRunning { return false }
        }
        return true
    }

    static func stop() async {
        process?.terminate()
        process = nil
        _ = await Shell.run("/usr/bin/pkill", ["-f", pattern], timeout: 3)
    }
}
