// Renders AppIcon.iconset: the Lab bot face filling the whole tile — a purple squircle (the orb's gradient, a soft light on
// its upper left) with the two slanted, round-ended slot eyes in white, as in the app. Usage: swift make-icon.swift <out.iconset>
import AppKit

let out = CommandLine.arguments[1]
try? FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)

func render(_ px: Int) -> Data {
    let s = CGFloat(px)
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px, bitsPerSample: 8, samplesPerPixel: 4,
                               hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    // the tile on the macOS icon grid (824 of 1024, centred), with the system's soft drop shadow
    let rect = NSRect(x: s * 0.1, y: s * 0.1, width: s * 0.8, height: s * 0.8)
    let tile = NSBezierPath(roundedRect: rect, xRadius: s * 0.18, yRadius: s * 0.18)
    NSGraphicsContext.saveGraphicsState()
    let shadow = NSShadow()
    shadow.shadowColor = NSColor.black.withAlphaComponent(0.28)
    shadow.shadowBlurRadius = s * 0.025
    shadow.shadowOffset = NSSize(width: 0, height: -s * 0.012)
    shadow.set()
    NSColor(red: 0.30, green: 0.24, blue: 0.82, alpha: 1).setFill(); tile.fill()
    NSGraphicsContext.restoreGraphicsState()
    NSGradient(colors: [NSColor(red: 0.62, green: 0.55, blue: 1.0, alpha: 1), NSColor(red: 0.40, green: 0.32, blue: 0.93, alpha: 1),
                        NSColor(red: 0.22, green: 0.16, blue: 0.70, alpha: 1)])!.draw(in: tile, angle: -60)
    // a soft light on the upper left, and a darker rim at the bottom, so it reads as a round face
    NSGraphicsContext.saveGraphicsState()
    tile.addClip()
    NSGradient(starting: NSColor.white.withAlphaComponent(0.28), ending: NSColor.white.withAlphaComponent(0))!
        .draw(fromCenter: NSPoint(x: s * 0.32, y: s * 0.72), radius: 0, toCenter: NSPoint(x: s * 0.32, y: s * 0.72), radius: s * 0.42, options: [])
    NSGradient(starting: NSColor.black.withAlphaComponent(0), ending: NSColor.black.withAlphaComponent(0.18))!
        .draw(in: NSRect(x: rect.minX, y: rect.minY, width: rect.width, height: rect.height * 0.35), angle: -90)
    NSGraphicsContext.restoreGraphicsState()
    // the eyes: the app's proportions (w = 0.115 d, h = 0.33 d, 10° slant, at 34.6% / 63.5% across and 42.3% down),
    // with the tile standing in for the orb
    let d = rect.width
    let w = d * 0.115, h = d * 0.33
    for fx in [0.346, 0.635] as [CGFloat] {
        let ec = NSPoint(x: rect.minX + d * fx, y: rect.maxY - d * 0.45)
        let eye = NSBezierPath(roundedRect: NSRect(x: -w / 2, y: -h / 2, width: w, height: h), xRadius: w / 2, yRadius: w / 2)
        var t = AffineTransform(translationByX: ec.x, byY: ec.y)
        t.rotate(byDegrees: -10)
        eye.transform(using: t)
        NSGraphicsContext.saveGraphicsState()
        let glow = NSShadow()
        glow.shadowColor = NSColor(red: 0.15, green: 0.08, blue: 0.5, alpha: 0.35)
        glow.shadowBlurRadius = s * 0.015
        glow.shadowOffset = NSSize(width: 0, height: -s * 0.006)
        glow.set()
        NSColor.white.setFill(); eye.fill()
        NSGraphicsContext.restoreGraphicsState()
    }
    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])!
}

for (name, px) in [("16x16", 16), ("16x16@2x", 32), ("32x32", 32), ("32x32@2x", 64), ("128x128", 128), ("128x128@2x", 256),
                   ("256x256", 256), ("256x256@2x", 512), ("512x512", 512), ("512x512@2x", 1024)] {
    try! render(px).write(to: URL(fileURLWithPath: "\(out)/icon_\(name).png"))
}
