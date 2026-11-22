import Foundation
import CoreWLAN

/// Shared CoreWLAN join/restore for joining a phone hotspot and returning to
/// the previous network. Reuses FastLinkManager's static helpers so the
/// fast-link path stays untouched. Runs its blocking scan/associate loops off
/// the main thread (call from a background queue).
enum LinkWifiJoiner {
    private static let wifiClient = CWWiFiClient.shared()

    enum JoinError: Error {
        case noInterface
        case powerOn
        case joinFailed
        case noIP
    }

    /// Join [ssid] with [psk]. Throws JoinError on failure. Does NOT wait for
    /// an IP — callers that need one can poll `usableIPv4`.
    static func join(ssid: String, psk: String) throws {
        guard let iface = wifiClient.interface(), let interfaceName = iface.interfaceName else {
            throw JoinError.noInterface
        }
        if iface.ssid() != ssid {
            if !iface.powerOn() {
                do { try iface.setPower(true) } catch { throw JoinError.powerOn }
                let deadline = Date().addingTimeInterval(3)
                while !iface.powerOn() && Date() < deadline { usleep(100_000) }
                guard iface.powerOn() else { throw JoinError.powerOn }
            }
            var joined = false
            let joinDeadline = Date().addingTimeInterval(12)
            var emptyScans = 0
            var triedNetworkSetup = false
            while Date() < joinDeadline && !joined {
                let cachedHit = iface.cachedScanResults()?.first { $0.ssid == ssid }
                let network = cachedHit ?? (try? iface.scanForNetworks(withName: ssid))?.first
                if let network = network {
                    emptyScans = 0
                    do {
                        try iface.associate(to: network, password: psk)
                        joined = true
                    } catch { usleep(200_000) }
                } else {
                    emptyScans += 1
                    if emptyScans >= 3 && !triedNetworkSetup {
                        triedNetworkSetup = true
                        joined = FastLinkManager.joinViaNetworkSetup(
                            interfaceName: interfaceName, ssid: ssid, psk: psk)
                    }
                    usleep(250_000)
                }
            }
            guard joined else { throw JoinError.joinFailed }
        }
        // Wait briefly for an IP so the caller knows the link is usable.
        let ipDeadline = Date().addingTimeInterval(10)
        while Date() < ipDeadline {
            if FastLinkManager.usableIPv4(onInterface: interfaceName) != nil { return }
            usleep(50_000)
        }
        throw JoinError.noIP
    }

    /// Restore the network captured before joining. Mirrors FastLink's
    /// restore: rejoin previous SSID, power-cycle if Wi-Fi was on but no SSID,
    /// else just disassociate. Unlike fast link, the hotspot is left in
    /// preferred networks (the user wants internet).
    static func restore(previousSSID: String?, wasOnWifi: Bool) {
        let iface = wifiClient.interface()
        let effectivePrev = (previousSSID != nil && previousSSID == iface?.ssid()) ? nil : previousSSID
        let plan = FastLinkManager.restorePlan(previousSSID: effectivePrev, wasOnWifi: wasOnWifi)
        iface?.disassociate()
        switch plan {
        case .rejoin(let ssid):
            for _ in 0..<3 {
                guard let iface = iface else { break }
                let network = iface.cachedScanResults()?.first { $0.ssid == ssid }
                    ?? (try? iface.scanForNetworks(withName: ssid))?.first
                if let network = network {
                    do {
                        try iface.associate(to: network, password: nil)
                        return
                    } catch { usleep(500_000) }
                } else { usleep(500_000) }
            }
        case .powerCycle:
            iface?.disassociate()
            try? iface?.setPower(false)
            usleep(500_000)
            try? iface?.setPower(true)
        case .disassociateOnly:
            break
        }
    }
}
