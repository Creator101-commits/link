import Foundation
import AppKit
import SwiftUI
import Combine
import CoreImage.CIFilterBuiltins

class BridgeModel: ObservableObject {
    @Published var rawCode: String = ""
    @Published var formattedCode: String = ""
    @Published var localIp: String = "127.0.0.1"
    @Published var port: Int = 8765
    @Published var deviceName: String = "Mac"
    @Published var isConnected: Bool = false
    @Published var connectedDevice: String? = nil
    @Published var pairedDevice: String? = nil
    @Published var lastSyncedText: String? = nil
    @Published var lastSyncedOutgoing: Bool = false
    @Published var qrCodeImage: NSImage? = nil
    @Published var copiedNotice: Bool = false
    @Published var connectedDevicesList: [ConnectedDeviceInfo] = []
    @Published var selectedTargetDeviceId: UUID? = nil
    @Published var isDropTargeted: Bool = false {
        didSet {
            if isDropTargeted {
                prewarmFastLinkIfBluetooth()
            }
        }
    }
    @Published var deviceToUnpair: ConnectedDeviceInfo? = nil
    @Published var revealedDeviceIpIds: Set<UUID> = []
    @Published var isLocalIpRevealed: Bool = false
    enum BridgeTab: String, CaseIterable {
        case devices = "Devices"
        case notifications = "Notifications"
        case files = "Transfer"
        case settings = "Settings"
    }
    @Published var selectedTab: BridgeTab = .devices {
        didSet {
            if selectedTab != .devices && showPairingSection {
                showPairingSection = false
            }
            onSizeChangeRequested?()
        }
    }
    var notificationsManager: BridgeNotificationManager {
        return BridgeNotificationManager.shared
    }
    var fileTransferManager: BridgeFileTransferManager {
        return BridgeFileTransferManager.shared
    }

    @Published var showPairingSection: Bool = false {
        didSet {
            onSizeChangeRequested?()
        }
    }
    @Published var showUnpairConfirmation: Bool = false {
        didSet {
            onSizeChangeRequested?()
        }
    }

    var onSizeChangeRequested: (() -> Void)?
    
    let server = BridgeServer()
    private var cancellables = Set<AnyCancellable>()

    private static let keyPairedDevice = "bridge_paired_device_name"
    
    init(startServer: Bool = true) {
        self.rawCode = server.pairingCode
        if rawCode.count == 6 {
            self.formattedCode = "\(rawCode.prefix(3)) \(rawCode.suffix(3))"
        } else {
            self.formattedCode = rawCode
        }
        
        self.deviceName = Host.current().localizedName ?? "Mac"
        self.port = Int(server.port.rawValue)
        self.localIp = getPrimaryIPv4Address() ?? "127.0.0.1"
        self.pairedDevice = UserDefaults.standard.string(forKey: BridgeModel.keyPairedDevice)
        
        updateQrCode()
        setupServerCallbacks()
        if startServer {
            server.start()
            BridgeBluetoothRelay.shared.start()
            BridgeFastLinkManager.shared.requestLocationAuthorizationIfNeeded()
        }

        BridgeNotificationManager.shared.$activeNotifications
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.onSizeChangeRequested?()
            }
            .store(in: &cancellables)

