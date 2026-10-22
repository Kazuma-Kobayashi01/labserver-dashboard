import AppKit
import Darwin
import Foundation
import LocalAuthentication
import SwiftUI

// MARK: - API types (read-only dashboard API)

struct Envelope<T: Decodable>: Decodable {
    let ok: Bool
    let data: T?
}

struct Instance: Decodable, Identifiable {
    var id: String { name }
    let name: String
    let type: String
    let status: String
    let running: Bool
    let ipv4: [String]
}

struct SysInfo: Decodable {
    struct Mem: Decodable { let used: Double; let total: Double }
    let hostname: String
    let cpuUsage: Double
    let memory: Mem
    let temperature: Double?
}

// MARK: - Process helpers

enum Shell {
    static func run(_ exe: String, _ args: [String], timeout: TimeInterval = 10) async -> (status: Int32, out: String) {
        await withCheckedContinuation { cont in
            DispatchQueue.global().async {
                let p = Process()
                p.executableURL = URL(fileURLWithPath: exe)
                p.arguments = args
                let pipe = Pipe()
                p.standardOutput = pipe
                p.standardError = pipe
                do { try p.run() } catch {
                    cont.resume(returning: (-1, "\(error)"))
                    return
                }
                let killer = DispatchWorkItem { if p.isRunning { p.terminate() } }
                DispatchQueue.global().asyncAfter(deadline: .now() + timeout, execute: killer)
                let data = pipe.fileHandleForReading.readDataToEndOfFile()
                p.waitUntilExit()
                killer.cancel()
                cont.resume(returning: (p.terminationStatus, String(data: data, encoding: .utf8) ?? ""))
            }
        }
    }

    /// Runs a shell command as root through the macOS admin prompt (password or Touch ID). Nothing is installed.
    static func privileged(_ command: String, prompt: String) async -> (status: Int32, out: String) {
        func esc(_ s: String) -> String {
            s.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
        }
        let script = "do shell script \"\(esc(command))\" with administrator privileges with prompt \"\(esc(prompt))\""
        return await run("/usr/bin/osascript", ["-e", script], timeout: 180)
    }
}

// MARK: - Tunnel model

@MainActor
final class TunnelModel: ObservableObject {
    static let shared = TunnelModel()

    enum Phase { case off, connecting, on, disconnecting }

    nonisolated static let tunnelAddress = Config.tunnelAddress
    static let homeGateway = Config.homeGateway
    static let homeLAN = Config.homeLAN
    static let tunnelHost = Config.tunnelHost

    @Published var phase: Phase = .off
    @Published var latency: Int?
    @Published var atHome = false
    @Published var instances: [Instance] = []
    @Published var sys: SysInfo?
    @Published var rxBytes: UInt64 = 0
    @Published var txBytes: UInt64 = 0
    @Published var toast: String?
    @Published var touchIDInstalled = false

    private var started = false
    private var lastUp: Bool?
    private var toastTask: Task<Void, Never>?

    nonisolated static let installedHelper = "/usr/local/libexec/lablink/lablink-helper"
    var conf: String { Config.confPath }
    var helper: String { (Bundle.main.resourcePath ?? "") + "/lablink-helper" }
    var tunnelUp: Bool { phase == .on || phase == .disconnecting }
    /// Address of the home server we can reach right now (nil when neither tunnel nor LAN works).
    var base: String? {
        if tunnelUp { return Self.tunnelHost }
        if atHome { return Self.homeLAN }
        return nil
    }

    func start() {
        guard !started else { return }
        started = true
        Task {
            while !Task.isCancelled {
                await refresh()
                try? await Task.sleep(nanoseconds: 3_000_000_000)
            }
        }
    }

    func show(_ message: String) {
        toast = message
        toastTask?.cancel()
        toastTask = Task {
            try? await Task.sleep(nanoseconds: 2_800_000_000)
            if !Task.isCancelled { toast = nil }
        }
    }

    // MARK: refresh

    func refresh() async {
        touchIDInstalled = await Self.checkInstalled()
        let iface = Self.tunnelInterface()
        let up = iface != nil
        notifyIfChanged(up: up)
        if phase != .connecting && phase != .disconnecting { phase = up ? .on : .off }

        if let iface = iface {
            latency = await Self.pingMs(Self.homeGateway)
            if let c = Self.counters(iface) { rxBytes = c.rx; txBytes = c.tx }
            atHome = false
        } else {
            latency = nil
            rxBytes = 0
            txBytes = 0
            atHome = await Self.probe(Self.homeLAN)
        }

        guard let host = base else {
            instances = []
            sys = nil
            return
        }
        if let list: [Instance] = await Self.fetch("instances", host: host) { instances = list }
        if let s: SysInfo = await Self.fetch("system", host: host) { sys = s }
    }

