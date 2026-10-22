import AppKit
import SwiftUI

// MARK: - Palette (old-QQ flavoured, light + dark)

private enum Pal {
    // Header themes: green = at home, grey = away and not connected, red = tunnel up
    static let greenTop = Color(light: Color(red: 0.13, green: 0.68, blue: 0.36), dark: Color(red: 0.09, green: 0.48, blue: 0.26))
    static let greenBottom = Color(light: Color(red: 0.38, green: 0.84, blue: 0.55), dark: Color(red: 0.15, green: 0.60, blue: 0.35))
    static let greenAccent = Color(red: 0.13, green: 0.62, blue: 0.33)
    static let redTop = Color(light: Color(red: 0.90, green: 0.26, blue: 0.26), dark: Color(red: 0.65, green: 0.17, blue: 0.17))
    static let redBottom = Color(light: Color(red: 1.0, green: 0.46, blue: 0.44), dark: Color(red: 0.78, green: 0.27, blue: 0.26))
    static let redAccent = Color(red: 0.86, green: 0.24, blue: 0.24)
    static let grayTop = Color(light: Color(red: 0.50, green: 0.55, blue: 0.62), dark: Color(red: 0.28, green: 0.31, blue: 0.37))
    static let grayBottom = Color(light: Color(red: 0.70, green: 0.74, blue: 0.80), dark: Color(red: 0.38, green: 0.42, blue: 0.49))
    static let grayAccent = Color(red: 0.45, green: 0.50, blue: 0.58)
    static let accent = Color(red: 0.12, green: 0.61, blue: 0.94)
    static let ink = Color(light: Color(red: 0.13, green: 0.18, blue: 0.26), dark: Color(red: 0.91, green: 0.93, blue: 0.96))
    static let sub = Color(light: Color(red: 0.45, green: 0.52, blue: 0.62), dark: Color(red: 0.58, green: 0.63, blue: 0.71))
    static let line = Color(light: Color(red: 0.88, green: 0.92, blue: 0.97), dark: Color(red: 0.17, green: 0.20, blue: 0.26))
    static let hover = Color(light: Color(red: 0.90, green: 0.95, blue: 1.0), dark: Color(red: 0.14, green: 0.20, blue: 0.29))
    static let surface = Color(light: .white, dark: Color(red: 0.105, green: 0.13, blue: 0.17))
    static let groupBg = Color(light: Color(red: 0.96, green: 0.975, blue: 1.0), dark: Color(red: 0.13, green: 0.16, blue: 0.21))
    static let footerBg = Color(light: Color(red: 0.95, green: 0.97, blue: 1.0), dark: Color(red: 0.12, green: 0.145, blue: 0.19))
    static let green = Color(red: 0.20, green: 0.78, blue: 0.40)
    static let orange = Color(red: 1.0, green: 0.55, blue: 0.20)
    static let gray = Color(red: 0.62, green: 0.66, blue: 0.72)
}

private struct Entry: Identifiable {
    let id: String
    let title: String
    let subKey: String
    let symbol: String
    let tint: Color
    let url: String
}

private let panels: [Entry] = [
    Entry(id: "dash", title: "Dashboard", subKey: "p.dash", symbol: "chart.bar.xaxis",
          tint: Color(red: 0.95, green: 0.45, blue: 0.30), url: "http://\(Config.domain)/"),
    Entry(id: "incus", title: "Incus", subKey: "p.incus", symbol: "shippingbox.fill",
          tint: Color(red: 0.30, green: 0.55, blue: 0.95), url: "http://incus.\(Config.domain)/"),
    Entry(id: "mihomo", title: "Mihomo", subKey: "p.mihomo", symbol: "bolt.horizontal.fill",
          tint: Color(red: 0.55, green: 0.40, blue: 0.90), url: "http://mihomo.\(Config.domain)/"),
    Entry(id: "git", title: "Git", subKey: "p.git", symbol: "arrow.triangle.branch",
          tint: Color(red: 0.95, green: 0.60, blue: 0.15), url: "http://git.\(Config.domain)/"),
    Entry(id: "nc", title: "Nextcloud", subKey: "p.nc", symbol: "cloud.fill",
          tint: Color(red: 0.20, green: 0.65, blue: 0.85), url: "http://nextcloud.\(Config.domain)/"),
    Entry(id: "hermes", title: "Hermes", subKey: "p.hermes", symbol: "sparkles",
          tint: Color(red: 0.90, green: 0.35, blue: 0.60), url: "http://hermes.\(Config.domain)/"),
]

