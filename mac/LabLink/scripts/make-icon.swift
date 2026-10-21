// Renders AppIcon.iconset (blue gradient squircle + white network glyph). Usage: swift make-icon.swift <out.iconset>
import AppKit

let out = CommandLine.arguments[1]
try? FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)

func render(_ px: Int) -> Data {
    let s = CGFloat(px)
    let img = NSImage(size: NSSize(width: s, height: s))
    img.lockFocus()
    let rect = NSRect(x: s * 0.06, y: s * 0.06, width: s * 0.88, height: s * 0.88)
    let path = NSBezierPath(roundedRect: rect, xRadius: s * 0.20, yRadius: s * 0.20)
    NSGraphicsContext.current?.saveGraphicsState()
    let shadow = NSShadow()
    shadow.shadowColor = NSColor.black.withAlphaComponent(0.25)
    shadow.shadowBlurRadius = s * 0.03
    shadow.shadowOffset = NSSize(width: 0, height: -s * 0.015)
    shadow.set()
    NSColor.white.setFill(); path.fill()
    NSGraphicsContext.current?.restoreGraphicsState()
    NSGradient(starting: NSColor(red: 0.12, green: 0.55, blue: 0.93, alpha: 1),
               ending: NSColor(red: 0.40, green: 0.80, blue: 1.0, alpha: 1))!.draw(in: path, angle: 90)
    let cfg = NSImage.SymbolConfiguration(pointSize: s * 0.46, weight: .semibold)
        .applying(NSImage.SymbolConfiguration(paletteColors: [.white]))
    if let sym = NSImage(systemSymbolName: "point.3.connected.trianglepath.dotted", accessibilityDescription: nil)?
        .withSymbolConfiguration(cfg) {
        let w = sym.size.width, h = sym.size.height
        sym.draw(in: NSRect(x: (s - w) / 2, y: (s - h) / 2, width: w, height: h))
    }
    img.unlockFocus()
    let rep = NSBitmapImageRep(data: img.tiffRepresentation!)!
    return rep.representation(using: .png, properties: [:])!
}

for (name, px) in [("16", 16), ("16@2x", 32), ("32", 32), ("32@2x", 64), ("128", 128), ("128@2x", 256),
                   ("256", 256), ("256@2x", 512), ("512", 512), ("512@2x", 1024)] {
    let base = name.replacingOccurrences(of: "@2x", with: "")
    let file = name.contains("@2x") ? "icon_\(base)x\(base)@2x.png" : "icon_\(base)x\(base).png"
    try! render(px).write(to: URL(fileURLWithPath: "\(out)/\(file)"))
}
