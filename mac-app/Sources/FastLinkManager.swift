import Foundation
import SwiftUI
import Combine
import Network
import CoreWLAN
import CoreLocation
import Darwin

/**
 * "Fast link" manager: when the control channel to Android rides the
 * Bluetooth relay (devices not on the same Wi-Fi), the phone runs a private
 * LocalOnlyHotspot and this manager joins it with CoreWLAN so file-transfer
 * data sockets can use plain fast Wi-Fi instead of the BLE relay.
 *
 * Protocol (encrypted control channel, inner JSON):
 *   Android -> Mac  {"type":"fastlink_offer","session":"…","ssid":"…","psk":"…"}
 *   Mac -> Android  {"type":"fastlink_ready","session":"…","ip":"…"}
 *                   {"type":"fastlink_failed","session":"…","reason":"…"}
 *   Mac -> Android  {"type":"fastlink_request"}   (pre-warm hint)
 *   Android -> Mac  {"type":"fastlink_end","session":"…"}
 *
 * All Wi-Fi work runs on the serial fastLinkQueue (CoreWLAN calls block).
 * The passphrase is never logged.
 */
final class FastLinkManager: ObservableObject {
    static let shared = FastLinkManager()

    private static let enabledKey = "bridgeFastLinkEnabled"

    enum LinkState {
        case idle, joining, ready, restoring
    }

    struct Offer {
        let session: String
        let ssid: String
        let psk: String
    }

    enum RestorePlan: Equatable {
        case rejoin(String)
        case powerCycle
        case disassociateOnly
    }

    // MARK: - Published state

    @Published private(set) var statusText: String? = nil

    @Published var isFastLinkEnabled: Bool {
        didSet {
            UserDefaults.standard.set(isFastLinkEnabled, forKey: Self.enabledKey)
        }
    }

    // MARK: - Internals

    private let fastLinkQueue = DispatchQueue(label: "bridge.fastlink", qos: .userInitiated)
    private let wifiClient = CWWiFiClient.shared()
    private var wifiMonitor: NWPathMonitor?
    private var wifiPathSatisfied = false
    private lazy var locationManager = CLLocationManager()

    // Session state — fastLinkQueue only.
    private var state: LinkState = .idle

    /// True while a fast-link session is joining/ready/restoring (blocks other
    /// Wi-Fi switching like phone-internet).
    var isActive: Bool { state != .idle }

    /// Short human-readable state for UI display ("Idle", "Joining…", "Hotspot", "Restoring…").
    var stateDescription: String {
        switch state {
        case .idle: return "Idle"
        case .joining: return "Joining…"
        case .ready: return "Hotspot"
        case .restoring: return "Restoring…"
        }
    }
    private var session: String?
    private var previousSSID: String?
    private var wasOnWifi = false
    private var joinedSSID: String?
    private var ownerDeviceId: UUID?
    private var joinedAt: Date?
    private var watchdogWork: DispatchWorkItem?

    private init() {
        isFastLinkEnabled = UserDefaults.standard.object(forKey: Self.enabledKey) as? Bool ?? true

        let monitor = NWPathMonitor(requiredInterfaceType: .wifi)
        monitor.pathUpdateHandler = { [weak self] path in
            self?.wifiPathSatisfied = path.status == .satisfied
        }
        monitor.start(queue: fastLinkQueue)
        wifiMonitor = monitor
    }

    // MARK: - Lifecycle hooks

    /// Requests Location permission (needed for CWInterface.ssid()) when the
    /// fast-link setting is on. Call once at app start on the main thread.
    func requestLocationAuthorizationIfNeeded() {
        guard isFastLinkEnabled,
              CLLocationManager.authorizationStatus() == .notDetermined else { return }
        requestLocationAuthorization()
    }

    /// Unconditional Location request for reading the Wi-Fi SSID (used by
    /// Wi-Fi password sharing even when fast transfers are off). No-op unless
    /// status is .notDetermined.
    func requestLocationAuthorization() {
        guard CLLocationManager.authorizationStatus() == .notDetermined else { return }
        DispatchQueue.main.async { [weak self] in
            self?.locationManager.requestWhenInUseAuthorization()
        }
    }

    /// Sends the pre-warm hint so the phone can start its hotspot early.
    func sendPrewarmRequest(toDeviceId: UUID) {
        guard isFastLinkEnabled else { return }
        LinkFileTransferManager.shared.sendControlMessage?("{\"type\":\"fastlink_request\"}", toDeviceId)
    }