// MARK: - Root

struct ContentView: View {
    @ObservedObject private var m = TunnelModel.shared
    @ObservedObject private var s = AppSettings.shared
    @State private var openPanels = true
    @State private var openInstances = true
    @State private var openTerminal = true

    var body: some View {
        VStack(spacing: 0) {
            Header(m: m)
            ScrollView {
                VStack(spacing: 0) {
                    Group_(title: L10n.t("group.console"), count: "\(panels.count)", open: $openPanels) {
                        ForEach(panels) { e in
                            ItemRow(symbol: e.symbol, tint: e.tint,
                                    title: e.id == "dash" ? L10n.t("p.dash") : e.title,
                                    subtitle: e.id == "dash" ? Config.domain : L10n.t(e.subKey),
                                    dim: m.base == nil) { m.openPanel(e.url) }
                        }
                    }
                    Group_(title: L10n.t("group.instances"), count: instanceCount, open: $openInstances) {
                        if m.instances.isEmpty {
                            Text(m.base == nil ? L10n.t("inst.offline") : L10n.t("inst.loading"))
                                .font(.ui(size: 11)).foregroundColor(Pal.sub)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 16).padding(.vertical, 8)
                        } else {
                            ForEach(m.instances) { i in InstanceRow(i: i) { if let ip = i.ipv4.first { m.copy(ip) } } }
                        }
                    }
                    Group_(title: L10n.t("group.terminal"), count: nil, open: $openTerminal) {
                        ItemRow(symbol: "terminal.fill", tint: Color(red: 0.22, green: 0.27, blue: 0.36),
                                title: "SSH · \(Config.serverLabel)", subtitle: "\(Config.sshUser)@\(m.base ?? "—")",
                                dim: m.base == nil) { m.openSSH() }
                    }
                }
            }
            .background(Pal.surface)
            Footer(m: m)
        }
        .background(Pal.surface)
        .overlay(alignment: .bottom) {
            if let t = m.toast {
                Text(t)
                    .font(.ui(size: 12, weight: .medium)).foregroundColor(.white)
                    .padding(.horizontal, 14).padding(.vertical, 7)
                    .background(Capsule().fill(Color.black.opacity(0.78)))
                    .padding(.bottom, 48)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }
        .animation(.easeOut(duration: 0.2), value: m.toast)
        .ignoresSafeArea()
        .onAppear { m.start() }
    }

    private var instanceCount: String? {
        m.instances.isEmpty ? nil : "\(m.instances.filter { $0.running }.count)/\(m.instances.count)"
    }
}

// MARK: - Header

/// Blurs whatever is behind the window (needs a non-opaque window, see WindowConfigurator).
private struct VisualEffectBlur: NSViewRepresentable {
    func makeNSView(context: Context) -> NSVisualEffectView {
        let v = NSVisualEffectView()
        v.material = .sidebar
        v.blendingMode = .behindWindow
        v.state = .active
        return v
    }

    func updateNSView(_ nsView: NSVisualEffectView, context: Context) {}
}

private struct Header: View {
    @ObservedObject var m: TunnelModel
    @ObservedObject private var s = AppSettings.shared

    var body: some View {
        ZStack(alignment: .topLeading) {
            // Frosted glass: blurred desktop behind the window, tinted by the state colour
            VisualEffectBlur()
            LinearGradient(colors: [theme.top, theme.bottom], startPoint: .top, endPoint: .bottom)
                .opacity(0.68)
                .animation(.easeOut(duration: 0.3), value: m.phase)
                .animation(.easeOut(duration: 0.3), value: m.atHome)
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 14).fill(Color.white.opacity(0.95))
                        Image(systemName: "point.3.connected.trianglepath.dotted")
                            .font(.ui(size: 26, weight: .semibold)).foregroundColor(theme.accent)
                    }
                    .frame(width: 58, height: 58)
                    .shadow(color: .black.opacity(0.18), radius: 4, y: 2)

