import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// Where a message is written: files waiting to go, the text, and a row with attach, the model and send / stop.
/// Return sends, Shift-Return makes a new line; files can be dropped on it or pasted.
struct Composer: View {
    @Environment(AppModel.self) private var app
    @Binding var input: String
    @FocusState private var focused: Bool
    @State private var picking = false
    @State private var dropping = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if !app.pending.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) { ForEach(app.pending) { PendingChip(p: $0) { app.removePending($0) } } }
                }
            }
            TextField(L.f("composer.hint", app.bot.name), text: $input, axis: .vertical)
                .textFieldStyle(.plain).font(.body).lineLimit(1...10)
                .focused($focused)
                .onKeyPress(.return, phases: .down) { press in
                    if press.modifiers.contains(.shift) { return .ignored }
                    send(); return .handled
                }
                .onPasteCommand(of: [.fileURL, .image, .png, .tiff, .plainText]) { providers in paste(providers) }
            HStack(spacing: 8) {
                Button { picking = true } label: {
                    Image(systemName: "plus").font(.callout.weight(.semibold)).frame(width: 30, height: 30)
                        .background(Color.primary.opacity(0.07), in: Circle()).contentShape(Circle())
                }
                .buttonStyle(.plain).help(L.t("composer.attach"))
                ModelMenu()
                    .padding(.horizontal, 10).frame(height: 30)
                    .background(Color.primary.opacity(0.07), in: Capsule())
                Spacer()
                if app.streaming {
                    Button(action: app.stop) { Image(systemName: "stop.fill").font(.callout).frame(width: 30, height: 30) }
                        .buttonStyle(RoundButton(color: .primary)).help(L.t("composer.stop"))
                } else {
                    let empty = input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !app.pending.contains { $0.state == .ready }
                    Button(action: send) { Image(systemName: "arrow.up").font(.callout.weight(.bold)).frame(width: 30, height: 30) }
                        .buttonStyle(RoundButton(color: empty ? Color.secondary.opacity(0.5) : app.bot.tint, glow: !empty))
                        .disabled(empty || !app.canSend).help(L.t("composer.send"))
                }
            }
        }
        .padding(.horizontal, 14).padding(.top, 12).padding(.bottom, 8)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous)
            .strokeBorder(dropping ? accentInk(app.bot.tint) : focused ? app.bot.tint.opacity(0.55) : Color.primary.opacity(0.08), lineWidth: dropping ? 2 : 1))
        // a soft light in the bot's colour while you write
        .shadow(color: focused && !Theme.classic ? app.bot.tint.opacity(0.28) : .black.opacity(0.08), radius: focused && !Theme.classic ? 18 : 12, y: 4)
        .animation(.smooth(duration: 0.25), value: focused)
        .onDrop(of: [.fileURL], isTargeted: $dropping) { providers in paste(providers); return true }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.item], allowsMultipleSelection: true) { r in
            if case .success(let urls) = r { app.attach(urls) }
        }
        .onAppear { focused = true }
        .onChange(of: app.conversationID) { focused = true }
    }

    private func send() {
        guard !app.streaming, app.canSend else { return }
        app.send(input)
        input = ""
    }

    private func paste(_ providers: [NSItemProvider]) {
        for p in providers {
            if p.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
                _ = p.loadObject(ofClass: URL.self) { url, _ in if let url { Task { @MainActor in app.attach([url]) } } }
            } else if p.canLoadObject(ofClass: NSImage.self) {
                _ = p.loadObject(ofClass: NSImage.self) { img, _ in
                    if let img = img as? NSImage { Task { @MainActor in app.attach(image: img) } }
                }
            } else if p.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                _ = p.loadObject(ofClass: String.self) { s, _ in if let s { Task { @MainActor in input += s } } }
            }
        }
    }
}

private struct PendingChip: View {
    var p: Pending
    var onRemove: (String) -> Void
    var body: some View {
        HStack(spacing: 6) {
            switch p.state {
            case .working: ProgressView().controlSize(.mini)
            case .ready:
                if let a = p.attachment, a.kind == .image, let img = NSImage(contentsOfFile: a.local) {
                    Image(nsImage: img).resizable().aspectRatio(contentMode: .fill).frame(width: 22, height: 22).clipShape(RoundedRectangle(cornerRadius: 5))
                } else { Image(systemName: "doc") }
            case .failed: Image(systemName: "exclamationmark.triangle").foregroundStyle(.orange)
            }
            Text(p.name).lineLimit(1).frame(maxWidth: 160)
            if case .failed(let why) = p.state { Text(why).foregroundStyle(.secondary) }
            Button { onRemove(p.id) } label: { Image(systemName: "xmark.circle.fill") }.buttonStyle(.borderless).foregroundStyle(.secondary)
        }
        .font(.callout).padding(.horizontal, 10).padding(.vertical, 5).background(Color.primary.opacity(0.07), in: Capsule())
    }
}

struct RoundButton: ButtonStyle {
    var color: Color
    var glow = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.foregroundStyle(.white)
            .background(LinearGradient(colors: [color, color.mix(.purple, glow ? 0.3 : 0)], startPoint: .topLeading, endPoint: .bottomTrailing), in: Circle())
            .shadow(color: glow ? color.opacity(0.4) : .clear, radius: 6, y: 2)
            .scaleEffect(configuration.isPressed ? 0.9 : 1).animation(.spring(duration: 0.2, bounce: 0.5), value: configuration.isPressed)
    }
}

/// The model and thinking level for this chat; the bot's own default comes first.
struct ModelMenu: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        Menu {
            Button {
                app.setPick(ModelPick())
            } label: {
                Label(L.t("model.default") + (app.bot.pick.isDefault ? "" : " (\(app.catalog.label(app.bot.pick)))"), systemImage: app.pick.isDefault ? "checkmark" : "")
            }
            ForEach(app.catalog.providers) { prov in
                Section(prov.name) {
                    ForEach(prov.models) { m in
                        if m.efforts.isEmpty {
                            pickButton(m.name, ModelPick(provider: prov.id, model: m.id))
                        } else {
                            Menu(m.name) {
                                ForEach(m.efforts, id: \.self) { e in pickButton(L.t("effort.\(e)"), ModelPick(provider: prov.id, model: m.id, reasoning: e)) }
                            }
                        }
                    }
                }
            }
        } label: {
            Text(app.catalog.label(app.effectivePick)).font(.callout)
        }
        .menuStyle(.borderlessButton).fixedSize()
        .help(L.t("model.title"))
    }

    private func pickButton(_ title: String, _ p: ModelPick) -> some View {
        Button { app.setPick(p) } label: { Label(title, systemImage: app.pick == p ? "checkmark" : "") }
    }
}
