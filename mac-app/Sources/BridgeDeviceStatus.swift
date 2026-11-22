import SwiftUI
import UserNotifications

/// Latest phone status received from Android via {"type":"device_status", ...}.
/// Cleared on disconnect. Drives the Devices-tab Phone card, the header
/// battery pill, and the low-battery alert.
@MainActor
final class BridgeDeviceStatus: ObservableObject {
    static let shared = BridgeDeviceStatus()

    struct Status: Equatable {
        var battery: Int?
        var charging: Bool?
        var plug: String?
        var carrier: String?
        var network: String?
        var signal: Int?
        var noService: Bool?
        var airplane: Bool?
    }

    @Published var status: Status?
    @Published var phoneName: String = "Phone"

    private var alertedThisDischarge = false
    private var lastLevel = 100
    private var wasCharging = false

    private init() {}

    func update(from json: [String: Any]) {
        var s = Status()
        s.battery = json["battery"] as? Int
        s.charging = json["charging"] as? Bool
        s.plug = json["plug"] as? String
        s.carrier = json["carrier"] as? String
        s.network = json["network"] as? String
        s.signal = json["signal"] as? Int
        s.noService = json["noService"] as? Bool
        s.airplane = json["airplane"] as? Bool
        status = s
        if let n = json["name"] as? String, !n.isEmpty { phoneName = n }
        evaluateLowBattery(s)
    }

    func clear() {
        status = nil
        alertedThisDischarge = false
        lastLevel = 100
        wasCharging = false
    }

    // MARK: - Low-battery alert

    /// Pure decision function — unit-tested.
    /// Returns the level to alert on (if any) and the new armed state.
    /// state: 0 = armed (can alert), 1 = alerted this discharge cycle.
    nonisolated static func lowBatteryDecision(
        level: Int, charging: Bool, threshold: Int, state: Int
    ) -> (alert: Int?, newState: Int) {
        guard !charging else {
            // Charging re-arms the alert for the next discharge.
            return (nil, 0)
        }
        guard threshold > 0 else { return (nil, state) }
        // Alert at the configured threshold; a higher threshold (20/30) still
        // catches the 10% floor because 10 <= threshold.
        guard level <= threshold else {
            // Re-arm once the level climbs 5 above the configured threshold.
            if state == 1 && level > threshold + 5 { return (nil, 0) }
            return (nil, state)
        }
        if state == 1 { return (nil, 1) } // already alerted this discharge
        return (level, 1)
    }

    private func evaluateLowBattery(_ s: Status) {
        let threshold = UserDefaults.standard.integer(forKey: "lowBatteryThreshold")
        guard threshold > 0, let level = s.battery, let charging = s.charging else { return }
        let (alertLevel, newState) = BridgeDeviceStatus.lowBatteryDecision(
            level: level, charging: charging, threshold: threshold, state: alertedThisDischarge ? 1 : 0
        )
        alertedThisDischarge = (newState == 1)
        wasCharging = charging
        lastLevel = level
        guard let alert = alertLevel else { return }
        postLowBatteryNotification(level: alert)
    }

    private func postLowBatteryNotification(level: Int) {
        let content = UNMutableNotificationContent()
        content.title = "Phone battery low"
        content.body = "\(phoneName) is at \(level)%."
        content.sound = .default
        let req = UNNotificationRequest(identifier: "low-battery", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(req)
    }
}
