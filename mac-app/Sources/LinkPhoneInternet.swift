import Foundation
import CoreWLAN

/// Uses the paired phone's hotspot for internet. Flow: send hotspot_request,
/// the phone prompts the user / reports ready, we join the hotspot SSID and
/// stay connected (internet). Restores the previous network on disconnect or
/// when the phone reports the hotspot off. All over the encrypted JSON channel.
@MainActor
final class LinkPhoneInternet: ObservableObject {
    static let shared = LinkPhoneInternet()

    enum State: Equatable, CustomStringConvertible {
        case idle
        case waitingForPhone            // sent hotspot_request; cancellable
        case needsSetup                 // phone has no hotspot name/password saved
        case joining
        case connected(ssid: String)
        case failed(String)

        var description: String {
            switch self {
            case .idle: return "idle"
            case .waitingForPhone: return "waitingForPhone"
            case .needsSetup: return "needsSetup"
            case .joining: return "joining"
            case .connected(let ssid): return "connected(\(ssid))"
            case .failed(let msg): return "failed(\(msg))"
            }
        }
    }

    /// Thread-safe mirror of `isConnected` for readers off the main actor
    /// (e.g. FastLinkManager.handleOffer on fastLinkQueue).
    nonisolated(unsafe) static var isConnectedFlag = false

    @Published var state: State = .idle {
        didSet {
            Self.isConnectedFlag = isConnected
            NSLog("[PhoneInternet] state %@ -> %@", oldValue.description, state.description)
        }
    }
    /// True while joined to the phone hotspot (used to suppress fast link).
    var isConnected: Bool {
        if case .connected = state { return true }
        return false
    }

    var sendJsonHandler: ((String) -> Void)?

    private let wifiClient = CWWiFiClient.shared()
    private var waitTimeout: DispatchWorkItem?
    private var previousSSID: String?
    private var wasOnWifi = false
    private var joinedSSID: String?

    private init() {}

    // MARK: - Request

    /// Send {"type":"hotspot_request"} and wait for the phone's status.
    func request() {
        guard state == .idle || isTerminalFailure else { return }
        guard !FastLinkManager.shared.isActive else {
            state = .failed("Wait for the current transfer to finish")
            return
        }
        state = .waitingForPhone
        send(["type": "hotspot_request"])
        NSLog("[PhoneInternet] hotspot_request sent")
        armWaitTimeout()
    }

    private var isTerminalFailure: Bool {
        if case .failed = state { return true }
        return false
    }

    /// User tapped Cancel while waiting.
    func cancel() {
        guard state == .waitingForPhone else { return }
        send(["type": "hotspot_cancel"])
        NSLog("[PhoneInternet] hotspot_cancel sent")
        clearWait()
        state = .idle
    }

    /// User tapped Disconnect while connected.
    func disconnect() {
        guard isConnected else { return }
        restoreAndReset()
    }

    private func armWaitTimeout() {
        clearWait()
        let work = DispatchWorkItem { [weak self] in
            guard let self = self, self.state == .waitingForPhone else { return }
            self.send(["type": "hotspot_cancel"])
            self.state = .failed("Your phone didn't turn on the hotspot")
            DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in
                if case .failed = self?.state { self?.state = .idle }
            }
        }
        waitTimeout = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 180, execute: work)
    }

    private func clearWait() {
        waitTimeout?.cancel()
        waitTimeout = nil
    }

    // MARK: - Incoming hotspot_status

    /// Handle {"type":"hotspot_status", ...} routed from LinkServer.
    func handleStatus(_ json: [String: Any]) {
        guard let stateName = json["state"] as? String else { return }
        NSLog("[PhoneInternet] hotspot_status received state=%@", stateName)
        switch stateName {
        case "needs_setup":
            clearWait()
            state = .needsSetup
        case "prompted":
            // Phone showed its prompt; keep waiting (timeout still armed).
            break
        case "ready":
            guard let ssid = json["ssid"] as? String, !ssid.isEmpty,
                  let password = json["password"] as? String else {
                state = .failed("Your phone didn't share hotspot details")
                return
            }
            clearWait()
            beginJoin(ssid: ssid, password: password)
        case "off":
            if isConnected { restoreAndReset() }
            else if state == .waitingForPhone { state = .idle }
        case "timeout":
            if state == .waitingForPhone { state = .failed("Timed out waiting for your phone") }
        default:
            break
        }
    }

    // MARK: - Join / restore

    private func beginJoin(ssid: String, password: String) {
        // Remember where we were so we can restore.
        if let iface = wifiClient.interface() {
            wasOnWifi = iface.powerOn()
            previousSSID = iface.ssid().flatMap { $0.isEmpty ? nil : $0 }
        }
        state = .joining
        joinedSSID = ssid
        NSLog("[PhoneInternet] join start ssid=%@", ssid)
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            do {
                try LinkWifiJoiner.join(ssid: ssid, psk: password)
                NSLog("[PhoneInternet] join result ok ssid=%@", ssid)
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    self.state = .connected(ssid: ssid)
                }
            } catch {
                NSLog("[PhoneInternet] join result fail ssid=%@ err=%@", ssid, "\(error)")
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    // Restore the previous network but KEEP the failure visible;
                    // auto-return to idle after 6 s.
                    self.restoreNetwork()
                    self.state = .failed("Couldn't join \(ssid)")
                    DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak self] in
                        if case .failed = self?.state { self?.state = .idle }
                    }
                }
            }
        }
    }

    /// Restore the previous network only (does not change state).
    private func restoreNetwork() {
        let prev = previousSSID
        let wasOn = wasOnWifi
        NSLog("[PhoneInternet] restore prev=%@ wasOnWifi=%d", prev ?? "nil", wasOn)
        DispatchQueue.global(qos: .userInitiated).async {
            LinkWifiJoiner.restore(previousSSID: prev, wasOnWifi: wasOn)
        }
    }

    private func restoreAndReset() {
        joinedSSID = nil
        state = .idle
        restoreNetwork()
    }

    // MARK: - Send helper

    private func send(_ obj: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: obj, options: []),
              let json = String(data: data, encoding: .utf8) else { return }
        sendJsonHandler?(json)
    }
}
