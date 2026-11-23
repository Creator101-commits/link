import Foundation
import CoreWLAN
import CoreLocation

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
        NSLog("[WifiJoiner] join start ssid=%@ locationAuthRaw=%@",
              ssid, "\(CLLocationManager.authorizationStatus().rawValue)")
        guard let iface = wifiClient.interface(), let interfaceName = iface.interfaceName else {
            NSLog("[WifiJoiner] join fail: noInterface")
            throw JoinError.noInterface
        }
        if iface.ssid() != ssid {
            if !iface.powerOn() {
                do { try iface.setPower(true) } catch {
                    NSLog("[WifiJoiner] join fail: powerOn setPower threw %@", "\(error)")
                    throw JoinError.powerOn
                }
                let deadline = Date().addingTimeInterval(3)
                while !iface.powerOn() && Date() < deadline { usleep(100_000) }
                guard iface.powerOn() else {
                    NSLog("[WifiJoiner] join fail: powerOn never came up")
                    throw JoinError.powerOn
                }
            }
            var joined = false
            let joinDeadline = Date().addingTimeInterval(12)
            var emptyScans = 0
            var triedNetworkSetup = false
            while Date() < joinDeadline && !joined {
                var scanResults = iface.cachedScanResults()
                var network = scanResults?.first { $0.ssid == ssid }
                if network == nil {
                    scanResults = try? iface.scanForNetworks(withName: ssid)
                    network = scanResults?.first
                }
                let scanCount = scanResults?.count ?? -1
                let nilSSIDCount = scanResults?.filter { $0.ssid == nil }.count ?? 0
                NSLog("[WifiJoiner] scan count=%d nilSSIDs=%d", scanCount, nilSSIDCount)
                if let network = network {
                    emptyScans = 0
                    do {
                        try iface.associate(to: network, password: psk)
                        joined = true
                    } catch {
                        let e = error as NSError
                        NSLog("[WifiJoiner] associate error code=%ld desc=%@", e.code, e.localizedDescription)
                        usleep(200_000)
                    }
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
            guard joined else {
                NSLog("[WifiJoiner] join fail: joinFailed (deadline)")
                throw JoinError.joinFailed
            }
        }
        // Wait briefly for an IP so the caller knows the link is usable.
        let ipDeadline = Date().addingTimeInterval(10)
        var gotIP: String?
        while Date() < ipDeadline {
            if let found = FastLinkManager.usableIPv4(onInterface: interfaceName) {
                gotIP = found
                break
            }
            usleep(50_000)
        }
        NSLog("[WifiJoiner] ipv4 wait result=%@", gotIP ?? "none")
        guard gotIP != nil else {
            NSLog("[WifiJoiner] join fail: noIP")
            throw JoinError.noIP
        }
    }

    /// Restore the network captured before joining. Mirrors FastLink's
    /// restore: rejoin previous SSID, power-cycle if Wi-Fi was on but no SSID,
    /// else just disassociate. Unlike fast link, the hotspot is left in
    /// preferred networks (the user wants internet).
    static func restore(previousSSID: String?, wasOnWifi: Bool) {
        let iface = wifiClient.interface()
        let effectivePrev = (previousSSID != nil && previousSSID == iface?.ssid()) ? nil : previousSSID
        let plan = FastLinkManager.restorePlan(previousSSID: effectivePrev, wasOnWifi: wasOnWifi)
        NSLog("[WifiJoiner] restore prev=%@ wasOnWifi=%d plan=%@",
              effectivePrev ?? "nil", wasOnWifi, plan.name)
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
                        NSLog("[WifiJoiner] restore rejoin %@ -> ok", ssid)
                        return
                    } catch {
                        let e = error as NSError
                        NSLog("[WifiJoiner] restore rejoin error code=%ld desc=%@", e.code, e.localizedDescription)
                        usleep(500_000)
                    }
                } else { usleep(500_000) }
            }
            NSLog("[WifiJoiner] restore rejoin %@ -> gave up", ssid)
        case .powerCycle:
            NSLog("[WifiJoiner] restore powerCycle")
            iface?.disassociate()
            try? iface?.setPower(false)
            usleep(500_000)
            try? iface?.setPower(true)
        case .disassociateOnly:
            NSLog("[WifiJoiner] restore disassociateOnly")
            break
        }
    }
}
