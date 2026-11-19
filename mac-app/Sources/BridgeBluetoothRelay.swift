import Foundation
import CoreBluetooth
import Network

// MARK: - Bluetooth LE L2CAP Loopback Relay
//
// Exposes the Mac's Bridge TCP service over Bluetooth LE so an Android phone
// can pair and transfer files with no Wi-Fi or internet. Each accepted L2CAP
// channel starts with a one-line JSON header {"relay":<port>} and is then
// piped byte-for-byte to 127.0.0.1:<port>, leaving the existing
// line-delimited JSON protocol (SPEKE + AES-256-GCM) untouched. Only the
// Bridge control port and active file-transfer data ports may be relayed.

final class BridgeBluetoothRelay: NSObject, CBPeripheralManagerDelegate {
    static let shared = BridgeBluetoothRelay()

    // Shared with the Android client; keep in sync.
    static let serviceUUID = "6B3F0E2A-6C1D-4E0B-9A57-2F1B8D5C0A01"
    static let infoCharUUID = "6B3F0E2A-6C1D-4E0B-9A57-2F1B8D5C0A02"

    static let maxHeaderBytes = 128

    private static let maxRelaySessions = 8
    private static let bridgeControlPort: UInt16 = 8765
    private static let tcpReceiveChunkSize = 256 * 1024
    private static let outboundHighWaterMark = 256 * 1024
    private static let maxPendingToTcpBytes = 8 * 1024 * 1024
    private static let bluetoothIdDefaultsKey = "bridgeBluetoothId"

    private let btQueue = DispatchQueue(label: "com.link.bridge.bluetooth")
    private let relayNetQueue = DispatchQueue(label: "com.link.bridge.bluetooth.net")
    private var peripheralManager: CBPeripheralManager?
    private var publishedPSM: UInt16?
    private var gattService: CBMutableService?
    private var gattServiceAddAttempted = false
    private var advertisingRequested = false

    private(set) var bluetoothState: CBManagerState = .unknown
    private(set) var isAdvertising = false
    var onStateChanged: (() -> Void)?

    private var relayThread: RelayRunLoopThread?
    var relayRunLoop: RunLoop? { relayThread?.runLoop }

    private let sessionsLock = NSLock()
    private var sessions = [RelaySession]()

    private let relayedPortsLock = NSLock()
    private var relayedLocalPorts = Set<UInt16>()

    // MARK: - Public API

    func start() {
        btQueue.async { [weak self] in
            guard let self = self, self.peripheralManager == nil else { return }
            self.peripheralManager = CBPeripheralManager(delegate: self, queue: self.btQueue)
        }
    }

    static var bluetoothId: String {
        bluetoothIdLock.lock()
        defer { bluetoothIdLock.unlock() }
        if let cached = cachedBluetoothId { return cached }

        let defaults = UserDefaults.standard
        if let existing = defaults.string(forKey: bluetoothIdDefaultsKey),
           existing.count == 8,
           existing.allSatisfy({ $0.isHexDigit }) {
            cachedBluetoothId = existing
            return existing
        }

        var bytes = [UInt8](repeating: 0, count: 4)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        let generated = bytes.map { String(format: "%02x", $0) }.joined()
        defaults.set(generated, forKey: bluetoothIdDefaultsKey)
        cachedBluetoothId = generated
        return generated
    }

    private static let bluetoothIdLock = NSLock()
    private static var cachedBluetoothId: String?

    func isBluetoothRelayedConnection(remotePort: UInt16) -> Bool {
        relayedPortsLock.lock()
        defer { relayedPortsLock.unlock() }
        return relayedLocalPorts.contains(remotePort)
    }

    // MARK: - Relay header parsing (pure; unit tested)

