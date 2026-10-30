import SwiftUI

/// The thinking levels in the order they are shown.
let effortOrder = ["none", "low", "medium", "high", "xhigh", "max"]

private let ultraPink = Color(red: 0.94, green: 0.42, blue: 0.66)
private let ultraPurple = Color(red: 0.61, green: 0.36, blue: 0.96)
private let ultraViolet = Color(red: 0.78, green: 0.49, blue: 1.0)
let ultraGradient = LinearGradient(colors: [ultraViolet, ultraPurple], startPoint: .leading, endPoint: .trailing)

/// Each level has its colour, from cool and quick to hot and deep (the top one is the ultra gradient).
private func effortColor(_ e: String) -> Color {
    switch e {
    case "none": Color(red: 0.54, green: 0.58, blue: 0.65)
    case "low": Color(red: 0.31, green: 0.70, blue: 0.91)
    case "medium": ultraPink
    case "high": Color(red: 1.0, green: 0.54, blue: 0.36)
    case "xhigh": Color(red: 0.91, green: 0.36, blue: 0.46)
    case "max": ultraPurple
    default: .secondary
    }
}

extension Catalog.Model {
    /// The deepest level this model has, shown as "Ultra".
    var top: String { effortOrder.last { efforts.contains($0) } ?? "" }
}

extension Catalog {
    func find(_ p: ModelPick) -> Model? { providers.first { $0.id == p.provider }?.models.first { $0.id == p.model } }
}

/// The composer's model button: "⚡ GPT-6.1 Terra Ultra" (Fast, at the model's deepest level, "Ultra" in its gradient).
/// A click opens the picker card.
struct ModelButton: View {
    @Environment(AppModel.self) private var app
    @Binding var open: Bool

    var body: some View {
        let p = app.effectivePick
        let m = app.catalog.find(p)
        let isUltra = m != nil && (m!.efforts.count > 1) && p.reasoning == m!.top
        Button { withAnimation(.spring(duration: 0.32, bounce: 0.3)) { open.toggle() } } label: {
            HStack(spacing: 4) {
                if p.fast && m?.fast == true { Image(systemName: "bolt.fill").foregroundStyle(.orange).font(.caption) }
                Text(p.isDefault ? L.t("model.default") : (m?.name ?? p.model)).foregroundStyle(.secondary)
                if isUltra { Text("Ultra").fontWeight(.bold).foregroundStyle(ultraGradient) }
                else if !p.reasoning.isEmpty, !p.isDefault { Text(L.t("effort.\(p.reasoning)")).foregroundStyle(.secondary) }
                Image(systemName: "chevron.down").font(.caption2.weight(.bold)).foregroundStyle(.tertiary)
            }
            .font(.callout)
            .padding(.horizontal, 10).frame(height: 30)
            .background(Color.primary.opacity(0.07), in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .help(L.t("model.title"))
    }
}

/// The picker, as on the phone: Fast on the left (for models that have it), the level and the model in the middle (a click
/// lists the models), reset on the right, and a stepped slider of levels underneath.
struct ModelPickerCard: View {
    @Environment(AppModel.self) private var app
    @State private var listing = false

    var body: some View {
        ZStack {
            if listing {
                ModelList(onDone: { withAnimation(.spring(duration: 0.35, bounce: 0.15)) { listing = false } })
                    .transition(.move(edge: .trailing).combined(with: .opacity))
            } else {
                level.transition(.move(edge: .leading).combined(with: .opacity))
            }
        }
        .clipped()
        .padding(10)
        .frame(width: 360)
        // the app's own floating card (not the system popover): blur, a hairline and a soft shadow
        .background {
            let shape = RoundedRectangle(cornerRadius: 28, style: .continuous)
            if Theme.classic { shape.fill(Color(nsColor: .controlBackgroundColor)) } else { shape.fill(.regularMaterial) }
        }
        .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).strokeBorder(Color.primary.opacity(0.08)))
        .shadow(color: .black.opacity(0.22), radius: 24, y: 10)
    }

