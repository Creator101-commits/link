import Foundation
import CoreWLAN
import AppKit
import CoreLocation

/// Shares the current Wi-Fi network's password with the paired phone as
/// {"type":"wifi_share", ...}; the phone replies {"type":"wifi_share_ack", ...}.
/// Password is read from the System keychain via `/usr/bin/security` (macOS
/// shows its own admin prompt). Never logged.
@MainActor
final class LinkWifiShare: ObservableObject {
    static let shared = LinkWifiShare()

    enum State: Equatable {
        case idle
        case reading
        case sent(ssid: String)
        case failed(String)
        case unsupported(String)
    }

    @Published var state: State = .idle
    /// Current Wi-Fi SSID for the subtitle (nil = not on Wi-Fi).
    @Published var currentSSID: String?

    /// Set by LinkModel to the server's broadcastRawJson (String-based).
    var sendJsonHandler: ((String) -> Void)?

    /// Snapshot harness: when true, refreshSSID() is a no-op so injected SSIDs
    /// aren't overwritten by this Mac's real (nil) value.
    var freezeSSIDForSnapshots = false

    private let wifiClient = CWWiFiClient.shared()
    private var sentResetWork: DispatchWorkItem?

    private init() {}

    // MARK: - Pure helpers (unit-tested)

    /// Map a CoreWLAN security type to a share label, or nil if unsupported.
    nonisolated static func securityLabel(_ s: CWSecurity) -> String? {
        switch s {
        case .wpa3Personal, .wpa3Transition: return "wpa3"
        case .wpaPersonal, .wpa2Personal, .wpaPersonalMixed: return "wpa2"
        case .none: return "open"
        case .dynamicWEP: return "wep"
        default: return nil // enterprise & unknown
        }
    }

    /// Parse `security find-generic-password -w` output: trim trailing newline,
    /// empty -> nil.
    nonisolated static func parseSecurityOutput(_ data: Data) -> String? {
        guard let s = String(data: data, encoding: .utf8) else { return nil }
        let trimmed = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    // MARK: - Reading the network

    /// Refresh the displayed SSID (call on appear / periodically on user action).
    func refreshSSID() {
        guard !freezeSSIDForSnapshots else { return }
        currentSSID = readCurrentSSID()
    }

    private func readCurrentSSID() -> String? {
        guard let iface = wifiClient.interface() else { return nil }
        let ssid = iface.ssid()
        return (ssid?.isEmpty == false) ? ssid : nil
    }

    private func currentInterface() -> CWInterface? { wifiClient.interface() }

    // MARK: - Location gap

    /// True when Wi-Fi interface is up but Location isn't authorized, so
    /// CWInterface.ssid() returns nil and we can't read the network name.
    var needsLocationForSSID: Bool {
        guard currentSSID == nil else { return false }
        guard let iface = currentInterface(), iface.powerOn() else { return false }
        let status = CLLocationManager.authorizationStatus()
        #if os(macOS)
        return status != .authorized
        #else
        return status != .authorizedAlways && status != .authorizedWhenInUse
        #endif
    }

    /// True when the row should prompt for Location (interface up, no SSID,
    /// Location not yet determined).
    var shouldRequestLocation: Bool {
        needsLocationForSSID
            && CLLocationManager.authorizationStatus() == .notDetermined
    }

    /// Tap handler for the location-prompt state: request permission if not
    /// determined, else open System Settings › Privacy › Location Services.
    func handleLocationPrompt() {
        if CLLocationManager.authorizationStatus() == .notDetermined {
            FastLinkManager.shared.requestLocationAuthorization()
        } else {
            if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_LocationServices") {
                NSWorkspace.shared.open(url)
            }
        }
    }

    // MARK: - Share flow

    /// Entry point from the Actions card. Requires a connected phone (caller
    /// checks) and Location for SSID (reuses fast-link's request).
    func sharePassword() {
        guard state != .reading else { return }
        guard let iface = currentInterface() else {
            state = .failed("Not on Wi-Fi")
            return
        }
        guard let ssid = readCurrentSSID() else {
            state = .failed("Not on Wi-Fi")
            return
        }
        guard let security = Self.securityLabel(iface.security()) else {
            state = .unsupported("Enterprise networks can't be shared")
            return
        }
        // Open networks have no password to share.
        if security == "open" {
            send(ssid: ssid, password: "", security: security)
            return
        }
        state = .reading
        readPassword(ssid: ssid, security: security)
    }

    /// Run `/usr/bin/security` on a background queue; 120 s timeout.
    private func readPassword(ssid: String, security: String) {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let proc = Process()
            proc.executableURL = URL(fileURLWithPath: "/usr/bin/security")
            proc.arguments = [
                "find-generic-password", "-D", "AirPort network password",
                "-a", ssid, "-w", "/Library/Keychains/System.keychain",
            ]
            let pipe = Pipe()
            proc.standardOutput = pipe
            proc.standardError = FileHandle.nullDevice
            let semaphore = DispatchSemaphore(value: 0)
            proc.terminationHandler = { _ in semaphore.signal() }
            do {
                try proc.run()
            } catch {
                DispatchQueue.main.async { self?.state = .failed("Couldn't read the Wi-Fi password") }
                return
            }
            // 120 s timeout
            if semaphore.wait(timeout: .now() + 120) == .timedOut {
                proc.terminate()
                DispatchQueue.main.async { self?.state = .failed("Couldn't read the Wi-Fi password") }
                return
            }
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            let password = Self.parseSecurityOutput(data)
            DispatchQueue.main.async {
                guard let self = self else { return }
                guard proc.terminationStatus == 0, let password = password else {
                    self.state = .failed("Couldn't read the Wi-Fi password")
                    return
                }
                self.send(ssid: ssid, password: password, security: security)
            }
        }
    }

    private func send(ssid: String, password: String, security: String) {
        guard let data = try? JSONSerialization.data(withJSONObject: [
            "type": "wifi_share",
            "ssid": ssid,
            "password": password,
            "security": security,
        ], options: []) else { return }
        guard let json = String(data: data, encoding: .utf8) else { return }
        sendJsonHandler?(json)
        state = .sent(ssid: ssid)
        scheduleReset()
    }

    /// Phone acknowledged — show the "check your phone" hint for 6 s.
    func handleAck(_ json: [String: Any]) {
        guard let ok = json["ok"] as? Bool else { return }
        if ok {
            // Keep the sent state; ack just confirms delivery.
        } else {
            let reason = json["reason"] as? String ?? "Couldn't add the network"
            state = .failed(reason)
            scheduleReset()
        }
    }

    private func scheduleReset() {
        sentResetWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.state = .idle }
        sentResetWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 6, execute: work)
    }
}