    /// Parses the `{"relay":<port>}\n` header that must open every L2CAP relay
    /// channel. Returns the target port and the number of header bytes
    /// (including the newline); anything after it is payload. Returns nil for
    /// incomplete, oversized, malformed, or out-of-range headers.
    static func parseRelayHeader(_ data: Data) -> (port: UInt16, headerLength: Int)? {
        guard let newlineIndex = data.firstIndex(of: UInt8(ascii: "\n")) else {
            return nil
        }
        let headerLength = newlineIndex + 1
        guard headerLength <= maxHeaderBytes else { return nil }
        let headerData = data.subdata(in: 0..<newlineIndex)
        guard let object = try? JSONSerialization.jsonObject(with: headerData) as? [String: Any],
              let relay = object["relay"] as? Int,
              relay > 0,
              relay <= Int(UInt16.max) else {
            return nil
        }
        return (port: UInt16(relay), headerLength: headerLength)
    }

    // MARK: - CBPeripheralManagerDelegate (btQueue)

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        bluetoothState = peripheral.state
        switch peripheral.state {
        case .poweredOn:
            if publishedPSM == nil {
                peripheral.publishL2CAPChannel(withEncryption: false)
            }
        case .poweredOff, .resetting, .unauthorized, .unsupported:
            publishedPSM = nil
            gattServiceAddAttempted = false
            advertisingRequested = false
            isAdvertising = false
            closeAllSessions()
        case .unknown:
            break
        @unknown default:
            break
        }
        notifyStateChanged()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didPublishL2CAPChannel psm: CBL2CAPPSM, error: Error?) {
        if let error = error {
            NSLog("[BridgeBluetooth] Failed to publish L2CAP channel: %@", "\(error)")
            publishedPSM = nil
            return
        }
        publishedPSM = psm
        ensureGATTService(peripheral: peripheral)
        ensureAdvertising(peripheral: peripheral)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didOpen channel: CBL2CAPChannel?, error: Error?) {
        if let error = error {
            NSLog("[BridgeBluetooth] L2CAP channel open failed: %@", "\(error)")
            return
        }
        guard let channel = channel else { return }

        sessionsLock.lock()
        let atCapacity = sessions.count >= Self.maxRelaySessions
        sessionsLock.unlock()
        guard !atCapacity else {
            NSLog("[BridgeBluetooth] L2CAP relay session limit reached; rejecting channel")
            channel.inputStream?.close()
            channel.outputStream?.close()
            return
        }

        ensureRelayThread()
        let session = RelaySession(channel: channel, owner: self)
        registerSession(session)
        session.begin()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard request.characteristic.uuid == CBUUID(string: Self.infoCharUUID) else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
            return
        }

        let info: [String: Any] = [
            "psm": Int(publishedPSM ?? 0),
            "id": Self.bluetoothId,
            "name": Host.current().localizedName ?? "Mac"
        ]
        guard let jsonData = try? JSONSerialization.data(withJSONObject: info) else {
            peripheral.respond(to: request, withResult: .unlikelyError)
            return
        }

        let bytes = [UInt8](jsonData)
        let offset = max(0, request.offset)
        guard offset <= bytes.count else {
            peripheral.respond(to: request, withResult: .invalidOffset)
            return
        }
        request.value = jsonData.subdata(in: offset..<jsonData.count)
        peripheral.respond(to: request, withResult: .success)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error = error {
            NSLog("[BridgeBluetooth] Failed to add GATT service: %@", "\(error)")
            gattServiceAddAttempted = false
        }
        notifyStateChanged()
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error = error {
            NSLog("[BridgeBluetooth] Failed to start advertising: %@", "\(error)")
            advertisingRequested = false
            isAdvertising = false
        } else {
            isAdvertising = true
        }
        notifyStateChanged()
    }

    // MARK: - Session & port registry (any thread)

    private func registerSession(_ session: RelaySession) {
        sessionsLock.lock()
        sessions.append(session)
        sessionsLock.unlock()
    }

    private func unregisterSession(_ session: RelaySession) {
        sessionsLock.lock()
        sessions.removeAll { $0 === session }
        sessionsLock.unlock()
    }

    private func registerRelayedLocalPort(_ port: UInt16) {
        relayedPortsLock.lock()
        relayedLocalPorts.insert(port)
        relayedPortsLock.unlock()
    }

    private func unregisterRelayedLocalPort(_ port: UInt16) {
        relayedPortsLock.lock()
        relayedLocalPorts.remove(port)
        relayedPortsLock.unlock()
    }

    // MARK: - Internals (btQueue)

    private func ensureGATTService(peripheral: CBPeripheralManager) {
        if gattService == nil {
            let infoCharacteristic = CBMutableCharacteristic(
                type: CBUUID(string: Self.infoCharUUID),
                properties: [.read],
                value: nil,
                permissions: [.readable]
            )
            let svc = CBMutableService(type: CBUUID(string: Self.serviceUUID), primary: true)
            svc.characteristics = [infoCharacteristic]
            gattService = svc
        }
        guard !gattServiceAddAttempted, let svc = gattService else { return }
        gattServiceAddAttempted = true
        peripheral.add(svc)
    }

    private func ensureAdvertising(peripheral: CBPeripheralManager) {
        guard !advertisingRequested else { return }
        advertisingRequested = true
        peripheral.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [CBUUID(string: Self.serviceUUID)],
            CBAdvertisementDataLocalNameKey: "Bridge"
        ])
    }

    private func ensureRelayThread() {
        if relayThread == nil {
            let thread = RelayRunLoopThread()
            thread.name = "com.link.bridge.bluetooth.relay"
            relayThread = thread
            thread.start()
            thread.waitUntilReady()
        }
    }

    private func closeAllSessions() {
        sessionsLock.lock()
        let all = sessions
        sessions.removeAll()
        sessionsLock.unlock()
        guard let rl = relayRunLoop else { return }
        rl.perform(inModes: [.default]) {
            for session in all {
                session.close()
            }
        }
        CFRunLoopWakeUp(rl.getCFRunLoop())
    }

    private func notifyStateChanged() {
        let callback = onStateChanged
        DispatchQueue.main.async {
            callback?()
        }
    }

    // MARK: - Relay run loop thread

    /// Dedicated thread whose run loop owns every relay session's NSStreams.
    /// A keep-alive timer prevents `run()` from exiting while idle.
    private final class RelayRunLoopThread: Thread {
        private let readySemaphore = DispatchSemaphore(value: 0)
        private(set) var runLoop: RunLoop?

        override func main() {
            Timer.scheduledTimer(withTimeInterval: 86400, repeats: true) { _ in }
            let rl = RunLoop.current
            runLoop = rl
            readySemaphore.signal()
            rl.run()
        }

        func waitUntilReady() {
            readySemaphore.wait()
        }
    }

    // MARK: - Per-channel relay session

    /// Pipes one accepted L2CAP channel to a loopback TCP socket. All stream
    /// and buffer state is confined to the relay thread's run loop; NWConnection
    /// callbacks arrive on `relayNetQueue` and hop back via `performOnRelayRunLoop`.
    private final class RelaySession: NSObject, StreamDelegate {
        private enum Phase {
            case awaitingHeader
            case piping
        }

        private weak var owner: BridgeBluetoothRelay?
        private let channel: CBL2CAPChannel

        private var phase: Phase = .awaitingHeader
        private var closed = false
        private var inputStream: InputStream?
        private var outputStream: OutputStream?
        private var headerBuffer = Data()
        private var pendingToTcp = Data()
        private var pendingOutbound = Data()
        private var tcpReady = false
        private var receiveInFlight = false
        private var conn: NWConnection?

        private let localPortLock = NSLock()
        private var storedLocalPort: UInt16?

        init(channel: CBL2CAPChannel, owner: BridgeBluetoothRelay) {
            self.channel = channel
            self.owner = owner
        }

        private var localPort: UInt16? {
            localPortLock.lock()
            defer { localPortLock.unlock() }
            return storedLocalPort
        }

        // MARK: Lifecycle

        func begin() {
            guard let rl = owner?.relayRunLoop else {
                close()
                return
            }
            rl.perform(inModes: [.default]) { [weak self] in
                self?.setupStreams()
            }
            CFRunLoopWakeUp(rl.getCFRunLoop())
        }

        func close() {
            guard !closed else { return }
            closed = true

            if let port = localPort {
                owner?.unregisterRelayedLocalPort(port)
            }

            conn?.cancel()
            conn = nil

            if let rl = owner?.relayRunLoop {
                inputStream?.delegate = nil
                outputStream?.delegate = nil
                inputStream?.remove(from: rl, forMode: .default)
                outputStream?.remove(from: rl, forMode: .default)
            }
            inputStream?.close()
            outputStream?.close()
            inputStream = nil
            outputStream = nil
            headerBuffer.removeAll()
            pendingToTcp.removeAll()
            pendingOutbound.removeAll()

            owner?.unregisterSession(self)
        }

        // MARK: Run loop confined

        private func setupStreams() {
            guard !closed, let rl = owner?.relayRunLoop else { return }
            guard let input = channel.inputStream, let output = channel.outputStream else {
                close()
                return
            }
            inputStream = input
            outputStream = output
            input.delegate = self
            output.delegate = self
            input.schedule(in: rl, forMode: .default)
            output.schedule(in: rl, forMode: .default)
            input.open()
            output.open()
        }

        func stream(_ aStream: Stream, handle event: Stream.Event) {
            // Invoked on the relay run loop.
            if event == .hasBytesAvailable, aStream === inputStream {
                readAvailableBytes()
            } else if event == .hasSpaceAvailable, aStream === outputStream {
                flushOutbound()
            } else if event == .endEncountered || event == .errorOccurred {
                if let error = aStream.streamError {
                    NSLog("[BridgeBluetooth] Relay stream error: %@", "\(error)")
                }
                close()
            }
        }

        private func readAvailableBytes() {
            guard let input = inputStream else { return }
            var buffer = [UInt8](repeating: 0, count: 32 * 1024)
            while input.hasBytesAvailable && !closed {
                let count = buffer.withUnsafeMutableBytes { raw -> Int in
                    guard let base = raw.baseAddress else { return 0 }
                    return input.read(base.assumingMemoryBound(to: UInt8.self), maxLength: raw.count)
                }
                guard count > 0 else { break }
                processInbound(Data(buffer[0..<count]))
            }
        }

        private func processInbound(_ data: Data) {
            guard !closed else { return }
            switch phase {
            case .awaitingHeader:
                headerBuffer.append(data)
                if let parsed = BridgeBluetoothRelay.parseRelayHeader(headerBuffer) {
                    openTcpConnection(port: parsed.port, headerLength: parsed.headerLength)
                } else if headerBuffer.contains(UInt8(ascii: "\n"))
                            || headerBuffer.count > BridgeBluetoothRelay.maxHeaderBytes {
                    NSLog("[BridgeBluetooth] Rejecting malformed relay header")
                    close()
                }
            case .piping:
                forwardToTcp(data)
            }
        }

        private static func isAllowedRelayPort(_ port: UInt16) -> Bool {
            if port == BridgeBluetoothRelay.bridgeControlPort { return true }
            return BridgeFileTransferManager.shared.isActiveDataPort(port)
        }

        private func openTcpConnection(port: UInt16, headerLength: Int) {
            guard Self.isAllowedRelayPort(port) else {
                NSLog("[BridgeBluetooth] Rejecting relay request for port %d", port)
                close()
                return
            }

            // Bytes after the header newline in the same read are payload.
            let remainder = headerBuffer.count > headerLength
                ? headerBuffer.subdata(in: headerLength..<headerBuffer.count)
                : Data()
            headerBuffer.removeAll(keepingCapacity: false)
            phase = .piping

            guard let nwPort = NWEndpoint.Port(rawValue: port), let owner = owner else {
                close()
                return
            }
            let params = NWParameters.tcp
            params.allowLocalEndpointReuse = true
            if let tcpOptions = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
                tcpOptions.noDelay = true
            }
            let connection = NWConnection(
                host: NWEndpoint.Host("127.0.0.1"),
                port: nwPort,
                using: params
            )
            conn = connection
            connection.stateUpdateHandler = { [weak self, weak connection] state in
                guard let self = self, let connection = connection else { return }
                self.noteLocalPort(of: connection)
                switch state {
                case .ready:
                    self.performOnRelayRunLoop {
                        guard !self.tcpReady else { return }
                        self.tcpReady = true
                        self.flushPendingToTcp()
                        self.startTcpReceiveLoop()
                    }
                case .failed, .cancelled:
                    self.performOnRelayRunLoop { self.close() }
                default:
                    break
                }
            }
            connection.start(queue: owner.relayNetQueue)
            if !remainder.isEmpty {
                forwardToTcp(remainder)
            }
        }

        /// Records the ephemeral local port of the outgoing loopback connection
        /// so BridgeServer can recognize relayed sessions. Runs on relayNetQueue.
        private func noteLocalPort(of connection: NWConnection) {
            guard let endpoint = connection.currentPath?.localEndpoint,
                  case .hostPort(_, let port) = endpoint else { return }
            let rawPort = port.rawValue
            localPortLock.lock()
            storedLocalPort = rawPort
            localPortLock.unlock()
            owner?.registerRelayedLocalPort(rawPort)
        }

        private func forwardToTcp(_ data: Data) {
            if tcpReady, conn != nil {
                sendToTcp(data)
            } else {
                pendingToTcp.append(data)
                if pendingToTcp.count > BridgeBluetoothRelay.maxPendingToTcpBytes {
                    NSLog("[BridgeBluetooth] Relay pending buffer overflow; closing")
                    close()
                }
            }
        }

        private func flushPendingToTcp() {
            guard !pendingToTcp.isEmpty else { return }
            let pending = pendingToTcp
            pendingToTcp.removeAll(keepingCapacity: true)
            sendToTcp(pending)
        }

        private func sendToTcp(_ data: Data) {
            guard let conn = conn else { return }
            conn.send(content: data, completion: .contentProcessed { [weak self] error in
                guard let self = self, let error = error else { return }
                NSLog("[BridgeBluetooth] Relay TCP send failed: %@", "\(error)")
                self.performOnRelayRunLoop { self.close() }
            })
        }

        private func startTcpReceiveLoop() {
            guard !closed, !receiveInFlight, let conn = conn else { return }
            receiveInFlight = true
            conn.receive(minimumIncompleteLength: 1, maximumLength: BridgeBluetoothRelay.tcpReceiveChunkSize) { [weak self] data, _, isComplete, error in
                guard let self = self else { return }
                self.performOnRelayRunLoop {
                    guard !self.closed else { return }
                    self.receiveInFlight = false
                    if let data = data, !data.isEmpty {
                        self.pendingOutbound.append(data)
                    }
                    if isComplete || error != nil {
                        if let error = error {
                            NSLog("[BridgeBluetooth] Relay TCP receive failed: %@", "\(error)")
                        }
                        self.close()
                        return
                    }
                    self.flushOutbound()
                }
            }
        }

        /// Writes buffered TCP->BLE bytes while the L2CAP output stream has
        /// room, then resumes TCP receives once the backlog drains.
        private func flushOutbound() {
            if let output = outputStream, output.hasSpaceAvailable {
                while !pendingOutbound.isEmpty && output.hasSpaceAvailable {
                    let written = pendingOutbound.withUnsafeBytes { raw -> Int in
                        guard let base = raw.baseAddress else { return 0 }
                        return output.write(base.assumingMemoryBound(to: UInt8.self), maxLength: raw.count)
                    }
                    guard written > 0 else { break }
                    pendingOutbound.removeSubrange(0..<written)
                }
            }
            if pendingOutbound.count < BridgeBluetoothRelay.outboundHighWaterMark {
                startTcpReceiveLoop()
            }
        }

        private func performOnRelayRunLoop(_ block: @escaping () -> Void) {
            guard let rl = owner?.relayRunLoop, !closed else { return }
            rl.perform(inModes: [.default]) { [weak self] in
                guard let self = self, !self.closed else { return }
                block()
            }
            CFRunLoopWakeUp(rl.getCFRunLoop())
        }
    }
}
