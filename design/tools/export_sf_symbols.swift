import AppKit

// Renders SF Symbols as white-on-transparent PNGs into android-app res/drawable-*dpi/.
// Usage: swift export_sf_symbols.swift <repo-root>
// Symbols are template-drawn in white; tint is applied in XML/code via color tokens.

let root = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : FileManager.default.currentDirectoryPath
let resDir = "\(root)/android-app/app/src/main/res"

// name -> SF Symbol name (nil = draw Link glyph instead)
let symbols: [(file: String, symbol: String?)] = [
    ("sf_house", "house"), ("sf_house_fill", "house.fill"),
    ("sf_folder", "folder"), ("sf_folder_fill", "folder.fill"),
    ("sf_gearshape", "gearshape"),
    ("sf_laptopcomputer", "laptopcomputer"), ("sf_iphone", "iphone"),
    ("sf_wifi", "wifi"), ("sf_bluetooth", "antenna.radiowaves.left.and.right"),
    ("sf_hotspot", "personalhotspot"),
    ("sf_share", "square.and.arrow.up"), ("sf_qrcode", "qrcode.viewfinder"),
    ("sf_chevron_right", "chevron.right"), ("sf_chevron_left", "chevron.left"),
    ("sf_chevron_up", "chevron.up"), ("sf_plus", "plus"),
    ("sf_doc", "doc"), ("sf_doc_fill", "doc.fill"), ("sf_photo", "photo"),
    ("sf_film", "film"), ("sf_music", "music.note"), ("sf_zip", "doc.zipper"),
    ("sf_apk", "shippingbox"), ("sf_check_circle", "checkmark.circle.fill"),
    ("sf_xmark", "xmark"), ("sf_pause", "pause.fill"), ("sf_play", "play.fill"),
    ("sf_bell", "bell"), ("sf_bell_slash", "bell.slash"), ("sf_bell_badge", "bell.badge.fill"),
    ("sf_warning", "exclamationmark.triangle.fill"),
    ("sf_link", "link"),
    ("sf_arrow_up_doc", "arrow.up.doc"), ("sf_arrow_down_doc", "arrow.down.doc.fill"),
    ("sf_tray", "tray"), ("sf_eye", "eye"), ("sf_eye_slash", "eye.slash"),
    ("sf_hand_raised", "hand.raised.fill"), ("sf_app_badge", "app.badge.fill"),
    ("sf_arrow_up_right", "arrow.up.right"), ("sf_arrow_down_left", "arrow.down.left"),
    ("sf_magnifyingglass", "magnifyingglass"), ("sf_exclamation", "exclamationmark.circle.fill"),
    ("sf_power", "power"), ("sf_link_badge", "link.badge.plus"),
    ("sf_phone", "phone"),
]

// density -> pixels for a 24dp icon
let densities: [(dir: String, px: Int)] = [
    ("drawable-mdpi", 24), ("drawable-hdpi", 36), ("drawable-xhdpi", 48),
    ("drawable-xxhdpi", 72), ("drawable-xxxhdpi", 96)
]

func linkGlyphPath(in rect: CGRect) -> NSBezierPath {
    let p = NSBezierPath()
    func r(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat) -> NSRect {
        NSRect(x: rect.minX + x / 128.0 * rect.width,
               y: rect.minY + rect.height - (y + h) / 128.0 * rect.height,
               width: w / 128.0 * rect.width, height: h / 128.0 * rect.height)
    }
    func rr(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, _ c: CGFloat) {
        p.appendRoundedRect(r(x, y, w, h), xRadius: c / 128.0 * rect.width, yRadius: c / 128.0 * rect.height)
    }
    rr(14, 14, 34, 14, 7); rr(14, 51, 14, 26, 7); rr(14, 100, 34, 14, 7)
    rr(80, 14, 34, 14, 7); rr(100, 51, 14, 26, 7); rr(80, 100, 34, 14, 7)
    rr(40, 56, 48, 16, 8)
    return p
}

func renderPNG(px: Int, draw: (NSGraphicsContext) -> Void) -> Data? {
    guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px,
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

var rendered = 0
for (file, symbol) in symbols {
    for (dir, px) in densities {
        let outDir = "\(resDir)/\(dir)"
        try? FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)
        let outPath = "\(outDir)/\(file).png"
        // pointSize ~ 78% of px so the glyph keeps padding inside the 24dp box
        let pt = CGFloat(px) * 0.78
        guard let data = renderPNG(px: px, draw: { _ in
            NSColor.white.setFill()
            let rect = NSRect(x: (CGFloat(px) - pt) / 2, y: (CGFloat(px) - pt) / 2, width: pt, height: pt)
            if let symbol {
                guard let img = NSImage(systemSymbolName: symbol,
                                        accessibilityDescription: nil) else {
                    FileHandle.standardError.write("missing symbol: \(symbol)\n".data(using: .utf8)!)
                    return
                }
                let config = NSImage.SymbolConfiguration(pointSize: pt, weight: .regular)
                if let sym = img.withSymbolConfiguration(config) {
                    sym.draw(in: rect, from: .zero, operation: .sourceOver, fraction: 1.0)
                }
            } else {
                // Link glyph fills the box (used for launcher/notification marks)
                let g = CGFloat(px) * 0.92
                let o = (CGFloat(px) - g) / 2
                linkGlyphPath(in: NSRect(x: o, y: o, width: g, height: g)).fill()
            }
        }) else { print("FAIL \(outPath)"); continue }
        try? data.write(to: URL(fileURLWithPath: outPath))
        rendered += 1
    }
}
print("rendered \(rendered) PNGs")
