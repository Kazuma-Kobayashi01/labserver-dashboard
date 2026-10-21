import AppKit
import SwiftUI

// MARK: - Localization (English / 中文 / 日本語), switchable at runtime

enum Lang: String { case en, zh, ja }

enum L10n {
    /// Effective language: the user's choice, or the system language when set to "system".
    static var lang: Lang {
        let pref = UserDefaults.standard.string(forKey: "language") ?? "system"
        if let l = Lang(rawValue: pref) { return l }
        let first = Locale.preferredLanguages.first ?? "en"
        if first.hasPrefix("zh") { return .zh }
        if first.hasPrefix("ja") { return .ja }
        return .en
    }

    static func t(_ key: String) -> String {
        guard let row = table[key] else { return key }
        switch lang {
        case .en: return row.0
        case .zh: return row.1
        case .ja: return row.2
        }
    }

    // key: (English, 中文, 日本語)
    private static let table: [String: (String, String, String)] = [
        // header / status
        "status.connected": ("Connected", "已连接", "接続済み"),
        "status.connecting": ("Connecting…", "连接中…", "接続中…"),
        "status.disconnecting": ("Disconnecting…", "断开中…", "切断中…"),
        "status.home": ("At home · LAN", "在家 · 局域网直连", "自宅 · LAN 直結"),
        "status.off": ("Not connected", "未连接", "未接続"),
        "btn.connect": ("Connect to home", "连接家里", "自宅に接続"),
        "btn.disconnect": ("Disconnect", "断开连接", "切断"),
        "stat.latency": ("Latency", "延迟", "遅延"),
        "stat.down": ("Down", "下行", "受信"),
        "stat.up": ("Up", "上行", "送信"),
        "sub.idle": ("Tunnel 10.77.0.3 → 10.0.0.0/24", "隧道 10.77.0.3 → 10.0.0.0/24", "トンネル 10.77.0.3 → 10.0.0.0/24"),
        "sub.loading": ("Reading…", "读取中…", "読み込み中…"),
        "sub.cpu": ("CPU", "CPU", "CPU"),
        "sub.mem": ("Mem", "内存", "メモリ"),
        // groups
        "group.console": ("Console", "控制台", "コンソール"),
        "group.instances": ("Instances", "实例", "インスタンス"),
        "group.terminal": ("Terminal", "终端", "ターミナル"),
        // panels
        "p.dash": ("Dashboard", "仪表盘", "ダッシュボード"),
        "p.incus": ("Containers & VMs", "容器与虚拟机", "コンテナと VM"),
        "p.mihomo": ("Proxy & rules", "代理与规则", "プロキシとルール"),
        "p.git": ("Forgejo code hosting", "Forgejo 代码托管", "Forgejo コードホスティング"),
        "p.nc": ("Cloud drive", "网盘", "クラウドドライブ"),
        "p.hermes": ("Agent dashboard", "智能体面板", "エージェントパネル"),
        // instances
        "inst.loading": ("Loading…", "加载中…", "読み込み中…"),
        "inst.offline": ("Not connected", "未连接", "未接続"),
        "inst.stopped": ("Stopped", "已停止", "停止中"),
        "inst.noaddr": ("No address", "无地址", "アドレスなし"),
        "inst.container": ("Container", "容器", "コンテナ"),
        "inst.copyhelp": ("Click to copy", "点击复制", "クリックでコピー"),
        // footer
        "foot.up": ("WireGuard", "WireGuard", "WireGuard"),
        "foot.down": ("WireGuard is down", "WireGuard 未建立", "WireGuard 未確立"),
        "foot.quit": ("Quit LabLink (the tunnel stays up)", "退出 LabLink(不会断开隧道)", "LabLink を終了(トンネルは維持)"),
        // toasts / prompts
        "toast.needconn": ("Connect the tunnel or get back home first", "先连接隧道或回到家里", "先にトンネルに接続するか自宅に戻ってください"),
        "toast.copied": ("Copied", "已复制", "コピーしました"),
        "toast.cancelled": ("Cancelled", "已取消", "キャンセルしました"),
        "toast.connfail": ("Connect failed", "连接失败", "接続に失敗しました"),
        "toast.discfail": ("Disconnect failed", "断开失败", "切断に失敗しました"),
        "auth.connect": ("LabLink needs administrator rights to bring up the tunnel", "LabLink 需要管理员权限来建立隧道", "トンネルの確立に管理者権限が必要です"),
        "auth.disconnect": ("LabLink needs administrator rights to take down the tunnel", "LabLink 需要管理员权限来断开隧道", "トンネルの切断に管理者権限が必要です"),
        // settings
        "set.title": ("Settings", "设置", "設定"),
        "set.appearance": ("Appearance", "外观", "外観"),
        "set.auto": ("Auto", "自动", "自動"),
        "set.dark": ("Dark", "深色", "ダーク"),
        "set.light": ("Light", "浅色", "ライト"),
        "set.language": ("Language", "语言", "言語"),
        "set.system": ("Follow system", "跟随系统", "システムに従う"),
        "set.auth": ("Authorization", "授权方式", "認証方式"),
        "set.authmode": ("Confirm with", "确认方式", "確認方法"),
        "set.password": ("Password each time", "每次输入密码", "毎回パスワード"),
        "set.touch": ("Touch ID", "指纹 (Touch ID)", "Touch ID"),
        "set.installed": ("Touch ID component: installed", "指纹组件:已安装", "Touch ID コンポーネント: インストール済み"),
        "set.notinstalled": ("Touch ID component: not installed", "指纹组件:未安装", "Touch ID コンポーネント: 未インストール"),
        "set.install": ("Install…", "安装…", "インストール…"),
        "set.uninstall": ("Remove…", "卸载…", "削除…"),
        "set.authnote": ("Installing asks for your password once. It adds a root-owned helper and a sudo rule that allows only “up” and “down”; every connect or disconnect is then confirmed with your fingerprint.",
                         "安装时需要输入一次密码,会装入归 root 所有的助手和只允许“up”“down”两个命令的 sudo 规则;之后每次连接或断开都用指纹确认。",
                         "インストール時にパスワードを一度入力します。root 所有のヘルパーと「up」「down」のみ許可する sudo ルールが追加され、以降の接続・切断は指紋で確認します。"),
        "auth.touch.connect": ("Connect LabLink to your home network", "连接家里的网络", "自宅ネットワークに接続"),
        "auth.touch.disconnect": ("Disconnect LabLink from your home network", "断开与家里网络的连接", "自宅ネットワークから切断"),
        "auth.install": ("LabLink needs administrator rights to install the Touch ID component", "LabLink 需要管理员权限来安装指纹授权组件", "Touch ID コンポーネントのインストールに管理者権限が必要です"),
        "auth.uninstall": ("LabLink needs administrator rights to remove the Touch ID component", "LabLink 需要管理员权限来卸载指纹授权组件", "Touch ID コンポーネントの削除に管理者権限が必要です"),
        "toast.installed": ("Touch ID enabled", "已启用指纹授权", "Touch ID を有効にしました"),
        "toast.removed": ("Touch ID component removed", "已卸载指纹组件", "Touch ID コンポーネントを削除しました"),
        "toast.installfail": ("Operation failed", "操作失败", "操作に失敗しました"),
        "set.restartnote": ("Menu bar items follow the system language.", "菜单栏文字跟随系统语言。", "メニューバーはシステム言語に従います。"),
    ]
}