        BridgeFileTransferManager.shared.$transfers
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.onSizeChangeRequested?()
            }
            .store(in: &cancellables)
    }
    
    private func setupServerCallbacks() {
        // Assigned once: the observer reads current connection state lazily.
        AXNotificationObserver.shared.shouldScan = { [weak self] in self?.isConnected == true }
        server.onStatusChanged = { [weak self] connected, device in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.isConnected = connected
                self.connectedDevice = device
                if connected {
                    if let dev = device, !dev.isEmpty {
                        self.pairedDevice = dev
                        UserDefaults.standard.set(dev, forKey: BridgeModel.keyPairedDevice)
                    }
                    self.showPairingSection = false
                }
                self.onSizeChangeRequested?()
            }
        }

        server.onConnectedDevicesChanged = { [weak self] devices in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.connectedDevicesList = devices
                if let first = devices.first {
                    self.connectedDevice = first.name
                    self.pairedDevice = first.name
                    UserDefaults.standard.set(first.name, forKey: BridgeModel.keyPairedDevice)
                    if self.selectedTargetDeviceId == nil || !devices.contains(where: { $0.id == self.selectedTargetDeviceId }) {
                        self.selectedTargetDeviceId = first.id
                    }
                } else {
                    self.selectedTargetDeviceId = nil
                }
                self.onSizeChangeRequested?()
            }
        }
        
        server.onClipboardSynced = { [weak self] text, outgoing in
            guard let self = self else { return }
            self.lastSyncedText = text
            self.lastSyncedOutgoing = outgoing
        }
        
        server.onIpChanged = { [weak self] newIp in
            guard let self = self else { return }
            if self.localIp != newIp {
                self.localIp = newIp
                self.updateQrCode()
            }
        }

        server.onRemoteUnpair = { [weak self] deviceId, _, remainingCount in
            guard let self = self else { return }
            self.connectedDevicesList.removeAll { $0.id == deviceId }
            self.revealedDeviceIpIds.remove(deviceId)
            if remainingCount == 0 && self.connectedDevicesList.isEmpty {
                self.finalizeFullUnpairState()
            } else if let first = self.connectedDevicesList.first {
                self.pairedDevice = first.name
                self.connectedDevice = first.name
                UserDefaults.standard.set(first.name, forKey: BridgeModel.keyPairedDevice)
                if self.selectedTargetDeviceId == deviceId || self.selectedTargetDeviceId == nil {
                    self.selectedTargetDeviceId = first.id
                }
                self.onSizeChangeRequested?()
            } else {
                self.onSizeChangeRequested?()
            }
        }
    }

    func toggleDeviceIpVisibility(_ id: UUID) {
        if revealedDeviceIpIds.contains(id) {
            revealedDeviceIpIds.remove(id)
        } else {
            revealedDeviceIpIds.insert(id)
        }
    }

    func hideAllIpAddresses() {
        isLocalIpRevealed = false
        revealedDeviceIpIds.removeAll()
    }
    
    func updateQrCode() {
        let jsonDict: [String: Any] = [
            "bridge": "pair",
            "ip": localIp,
            "port": port,
            "code": rawCode,
            "name": deviceName,
            "bt": BridgeBluetoothRelay.bluetoothId
        ]
        
        guard let jsonData = try? JSONSerialization.data(withJSONObject: jsonDict),
              let jsonString = String(data: jsonData, encoding: .utf8) else {
            return
        }
        
        self.qrCodeImage = generateQrImage(from: jsonString)
    }
    
    private func generateQrImage(from string: String) -> NSImage? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        
        guard let outputImage = filter.outputImage else { return nil }
        let scale = CGAffineTransform(scaleX: 7, y: 7)
        let scaledImage = outputImage.transformed(by: scale)
        
        guard let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) else { return nil }
        return NSImage(cgImage: cgImage, size: NSSize(width: scaledImage.extent.width, height: scaledImage.extent.height))
    }
    
    func copyPairingCode() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(rawCode, forType: .string)
        copiedNotice = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { [weak self] in
            self?.copiedNotice = false
        }
    }
    
    private func refreshPairingCodeFromServer() {
        rawCode = server.pairingCode
        if rawCode.count == 6 {
            formattedCode = "\(rawCode.prefix(3)) \(rawCode.suffix(3))"
        } else {
            formattedCode = rawCode
        }
        updateQrCode()
    }

    private func finalizeFullUnpairState() {
        pairedDevice = nil
        connectedDevicesList.removeAll()
        selectedTargetDeviceId = nil
        deviceToUnpair = nil
        showUnpairConfirmation = false
        showPairingSection = false
        isConnected = false
        connectedDevice = nil
        hideAllIpAddresses()
        UserDefaults.standard.removeObject(forKey: BridgeModel.keyPairedDevice)
        BridgeNotificationManager.shared.clearLocalNotificationsOnly()
        refreshPairingCodeFromServer()
        onSizeChangeRequested?()
    }

    func unpair(deviceId: UUID? = nil) {
        if let id = deviceId {
            server.disconnectDevice(id: id)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
                guard let self = self else { return }
                self.connectedDevicesList.removeAll { $0.id == id }
                self.revealedDeviceIpIds.remove(id)
                if self.connectedDevicesList.isEmpty {
                    self.finalizeFullUnpairState()
                } else if let first = self.connectedDevicesList.first {
                    self.pairedDevice = first.name
                    self.connectedDevice = first.name
                    UserDefaults.standard.set(first.name, forKey: BridgeModel.keyPairedDevice)
                    if self.selectedTargetDeviceId == id || self.selectedTargetDeviceId == nil {
                        self.selectedTargetDeviceId = first.id
                    }
                    self.deviceToUnpair = nil
                    self.onSizeChangeRequested?()
                }
            }
        } else {
            finalizeFullUnpairState()
            server.unpairAllDevices()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
                self?.refreshPairingCodeFromServer()
                self?.onSizeChangeRequested?()
            }
        }
    }

    var targetDeviceDisplayName: String {
        if let selId = selectedTargetDeviceId,
           let dev = connectedDevicesList.first(where: { $0.id == selId }) {
            return dev.name
        }
        if let first = connectedDevicesList.first {
            return first.name
        }
        return pairedDevice ?? "Android"
    }

    /// Sends the fast-link pre-warm hint when the drop target is on Bluetooth.
    private func prewarmFastLinkIfBluetooth() {
        let target = connectedDevicesList.first(where: { $0.id == selectedTargetDeviceId })
            ?? connectedDevicesList.first
        guard let target = target, target.ip == "Bluetooth" else { return }
        BridgeFastLinkManager.shared.sendPrewarmRequest(toDeviceId: target.id)
    }

    func sendFiles(_ urls: [URL]) {
        guard !urls.isEmpty else { return }
        let targetDev = connectedDevicesList.first(where: { $0.id == selectedTargetDeviceId })
            ?? connectedDevicesList.first
            ?? ConnectedDeviceInfo(id: UUID(), name: connectedDevice ?? pairedDevice ?? "Android", ip: nil, isConnected: isConnected)
        if targetDev.ip == "Bluetooth" {
            BridgeFastLinkManager.shared.sendPrewarmRequest(toDeviceId: targetDev.id)
        }
        BridgeFileTransferManager.shared.sendFiles(urls: urls, to: targetDev)
    }

    func selectFilesToSend() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseFiles = true
        panel.canChooseDirectories = true
        panel.title = "Select Files or Folders to Send to Android"
        panel.prompt = "Send"
        NSApp.activate(ignoringOtherApps: true)
        if panel.runModal() == .OK {
            sendFiles(panel.urls)
        }
    }

    var statusText: String {
        if isConnected {
            return "CONNECTED"
        } else if pairedDevice != nil {
            return "DISCONNECTED"
        } else {
            return "WAITING"
        }
    }

    var isConnectedOverBluetooth: Bool {
        connectedDevicesList.contains { $0.ip == "Bluetooth" }
    }

    static func isNewerVersion(remote: String, current: String) -> Bool {
        let rParts = parseVersionParts(remote)
        let cParts = parseVersionParts(current)
        let count = max(rParts.count, cParts.count)
        for i in 0..<count {
            let r = i < rParts.count ? rParts[i] : 0
            let c = i < cParts.count ? cParts[i] : 0
            if r > c { return true }
            if r < c { return false }
        }
        return false
    }

    private static func parseVersionParts(_ version: String) -> [Int] {
        let cleaned = version.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "^[vV]", with: "", options: .regularExpression)
            .components(separatedBy: CharacterSet(charactersIn: "-+")).first ?? ""
        return cleaned.split(separator: ".").map { Int($0) ?? 0 }
    }
}