    @ViewBuilder private var level: some View {
        let cur = app.pick.isDefault ? app.bot.pick : app.pick
        let m = app.catalog.find(cur)
        let steps = [""] + effortOrder.filter { m?.efforts.contains($0) == true }
        let index = max(0, steps.firstIndex(of: cur.reasoning) ?? 0)
        let ultra = m != nil && steps.count > 2 && index == steps.count - 1
        let canFast = m?.fast == true
        VStack(spacing: 12) {
            HStack(alignment: .center) {
                Button { if canFast { var p = cur; p.fast.toggle(); app.setPick(p) } } label: {
                    Image(systemName: cur.fast && canFast ? "bolt.fill" : "bolt").font(.title3)
                        .foregroundStyle(cur.fast && canFast ? Color.orange : Color.secondary.opacity(canFast ? 1 : 0.35))
                        .frame(width: 34, height: 34).contentShape(Rectangle())
                }
                .buttonStyle(.plain).disabled(!canFast).help(L.t(canFast ? "model.fast" : "model.noFast"))
                Spacer()
                Button { withAnimation(.spring(duration: 0.35, bounce: 0.15)) { listing = true } } label: {
                    VStack(spacing: 2) {
                        Group {
                            if cur.isDefault { Text(L.t("model.default")).foregroundStyle(.primary) }
                            else if ultra { Text("Ultra").foregroundStyle(ultraGradient) }
                            else { Text(L.t(cur.reasoning.isEmpty ? "effort.auto" : "effort.\(cur.reasoning)")).foregroundStyle(effortColor(cur.reasoning)) }
                        }
                        .font(.system(.title3, design: .rounded, weight: .bold))
                        HStack(spacing: 2) {
                            Text(cur.isDefault ? L.t("model.defaultSub") : (m?.name ?? cur.model)).font(.callout).foregroundStyle(.secondary).lineLimit(1)
                            Image(systemName: "chevron.right").font(.caption2.weight(.bold)).foregroundStyle(.tertiary)
                        }
                    }
                    .padding(.horizontal, 10).padding(.vertical, 4).contentShape(RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                Spacer()
                Button { app.setPick(ModelPick()) } label: {
                    Image(systemName: "arrow.counterclockwise").font(.title3).foregroundStyle(.secondary).frame(width: 34, height: 34).contentShape(Rectangle())
                }
                .buttonStyle(.plain).disabled(app.pick.isDefault).help(L.t("model.reset"))
            }
            EffortSlider(steps: steps.count, index: index, color: effortColor(steps[index]), ultra: ultra, enabled: steps.count > 1) { i in
                var p = cur; p.reasoning = steps[i]; app.setPick(p)
            }
            .frame(height: 30)
        }
        .padding(10)
        .background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

/// The models, by provider, as rows in the card (no system menu): the bot's own default first, a tick on the chosen one,
/// a bolt on the ones with a fast mode.
private struct ModelList: View {
    @Environment(AppModel.self) private var app
    var onDone: () -> Void

    var body: some View {
        let cur = app.pick.isDefault ? app.bot.pick : app.pick
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Button(action: onDone) { Image(systemName: "chevron.left").font(.callout.weight(.bold)).frame(width: 28, height: 28).contentShape(Rectangle()) }
                    .buttonStyle(.plain)
                Text(L.t("model.title")).font(.system(.headline, design: .rounded))
                Spacer()
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 2) {
                    Row(title: L.t("model.default"), subtitle: L.t("model.defaultSub"), selected: app.pick.isDefault, fast: false) {
                        app.setPick(ModelPick()); onDone()
                    }
                    ForEach(app.catalog.providers) { prov in
                        Text(prov.name).font(.caption.weight(.semibold)).foregroundStyle(.tertiary).padding(.leading, 10).padding(.top, 8)
                        ForEach(prov.models) { mo in
                            Row(title: mo.name, subtitle: nil, selected: !app.pick.isDefault && mo.id == cur.model && prov.id == cur.provider, fast: mo.fast) {
                                // a new model keeps the level when it has it, else starts at its default
                                let r = mo.efforts.contains(cur.reasoning) ? cur.reasoning : ""
                                app.setPick(ModelPick(provider: prov.id, model: mo.id, reasoning: r, fast: cur.fast && mo.fast))
                                onDone()
                            }
                        }
                    }
                }
            }
            .frame(maxHeight: 280)
        }
        .padding(8)
    }

    private struct Row: View {
        var title: String
        var subtitle: String?
        var selected: Bool
        var fast: Bool
        var action: () -> Void
        @State private var hover = false

        var body: some View {
            Button(action: action) {
                HStack(spacing: 8) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(title).font(.callout.weight(selected ? .semibold : .regular))
                        if let subtitle { Text(subtitle).font(.caption).foregroundStyle(.secondary) }
                    }
                    Spacer()
                    if fast { Image(systemName: "bolt.fill").font(.caption).foregroundStyle(.orange.opacity(0.8)).help(L.t("model.fast")) }
                    if selected { Image(systemName: "checkmark").font(.caption.weight(.bold)).foregroundStyle(accentInk(Bot.lab.tint)) }
                }
                .padding(.horizontal, 10).padding(.vertical, 7)
                .background(Color.primary.opacity(selected ? 0.08 : hover ? 0.05 : 0), in: RoundedRectangle(cornerRadius: 10))
                .contentShape(RoundedRectangle(cornerRadius: 10))
            }
            .buttonStyle(.plain).onHover { hover = $0 }
        }
    }
}

