import SwiftUI

/// The first thing a new install shows, instead of an error: the orb says hello, then two short steps — the bot
/// manager key (the same one as on the phone) and, when this Mac does not know the server's name yet, the one line for
/// /etc/hosts — and a button that tries the connection.
struct SetupView: View {
    @Environment(AppModel.self) private var app
    @State private var key = ""
    @State private var url = ""
    @State private var testing = false
    @State private var showAddress = false

    var body: some View {
        let needsName = app.serverError?.isNameMissing == true
        ZStack {
            Aurora(color: Bot.lab.tint).ignoresSafeArea()
            ScrollView {
                VStack(spacing: 22) {
                    ZStack {
                        Circle().fill(RadialGradient(colors: [Bot.lab.tint.opacity(0.35), .clear], center: .center, startRadius: 0, endRadius: 90)).frame(width: 180, height: 180)
                        Orb(color: Bot.lab.tint, size: 96, alive: true)
                    }
                    .frame(height: 170)
                    VStack(spacing: 6) {
                        Text(L.t("setup.title")).font(.system(size: 28, weight: .heavy, design: .rounded))
                        Text(L.t("setup.sub")).font(.title3).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    }

                    VStack(alignment: .leading, spacing: 16) {
                        Step(number: 1, title: L.t("setup.key"), done: !app.settings.managerKey.isEmpty) {
                            HStack(spacing: 8) {
                                SecureField(L.t("settings.managerKey"), text: $key)
                                    .textFieldStyle(.plain).padding(.horizontal, 12).frame(height: 34)
                                    .background(Color.primary.opacity(0.06), in: RoundedRectangle(cornerRadius: 10))
                                    .onSubmit(connect)
                            }
                            Text(L.t("settings.keyHint")).font(.caption).foregroundStyle(.secondary)
                            DisclosureGroup(L.t("setup.address"), isExpanded: $showAddress) {
                                TextField(AppSettings.defaultManagerURL, text: $url)
                                    .textFieldStyle(.plain).padding(.horizontal, 12).frame(height: 30)
                                    .background(Color.primary.opacity(0.06), in: RoundedRectangle(cornerRadius: 10))
                                    .padding(.top, 6)
                            }
                            .font(.caption).foregroundStyle(.secondary)
                        }
                        if needsName {
                            Step(number: 2, title: L.t("setup.hosts"), done: false) {
                                Text(L.t("err.hosts")).font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                                HostsLine()
                            }
                        }
                        HStack(spacing: 12) {
                            Button(action: connect) {
                                HStack(spacing: 8) {
                                    if testing { ProgressView().controlSize(.small).tint(.white) }
                                    Text(L.t(testing ? "setup.connecting" : "setup.connect")).fontWeight(.semibold)
                                }
                                .frame(maxWidth: .infinity).frame(height: 38)
                                .foregroundStyle(.white)
                                .background(LinearGradient(colors: [Bot.lab.tint, Bot.lab.tint.mix(.purple, 0.3)], startPoint: .leading, endPoint: .trailing),
                                            in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                .shadow(color: Bot.lab.tint.opacity(0.35), radius: 10, y: 4)
                            }
                            .buttonStyle(.plain).disabled(testing || (key.isEmpty && app.settings.managerKey.isEmpty))
                            .keyboardShortcut(.defaultAction)
                        }
                        if let e = app.serverError, !testing, !app.settings.managerKey.isEmpty, !e.isNameMissing {
                            Label(e.message, systemImage: "exclamationmark.triangle.fill").font(.callout).foregroundStyle(.orange)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .padding(22)
                    .frame(width: 460)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(Color.primary.opacity(0.07)))
                    .shadow(color: .black.opacity(0.12), radius: 24, y: 10)
                }
                .padding(.vertical, 40)
                .frame(maxWidth: .infinity)
            }
        }
        .onAppear { key = app.settings.managerKey; url = app.settings.managerURL == AppSettings.defaultManagerURL ? "" : app.settings.managerURL }
    }

    private func connect() {
        if !key.isEmpty { app.settings.managerKey = key }
        app.settings.managerURL = url.isEmpty ? AppSettings.defaultManagerURL : url
        testing = true
        Task {
            await app.refresh()
            testing = false
        }
    }
}

private struct Step<Content: View>: View {
    var number: Int
    var title: String
    var done: Bool
    @ViewBuilder var content: Content

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                Circle().fill(done ? AnyShapeStyle(Color.green) : AnyShapeStyle(Color.primary.opacity(0.1))).frame(width: 24, height: 24)
                if done { Image(systemName: "checkmark").font(.caption.weight(.bold)).foregroundStyle(.white) }
                else { Text("\(number)").font(.caption.weight(.bold)) }
            }
            VStack(alignment: .leading, spacing: 8) {
                Text(title).font(.headline)
                content
            }
        }
    }
}