    /// Called by LinkServer when an authenticated control connection drops.
    func controlConnectionClosed(deviceId: UUID) {
        fastLinkQueue.async { [weak self] in
            guard let self = self, self.ownerDeviceId == deviceId, self.state != .idle else { return }
            self.restore()
        }
    }

    /// Best-effort synchronous restore with a 3s cap during app termination.
    func applicationTerminating() {
        let sem = DispatchSemaphore(value: 0)
        fastLinkQueue.async { [weak self] in
            if let self = self, self.state != .idle {
                self.restore()
            }
            sem.signal()
        }
        _ = sem.wait(timeout: .now() + 3)
    }

    // MARK: - Control messages (arrive on the server's networkQueue)

    func handleControlMessage(_ jsonObject: [String: Any], fromDeviceId: UUID) {
        guard let type = jsonObject["type"] as? String, type.hasPrefix("fastlink_") else { return }
        switch type {
        case "fastlink_offer":
            guard let offer = Self.parseOffer(jsonObject) else {
                let sid = jsonObject["session"] as? String ?? ""
                replyFailed(deviceId: fromDeviceId, session: sid, reason: "bad_offer")
                return
            }
            fastLinkQueue.async { [weak self] in
                self?.handleOffer(offer, deviceId: fromDeviceId)
            }
        case "fastlink_end":
            let endedSession = jsonObject["session"] as? String
            fastLinkQueue.async { [weak self] in
                self?.handleEnd(session: endedSession)
            }
        default:
            break
        }
    }

    // MARK: - Offer handling (fastLinkQueue)

    private func handleOffer(_ offer: Offer, deviceId: UUID) {
        guard isFastLinkEnabled else {
            replyFailed(deviceId: deviceId, session: offer.session, reason: "disabled")
            return
        }
        // Don't hijack Wi-Fi while the phone's hotspot is being used for
        // internet — Android falls back on a failed reply.
        if LinkPhoneInternet.isConnectedFlag {
            replyFailed(deviceId: deviceId, session: offer.session, reason: "phone_internet_active")
            return
        }
        guard let iface = wifiClient.interface(), let interfaceName = iface.interfaceName else {
            replyFailed(deviceId: deviceId, session: offer.session, reason: "no_wifi")
            return
        }

        if state == .idle {
            // Snapshot pre-hotspot Wi-Fi state only for a fresh session. When a
            // session is already active the Mac may be ON the old hotspot, so
            // iface.ssid() would capture the hotspot itself — keep the
            // existing snapshot instead.
            previousSSID = iface.ssid()
            wasOnWifi = wifiPathSatisfied
        }
        session = offer.session
        joinedSSID = offer.ssid
        ownerDeviceId = deviceId
        joinedAt = nil
        state = .joining
        setStatus("Switching to fast link…")

        // Already associated to this hotspot (e.g. a repeated offer)? Skip
        // the join loop and go straight to the IP wait.
        if iface.ssid() != offer.ssid {
            if !iface.powerOn() {
                do {
                    try iface.setPower(true)
                } catch {
                    failJoin(deviceId: deviceId, reason: "power_on")
                    return
                }
                let powerDeadline = Date().addingTimeInterval(3)
                while !iface.powerOn() && Date() < powerDeadline {
                    usleep(100_000)
                }
                guard iface.powerOn() else {
                    failJoin(deviceId: deviceId, reason: "power_on")
                    return
                }
            }

            var joined = false
            let joinDeadline = Date().addingTimeInterval(12)
            var emptyScans = 0
            var triedNetworkSetup = false
            while Date() < joinDeadline && !joined {
                // Prefer cached scan results before paying for an active scan.
                let cachedHit = iface.cachedScanResults()?.first { $0.ssid == offer.ssid }
                let network = cachedHit ?? (try? iface.scanForNetworks(withName: offer.ssid))?.first
                if let network = network {
                    emptyScans = 0
                    do {
                        try iface.associate(to: network, password: offer.psk)
                        joined = true
                    } catch {
                        usleep(200_000)
                    }
                } else {
                    emptyScans += 1
                    if emptyScans >= 3 && !triedNetworkSetup {
                        triedNetworkSetup = true
                        joined = Self.joinViaNetworkSetup(
                            interfaceName: interfaceName,
                            ssid: offer.ssid,
                            psk: offer.psk
                        )
                    }
                    usleep(250_000)
                }
            }
            guard joined else {
                failJoin(deviceId: deviceId, reason: "join_failed")
                return
            }
        }
        joinedAt = Date()

        var ip: String?
        let ipDeadline = Date().addingTimeInterval(10)
        while Date() < ipDeadline {
            if let found = Self.usableIPv4(onInterface: interfaceName) {
                ip = found
                break
            }
            usleep(50_000)
        }
        guard let ip = ip else {
            failJoin(deviceId: deviceId, reason: "no_ip")
            return
        }

        state = .ready
        setStatus("Fast link ready")
        armWatchdog()
        replyReady(deviceId: deviceId, session: offer.session, ip: ip)
    }

