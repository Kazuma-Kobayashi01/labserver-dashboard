import SwiftUI

/// Making or changing a bot: a live orb in the picked colour, templates, name, role, prompt, default model, and whether
/// it has a sandbox (chosen once, when it is made; the Coder template asks for one). Lab bot itself is the server's admin
/// and is set up on the server, so only its look can change here.
struct BotEditor: View {
    var initial: Bot?
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var b = Bot(id: "", name: "", color: BotColors.all.randomElement()!, sandboxed: false)
    @State private var saving = false
    @State private var problem: String? = nil
    @State private var confirmTrash = false

    private var isLab: Bool { initial?.id == defaultBotID }
    private var isNew: Bool { initial == nil }

    private struct Preset { var key: String; var color: UInt32; var sandbox: Bool }
    private let presets = [Preset(key: "translator", color: 0xFF3B9BE8, sandbox: false), Preset(key: "coder", color: 0xFF2B2B3A, sandbox: true),
                           Preset(key: "writer", color: 0xFFF2724D, sandbox: false), Preset(key: "buddy", color: 0xFFE8589B, sandbox: false)]

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(L.t(isNew ? "editor.new" : "editor.edit")).font(.title2.weight(.heavy))
                Spacer()
                Button(L.t("cancel")) { dismiss() }.keyboardShortcut(.cancelAction)
                Button(saving ? "…" : L.t("save")) { save() }.keyboardShortcut(.defaultAction).buttonStyle(.borderedProminent)
                    .disabled(saving || (!isLab && b.name.trimmingCharacters(in: .whitespaces).isEmpty))
            }
            .padding(18)
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HStack(spacing: 16) {
                        Orb(color: b.tint, size: 84, alive: true)
                        VStack(alignment: .leading) {
                            Text(b.name.isEmpty ? L.t("editor.new") : b.name).font(.title2.weight(.bold))
                            if !b.role.isEmpty { Text(b.role).foregroundStyle(.secondary) }
                        }
                    }
                    HStack(spacing: 10) {
                        ForEach(BotColors.all, id: \.self) { c in
                            Circle().fill(Color(argb: c)).frame(width: 26, height: 26)
                                .overlay(Circle().strokeBorder(.primary, lineWidth: b.color == c ? 2.5 : 0).padding(-3))
                                .onTapGesture { withAnimation(.snappy) { b.color = c } }
                        }
                    }
                    if let problem { Text(problem).foregroundStyle(.red).font(.callout) }
                    if isLab {
                        Text(L.t("editor.labNote")).font(.callout).foregroundStyle(.secondary)
                    } else {
                        if isNew {
                            Text(L.t("editor.presets")).font(.caption).foregroundStyle(.secondary)
                            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                                ForEach(presets, id: \.key) { p in
                                    Button {
                                        b.name = L.t("preset.\(p.key)"); b.role = L.t("preset.\(p.key).role"); b.systemPrompt = L.t("preset.\(p.key).prompt")
                                        b.color = p.color; b.sandboxed = p.sandbox
                                    } label: {
                                        HStack(spacing: 10) {
                                            Orb(color: Color(argb: p.color), size: 30)
                                            VStack(alignment: .leading) {
                                                Text(L.t("preset.\(p.key)")).fontWeight(.semibold)
                                                Text(L.t("preset.\(p.key).role")).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                            }
                                            Spacer()
                                        }
                                        .padding(10).background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 12)).contentShape(Rectangle())
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                        VStack(alignment: .leading, spacing: 12) {
                            field(L.t("editor.name")) { TextField("", text: $b.name).textFieldStyle(.roundedBorder) }
                            field(L.t("editor.role")) { TextField("", text: $b.role).textFieldStyle(.roundedBorder) }
                            field(L.t("editor.prompt")) {
                                TextEditor(text: $b.systemPrompt).font(.body).frame(minHeight: 110).scrollContentBackground(.hidden)
                                    .padding(6).background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 8))
                                    .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Color.primary.opacity(0.12)))
                                Text(L.t("editor.promptHint")).font(.caption).foregroundStyle(.secondary)
                            }
                            field(L.t("editor.model")) {
                                Picker("", selection: $b.modelPick) {
                                    Text(L.t("model.default")).tag(ModelPick())
                                    ForEach(app.catalog.providers) { prov in
                                        ForEach(prov.models) { m in
                                            if m.efforts.isEmpty {
                                                Text("\(prov.name) · \(m.name)").tag(ModelPick(provider: prov.id, model: m.id))
                                            } else {
                                                ForEach(m.efforts, id: \.self) { e in
                                                    Text("\(prov.name) · \(m.name) · \(L.t("effort.\(e)"))").tag(ModelPick(provider: prov.id, model: m.id, reasoning: e))
                                                }
                                            }
                                        }
                                    }
                                }
                                .labelsHidden().fixedSize()
                            }
                        }
                        // a sandbox: commands, files and a terminal; chosen when the bot is made
                        HStack(alignment: .top, spacing: 12) {
                            Image(systemName: b.sandboxed ? "terminal" : "bubble.left").font(.title3).foregroundStyle(b.sandboxed ? accentInk(b.tint) : .secondary).frame(width: 24)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(L.t("editor.sandbox")).fontWeight(.semibold)
                                Text(L.t(b.sandboxed ? "editor.sandboxOn" : (isNew ? "editor.sandboxOff" : "editor.sandboxNone"))).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if isNew { Toggle("", isOn: $b.sandboxed).toggleStyle(.switch).labelsHidden() }
                        }
                        .padding(12).background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 12))
                        if !isNew {
                            Button(L.t("editor.delete"), role: .destructive) { confirmTrash = true }
                        }
                    }
                }
                .padding(20)
            }
        }
        .frame(width: 560, height: isLab ? 300 : 680)
        .onAppear { if let initial { b = initial } }
        .confirmationDialog(L.t("editor.delete"), isPresented: $confirmTrash) {
            Button(L.t("editor.delete"), role: .destructive) {
                Task {
                    do { try await app.trashBot(b.id); dismiss() } catch let e as ServerError { problem = e.message } catch {}
                }
            }
        }
    }

    private func field<C: View>(_ title: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.callout.weight(.medium))
            content()
        }
    }

    private func save() {
        if isLab {
            // the admin bot's look is kept on this Mac
            if let i = app.bots.firstIndex(where: { $0.id == defaultBotID }) { app.bots[i].color = b.color }
            app.bot.color = b.color
            UserDefaults.standard.set(Int(b.color), forKey: "labColor")
            dismiss(); return
        }
        saving = true
        problem = nil
        Task {
            do {
                if isNew { try await app.createBot(b) } else { try await app.updateBot(b) }
                dismiss()
            } catch let e as ServerError { problem = e.message } catch { problem = error.localizedDescription }
            saving = false
        }
    }
}

