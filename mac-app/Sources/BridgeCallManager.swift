import SwiftUI
import UserNotifications

/// Receives {"type":"call_state", ...} from Android and mirrors the call on the
/// Mac: a ringing banner with Answer/Decline actions, and a call card pinned
/// above the tab strip while a call is ringing/active.
/// Call audio stays on the phone — this is display + remote control only.
@MainActor
final class BridgeCallManager: ObservableObject {
    static let shared = BridgeCallManager()

    enum CallState: String { case ringing, active, ended }

    struct CallInfo: Equatable {
        var state: CallState
        var name: String
        var number: String
        var direction: String // "incoming" | "outgoing"
        var startedAt: Date?
    }

    @Published var currentCall: CallInfo?

    private var endDismissWorkItem: DispatchWorkItem?
    /// Set by BridgeModel to the server's broadcastRawJson (String-based).
    var sendJsonHandler: ((String) -> Void)?

    private init() {}

    // MARK: - Routing (called from BridgeServer for "call_" types)

    func handleIncoming(json: [String: Any], from deviceId: String) {
        guard let type = json["type"] as? String else { return }
        if type == "call_state" {
            applyState(json)
        }
    }

    private func applyState(_ json: [String: Any]) {
        let stateStr = json["state"] as? String ?? "ended"
        let state = CallState(rawValue: stateStr) ?? .ended
        let name = (json["name"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let number = (json["number"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let direction = json["direction"] as? String ?? "incoming"
        let startedAtMs = json["startedAt"] as? Double ?? json["startedAt"] as? Int as? Double
        let startedAt = startedAtMs.map { Date(timeIntervalSince1970: $0 / 1000.0) }

        endDismissWorkItem?.cancel()

        switch state {
        case .ringing:
            currentCall = CallInfo(state: .ringing, name: name ?? number ?? "Unknown",
                                   number: number ?? "", direction: direction, startedAt: startedAt)
            postRingingNotification(name: name ?? number ?? "Unknown", number: number ?? "")
        case .active:
            let info = CallInfo(state: .active, name: name ?? number ?? "Unknown",
                                number: number ?? "", direction: direction,
                                startedAt: startedAt ?? Date())
            currentCall = info
            removeCallNotifications()
        case .ended:
            postEndedNotification()
            // Keep the card for 3 s so the user sees the end, then clear.
            let work = DispatchWorkItem { [weak self] in self?.currentCall = nil }
            endDismissWorkItem = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: work)
            currentCall = nil
        }
    }

    // MARK: - Actions (send call_action back to Android)

    func answerCall() { sendAction("answer") }
    func declineCall() { sendAction("decline") }
    func endCall() { sendAction("end") }

    private func sendAction(_ action: String) {
        let json = "{\"type\":\"call_action\",\"action\":\"\(action)\"}"
        sendJsonHandler?(json)
        if action == "end" {
            // Optimistically clear; the phone will confirm with call_state ended.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                if self?.currentCall?.state == .active { self?.currentCall = nil }
            }
        }
    }

    // MARK: - Local notifications

    private func postRingingNotification(name: String, number: String) {
        let content = UNMutableNotificationContent()
        content.title = "Incoming call"
        content.body = number.isEmpty ? name : "\(name) · \(number)"
        content.sound = .default
        content.categoryIdentifier = "CALL_ACTIONS"
        let req = UNNotificationRequest(identifier: "incoming-call", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(req)
    }

    private func removeCallNotifications() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ["incoming-call"])
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["incoming-call"])
    }

    private func postEndedNotification() {
        // No banner on end — the card fade is enough.
    }

}

extension BridgeCallManager {
    /// Elapsed seconds for the active call (computed in the view's TimelineView).
    static func elapsedSeconds(since startedAt: Date?) -> Int {
        guard let start = startedAt else { return 0 }
        return max(0, Int(Date().timeIntervalSince(start)))
    }

    static func formatElapsed(_ seconds: Int) -> String {
        let m = seconds / 60, s = seconds % 60
        return String(format: "%02d:%02d", m, s)
    }
}
