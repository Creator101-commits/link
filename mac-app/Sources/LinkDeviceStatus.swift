import SwiftUI
import UserNotifications

/// Latest phone status received from Android via {"type":"device_status", ...}.
/// Cleared on disconnect. Drives the Devices-tab Phone card, the header
/// battery pill, and the low-battery alert.
@MainActor
final class LinkDeviceStatus: ObservableObject {
    static let shared = LinkDeviceStatus()

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

    private var alertStage = 0

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
        alertStage = 0
    }

    // MARK: - Low-battery alert

    /// Final alert level, always used in addition to the configured threshold.
    nonisolated static let criticalLevel = 10
    nonisolated static let defaultThreshold = 20

    /// Pure decision function — unit-tested.
    /// Alerts once at the configured threshold and once more at 10%.
    /// state: 0 = armed, 1 = alerted at threshold, 2 = alerted at 10%.
    nonisolated static func lowBatteryDecision(
        level: Int, charging: Bool, threshold: Int, state: Int
    ) -> (alert: Int?, newState: Int) {
        // Charging re-arms the alert for the next discharge.
        if charging { return (nil, 0) }
        guard threshold > 0 else { return (nil, state) }
        if level <= criticalLevel {
            return state < 2 ? (level, 2) : (nil, state)
        }
        if level <= threshold {
            return state < 1 ? (level, 1) : (nil, state)
        }
        // Re-arm once the level climbs 5 above the configured threshold.
        if state > 0 && level > threshold + 5 { return (nil, 0) }
        return (nil, state)
    }

    nonisolated static var configuredThreshold: Int {
        UserDefaults.standard.object(forKey: "lowBatteryThreshold") == nil
            ? defaultThreshold
            : UserDefaults.standard.integer(forKey: "lowBatteryThreshold")
    }

    private func evaluateLowBattery(_ s: Status) {
        guard let level = s.battery, let charging = s.charging else { return }
        let (alertLevel, newState) = LinkDeviceStatus.lowBatteryDecision(
            level: level, charging: charging, threshold: LinkDeviceStatus.configuredThreshold, state: alertStage
        )
        alertStage = newState
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
