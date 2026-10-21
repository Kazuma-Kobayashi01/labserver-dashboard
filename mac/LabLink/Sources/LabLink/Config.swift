import Foundation

/// Everything you change to point LabLink at your own home server.
/// Keep the network constants in sync with the variables at the top of `Resources/lablink-helper`.
enum Config {
    /// Base domain of the dashboard sites (`incus.<domain>`, `git.<domain>`, ...) and the Host header for the API.
    static let domain = "labserver.internal"
    /// This Mac's address inside the WireGuard overlay.
    static let tunnelAddress = "10.77.0.3"
    /// The home gateway peer in the overlay; it is pinged to measure latency.
    static let homeGateway = "10.77.0.2"
    /// The home server's LAN address (used while you are at home, without the tunnel).
    static let homeLAN = "192.168.1.240"
    /// The same server as seen through the tunnel (Incus bridge address on the server).
    static let tunnelHost = "10.0.0.1"
    /// SSH login on the home server (defaults to your macOS user name).
    static var sshUser: String { NSUserName() }
    /// Name shown on the Terminal row.
    static let serverLabel = "home server"
    /// known_hosts alias, so the same host key is used for LAN and tunnel connections.
    static let sshHostKeyAlias = "labserver-home"

    /// WireGuard client config (wg-quick format). The legacy path is still honoured if it exists.
    static var confPath: String {
        let home = NSHomeDirectory()
        let current = home + "/.config/lablink/lablink.conf"
        let legacy = home + "/.mbk/wg/mac.conf"
        if !FileManager.default.fileExists(atPath: current), FileManager.default.fileExists(atPath: legacy) { return legacy }
        return current
    }
}