    /// Posts a system notification when the tunnel goes up or down (the first refresh only sets the baseline).
    private func notifyIfChanged(up: Bool) {
        defer { lastUp = up }
        guard let prev = lastUp, prev != up, AppSettings.shared.notify else { return }
        Notifier.post(title: AppSettings.shared.displayName, body: L10n.t(up ? "notify.connected" : "notify.disconnected"))
    }

    // MARK: actions

    func connect() async {
        guard phase == .off else { return }
        if atHome { show(L10n.t("toast.athome")); return }
        phase = .connecting
        let r = await runHelper("up")
        if r.status != 0 {
            show(r.out.contains("-128") || r.out.lowercased().contains("cancel") ? L10n.t("toast.cancelled") : "\(L10n.t("toast.connfail")): \(r.out.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))")
        }
        phase = Self.tunnelInterface() != nil ? .on : .off
        await refresh()
    }

    func disconnect() async {
        guard phase == .on else { return }
        phase = .disconnecting
        let r = await runHelper("down")
        if r.status != 0 { show("\(L10n.t("toast.discfail")): \(r.out.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))") }
        phase = Self.tunnelInterface() != nil ? .on : .off
        await refresh()
    }

    /// Runs the privileged helper. With the Touch ID component installed and selected, the user confirms with a
    /// fingerprint and the helper runs through the fixed sudo rule; otherwise the macOS admin password prompt is used.
    private func runHelper(_ action: String) async -> (status: Int32, out: String) {
        if AppSettings.shared.authMode == "touchid" && touchIDInstalled {
            let reason = L10n.t(action == "up" ? "auth.touch.connect" : "auth.touch.disconnect")
            guard await Self.biometric(reason: reason) else { return (1, "cancelled") }
            return await Shell.run("/usr/bin/sudo", ["-n", Self.installedHelper, action], timeout: 40)
        }
        let cmd = action == "up" ? "'\(helper)' up '\(conf)'" : "'\(helper)' down"
        return await Shell.privileged(cmd, prompt: L10n.t(action == "up" ? "auth.connect" : "auth.disconnect"))
    }

    func installTouchID() async {
        let res = Bundle.main.resourcePath ?? ""
        let cmd = "/bin/bash '\(res)/install.sh' install '\(NSUserName())' '\(res)' '\(conf)'"
        let r = await Shell.privileged(cmd, prompt: L10n.t("auth.install"))
        touchIDInstalled = await Self.checkInstalled()
        if r.status == 0 && touchIDInstalled {
            AppSettings.shared.authMode = "touchid"
            show(L10n.t("toast.installed"))
        } else {
            AppSettings.shared.authMode = "password"
            show(r.out.contains("-128") || r.out.lowercased().contains("cancel") ? L10n.t("toast.cancelled") : "\(L10n.t("toast.installfail")): \(r.out.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))")
        }
    }

    func uninstallTouchID() async {
        let res = Bundle.main.resourcePath ?? ""
        let r = await Shell.privileged("/bin/bash '\(res)/install.sh' uninstall", prompt: L10n.t("auth.uninstall"))
        touchIDInstalled = await Self.checkInstalled()
        if r.status == 0 && !touchIDInstalled {
            AppSettings.shared.authMode = "password"
            show(L10n.t("toast.removed"))
        } else {
            show(r.out.contains("-128") || r.out.lowercased().contains("cancel") ? L10n.t("toast.cancelled") : "\(L10n.t("toast.installfail")): \(r.out.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))")
        }
    }

    func openPanel(_ urlString: String) {
        guard base != nil, let url = URL(string: urlString) else {
            show(L10n.t("toast.needconn"))
            return
        }
        NSWorkspace.shared.open(url)
    }