    private func handleEnd(session endedSession: String?) {
        guard let endedSession = endedSession,
              endedSession == session,
              state != .idle else { return }
        restore()
    }

    private func failJoin(deviceId: UUID, reason: String) {
        let sid = session
        replyFailed(deviceId: deviceId, session: sid ?? "", reason: reason)
        restore()
    }

    // MARK: - Restore previous Wi-Fi (fastLinkQueue)

    private func restore() {
        guard state == .joining || state == .ready || state == .restoring else {
            tearDownSession()
            return
        }
        // If the snapshot captured the hotspot itself (e.g. a session was
        // already active on it), treat it as "no previous SSID".
        let effectivePrevSSID = (previousSSID != nil && previousSSID == joinedSSID) ? nil : previousSSID
        let plan = Self.restorePlan(previousSSID: effectivePrevSSID, wasOnWifi: wasOnWifi)
        let iface = wifiClient.interface()
        let hotspotSSID = joinedSSID
        let interfaceName = iface?.interfaceName

        state = .restoring
        setStatus("Rejoining \(previousSSID ?? "your") Wi-Fi…")

        iface?.disassociate()

        switch plan {
        case .rejoin(let ssid):
            var rejoined = false
            for _ in 0..<3 {
                guard let iface = iface else { break }
                let network = iface.cachedScanResults()?.first { $0.ssid == ssid }
                    ?? (try? iface.scanForNetworks(withName: ssid))?.first
                if let network = network {
                    do {
                        try iface.associate(to: network, password: nil)
                        rejoined = true
                        break
                    } catch {
                        usleep(500_000)
                    }
                } else {
                    usleep(500_000)
                }
            }
            if !rejoined {
                powerCycle(iface)
            }
        case .powerCycle:
            powerCycle(iface)
        case .disassociateOnly:
            break
        }

        if let interfaceName = interfaceName, let hotspotSSID = hotspotSSID {
            Self.removePreferredNetwork(interfaceName: interfaceName, ssid: hotspotSSID)
        }
        tearDownSession()
        setStatus(nil)
    }

    private func powerCycle(_ iface: CWInterface?) {
        guard let iface = iface else { return }
        do {
            try iface.setPower(false)
        } catch {
            // Continue best-effort.
        }
        usleep(1_000_000)
        do {
            try iface.setPower(true)
        } catch {
            // Continue best-effort.
        }
    }

    private func tearDownSession() {
        watchdogWork?.cancel()
        watchdogWork = nil
        session = nil
        previousSSID = nil
        joinedSSID = nil
        ownerDeviceId = nil
        joinedAt = nil
        wasOnWifi = false
        state = .idle
    }

    // MARK: - Watchdog (fastLinkQueue)

