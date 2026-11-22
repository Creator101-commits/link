import AppKit
import SwiftUI

// Snapshot harness: renders BridgeMenuView offscreen-ish for design/screenshots.
// Usage: BridgeSnapshot <screen> <light|dark> <out.png>
//   screen: not-paired | devices | notifications | transfers | settings
// No networking, no Bluetooth, no Wi-Fi changes: BridgeModel(startServer: false).

let args = CommandLine.arguments
guard args.count >= 4 else {
    FileHandle.standardError.write("usage: BridgeSnapshot <screen> <light|dark> <out.png>\n".data(using: .utf8)!)
    exit(2)
}
let screen = args[1]
let mode = args[2]
let outPath = args[3]

let model = BridgeModel(startServer: false)
let notifManager = BridgeNotificationManager.shared
let fileManager = BridgeFileTransferManager.shared

// Render the documented glass fallback so switches/icons/cards rasterize
// identically under cacheDisplay (glassEffect does not rasterize offscreen).
BridgeGlass.forceFallback = true

// Deterministic paired state per screen.
switch screen {
case "not-paired":
    model.pairedDevice = nil
    model.connectedDevice = nil
    model.isConnected = false
    model.connectedDevicesList = []
    model.selectedTab = .devices
case "devices":
    model.pairedDevice = "Pixel 8"
    model.connectedDevice = "Pixel 8"
    model.isConnected = true
    model.connectedDevicesList = [
        ConnectedDeviceInfo(id: UUID(), name: "Pixel 8", ip: "192.168.1.42", isConnected: true),
        ConnectedDeviceInfo(id: UUID(), name: "Galaxy S24", ip: "Bluetooth", isConnected: true),
    ]
    model.selectedTab = .devices
case "notifications":
    model.pairedDevice = "Pixel 8"
    model.connectedDevice = "Pixel 8"
    model.isConnected = true
    model.selectedTab = .notifications
    injectSampleNotifications()
case "transfers":
    model.pairedDevice = "Pixel 8"
    model.connectedDevice = "Pixel 8"
    model.isConnected = true
    model.connectedDevicesList = [
        ConnectedDeviceInfo(id: UUID(), name: "Pixel 8", ip: "192.168.1.42", isConnected: true),
    ]
    model.selectedTab = .files
    injectSampleTransfers()
case "settings":
    model.pairedDevice = "Pixel 8"
    model.connectedDevice = "Pixel 8"
    model.isConnected = true
    model.selectedTab = .settings
    // Settings toggles ON for the screenshot.
    notifManager.isMasterNotificationsEnabled = true
    notifManager.isMirroringEnabled = true
    notifManager.isMacAlertsForwardingEnabled = true
    notifManager.isSoundEnabled = true
    fileManager.autoAcceptIncomingFiles = true
    BridgeFastLinkManager.shared.isFastLinkEnabled = true
default:
    FileHandle.standardError.write("unknown screen: \(screen)\n".data(using: .utf8)!)
    exit(2)
}

// App icons for sample notifications (rounded tile + letter, per pkg).
func sampleIcon(_ letter: String, _ rgb: (CGFloat, CGFloat, CGFloat)) -> String {
    let size: CGFloat = 128
    guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 128, pixelsHigh: 128,
                                     bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true,
                                     isPlanar: false, colorSpaceName: .deviceRGB,
                                     bytesPerRow: 0, bitsPerPixel: 0) else { return "" }
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    NSColor(calibratedRed: rgb.0, green: rgb.1, blue: rgb.2, alpha: 1).setFill()
    NSBezierPath(roundedRect: NSRect(x: 0, y: 0, width: size, height: size),
                 xRadius: 26, yRadius: 26).fill()
    let attrs: [NSAttributedString.Key: Any] = [
        .font: NSFont.systemFont(ofSize: 64, weight: .bold),
        .foregroundColor: NSColor.white,
    ]
    let str = NSAttributedString(string: letter, attributes: attrs)
    let sz = str.size()
    str.draw(at: NSPoint(x: (size - sz.width) / 2, y: (size - sz.height) / 2))
    NSGraphicsContext.restoreGraphicsState()
    guard let png = rep.representation(using: .png, properties: [:]) else { return "" }
    return png.base64EncodedString()
}