                    VStack(alignment: .leading, spacing: 4) {
                        Text(s.displayName).font(.ui(size: 18, weight: .bold)).foregroundColor(.white)
                        HStack(spacing: 6) {
                            Circle().fill(dot).frame(width: 9, height: 9)
                                .overlay(Circle().stroke(Color.white.opacity(0.9), lineWidth: 1.5))
                            Text(statusText).font(.ui(size: 12, weight: .medium)).foregroundColor(.white)
                        }
                        Text(subline).font(.ui(size: 10.5)).foregroundColor(.white.opacity(0.85)).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }

                Button(action: tap) {
                    HStack(spacing: 6) {
                        if busy { ProgressView().controlSize(.small).scaleEffect(0.7) }
                        Text(buttonTitle).font(.ui(size: 13, weight: .semibold))
                    }
                    .frame(maxWidth: .infinity).frame(height: 32)
                    .foregroundColor(theme.accent)
                    .background(Capsule().fill(Color.white))
                    .shadow(color: .black.opacity(0.15), radius: 3, y: 1)
                }
                .buttonStyle(.plain)
                .disabled(busy)

                HStack(spacing: 0) {
                    Stat(title: L10n.t("stat.latency"), value: m.latency.map { "\($0) ms" } ?? "—")
                    Stat(title: L10n.t("stat.down"), value: m.phase == .on ? bytes(m.rxBytes) : "—")
                    Stat(title: L10n.t("stat.up"), value: m.phase == .on ? bytes(m.txBytes) : "—")
                }
            }
            .padding(.horizontal, 16).padding(.top, 40).padding(.bottom, 12)
        }
        .frame(height: 202)
    }

    private var busy: Bool { m.phase == .connecting || m.phase == .disconnecting }
    private var theme: (top: Color, bottom: Color, accent: Color) {
        if m.tunnelUp { return (Pal.redTop, Pal.redBottom, Pal.redAccent) }
        if m.atHome { return (Pal.greenTop, Pal.greenBottom, Pal.greenAccent) }
        return (Pal.grayTop, Pal.grayBottom, Pal.grayAccent)
    }
    private var dot: Color {
        switch m.phase {
        case .on: return Pal.green
        case .connecting, .disconnecting: return Pal.orange
        case .off: return m.atHome ? Pal.green : Pal.gray
        }
    }
    private var statusText: String {
        switch m.phase {
        case .on: return m.latency.map { "\(L10n.t("status.connected")) · \($0) ms" } ?? L10n.t("status.connected")
        case .connecting: return L10n.t("status.connecting")
        case .disconnecting: return L10n.t("status.disconnecting")
        case .off: return m.atHome ? L10n.t("status.home") : L10n.t("status.off")
        }
    }
    private var buttonTitle: String {
        switch m.phase {
        case .on: return L10n.t("btn.disconnect")
        case .connecting: return L10n.t("status.connecting")
        case .disconnecting: return L10n.t("status.disconnecting")
        case .off: return L10n.t("btn.connect")
        }
    }
    private var subline: String {
        guard let sys = m.sys else { return m.base == nil ? L10n.t("sub.idle") : L10n.t("sub.loading") }
        let gb = { (v: Double) in String(format: "%.1f", v / 1_073_741_824) }
        return "\(L10n.t("sub.cpu")) \(String(format: "%.0f", sys.cpuUsage))% · \(L10n.t("sub.mem")) \(gb(sys.memory.used))/\(gb(sys.memory.total))G · \(String(format: "%.0f", sys.temperature ?? 0))°C"
    }

    private func tap() {
        Task { if m.phase == .on { await m.disconnect() } else { await m.connect() } }
    }

    private func bytes(_ n: UInt64) -> String {
        let v = Double(n)
        if v < 1024 { return "\(n) B" }
        if v < 1_048_576 { return String(format: "%.1f KB", v / 1024) }
        if v < 1_073_741_824 { return String(format: "%.1f MB", v / 1_048_576) }
        return String(format: "%.2f GB", v / 1_073_741_824)
    }
}