extension Bot {
    var modelPick: ModelPick {
        get { pick }
        set { modelProvider = newValue.provider; modelId = newValue.model; reasoning = newValue.reasoning }
    }
}

/// ⌘, : two tabs, as Mac apps have them — the server (address, key, a connection test) and the conversation display
/// (four levels, with what each one shows, and Markdown).
struct SettingsView: View {
    var body: some View {
        TabView {
            ServerSettings().tabItem { Label(L.t("settings.server"), systemImage: "server.rack") }
            DisplaySettings().tabItem { Label(L.t("settings.display"), systemImage: "text.bubble") }
        }
        .frame(width: 540)
    }
}

private struct ServerSettings: View {
    @Environment(AppModel.self) private var app
    @State private var key = ""
    @State private var url = ""
    @State private var testing = false
    @State private var result: (ok: Bool, text: String)? = nil

    var body: some View {
        Form {
            Section {
                TextField(L.t("settings.managerURL"), text: $url, prompt: Text(AppSettings.defaultManagerURL))
                SecureField(L.t("settings.managerKey"), text: $key)
            } footer: {
                Text(L.t("settings.keyHint")).font(.caption).foregroundStyle(.secondary)
            }
            Section {
                HStack {
                    Button(L.t("settings.test")) { apply(); test() }.disabled(testing)
                    if testing { ProgressView().controlSize(.small) }
                    if let result {
                        Label(result.text, systemImage: result.ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                            .foregroundStyle(result.ok ? .green : .orange).font(.callout)
                    }
                    Spacer()
                    Button(L.t("save")) { apply() }.keyboardShortcut(.defaultAction)
                }
                if app.serverError?.isNameMissing == true { HostsLine() }
            }
        }
        .formStyle(.grouped)
        .frame(height: 300)
        .onAppear { key = app.settings.managerKey; url = app.settings.managerURL == AppSettings.defaultManagerURL ? "" : app.settings.managerURL }
    }

    private func apply() {
        app.settings.managerURL = url.isEmpty ? AppSettings.defaultManagerURL : url
        app.settings.managerKey = key
    }

    private func test() {
        testing = true
        result = nil
        Task {
            await app.refresh()
            if let e = app.serverError { result = (false, e.isNameMissing ? L.t("setup.hosts") : e.message) }
            else { result = (true, L.f("settings.ok", app.bots.count - 1)) }
            testing = false
        }
    }
}

private struct DisplaySettings: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        @Bindable var s = app.settings
        Form {
            Section {
                Picker(L.t("settings.display"), selection: $s.detail) {
                    ForEach(ChatDetail.allCases) { Text(L.t("detail.\($0.rawValue)")).tag($0) }
                }
                .pickerStyle(.segmented)
                Text(L.t("detail.\(s.detail.rawValue).hint")).font(.callout).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Section {
                Picker(L.t("settings.theme"), selection: $s.theme) {
                    Text("Modern").tag("modern")
                    Text("Classic").tag("classic")
                }
                .pickerStyle(.segmented)
                Text(L.t(s.theme == "classic" ? "theme.classic.hint" : "theme.modern.hint")).font(.callout).foregroundStyle(.secondary)
            }
            Section {
                Toggle(L.t("settings.markdown"), isOn: $s.markdown)
                Picker(L.t("settings.appearance"), selection: $s.appearance) {
                    Text(L.t("appearance.system")).tag("system")
                    Text(L.t("appearance.light")).tag("light")
                    Text(L.t("appearance.dark")).tag("dark")
                }
            }
        }
        .formStyle(.grouped)
        .frame(height: 360)
    }
}
