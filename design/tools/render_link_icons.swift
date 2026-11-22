import AppKit

// Renders the Link glyph (design/logo-concepts/link.svg geometry) into:
//  - mac-app/Resources/MenuBarIcon.png (18x18) + MenuBarIcon@2x.png (36x36), black on transparent
//  - mac-app/Resources/Bridge.icon/Assets/glyph-black.png + glyph-white.png (1024x1024)
//  - mac-app/Resources/Bridge.iconset/*.png -> Bridge.icns via `iconutil`
// Usage: swift render_link_icons.swift <repo-root>

let root = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : FileManager.default.currentDirectoryPath
let resDir = "\(root)/mac-app/Resources"

func glyphPath(in rect: CGRect) -> NSBezierPath {
    // SVG viewBox 0 0 128 128. Brackets + center bar, filled.
    let p = NSBezierPath()
    func r(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, _ c: CGFloat) -> NSRect {
        // SVG y grows downward; flip into AppKit coords.
        NSRect(x: rect.minX + x / 128.0 * rect.width,
               y: rect.minY + rect.height - (y + h) / 128.0 * rect.height,
               width: w / 128.0 * rect.width,
               height: h / 128.0 * rect.height)
    }
    func rr(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, _ c: CGFloat) {
        p.appendRoundedRect(r(x, y, w, h, c), xRadius: c / 128.0 * rect.width, yRadius: c / 128.0 * rect.height)
    }
    rr(14, 14, 34, 14, 7)
    rr(14, 51, 14, 26, 7)
    rr(14, 100, 34, 14, 7)
    rr(80, 14, 34, 14, 7)
    rr(100, 51, 14, 26, 7)
    rr(80, 100, 34, 14, 7)
    rr(40, 56, 48, 16, 8)
    return p
}

func pngData(size: Int, draw: (NSGraphicsContext) -> Void) -> Data? {
    guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
                                     bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true,
                                     isPlanar: false, colorSpaceName: .deviceRGB,
                                     bytesPerRow: 0, bitsPerPixel: 0) else { return nil }
    NSGraphicsContext.saveGraphicsState()
    let ctx = NSGraphicsContext(bitmapImageRep: rep)
    NSGraphicsContext.current = ctx
    ctx?.imageInterpolation = .high
    draw(ctx!)
    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])
}

func writePNG(_ data: Data?, to path: String) {
    guard let data else { print("FAIL: no png data for \(path)"); return }
    try? FileManager.default.createDirectory(atPath: (path as NSString).deletingLastPathComponent, withIntermediateDirectories: true)
    try? data.write(to: URL(fileURLWithPath: path))
    print("wrote \(path) (\(data.count) bytes)")
}

// 1. Menu bar icons: black glyph on transparent, template-ready.
for (name, px) in [("MenuBarIcon.png", 18), ("MenuBarIcon@2x.png", 36)] {
    writePNG(pngData(size: px) { _ in
        NSColor.black.setFill()
        glyphPath(in: NSRect(x: 0, y: 0, width: px, height: px)).fill()
    }, to: "\(resDir)/\(name)")
}

// 2. Icon Composer glyph layers: 1024x1024, glyph ~62% centered.
for (name, color) in [("glyph-black.png", NSColor.black), ("glyph-white.png", NSColor.white)] {
    writePNG(pngData(size: 1024) { _ in
        let side: CGFloat = 1024
        let glyphSide = side * 0.62
        let origin = (side - glyphSide) / 2
        color.setFill()
        glyphPath(in: NSRect(x: origin, y: origin, width: glyphSide, height: glyphSide)).fill()
    }, to: "\(resDir)/Bridge.icon/Assets/\(name)")
}

// 3. .iconset for icns: white squircle + black glyph (~58% of canvas).
let iconset = "\(resDir)/Bridge.iconset"
try? FileManager.default.removeItem(atPath: iconset)
try? FileManager.default.createDirectory(atPath: iconset, withIntermediateDirectories: true)
let sizes: [(String, Int)] = [
    ("icon_16x16.png", 16), ("icon_16x16@2x.png", 32), ("icon_32x32.png", 32), ("icon_32x32@2x.png", 64),
    ("icon_128x128.png", 128), ("icon_128x128@2x.png", 256), ("icon_256x256.png", 256),
    ("icon_256x256@2x.png", 512), ("icon_512x512.png", 512), ("icon_512x512@2x.png", 1024)
]
for (name, px) in sizes {
    writePNG(pngData(size: px) { _ in
        let s = CGFloat(px)
        // White squircle (macOS icon shape ~ continuous corner)
        let bg = NSBezierPath(roundedRect: NSRect(x: 0, y: 0, width: s, height: s),
                              xRadius: s * 0.225, yRadius: s * 0.225)
        NSColor.white.setFill()
        bg.fill()
        // Black glyph centered at ~58%
        let g = s * 0.58
        let o = (s - g) / 2
        NSColor.black.setFill()
        glyphPath(in: NSRect(x: o, y: o, width: g, height: g)).fill()
    }, to: "\(iconset)/\(name)")
}
print("done")