    func openSSH() {
        guard let host = base else {
            show(L10n.t("toast.needconn"))
            return
        }
        let dir = NSHomeDirectory() + "/Library/Application Support/LabLink"
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        let file = dir + "/ssh.command"
        let body = "#!/bin/zsh\nclear\nexec ssh -o HostKeyAlias=\(Config.sshHostKeyAlias) -o ServerAliveInterval=15 \(Config.sshUser)@\(host)\n"
        try? body.write(toFile: file, atomically: true, encoding: .utf8)
        try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: file)
        NSWorkspace.shared.open(
            [URL(fileURLWithPath: file)],
            withApplicationAt: URL(fileURLWithPath: "/System/Applications/Utilities/Terminal.app"),
            configuration: NSWorkspace.OpenConfiguration()
        )
    }

    func copy(_ text: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        show("\(L10n.t("toast.copied")) \(text)")
    }

    // MARK: low-level

    /// Name of the interface that carries our tunnel address, if the tunnel is up.
    nonisolated static func tunnelInterface() -> String? {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return nil }
        defer { freeifaddrs(head) }
        var cur: UnsafeMutablePointer<ifaddrs>? = first
        while let p = cur {
            defer { cur = p.pointee.ifa_next }
            guard let sa = p.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET) else { continue }
            var addr = sockaddr_in()
            memcpy(&addr, sa, MemoryLayout<sockaddr_in>.size)
            var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            var inAddr = addr.sin_addr
            inet_ntop(AF_INET, &inAddr, &buf, socklen_t(INET_ADDRSTRLEN))
            if String(cString: buf) == tunnelAddress { return String(cString: p.pointee.ifa_name) }
        }
        return nil
    }

    nonisolated static func counters(_ iface: String) -> (rx: UInt64, tx: UInt64)? {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return nil }
        defer { freeifaddrs(head) }
        var cur: UnsafeMutablePointer<ifaddrs>? = first
        while let p = cur {
            defer { cur = p.pointee.ifa_next }
            guard String(cString: p.pointee.ifa_name) == iface,
                  let sa = p.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_LINK),
                  let data = p.pointee.ifa_data else { continue }
            let d = data.assumingMemoryBound(to: if_data.self).pointee
            return (UInt64(d.ifi_ibytes), UInt64(d.ifi_obytes))
        }
        return nil
    }

    /// True when the root-owned helper exists and sudo allows running it without a password.
    nonisolated static func checkInstalled() async -> Bool {
        guard FileManager.default.fileExists(atPath: installedHelper) else { return false }
        let r = await Shell.run("/usr/bin/sudo", ["-n", "-l", installedHelper, "up"], timeout: 5)
        return r.status == 0
    }

    /// Touch ID; falls back to the system password sheet if fingerprint is unavailable or the user asks for it.
    nonisolated static func biometric(reason: String) async -> Bool {
        let ctx = LAContext()
        var err: NSError?
        if ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &err) {
            do {
                return try await ctx.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason)
            } catch let e as LAError where e.code == .userFallback {
                return (try? await LAContext().evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)) ?? false
            } catch { return false }
        }
        return (try? await LAContext().evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)) ?? false
    }

    nonisolated static func pingMs(_ ip: String) async -> Int? {
        let r = await Shell.run("/sbin/ping", ["-c", "1", "-W", "1500", ip], timeout: 4)
        guard r.status == 0, let range = r.out.range(of: "time=") else { return nil }
        let tail = r.out[range.upperBound...]
        let num = tail.prefix { $0.isNumber || $0 == "." }
        return Double(num).map { Int($0.rounded()) }
    }

    nonisolated static func probe(_ host: String) async -> Bool {
        var req = URLRequest(url: URL(string: "http://\(host)/api/v1/health")!)
        req.timeoutInterval = 1.2
        req.setValue(Config.domain, forHTTPHeaderField: "Host")
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 1.2
        guard let (data, resp) = try? await URLSession(configuration: cfg).data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200 else { return false }
        return String(data: data, encoding: .utf8)?.contains("\"ok\":true") ?? false
    }

    nonisolated static func fetch<T: Decodable>(_ path: String, host: String) async -> T? {
        var req = URLRequest(url: URL(string: "http://\(host)/api/v1/\(path)")!)
        req.timeoutInterval = 4
        req.setValue(Config.domain, forHTTPHeaderField: "Host")
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 4
        guard let (data, _) = try? await URLSession(configuration: cfg).data(for: req),
              let env = try? JSONDecoder().decode(Envelope<T>.self, from: data), env.ok else { return nil }
        return env.data
    }
}