    private func armWatchdog() {
        watchdogWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            self?.watchdogTick()
        }
        watchdogWork = work
        fastLinkQueue.asyncAfter(deadline: .now() + 60, execute: work)
    }

    private func watchdogTick() {
        guard state == .ready, let joined = joinedAt else { return }
        if Date().timeIntervalSince(joined) >= 600,
           !LinkFileTransferManager.shared.hasActiveSessions() {
            NSLog("[FastLink] Watchdog: no fastlink_end and no active transfers; restoring Wi-Fi")
            restore()
            return
        }
        armWatchdog()
    }

    // MARK: - Replies & status

    private func replyReady(deviceId: UUID, session: String, ip: String) {
        sendFastLinkReply(deviceId: deviceId, fields: [
            "type": "fastlink_ready",
            "session": session,
            "ip": ip
        ])
    }

    private func replyFailed(deviceId: UUID, session: String, reason: String) {
        // reason is an internal short code; sanitize anyway.
        let safeReason = reason.filter { $0.isLetter || $0.isNumber || $0 == "_" }
        sendFastLinkReply(deviceId: deviceId, fields: [
            "type": "fastlink_failed",
            "session": session,
            "reason": safeReason
        ])
    }

    private func sendFastLinkReply(deviceId: UUID, fields: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: fields),
              let json = String(data: data, encoding: .utf8) else { return }
        LinkFileTransferManager.shared.sendControlMessage?(json, deviceId)
    }

    private func setStatus(_ text: String?) {
        DispatchQueue.main.async { [weak self] in
            self?.statusText = text
        }
    }

    // MARK: - Pure helpers (unit tested)

    /**
     * Validates a fastlink_offer payload:
     * session 8–64 chars of [A-Za-z0-9-], ssid 1–32 UTF-8 bytes,
     * psk 8–63 chars or exactly 64 hex chars.
     */
    static func parseOffer(_ json: [String: Any]) -> Offer? {
        guard let session = json["session"] as? String,
              session.count >= 8 && session.count <= 64,
              session.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }),
              let ssid = json["ssid"] as? String,
              !ssid.isEmpty && ssid.utf8.count <= 32,
              let psk = json["psk"] as? String else {
            return nil
        }
        let pskOk = (psk.count >= 8 && psk.count <= 63) ||
            (psk.count == 64 && psk.allSatisfy { $0.isHexDigit })
        guard pskOk else { return nil }
        return Offer(session: session, ssid: ssid, psk: psk)
    }

    /** Private RFC1918 IPv4 only: 10/8, 172.16/12, 192.168/16. */
    static func isUsableHotspotIPv4(_ ip: String) -> Bool {
        let parts = ip.split(separator: ".").compactMap { Int($0) }
        guard parts.count == 4, parts.allSatisfy({ $0 >= 0 && $0 <= 255 }) else { return false }
        if parts[0] == 10 { return true }
        if parts[0] == 172 && parts[1] >= 16 && parts[1] <= 31 { return true }
        if parts[0] == 192 && parts[1] == 168 { return true }
        return false
    }

    static func restorePlan(previousSSID: String?, wasOnWifi: Bool) -> RestorePlan {
        if let ssid = previousSSID, !ssid.isEmpty {
            return .rejoin(ssid)
        }
        if wasOnWifi {
            return .powerCycle
        }
        return .disassociateOnly
    }

    // MARK: - System helpers (fastLinkQueue)

    /// First private RFC1918 IPv4 currently assigned to [name] (e.g. "en0").
    static func usableIPv4(onInterface name: String) -> String? {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return nil }
        defer { freeifaddrs(head) }
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let current = cursor {
            let entry = current.pointee
            if String(cString: entry.ifa_name) == name,
               let addr = entry.ifa_addr,
               addr.pointee.sa_family == sa_family_t(AF_INET) {
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                let rc = getnameinfo(
                    addr,
                    socklen_t(addr.pointee.sa_len),
                    &host,
                    socklen_t(host.count),
                    nil,
                    0,
                    NI_NUMERICHOST
                )
                if rc == 0 {
                    let ip = String(cString: host)
                    if isUsableHotspotIPv4(ip) {
                        return ip
                    }
                }
            }
            cursor = entry.ifa_next
        }
        return nil
    }

    /// One-shot fallback when CoreWLAN scans cannot see the hotspot.
    /// Never logs arguments (they contain the passphrase).
    static func joinViaNetworkSetup(interfaceName: String, ssid: String, psk: String) -> Bool {
        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: "/usr/sbin/networksetup")
        proc.arguments = ["-setairportnetwork", interfaceName, ssid, psk]
        let pipe = Pipe()
        proc.standardOutput = pipe
        proc.standardError = pipe
        do {
            try proc.run()
            proc.waitUntilExit()
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            let output = String(data: data, encoding: .utf8) ?? ""
            let failed = proc.terminationStatus != 0
                || output.contains("Error")
                || output.contains("Could not")
            return !failed
        } catch {
            return false
        }
    }

    /// Best-effort removal of the phone hotspot from preferred networks.
    static func removePreferredNetwork(interfaceName: String, ssid: String) {
        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: "/usr/sbin/networksetup")
        proc.arguments = ["-removepreferredwirelessnetwork", interfaceName, ssid]
        proc.standardOutput = FileHandle.nullDevice
        proc.standardError = FileHandle.nullDevice
        do {
            try proc.run()
            proc.waitUntilExit()
        } catch {
            // Ignore: cleanup is best-effort.
        }
    }
}