func injectSampleNotifications() {
    let cache = BridgeAppIconCache.shared
    let icons: [(String, String, String, (CGFloat, CGFloat, CGFloat))] = [
        ("com.whatsapp", "WhatsApp", "W", (0.18, 0.72, 0.29)),
        ("com.instagram.android", "Instagram", "I", (0.55, 0.25, 0.75)),
        ("com.spotify.music", "Spotify", "S", (0.05, 0.65, 0.30)),
    ]
    for (pkg, app, letter, rgb) in icons {
        cache.store(pkg: pkg, base64: sampleIcon(letter, rgb))
    }
    let now = Int64(Date().timeIntervalSince1970 * 1000)
    notifManager.activeNotifications = [
        BridgeNotification(
            id: "snap-1", source: "android", pkg: "com.whatsapp", app: "WhatsApp",
            title: "Family Group", text: "Mom: Dinner at 7?",
            timestamp: now - 60_000,
            actions: [
                BridgeNotificationAction(id: 0, title: "Reply", hasInput: true, placeholder: "Reply"),
                BridgeNotificationAction(id: 1, title: "Mark as read"),
            ]
        ),
        BridgeNotification(
            id: "snap-2", source: "android", pkg: "com.instagram.android", app: "Instagram",
            title: "alex.k", text: "Liked your photo: \"Sunset hike\"",
            timestamp: now - 12 * 60_000
        ),
        BridgeNotification(
            id: "snap-3", source: "android", pkg: "com.spotify.music", app: "Spotify",
            title: "Daily Mix 3", text: "New releases from artists you follow",
            timestamp: now - 45 * 60_000
        ),
    ]
}

func injectSampleTransfers() {
    let devId = UUID()
    let devName = "Pixel 8"
    let active = FileTransferItem(
        id: "snap-active", fileName: "Quarterly-Report-Final-v3.pdf",
        fileSize: 48 * 1024 * 1024, direction: .outgoing,
        deviceId: devId, deviceName: devName, status: .transferring
    )
    active.bytesTransferred = Int64(Double(active.fileSize) * 0.4)
    active.speedBytesPerSec = 9_400_000
    let done = FileTransferItem(
        id: "snap-done", fileName: "vacation-photos-2026.zip",
        fileSize: 182 * 1024 * 1024, direction: .outgoing,
        deviceId: devId, deviceName: devName, status: .completed
    )
    done.bytesTransferred = done.fileSize
    let failed = FileTransferItem(
        id: "snap-fail", fileName: "setup-app-release.apk",
        fileSize: 64 * 1024 * 1024, direction: .incoming,
        deviceId: devId, deviceName: devName, status: .failed("Connection lost during transfer.")
    )
    fileManager.transfers = [active, done, failed]
}

// MARK: - Render window

let appearance: NSAppearance.Name = (mode == "dark") ? .darkAqua : .aqua
let hosting = NSHostingController(rootView: BridgeMenuView(model: model))
if #available(macOS 13.0, *) {
    hosting.sizingOptions = [.preferredContentSize]
}
let window = NSWindow(
    contentRect: NSRect(x: 0, y: 0, width: 360, height: 100),
    styleMask: [.borderless],
    backing: .buffered,
    defer: false
)
window.appearance = NSAppearance(named: appearance)
window.backgroundColor = NSColor.windowBackgroundColor
window.isOpaque = true
window.contentViewController = hosting
window.orderFrontRegardless()
window.makeKeyAndOrderFront(nil)
NSApp.setActivationPolicy(.regular)
NSApp.activate(ignoringOtherApps: true)

// Let SwiftUI lay out, then size the window to the view.
hosting.view.layoutSubtreeIfNeeded()
RunLoop.main.run(until: Date().addingTimeInterval(0.4))
hosting.view.layoutSubtreeIfNeeded()
let fit = hosting.view.fittingSize
let height = max(200, ceil(fit.height))
window.setContentSize(NSSize(width: 360, height: height))
window.center()
RunLoop.main.run(until: Date().addingTimeInterval(0.3))

// Prefer a real on-screen capture (renders materials/vibrancy like the popover).
var captured = false
if let winID = Optional(window.windowNumber) {
    let tmp = outPath + ".tmp.png"
    let proc = Process()
    proc.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
    proc.arguments = ["-x", "-o", "-l", String(winID), tmp]
    try? proc.run()
    proc.waitUntilExit()
    if proc.terminationStatus == 0, let attrs = try? FileManager.default.attributesOfItem(atPath: tmp),
       (attrs[.size] as? Int ?? 0) > 1000 {
        try? FileManager.default.removeItem(atPath: outPath)
        try? FileManager.default.moveItem(atPath: tmp, toPath: outPath)
        captured = true
    } else {
        try? FileManager.default.removeItem(atPath: tmp)
    }
}

if !captured {
    // Fallback: cacheDisplay into a bitmap (no screen-recording permission needed).
    guard let view = window.contentView else { exit(1) }
    view.layoutSubtreeIfNeeded()
    let bounds = view.bounds
    guard let rep = NSBitmapImageRep(
        bitmapDataPlanes: nil, pixelsWide: Int(bounds.width), pixelsHigh: Int(bounds.height),
        bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
        colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
    ) else { exit(1) }
    view.cacheDisplay(in: bounds, to: rep)
    guard let png = rep.representation(using: .png, properties: [:]) else { exit(1) }
    try? png.write(to: URL(fileURLWithPath: outPath))
    FileHandle.standardError.write("note: used cacheDisplay fallback for \(outPath)\n".data(using: .utf8)!)
}

window.orderOut(nil)
exit(0)