// MARK: - App settings

@MainActor
final class AppSettings: ObservableObject {
    static let shared = AppSettings()

    @Published var appearance: String { didSet { UserDefaults.standard.set(appearance, forKey: "appearance") } }
    @Published var authMode: String { didSet { UserDefaults.standard.set(authMode, forKey: "authMode") } }
    @Published var language: String { didSet { UserDefaults.standard.set(language, forKey: "language") } }

    init() {
        appearance = UserDefaults.standard.string(forKey: "appearance") ?? "auto"
        language = UserDefaults.standard.string(forKey: "language") ?? "system"
        authMode = UserDefaults.standard.string(forKey: "authMode") ?? "password"
    }

    var scheme: ColorScheme? {
        switch appearance {
        case "dark": return .dark
        case "light": return .light
        default: return nil
        }
    }
}

// MARK: - Settings window (⌘,)

struct SettingsView: View {
    @ObservedObject private var s = AppSettings.shared
    @ObservedObject private var m = TunnelModel.shared

    var body: some View {
        Form {
            Picker(L10n.t("set.appearance"), selection: $s.appearance) {
                Text(L10n.t("set.auto")).tag("auto")
                Text(L10n.t("set.dark")).tag("dark")
                Text(L10n.t("set.light")).tag("light")
            }
            .pickerStyle(.segmented)

            Picker(L10n.t("set.language"), selection: $s.language) {
                Text(L10n.t("set.system")).tag("system")
                Text("English").tag("en")
                Text("中文").tag("zh")
                Text("日本語").tag("ja")
            }
            .pickerStyle(.menu)

            Text(L10n.t("set.restartnote"))
                .font(.ui(size: 11)).foregroundStyle(.secondary)

            Section(L10n.t("set.auth")) {
                Picker(L10n.t("set.authmode"), selection: $s.authMode) {
                    Text(L10n.t("set.password")).tag("password")
                    Text(L10n.t("set.touch")).tag("touchid")
                }
                .pickerStyle(.segmented)
                .onChange(of: s.authMode) { _, new in
                    // choosing Touch ID without the component installs it first
                    if new == "touchid" && !m.touchIDInstalled { Task { await m.installTouchID() } }
                }

                HStack {
                    Image(systemName: m.touchIDInstalled ? "checkmark.circle.fill" : "circle.dashed")
                        .foregroundStyle(m.touchIDInstalled ? Color.green : Color.secondary)
                    Text(m.touchIDInstalled ? L10n.t("set.installed") : L10n.t("set.notinstalled"))
                        .font(.ui(size: 12))
                    Spacer()
                    if m.touchIDInstalled {
                        Button(L10n.t("set.uninstall")) { Task { await m.uninstallTouchID() } }
                    } else {
                        Button(L10n.t("set.install")) { Task { await m.installTouchID() } }
                    }
                }
                Text(L10n.t("set.authnote"))
                    .font(.ui(size: 11)).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .formStyle(.grouped)
        .font(.ui(size: 13))
        .frame(width: 440, height: 400)
        .preferredColorScheme(s.scheme)
    }
}

// MARK: - Shared look helpers

extension Font {
    /// One point larger than the nominal size, app-wide.
    static func ui(size: CGFloat, weight: Font.Weight = .regular, design: Font.Design = .default) -> Font {
        .system(size: size + 1, weight: weight, design: design)
    }
}

extension Color {
    init(light: Color, dark: Color) {
        self = Color(nsColor: NSColor(name: nil) { appearance in
            appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? NSColor(dark) : NSColor(light)
        })
    }
}
