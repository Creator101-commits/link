import Foundation
import AppKit
import CryptoKit

// MARK: - Notification Data Models

/// Disk+memory cache for remote app icons keyed by package name.
/// `pkg` is untrusted input: it is NEVER used in a file path — on-disk
/// filenames are the SHA-256 hex digest of the pkg string.
public final class BridgeAppIconCache {
    public static let shared = BridgeAppIconCache()

    private let memoryCache = NSCache<NSString, NSImage>()
    private let directoryURL: URL
    /// Pkgs known to have no icon on disk, so image(pkg:) doesn't hit disk
    /// on every render. Guarded by missingLock.
    private let missingLock = NSLock()
    private var missingPkgs = Set<String>()

    private init() {
        let cachesDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        directoryURL = cachesDir.appendingPathComponent("com.link.bridge/app-icons", isDirectory: true)
        memoryCache.countLimit = 64
    }

    /// On-disk filename for a pkg's cached icon: SHA-256 hex of the pkg +
    /// ".png". The pkg string itself is never used in a path.
    static func cacheFileName(forPkg pkg: String) -> String {
        let digest = SHA256.hash(data: Data(pkg.utf8))
        return digest.map { String(format: "%02x", $0) }.joined() + ".png"
    }

    private func fileURL(forPkg pkg: String) -> URL {
        directoryURL.appendingPathComponent(Self.cacheFileName(forPkg: pkg))
    }

    /// Stores a base64-encoded PNG icon. Rejects oversized or undecodable data.
    public func store(pkg: String, base64: String) {
        guard !pkg.isEmpty, pkg.count <= 128,
              let data = Data(base64Encoded: base64, options: .ignoreUnknownCharacters),
              data.count <= 512 * 1024,
              let image = NSImage(data: data) else { return }
        memoryCache.setObject(image, forKey: pkg as NSString)
        missingLock.lock()
        missingPkgs.remove(pkg)
        missingLock.unlock()
        let url = fileURL(forPkg: pkg)
        try? FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        try? data.write(to: url, options: .atomic)
    }

    public func image(pkg: String) -> NSImage? {
        if let cached = memoryCache.object(forKey: pkg as NSString) {
            return cached
        }
        missingLock.lock()
        let knownMissing = missingPkgs.contains(pkg)
        missingLock.unlock()
        if knownMissing { return nil }
        let url = fileURL(forPkg: pkg)
        guard let data = try? Data(contentsOf: url),
              let image = NSImage(data: data) else {
            missingLock.lock()
            missingPkgs.insert(pkg)
            missingLock.unlock()
            return nil
        }
        memoryCache.setObject(image, forKey: pkg as NSString)
        return image
    }

    /// Returns a unique temp copy of the cached icon for use as a
    /// UNNotificationAttachment — the system moves the file, so the cache
    /// file itself must never be handed over.
    public func attachmentURL(pkg: String) -> URL? {
        let source = fileURL(forPkg: pkg)
        guard FileManager.default.fileExists(atPath: source.path) else { return nil }
        let dest = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("bridge-icon-\(UUID().uuidString).png")
        do {
            try FileManager.default.copyItem(at: source, to: dest)
            return dest
        } catch {
            return nil
        }
    }
}

public struct BridgeNotificationAction: Codable, Identifiable {
    public let id: Int
    public let title: String
    public let hasInput: Bool
    public let placeholder: String?

    public init(id: Int, title: String, hasInput: Bool = false, placeholder: String? = nil) {
        self.id = id
        self.title = title
        self.hasInput = hasInput
        self.placeholder = placeholder
    }
}

public struct BridgeMessageItem: Codable, Identifiable {
    public var id: String { "\(timestamp)_\(text.hashValue)" }
    public let text: String
    public let timestamp: Int64
    public let sender: String?

    public init(text: String, timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000), sender: String? = nil) {
        self.text = text
        self.timestamp = timestamp
        self.sender = sender
    }
}

public struct BridgeNotification: Codable, Identifiable {
    public let id: String
    public let source: String?       // "android" or "mac"
    public let pkg: String?          // e.g. "com.whatsapp"
    public let app: String?          // e.g. "WhatsApp"
    public let title: String?        // e.g. "Sarah"
    public let text: String?         // e.g. "Are we still meeting at 3?"
    public let icon: String?         // Base64 encoded PNG
    public let timestamp: Int64?     // Epoch ms
    public let actions: [BridgeNotificationAction]?
    public let isSilent: Bool?
    public let messages: [BridgeMessageItem]?
    public let isNewestFirst: Bool?

    public init(
        id: String,
        source: String? = "android",
        pkg: String? = nil,
        app: String? = nil,
        title: String? = nil,
        text: String? = nil,
        icon: String? = nil,
        timestamp: Int64? = Int64(Date().timeIntervalSince1970 * 1000),
        actions: [BridgeNotificationAction]? = nil,
        isSilent: Bool? = false,
        messages: [BridgeMessageItem]? = nil,
        isNewestFirst: Bool? = nil
    ) {
        self.id = id
        self.source = source
        self.pkg = pkg
        self.app = app
        self.title = title
        self.text = text
        self.icon = icon
        self.timestamp = timestamp
        self.actions = actions
        self.isSilent = isSilent
        self.messages = messages
        self.isNewestFirst = isNewestFirst
    }

    public var iconImage: NSImage? {
        // Android-sourced icons live in BridgeAppIconCache keyed by pkg;
        // fall back to the inline base64 (mac-source notifications).
        if let pkg = pkg, !pkg.isEmpty,
           let cached = BridgeAppIconCache.shared.image(pkg: pkg) {
            return cached
        }
        guard let base64 = icon,
              let data = Data(base64Encoded: base64, options: .ignoreUnknownCharacters) else {
            return nil
        }
        return NSImage(data: data)
    }

    public var relativeTimeText: String {
        guard let ts = timestamp else { return "now" }
        let seconds = max(0, Int((Date().timeIntervalSince1970 * 1000 - Double(ts)) / 1000))
        if seconds < 60 { return "just now" }
        let mins = seconds / 60
        if mins < 60 { return "\(mins)m ago" }
        let hours = mins / 60
        if hours < 24 { return "\(hours)h ago" }
        return "\(hours / 24)d ago"
    }
}

public struct NotificationActionRequest: Codable {
    public var type: String = "notif_action"
    public let id: String
    public let actionId: Int
    public let replyText: String?

    public init(id: String, actionId: Int, replyText: String? = nil) {
        self.type = "notif_action"
        self.id = id
        self.actionId = actionId
        self.replyText = replyText
    }
}

public struct NotificationDismissRequest: Codable {
    public var type: String = "notif_dismiss"
    public let id: String
    public let target: String

    public init(id: String, target: String = "android") {
        self.type = "notif_dismiss"
        self.id = id
        self.target = target
    }
}