private struct Stat: View {
    let title: String
    let value: String
    var body: some View {
        VStack(spacing: 1) {
            Text(value).font(.ui(size: 12, weight: .semibold, design: .rounded)).foregroundColor(.white)
            Text(title).font(.ui(size: 9.5)).foregroundColor(.white.opacity(0.8))
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Groups and rows

private struct Group_<Content: View>: View {
    let title: String
    let count: String?
    @Binding var open: Bool
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(spacing: 0) {
            Button {
                withAnimation(.easeInOut(duration: 0.18)) { open.toggle() }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "chevron.right")
                        .font(.ui(size: 9, weight: .bold)).foregroundColor(Pal.sub)
                        .rotationEffect(.degrees(open ? 90 : 0))
                    Text(title).font(.ui(size: 12, weight: .semibold)).foregroundColor(Pal.ink)
                    Spacer()
                    if let c = count { Text(c).font(.ui(size: 11)).foregroundColor(Pal.sub) }
                }
                .padding(.horizontal, 14).frame(height: 32)
                .background(Pal.groupBg)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if open { VStack(spacing: 0) { content() } }
            Divider().background(Pal.line)
        }
    }
}

private struct ItemRow: View {
    let symbol: String
    let tint: Color
    let title: String
    let subtitle: String
    var dim = false
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8).fill(tint)
                    Image(systemName: symbol).font(.ui(size: 13, weight: .semibold)).foregroundColor(.white)
                }
                .frame(width: 32, height: 32)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.ui(size: 13, weight: .medium)).foregroundColor(Pal.ink)
                    Text(subtitle).font(.ui(size: 10.5)).foregroundColor(Pal.sub).lineLimit(1)
                }
                Spacer()
                Image(systemName: "arrow.up.right").font(.ui(size: 9, weight: .bold))
                    .foregroundColor(hover ? Pal.accent : Pal.gray.opacity(0.7))
            }
            .padding(.horizontal, 14).frame(height: 50)
            .background(hover ? Pal.hover : Pal.surface)
            .opacity(dim ? 0.45 : 1)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
    }
}

private struct InstanceRow: View {
    let i: Instance
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Circle().fill(i.running ? Pal.green : Pal.gray).frame(width: 9, height: 9)
                    .frame(width: 32)
                VStack(alignment: .leading, spacing: 1) {
                    Text(i.name).font(.ui(size: 13, weight: .medium)).foregroundColor(Pal.ink)
                    Text(i.ipv4.first ?? (i.running ? L10n.t("inst.noaddr") : L10n.t("inst.stopped")))
                        .font(.ui(size: 10.5, design: .monospaced)).foregroundColor(Pal.sub)
                }
                Spacer()
                Text(i.type == "virtual-machine" ? "VM" : L10n.t("inst.container"))
                    .font(.ui(size: 9.5, weight: .semibold)).foregroundColor(Pal.sub)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(RoundedRectangle(cornerRadius: 4).fill(Pal.line))
            }
            .padding(.horizontal, 14).frame(height: 46)
            .background(hover ? Pal.hover : Pal.surface)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
        .help(i.ipv4.first.map { "\(L10n.t("inst.copyhelp")) \($0)" } ?? "")
    }
}

// MARK: - Footer

private struct Footer: View {
    @ObservedObject var m: TunnelModel
    @ObservedObject private var s = AppSettings.shared

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "network").font(.ui(size: 12)).foregroundColor(Pal.sub)
            Text(m.phase == .on ? "10.77.0.3 · \(L10n.t("foot.up"))" : L10n.t("foot.down"))
                .font(.ui(size: 10.5)).foregroundColor(Pal.sub)
            Spacer()
            Button { NSApp.terminate(nil) } label: {
                Image(systemName: "power").font(.ui(size: 12, weight: .semibold)).foregroundColor(Pal.sub)
            }
            .buttonStyle(.plain)
            .help(L10n.t("foot.quit"))
        }
        .padding(.horizontal, 14).frame(height: 38)
        .background(Pal.footerBg)
        .overlay(alignment: .top) { Rectangle().fill(Pal.line).frame(height: 1) }
    }
}
