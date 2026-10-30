import SwiftUI

/// What the orb is doing: idle (a blink now and then), thinking (eyes drift up and around, a glow pulses), working (eyes
/// scan left and right), answering (happy half-closed eyes, a light bounce).
enum OrbMood { case idle, thinking, working, answering }

/// The bot's face: a gradient sphere with two slanted, round-ended slot eyes, as on the phone and in the app icon.
/// Only a busy orb redraws every frame; an idle one blinks now and then and is otherwise still.
struct Orb: View {
    var color: Color
    var size: CGFloat
    var mood: OrbMood = .idle
    /// Lets an idle orb blink and fidget (the big orb in an empty chat); keep it off in lists.
    var alive = false

    @State private var blink = false
    @State private var hop = false

    var body: some View {
        Group {
            if mood == .idle {
                Canvas { ctx, sz in draw(ctx, sz, t: 0) }
            } else {
                TimelineView(.animation) { tl in
                    Canvas { ctx, sz in draw(ctx, sz, t: tl.date.timeIntervalSinceReferenceDate) }
                }
            }
        }
        .frame(width: size, height: size)
        .offset(y: hop ? -size * 0.12 : 0)
        .task(id: alive && mood == .idle) {
            guard alive && mood == .idle else { return }
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(Double.random(in: 3...6.5)))
                withAnimation(.easeIn(duration: 0.08)) { blink = true }
                try? await Task.sleep(for: .milliseconds(110))
                withAnimation(.easeOut(duration: 0.12)) { blink = false }
                if Int.random(in: 0..<4) == 0 {
                    withAnimation(.spring(duration: 0.28, bounce: 0.5)) { hop = true }
                    try? await Task.sleep(for: .milliseconds(240))
                    withAnimation(.spring(duration: 0.4, bounce: 0.6)) { hop = false }
                }
            }
        }
    }

    private func draw(_ ctx: GraphicsContext, _ sz: CGSize, t: TimeInterval) {
        let r = min(sz.width, sz.height) * 0.44
        var c = CGPoint(x: sz.width / 2, y: sz.height / 2)
        let a = t.truncatingRemainder(dividingBy: 2.4) / 2.4 * 2 * .pi
        var gaze = CGPoint.zero
        var open: CGFloat = blink ? 0.1 : 1
        var glow: CGFloat = 0
        switch mood {
        case .idle: break
        case .thinking:
            gaze = CGPoint(x: 0.16 * sin(a), y: -0.19 + 0.05 * cos(a * 2))
            open = 0.85; glow = 0.45 + 0.2 * sin(a * 2)
            c.y -= r * 0.03 * (1 + sin(a))
        case .working:
            gaze = CGPoint(x: 0.17 * sin(a * 2), y: 0.06)
            open = 0.9; glow = 0.35
        case .answering:
            gaze = CGPoint(x: 0, y: -0.03)
            open = 0.7; glow = 0.12
            c.y -= r * 0.08 * abs(sin(a * 3))
        }
        if glow > 0.01 {
            let g = Gradient(colors: [color.opacity(glow), color.opacity(0)])
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - r * 1.35, y: c.y - r * 1.35, width: r * 2.7, height: r * 2.7)),
                     with: .radialGradient(g, center: c, startRadius: 0, endRadius: r * 1.35))
        }
        let ns = NSColor(color).usingColorSpace(.sRGB) ?? .systemIndigo
        let light = Color(ns.blended(withFraction: 0.30, of: .white) ?? ns)
        let dark = Color(ns.blended(withFraction: 0.30, of: .black) ?? ns)
        ctx.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)),
                 with: .linearGradient(Gradient(colors: [light, dark]), startPoint: CGPoint(x: c.x - r * 0.7, y: c.y - r * 0.8),
                                       endPoint: CGPoint(x: c.x + r * 0.7, y: c.y + r * 0.9)))
        if mood == .thinking || mood == .working {
            let sp = CGPoint(x: c.x + r * 0.5 * cos(a - .pi / 2), y: c.y + r * 0.5 * sin(a - .pi / 2))
            var inner = ctx
            inner.clip(to: Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)))
            inner.fill(Path(ellipseIn: CGRect(x: sp.x - r * 0.75, y: sp.y - r * 0.75, width: r * 1.5, height: r * 1.5)),
                       with: .radialGradient(Gradient(colors: [.white.opacity(0.4), .white.opacity(0)]), center: sp, startRadius: 0, endRadius: r * 0.75))
        }
        let d = r * 2, w = d * 0.115, h = d * 0.33 * max(0.08, open)
        for fx in [0.346, 0.635] {
            let ec = CGPoint(x: c.x - r + d * fx + gaze.x * r, y: c.y - r + d * 0.423 + gaze.y * r)
            var e = ctx
            e.translateBy(x: ec.x, y: ec.y)
            e.rotate(by: .degrees(10))
            e.fill(Path(roundedRect: CGRect(x: -w / 2, y: -h / 2, width: w, height: h), cornerRadius: w / 2), with: .color(.white))
        }
    }
}

extension Bot {
    func mood(streaming: Bool, working: Bool, hasText: Bool) -> OrbMood {
        !streaming ? .idle : working ? .working : hasText ? .answering : .thinking
    }
}