/// A stepped track: dots mark the levels, the fill runs up to a white knob. Click or drag; it snaps to the nearest level.
/// At the top level the fill turns into a flowing gradient with sparks drifting through it.
private struct EffortSlider: View {
    var steps: Int
    var index: Int
    var color: Color
    var ultra: Bool
    var enabled: Bool
    var onChange: (Int) -> Void

    var body: some View {
        GeometryReader { g in
            let h = g.size.height, r = h / 2, w = g.size.width
            let pos = steps <= 1 ? 0 : CGFloat(index) / CGFloat(steps - 1)
            let knobX = r + (w - 2 * r) * pos
            ZStack(alignment: .leading) {
                Capsule().fill(Color.primary.opacity(0.08))
                // the levels
                ForEach(0..<max(steps, 1), id: \.self) { i in
                    Circle().fill(Color.primary.opacity(0.3)).frame(width: 5, height: 5)
                        .position(x: r + (w - 2 * r) * (steps <= 1 ? 0 : CGFloat(i) / CGFloat(steps - 1)), y: r)
                }
                fill.frame(width: min(knobX + r, w)).clipShape(Capsule())
                Circle().fill(.white).shadow(color: .black.opacity(0.25), radius: 3, y: 1).frame(width: h - 4, height: h - 4).position(x: knobX, y: r)
            }
            .animation(.spring(duration: 0.35, bounce: 0.35), value: index)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { v in
                guard enabled, steps > 1 else { return }
                let f = min(max((v.location.x - r) / (w - 2 * r), 0), 1)
                let i = Int((f * CGFloat(steps - 1)).rounded())
                if i != index { onChange(i) }
            })
            .opacity(enabled ? 1 : 0.4)
        }
    }

    @ViewBuilder private var fill: some View {
        if ultra {
            TimelineView(.animation) { tl in
                let t = tl.date.timeIntervalSinceReferenceDate
                Canvas { ctx, size in
                    let shift = CGFloat(t.truncatingRemainder(dividingBy: 2.6) / 2.6) * size.width * 2
                    ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .linearGradient(
                        Gradient(colors: [ultraPurple, ultraPink, ultraViolet, ultraPurple, ultraPink]),
                        startPoint: CGPoint(x: -size.width * 2 + shift, y: 0), endPoint: CGPoint(x: shift, y: size.height)))
                    // sparks drifting right, twinkling
                    for k in 0..<18 {
                        let seed = Double(k) * 12.9898
                        let phase = (t * (0.15 + 0.1 * sin(seed)) + Double(k) / 18).truncatingRemainder(dividingBy: 1)
                        let x = CGFloat(phase) * size.width
                        let y = size.height * CGFloat(0.2 + 0.6 * abs(sin(seed * 3.1)))
                        let a = 0.5 + 0.5 * sin(t * 4 + seed)
                        ctx.fill(Path(ellipseIn: CGRect(x: x, y: y, width: 2.4, height: 2.4)), with: .color(.white.opacity(0.85 * a)))
                    }
                }
            }
        } else {
            Rectangle().fill(color)
        }
    }
}
