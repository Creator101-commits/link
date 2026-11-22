import SwiftUI
import AppKit
import UniformTypeIdentifiers

// MARK: - Design Tokens

private extension Color {
    static let bridgeCard = Color.primary.opacity(0.06)
    static let bridgeCardHover = Color.primary.opacity(0.05)
    static let bridgeFill = Color.primary.opacity(0.08)
    static let bridgeSeparator = Color(nsColor: .separatorColor)
    static let bridgeAccentSoft = Color.accentColor.opacity(0.13)
    static let bridgeGreen = Color(nsColor: .systemGreen)
    static let bridgeRed = Color(nsColor: .systemRed)
    static let bridgeOrange = Color(nsColor: .systemOrange)
}

struct BridgeGlass: ViewModifier {
    var radius: CGFloat = 12
    /// Snapshot harness sets this so glass falls back to the spec fill+stroke
    /// (glassEffect does not rasterize under cacheDisplay).
    static var forceFallback = false
    func body(content: Content) -> some View {
        if #available(macOS 26.0, *), !Self.forceFallback {
            content.glassEffect(.regular, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
        } else {
            content
                .background(
                    RoundedRectangle(cornerRadius: radius, style: .continuous)
                        .fill(Color.primary.opacity(0.06))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: radius, style: .continuous)
                        .stroke(Color.bridgeSeparator, lineWidth: 0.5)
                )
        }
    }
}

private extension View {
    func bridgeGlass(radius: CGFloat = 12) -> some View {
        modifier(BridgeGlass(radius: radius))
    }
}

// MARK: - Link Glyph (from design/logo-concepts/link.svg)

struct LinkGlyphShape: Shape {
    func path(in rect: CGRect) -> Path {
        // Exact geometry from design/logo-concepts/link.svg (viewBox 0 0 128 128).
        // Two bracket subpaths (cubic corners + 6pt inner arcs) + center bar.
        var p = Path()
        let sx = rect.width / 128.0
        let sy = rect.height / 128.0
        func pt(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            CGPoint(x: rect.minX + x * sx, y: rect.minY + y * sy)
        }
        // Left bracket
        p.move(to: pt(48, 29))
        p.addLine(to: pt(36, 29))
        p.addCurve(to: pt(14, 51), control1: pt(23.8, 29), control2: pt(14, 38.8))
        p.addLine(to: pt(14, 77))
        p.addCurve(to: pt(36, 99), control1: pt(14, 89.2), control2: pt(23.8, 99))
        p.addLine(to: pt(48, 99))
        p.addLine(to: pt(48, 83))
        p.addLine(to: pt(36, 83))
        p.addArc(tangent1End: pt(30, 83), tangent2End: pt(30, 51), radius: 6 * sx)
        p.addArc(tangent1End: pt(30, 45), tangent2End: pt(48, 45), radius: 6 * sx)
        p.addLine(to: pt(48, 45))
        p.closeSubpath()
        // Right bracket
        p.move(to: pt(80, 29))
        p.addLine(to: pt(92, 29))
        p.addCurve(to: pt(114, 51), control1: pt(104.2, 29), control2: pt(114, 38.8))
        p.addLine(to: pt(114, 77))
        p.addCurve(to: pt(92, 99), control1: pt(114, 89.2), control2: pt(104.2, 99))
        p.addLine(to: pt(80, 99))
        p.addLine(to: pt(80, 83))
        p.addLine(to: pt(92, 83))
        p.addArc(tangent1End: pt(98, 83), tangent2End: pt(98, 51), radius: 6 * sx)
        p.addArc(tangent1End: pt(98, 45), tangent2End: pt(80, 45), radius: 6 * sx)
        p.addLine(to: pt(80, 45))
        p.closeSubpath()
        // Center bar (sharp rect per SVG)
        p.addRect(CGRect(x: rect.minX + 40 * sx, y: rect.minY + 56 * sy,
                         width: 48 * sx, height: 16 * sy))
        return p
    }
}

struct LinkGlyphView: View {
    var size: CGFloat = 26
    var body: some View {
        LinkGlyphShape()
            .fill(Color.primary)
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

// MARK: - Shared Components

struct BridgeCard<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        content
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Color.bridgeCard)
            )
    }
}

struct SectionHeader: View {
    let title: String
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        HStack {
            Text(title.uppercased())
                .font(.system(size: 11, weight: .semibold))
                .foregroundColor(.secondary)
                .tracking(0.6)
            Spacer()
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .font(.system(size: 11))
                    .foregroundColor(Color.accentColor)
                    .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 4)
    }
}

struct StatusCapsule: View {
    @ObservedObject var deviceStatus = BridgeDeviceStatus.shared
    let model: BridgeModel

    private var state: (color: Color, text: String) {
        if model.isConnected {
            let transport = model.isConnectedOverBluetooth
                ? "Bluetooth"
                : (BridgeFastLinkManager.shared.stateDescription == "Hotspot" ? "Hotspot" : "Wi-Fi")
            let battery = BridgeDeviceStatus.shared.status?.battery
            let suffix = battery.map { " · \($0)%" } ?? ""
            return (.bridgeGreen, "Connected · \(transport)\(suffix)")
        } else if model.pairedDevice != nil {
            return (.bridgeOrange, "Waiting for phone")
        } else {
            return (.secondary, "Not paired")
        }
    }

    var body: some View {
        HStack(spacing: 5) {
            StatusDotView(isConnected: model.isConnected, isWaiting: model.pairedDevice != nil && !model.isConnected)
            Text(state.text)
                .font(.system(size: 11))
                .foregroundColor(.secondary)
                .lineLimit(1)
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 4)
        .background(Capsule().fill(Color.bridgeFill))
        .fixedSize()
    }
}

struct StatusDotView: View {
    let isConnected: Bool
    var isWaiting: Bool = false
    @State private var breathe = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var color: Color {
        isConnected ? .bridgeGreen : (isWaiting ? .bridgeOrange : Color.secondary.opacity(0.6))
    }

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 7, height: 7)
            .scaleEffect(breathe && isWaiting && !reduceMotion ? 0.8 : 1.0)
            .opacity(breathe && isWaiting && !reduceMotion ? 0.35 : 1.0)
            .onAppear { updateAnimation() }
            .onChange(of: isWaiting) { _ in updateAnimation() }
            .onChange(of: reduceMotion) { _ in updateAnimation() }
    }

    private func updateAnimation() {
        if isWaiting && !reduceMotion {
            withAnimation(.easeInOut(duration: 1.2).repeatForever(autoreverses: true)) {
                breathe = true
            }
        } else {
            withAnimation(.default) { breathe = false }
        }
    }
}

struct MaskedIpToggleView: View {
    let prefix: String
    let ip: String
    let suffix: String
    let isRevealed: Bool
    let onToggle: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            if !prefix.isEmpty {
                Text(prefix)
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }
            Button(action: onToggle) {
                HStack(spacing: 4) {
                    Text("\(isRevealed ? ip : "•••.•••.•••")\(suffix)")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                    Image(systemName: isRevealed ? "eye.slash" : "eye")
                        .font(.system(size: 9, weight: .medium))
                        .foregroundColor(.secondary.opacity(0.85))
                        .frame(width: 14, height: 14)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help(isRevealed ? "Hide IP address" : "Show IP address")
        }
        .fixedSize(horizontal: false, vertical: true)
    }
}

// MARK: - Notification Card

struct NotificationCardView: View {
    let notification: BridgeNotification
    @ObservedObject var manager: BridgeNotificationManager

    private var replyBinding: Binding<String> {
        Binding(
            get: { manager.replyDrafts[notification.id] ?? "" },
            set: { manager.replyDrafts[notification.id] = $0 }
        )
    }

    private var isReplyEmpty: Bool {
        (manager.replyDrafts[notification.id] ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            // Header: app icon tile + app name + time + close
            HStack(spacing: 8) {
                Group {
                    if let icon = notification.iconImage {
                        Image(nsImage: icon).resizable().scaledToFill()
                    } else {
                        Image(systemName: "app.badge.fill").font(.system(size: 13))
                    }
                }
                .frame(width: 28, height: 28)
                .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(Color.bridgeFill))
                .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))

                Text(notification.app ?? "Phone")
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
                    .lineLimit(1)

                if let count = notification.messages?.count, count > 1 {
                    Text("\(count)")
                        .font(.system(size: 9, weight: .semibold))
                        .foregroundColor(Color.accentColor)
                }

                Spacer()

                Text(notification.relativeTimeText)
                    .font(.system(size: 10))
                    .foregroundColor(.secondary)

                Button(action: { manager.dismiss(id: notification.id) }) {
                    Image(systemName: "xmark")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundColor(.secondary)
                        .frame(width: 20, height: 20)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help("Dismiss notification")
            }

            if let title = notification.title, !title.isEmpty {
                Text(title)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(.primary)
                    .lineLimit(2)
            }

            if let msgList = notification.messages, msgList.count > 1 {
                VStack(alignment: .leading, spacing: 5) {
                    ForEach(msgList) { msg in
                        VStack(alignment: .leading, spacing: 1) {
                            if let sender = msg.sender, !sender.isEmpty, sender != notification.title {
                                Text(sender)
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundColor(Color.accentColor)
                            }
                            Text(msg.text)
                                .font(.system(size: 12))
                                .foregroundColor(.primary)
                                .lineSpacing(2)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
            } else if let text = notification.text, !text.isEmpty {
                Text(text)
                    .font(.system(size: 12))
                    .foregroundColor(.primary)
                    .lineSpacing(2.5)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if let replyAction = notification.actions?.first(where: { $0.hasInput }) {
                HStack(spacing: 6) {
                    TextField(replyAction.placeholder ?? "Reply…", text: replyBinding, onCommit: {
                        submitReply(actionId: replyAction.id)
                    })
                    .textFieldStyle(.plain)
                    .font(.system(size: 12))
                    .padding(.horizontal, 9)
                    .padding(.vertical, 5)
                    .background(
                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                            .fill(Color.primary.opacity(0.06))
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                            .stroke(Color.bridgeSeparator.opacity(0.5), lineWidth: 0.5)
                    )

                    Button(action: { submitReply(actionId: replyAction.id) }) {
                        Text("Send")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundColor(.white)
                            .padding(.horizontal, 11)
                            .frame(height: 26)
                            .background(
                                RoundedRectangle(cornerRadius: 8, style: .continuous)
                                    .fill(Color.accentColor)
                            )
                    }
                    .buttonStyle(.plain)
                    .disabled(isReplyEmpty)
                    .opacity(isReplyEmpty ? 0.4 : 1.0)
                }
                .padding(.top, 1)
            }

            if let errorMsg = manager.replyErrors[notification.id] {
                HStack(spacing: 6) {
                    Image(systemName: "exclamationmark.circle.fill")
                        .font(.system(size: 11))
                        .foregroundColor(.bridgeRed)
                    Text(errorMsg)
                        .font(.system(size: 11))
                        .foregroundColor(.bridgeRed)
                    Spacer()
                    if let replyAction = notification.actions?.first(where: { $0.hasInput }) {
                        Button(action: { submitReply(actionId: replyAction.id) }) {
                            Text("Retry")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(.bridgeRed)
                                .underline()
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.top, 1)
            }

            if let actions = notification.actions?.filter({ !$0.hasInput }), !actions.isEmpty {
                HStack(spacing: 6) {
                    ForEach(actions) { action in
                        Button(action: {
                            manager.sendAction(id: notification.id, actionId: action.id)
                        }) {
                            Text(action.title)
                                .font(.system(size: 11, weight: .medium))
                                .foregroundColor(.primary)
                                .padding(.horizontal, 9)
                                .frame(height: 24)
                                .background(
                                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                                        .fill(Color.primary.opacity(0.08))
                                )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.top, 1)
            }
        }
        .padding(11)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Color.bridgeCard)
        )
    }

    private func submitReply(actionId: Int) {
        let text = manager.replyDrafts[notification.id] ?? ""
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        manager.sendReply(id: notification.id, actionId: actionId, replyText: trimmed)
        manager.replyDrafts[notification.id] = ""
    }
}

// MARK: - File Transfer Card

struct FileTransferCardView: View {
    @ObservedObject var item: FileTransferItem
    let manager: BridgeFileTransferManager

    private var isFailed: Bool {
        if case .failed = item.status { return true }
        return false
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 9) {
                ZStack(alignment: .bottomTrailing) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 6, style: .continuous)
                            .fill(Color.primary.opacity(0.08))
                            .frame(width: 28, height: 28)
                        Image(systemName: item.iconSymbolName)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(.secondary)
                    }
                    if item.status == .completed {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(.bridgeGreen)
                            .background(Circle().fill(Color(nsColor: .windowBackgroundColor)).frame(width: 8, height: 8))
                            .offset(x: 4, y: 4)
                    }
                }

                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 4) {
                        Image(systemName: item.direction == .outgoing ? "arrow.up.right" : "arrow.down.left")
                            .font(.system(size: 8, weight: .bold))
                            .foregroundColor(.secondary)
                        Text(item.fileName)
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(.primary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                            .help(item.fileName)
                    }
                    Text(statusSubtitle)
                        .font(.system(size: 10))
                        .foregroundColor(statusTextColor)
                        .lineLimit(1)
                        .help(item.status == .completed ? "\(item.direction == .incoming ? "Received from" : "Sent to") \(item.deviceName) (\(item.formattedTotalSize))" : statusSubtitle)
                }

                Spacer(minLength: 4)

                HStack(spacing: 2) {
                    if item.status == .waitingConfirmation && item.direction == .incoming {
                        Button(action: { manager.acceptIncomingTransfer(item) }) {
                            Text("Accept")
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundColor(.white)
                                .padding(.horizontal, 9)
                                .frame(height: 24)
                                .background(RoundedRectangle(cornerRadius: 7, style: .continuous).fill(Color.accentColor))
                        }
                        .buttonStyle(.plain)
                        smallCancelButton(help: "Decline") { manager.cancelTransfer(id: item.id) }
                    } else if item.status == .transferring {
                        smallIconButton(systemName: "pause.fill", color: .secondary, help: "Pause transfer") {
                            manager.pauseTransfer(id: item.id)
                        }
                        smallIconButton(systemName: "xmark", color: .bridgeRed, help: "Cancel transfer") {
                            manager.cancelTransfer(id: item.id)
                        }
                    } else if item.status == .paused {
                        smallIconButton(systemName: "play.fill", color: Color.accentColor, help: "Resume transfer") {
                            manager.resumeTransfer(id: item.id)
                        }
                        smallIconButton(systemName: "xmark", color: .bridgeRed, help: "Cancel transfer") {
                            manager.cancelTransfer(id: item.id)
                        }
                    } else if item.status == .hashing || item.status == .waitingConfirmation || item.status == .verifying {
                        smallIconButton(systemName: "xmark", color: .secondary, help: "Cancel") {
                            manager.cancelTransfer(id: item.id)
                        }
                    } else if item.status == .completed, let savedURL = (item.destinationUrl ?? item.sourceUrl) {
                        smallIconButton(systemName: "magnifyingglass", color: .secondary, help: "Show in Finder") {
                            NSWorkspace.shared.activateFileViewerSelecting([savedURL])
                        }
                        if item.direction == .incoming {
                            Button(action: { NSWorkspace.shared.open(savedURL) }) {
                                Text("Open")
                                    .font(.system(size: 10, weight: .semibold))
                                    .foregroundColor(Color.accentColor)
                                    .padding(.horizontal, 8)
                                    .frame(height: 24)
                                    .background(RoundedRectangle(cornerRadius: 7, style: .continuous).stroke(Color.accentColor.opacity(0.4), lineWidth: 0.5))
                            }
                            .buttonStyle(.plain)
                        }
                    } else if case .failed(let reason) = item.status {
                        if reason.contains("System Settings") || reason.contains("Permission denied") {
                            Button(action: { manager.openFilesAndFoldersSettings() }) {
                                Text("Settings")
                                    .font(.system(size: 10, weight: .semibold))
                                    .foregroundColor(Color.accentColor)
                                    .padding(.horizontal, 8)
                                    .frame(height: 24)
                                    .background(RoundedRectangle(cornerRadius: 7, style: .continuous).stroke(Color.accentColor.opacity(0.4), lineWidth: 0.5))
                            }
                            .buttonStyle(.plain)
                            .help("Open macOS Privacy & Security Settings")
                        } else {
                            Button(action: { manager.retryTransfer(id: item.id) }) {
                                Text("Retry")
                                    .font(.system(size: 10, weight: .semibold))
                                    .foregroundColor(Color.accentColor)
                                    .padding(.horizontal, 8)
                                    .frame(height: 24)
                                    .background(RoundedRectangle(cornerRadius: 7, style: .continuous).stroke(Color.accentColor.opacity(0.4), lineWidth: 0.5))
                            }
                            .buttonStyle(.plain)
                            .help("Retry transfer")
                        }
                        smallIconButton(systemName: "xmark", color: .secondary, help: "Dismiss") {
                            manager.dismissTransfer(id: item.id)
                        }
                    } else if item.status == .cancelled {
                        smallIconButton(systemName: "xmark", color: .secondary, help: "Dismiss") {
                            manager.dismissTransfer(id: item.id)
                        }
                    }
                }
            }

            if item.status == .transferring || item.status == .paused || item.status == .verifying {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.primary.opacity(0.08)).frame(height: 3)
                        Capsule()
                            .fill(item.status == .paused ? Color.secondary : Color.accentColor)
                            .frame(width: max(6, geo.size.width * CGFloat(item.progress)), height: 3)
                    }
                }
                .frame(height: 3)
            }

            if item.isExecutable {
                HStack(spacing: 5) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 9))
                        .foregroundColor(.bridgeOrange)
                    Text("Executable file · Verify sender before opening")
                        .font(.system(size: 10, weight: .medium))
                        .foregroundColor(.bridgeOrange)
                    Spacer()
                }
                .padding(.horizontal, 7)
                .padding(.vertical, 3)
                .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(Color.bridgeOrange.opacity(0.12)))
            }
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.bridgeCard))
    }

    @ViewBuilder
    private func smallIconButton(systemName: String, color: Color, help: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 9, weight: .bold))
                .foregroundColor(color)
                .frame(width: 24, height: 24)
                .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(Color.primary.opacity(0.06)))
        }
        .buttonStyle(.plain)
        .help(help)
    }

    @ViewBuilder
    private func smallCancelButton(help: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: "xmark")
                .font(.system(size: 9, weight: .bold))
                .foregroundColor(.secondary)
                .frame(width: 24, height: 24)
                .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(Color.primary.opacity(0.06)))
        }
        .buttonStyle(.plain)
        .help(help)
    }

    private var statusColor: Color {
        switch item.status {
        case .completed: return .bridgeGreen
        case .failed: return .bridgeRed
        case .cancelled, .paused: return .secondary
        default: return Color.accentColor
        }
    }

    private var statusTextColor: Color {
        switch item.status {
        case .failed: return .bridgeRed
        default: return .secondary
        }
    }

    private var statusSubtitle: String {
        switch item.status {
        case .hashing:
            return item.isFolder ? "Packaging folder…" : "\(item.formattedTotalSize) · Preparing…"
        case .waitingConfirmation:
            return item.direction == .incoming
                ? "\(item.formattedTotalSize) · From \(item.deviceName)"
                : "\(item.formattedTotalSize) · Connecting to \(item.deviceName)…"
        case .transferring:
            let pct = item.percentageInt
            let speedStr = item.formattedSpeed.isEmpty ? "" : " · \(item.formattedSpeed)"
            return "\(pct)% · \(item.formattedProgressSize)\(speedStr)"
        case .paused:
            return "Paused (\(item.percentageInt)%) · \(item.formattedProgressSize)"
        case .verifying:
            return item.isFolder ? "Extracting folder…" : "Verifying SHA-256 checksum…"
        case .completed:
            let dev = item.deviceName.isEmpty ? "" : " · \(item.deviceName)"
            return "\(item.formattedTotalSize)\(dev)"
        case .cancelled:
            return "Cancelled"
        case .failed(let msg):
            return msg.isEmpty ? "Connection lost during transfer." : msg
        }
    }
}

// MARK: - Settings Subviews

struct MacSaveLocationRow: View {
    @ObservedObject var fileTransferManager: BridgeFileTransferManager

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Save Location")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(.primary)
                Text(fileTransferManager.saveLocationDisplayName)
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Spacer()
            HStack(spacing: 6) {
                if fileTransferManager.hasCustomSaveLocation {
                    Button(action: { fileTransferManager.resetSaveLocation() }) {
                        Text("Reset")
                            .font(.system(size: 11, weight: .medium))
                            .foregroundColor(.secondary)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(Color.primary.opacity(0.08)))
                    }
                    .buttonStyle(.plain)
                }
                Button(action: { fileTransferManager.chooseSaveLocation() }) {
                    Text("Change")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundColor(Color.accentColor)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(Color.bridgeAccentSoft))
                }
                .buttonStyle(.plain)
            }
        }
    }
}

// MARK: - Main Menu Bar View

struct BridgeMenuView: View {
    /// Snapshot-harness only: render just the Apps settings section.
    static var snapshotAppsOnly = false

    @ObservedObject var model: BridgeModel
    @ObservedObject var notifManager: BridgeNotificationManager = BridgeNotificationManager.shared
    @ObservedObject var fileTransferManager: BridgeFileTransferManager = BridgeFileTransferManager.shared
    @ObservedObject var axObserver: AXNotificationObserver = AXNotificationObserver.shared
    @ObservedObject var fastLinkManager: BridgeFastLinkManager = BridgeFastLinkManager.shared
    @ObservedObject var deviceStatus: BridgeDeviceStatus = BridgeDeviceStatus.shared
    @ObservedObject var callManager: BridgeCallManager = BridgeCallManager.shared
    @State private var expandedNotifApps: Set<String> = []

    private var isPairedOrConnected: Bool {
        model.isConnected || model.pairedDevice != nil || !model.connectedDevicesList.isEmpty
    }

    private var activeFileTransferCount: Int {
        fileTransferManager.transfers.filter { $0.status.isActive }.count
    }

    private var showingSettings: Bool {
        model.selectedTab == .settings
    }

    var body: some View {
        VStack(spacing: 10) {
            header
            if let call = callManager.currentCall {
                callCard(call)
            }
            ScrollView(.vertical, showsIndicators: false) {
                VStack(spacing: 10) {
                    if isPairedOrConnected {
                        if !showingSettings {
                            tabStrip
                        }
                        sectionHeader
                        contentArea
                            .id(model.selectedTab)
                            .transition(.opacity)
                    } else {
                        pairingContent
                    }
                }
            }
            Divider()
                .overlay(Color.bridgeSeparator)
            footer
        }
        .padding(16)
        .frame(width: 360)
        .background(
            Color(nsColor: .windowBackgroundColor)
                .padding(.top, -60)
                .padding(.bottom, -30)
                .ignoresSafeArea()
        )
        .animation(.easeOut(duration: 0.15), value: model.selectedTab)
        .onAppear { axObserver.checkTrustNow() }
    }

    // MARK: Header

    private var header: some View {
        HStack {
            LinkGlyphView(size: 26)
            Spacer(minLength: 8)
            StatusCapsule(model: model)
        }
        .padding(.top, 14)
    }

    // MARK: Tab Strip

    private var tabStrip: some View {
        HStack(spacing: 2) {
            tabButton(tab: .devices, systemName: "laptopcomputer.and.iphone", badge: nil, help: "Devices")
            tabButton(tab: .notifications, systemName: "bell", badge: notifManager.activeNotifications.count, help: "Notifications")
            tabButton(tab: .files, systemName: "arrow.up.arrow.down", badge: activeFileTransferCount, help: "Transfers")
        }
        .padding(3)
        .bridgeGlass(radius: 12)
        .fixedSize(horizontal: false, vertical: true)
    }

    private func tabButton(tab: BridgeModel.BridgeTab, systemName: String, badge: Int?, help: String) -> some View {
        let selected = model.selectedTab == tab
        return Button(action: {
            if model.selectedTab != tab { model.selectedTab = tab }
        }) {
            ZStack(alignment: .topTrailing) {
                Image(systemName: systemName)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(selected ? Color.accentColor : Color.secondary)
                    .frame(maxWidth: .infinity)
                    .frame(height: 30)
                    .background(
                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                            .fill(selected ? Color.bridgeAccentSoft : Color.clear)
                    )
                if let badge, badge > 0 {
                    Text(badge > 9 ? "9+" : "\(badge)")
                        .font(.system(size: 8, weight: .bold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 4)
                        .frame(height: 13)
                        .background(Capsule().fill(Color.accentColor))
                        .offset(x: -2, y: -4)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .help(help)
    }

    // MARK: Section Header

    @ViewBuilder
    private var sectionHeader: some View {
        if showingSettings {
            HStack(spacing: 8) {
                Button(action: { model.selectedTab = .devices }) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(Color.accentColor)
                        .frame(width: 24, height: 24)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help("Back")
                Text("Settings")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(.primary)
                Spacer()
            }
            .padding(.horizontal, 4)
        } else {
            switch model.selectedTab {
            case .devices:
                SectionHeader(title: "Devices")
            case .notifications:
                SectionHeader(title: "Notifications",
                              actionTitle: notifManager.activeNotifications.isEmpty ? nil : "Clear all") {
                    notifManager.clearAll()
                }
            case .files:
                SectionHeader(title: "Transfers",
                              actionTitle: fileTransferManager.transfers.contains(where: { $0.status == .completed }) ? "Clear completed" : nil) {
                    fileTransferManager.clearCompletedTransfers()
                }
            case .settings:
                EmptyView()
            }
        }
    }

    // MARK: Content

    @ViewBuilder
    private var contentArea: some View {
        if showingSettings {
            settingsContent
        } else {
            switch model.selectedTab {
            case .devices: devicesContent
            case .notifications: notificationsContent
            case .files: filesContent
            case .settings: EmptyView()
            }
        }
    }

    // MARK: Devices

    @ViewBuilder
    private var devicesContent: some View {
        if model.showUnpairConfirmation {
            unpairConfirmationCard
        } else {
            VStack(spacing: 8) {
                phoneStatusCard
                // Devices: This Mac + phones in ONE grouped card with hairlines
                BridgeCard {
                    VStack(spacing: 0) {
                        deviceLine(
                            icon: "laptopcomputer",
                            name: "This Mac",
                            subtitle: "macOS",
                            statusColor: model.isConnected ? Color.bridgeGreen : .secondary,
                            onUnpair: nil
                        )
                        deviceHairline
                        if !model.connectedDevicesList.isEmpty {
                            ForEach(Array(model.connectedDevicesList.enumerated()), id: \.element.id) { idx, device in
                                deviceLine(
                                    icon: "iphone",
                                    name: device.name,
                                    subtitle: deviceSubtitle(for: device),
                                    statusColor: device.isConnected ? Color.bridgeGreen : .secondary,
                                    onUnpair: { model.deviceToUnpair = device; model.showUnpairConfirmation = true },
                                    ipReveal: device.ip.flatMap { $0 == "Bluetooth" ? nil : $0 }.map { ip in
                                        AnyView(MaskedIpToggleView(
                                            prefix: "",
                                            ip: ip,
                                            suffix: "",
                                            isRevealed: model.revealedDeviceIpIds.contains(device.id),
                                            onToggle: { model.toggleDeviceIpVisibility(device.id) }
                                        ))
                                    } ?? AnyView(EmptyView())
                                )
                                if idx < model.connectedDevicesList.count - 1 { deviceHairline }
                            }
                        } else {
                            deviceLine(
                                icon: "iphone",
                                name: model.connectedDevice ?? model.pairedDevice ?? "Phone",
                                subtitle: model.isConnected
                                    ? (model.isConnectedOverBluetooth ? "Connected · Bluetooth" : "Connected · Wi-Fi")
                                    : "Disconnected · Check Bluetooth or Wi-Fi",
                                statusColor: model.isConnected ? Color.bridgeGreen : .secondary,
                                onUnpair: { model.deviceToUnpair = nil; model.showUnpairConfirmation = true }
                            )
                        }
                    }
                }

                // Transport status card
                BridgeCard {
                    VStack(spacing: 0) {
                        transportLine(icon: "wifi", name: "Wi-Fi", active: activeTransport == .wifi)
                        deviceHairline
                        transportLine(icon: "antenna.radiowaves.left.and.right", name: "Bluetooth", active: activeTransport == .bluetooth)
                        deviceHairline
                        transportLine(icon: "personalhotspot", name: "Hotspot", active: activeTransport == .hotspot)
                        if let fastLinkStatus = fastLinkManager.statusText, !fastLinkStatus.isEmpty {
                            deviceHairline
                            HStack(spacing: 8) {
                                Image(systemName: "bolt.fill")
                                    .font(.system(size: 11, weight: .medium))
                                    .foregroundColor(Color.accentColor)
                                    .frame(width: 20)
                                Text(fastLinkStatus)
                                    .font(.system(size: 11))
                                    .foregroundColor(.secondary)
                                Spacer()
                            }
                            .padding(.vertical, 8)
                        }
                    }
                }

                // Actions card — Wi-Fi password share + phone internet
                actionsCard

                // Pair another device
                Button(action: { model.showPairingSection.toggle() }) {
                    HStack(spacing: 6) {
                        Image(systemName: model.showPairingSection ? "chevron.up" : "plus")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundColor(Color.accentColor)
                        Text(model.showPairingSection ? "Hide pairing code" : "Pair another device")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(.primary)
                        Spacer()
                    }
                    .padding(.horizontal, 12)
                    .frame(height: 36)
                    .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.bridgeCard))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                // Pairing card when expanded
                if model.showPairingSection && !model.showUnpairConfirmation {
                    pairingCard(compact: true)
                }
            }
        }
    }

    private enum TransportKind { case wifi, bluetooth, hotspot }

    private var activeTransport: TransportKind {
        if fastLinkManager.stateDescription == "Hotspot" { return .hotspot }
        if model.isConnectedOverBluetooth { return .bluetooth }
        return .wifi
    }

    private var deviceHairline: some View {
        Rectangle()
            .fill(Color.bridgeSeparator.opacity(0.5))
            .frame(height: 0.5)
            .padding(.leading, 50)
    }

    private func transportLine(icon: String, name: String, active: Bool) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(.secondary)
                .frame(width: 20)
            Text(name)
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(.primary)
            Spacer()
            if active {
                Text("Active")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundColor(Color.accentColor)
            } else {
                Text("Idle")
                    .font(.system(size: 11))
                    .foregroundColor(Color.secondary.opacity(0.6))
            }
        }
        .padding(.vertical, 8)
    }

    // MARK: - Actions card (Wi-Fi share + phone internet)

    @ObservedObject private var wifiShare = BridgeWifiShare.shared
    @ObservedObject private var phoneInternet = BridgePhoneInternet.shared

    private var actionsCard: some View {
        BridgeCard {
            VStack(spacing: 0) {
                actionRow(
                    icon: "wifi",
                    title: "Share Wi-Fi password",
                    subtitle: wifiShareSubtitle,
                    enabled: model.isConnected && wifiShareRowEnabled,
                    trailing: { wifiShareTrailing },
                    action: wifiShareAction
                )
                deviceHairline
                actionRow(
                    icon: "personalhotspot",
                    title: "Use phone's internet",
                    subtitle: phoneInternetSubtitle,
                    enabled: model.isConnected && phoneInternet.state != .joining,
                    trailing: { phoneInternetTrailing },
                    action: phoneInternetAction
                )
            }
        }
        .onAppear { wifiShare.refreshSSID() }
    }

    private var wifiShareSubtitle: String {
        switch wifiShare.state {
        case .reading: return "Reading password…"
        case .sent: return "Sent to your phone"
        case .failed(let msg): return msg
        case .unsupported(let msg): return msg
        case .idle:
            if wifiShare.needsLocationForSSID {
                return "Allow Location to read the Wi-Fi name"
            }
            return wifiShare.currentSSID ?? "Not on Wi-Fi"
        }
    }

    private var wifiShareRowEnabled: Bool {
        if wifiShare.state == .reading { return false }
        // Enabled when we have an SSID, or when Location is the only blocker.
        return wifiShare.currentSSID != nil || wifiShare.needsLocationForSSID
    }

    private func wifiShareAction() {
        if wifiShare.needsLocationForSSID && wifiShare.currentSSID == nil {
            wifiShare.handleLocationPrompt()
            return
        }
        wifiShare.sharePassword()
    }

    @ViewBuilder private var wifiShareTrailing: some View {
        switch wifiShare.state {
        case .reading:
            ProgressView().scaleEffect(0.6).frame(width: 16, height: 16)
        case .sent:
            Image(systemName: "checkmark.circle.fill").foregroundColor(.bridgeGreen).font(.system(size: 14))
        default:
            Image(systemName: "chevron.right").font(.system(size: 11)).foregroundColor(.secondary.opacity(0.5))
        }
    }

    private var phoneInternetSubtitle: String {
        switch phoneInternet.state {
        case .idle: return "Turns on your phone's hotspot"
        case .waitingForPhone: return "Waiting for your phone…"
        case .needsSetup: return "Set your hotspot name and password in Bridge on your phone"
        case .joining: return "Joining…"
        case .connected(let ssid): return "Connected to \(ssid)"
        case .failed(let msg): return msg
        }
    }

    @ViewBuilder private var phoneInternetTrailing: some View {
        switch phoneInternet.state {
        case .waitingForPhone:
            Button("Cancel") { phoneInternet.cancel() }
                .font(.system(size: 11, weight: .medium)).buttonStyle(.plain).foregroundColor(.secondary)
        case .connected:
            Button("Disconnect") { phoneInternet.disconnect() }
                .font(.system(size: 11, weight: .medium)).buttonStyle(.plain).foregroundColor(.red)
        case .joining:
            ProgressView().scaleEffect(0.6).frame(width: 16, height: 16)
        default:
            Image(systemName: "chevron.right").font(.system(size: 11)).foregroundColor(.secondary.opacity(0.5))
        }
    }

    private func phoneInternetAction() {
        switch phoneInternet.state {
        case .idle, .failed: phoneInternet.request()
        case .needsSetup: break // user must set up on the phone
        default: break
        }
    }

    private func actionRow(icon: String, title: String, subtitle: String, enabled: Bool,
                           @ViewBuilder trailing: () -> some View, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                ZStack {
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(Color.primary.opacity(0.08))
                        .frame(width: 28, height: 28)
                    Image(systemName: icon)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(.secondary)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.system(size: 12, weight: .medium)).foregroundColor(.primary)
                    Text(subtitle).font(.system(size: 10)).foregroundColor(.secondary).lineLimit(1)
                }
                Spacer()
                trailing()
            }
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
    }

    private func deviceLine(icon: String, name: String, subtitle: String, statusColor: Color, onUnpair: (() -> Void)?, ipReveal: AnyView? = nil) -> some View {
        HStack(spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(Color.primary.opacity(0.08))
                    .frame(width: 28, height: 28)
                Image(systemName: icon)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(.secondary)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(name)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(.primary)
                    .lineLimit(1)
                if let ipReveal {
                    ipReveal
                } else {
                    Text(subtitle)
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 4)
            Circle()
                .fill(statusColor)
                .frame(width: 6, height: 6)
            if let onUnpair {
                Button(action: onUnpair) {
                    Text("Unpair")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundColor(.bridgeRed)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 9)
        .padding(.horizontal, 2)
    }

    private func deviceSubtitle(for device: ConnectedDeviceInfo) -> String {
        if device.isConnected {
            if device.ip == "Bluetooth" { return "Connected · Bluetooth" }
            if device.ip != nil { return "Connected · Wi-Fi" }
            return "Connected · Same Wi-Fi"
        }
        return "Disconnected · Check Bluetooth or Wi-Fi"
    }

    private var unpairConfirmationCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 12))
                    .foregroundColor(.bridgeRed)
                Text("Unpair \(model.deviceToUnpair?.name ?? model.connectedDevice ?? model.pairedDevice ?? "this phone")?")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(.primary)
            }
            Text("This will remove the pairing between this phone and your Mac. You'll need to pair them again to reconnect.")
                .font(.system(size: 11))
                .foregroundColor(.secondary)
                .lineSpacing(2)
            HStack {
                Spacer()
                Button("Cancel") { model.showUnpairConfirmation = false; model.deviceToUnpair = nil }
                    .font(.system(size: 12))
                    .foregroundColor(.primary)
                    .padding(.horizontal, 12)
                    .frame(height: 28)
                    .background(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(Color.bridgeSeparator, lineWidth: 0.5))
                    .buttonStyle(.plain)
                Button("Unpair") {
                    let devId = model.deviceToUnpair?.id
                    model.showUnpairConfirmation = false
                    model.unpair(deviceId: devId)
                }
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(.white)
                .padding(.horizontal, 14)
                .frame(height: 28)
                .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Color.bridgeRed))
                .buttonStyle(.plain)
            }
            .padding(.top, 4)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.bridgeCard))
        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(Color.bridgeRed.opacity(0.4), lineWidth: 0.5))
    }

    // MARK: Notifications

    @ViewBuilder
    private var notificationsContent: some View {
        if model.showPairingSection {
            pairingCard(compact: false)
        } else {
            VStack(spacing: 8) {
                if !notifManager.isSystemNotificationAllowed && notifManager.isEffectiveAndroidReceiveEnabled {
                    notificationWarningBanner(
                        icon: "bell.badge.fill",
                        title: "Mac Banners Disabled",
                        subtitle: "Allow Bridge in System Settings → Notifications to display mirrored notifications.",
                        action: { notifManager.openNotificationSettings() }
                    )
                }
                if !axObserver.isAccessibilityTrusted && notifManager.isEffectiveMacForwardEnabled {
                    notificationWarningBanner(
                        icon: "hand.raised.fill",
                        title: "Mac Alert Forwarding Paused",
                        subtitle: "Grant Accessibility permission to forward Mac notifications to your phone.",
                        action: { axObserver.promptAccessibilityPermissions() }
                    )
                }
                if notifManager.activeNotifications.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: notifManager.isEffectiveAndroidReceiveEnabled ? "bell.slash" : "pause.circle.fill")
                            .font(.system(size: 26))
                            .foregroundColor(Color.secondary.opacity(0.6))
                            .padding(.top, 6)
                        Text(notifManager.isEffectiveAndroidReceiveEnabled ? "No new notifications" : "Notifications paused")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(.primary)
                        Text(notifManager.isEffectiveAndroidReceiveEnabled
                             ? "Notifications from your phone will appear here in real time."
                             : "Android notifications are turned off in Settings.")
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 16)
                            .padding(.bottom, 6)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 20)
                    .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.bridgeCard))
                } else {
                    ScrollView(.vertical, showsIndicators: true) {
                        notificationCardsList
                            .padding(.trailing, 2)
                    }
                    .frame(maxHeight: 280)
                }
            }
        }
    }

    private func notificationWarningBanner(icon: String, title: String, subtitle: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 9) {
                Image(systemName: icon)
                    .font(.system(size: 13))
                    .foregroundColor(.bridgeOrange)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(.primary)
                    Text(subtitle)
                        .font(.system(size: 10))
                        .foregroundColor(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundColor(.secondary)
            }
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.bridgeCard))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Per-app notification stacks

    private struct AppStack: Identifiable {
        let pkg: String
        let name: String
        let level: String
        let notifs: [BridgeNotification]   // newest first
        var id: String { pkg }
        var newestTimestamp: Int64 { notifs.first?.timestamp ?? 0 }
    }

    /// Group active notifications by package; "high" apps sort first, the rest
    /// by newest notification timestamp.
    private var appStacks: [AppStack] {
        var groups: [String: [BridgeNotification]] = [:]
        for n in notifManager.activeNotifications {
            let key = n.pkg ?? (n.app ?? "notifications")
            groups[key, default: []].append(n)
        }
        let stacks = groups.map { (key, notifs) -> AppStack in
            let isRuled = notifManager.appRules[key] != nil
            let name = isRuled || notifManager.appDisplayNames[key] != nil
                ? notifManager.appDisplayName(for: key)
                : (notifs.first?.app ?? key)
            return AppStack(pkg: key, name: name,
                            level: notifManager.appRules[key] ?? "normal",
                            notifs: notifs)
        }
        return stacks.sorted { a, b in
            let aHigh = a.level == "high" ? 1 : 0
            let bHigh = b.level == "high" ? 1 : 0
            if aHigh != bHigh { return aHigh > bHigh }
            return a.newestTimestamp > b.newestTimestamp
        }
    }

    private var notificationCardsList: some View {
        VStack(spacing: 8) {
            ForEach(appStacks) { stack in
                appNotificationStack(stack)
            }
        }
    }

    private func appNotificationStack(_ stack: AppStack) -> some View {
        let expanded = expandedNotifApps.contains(stack.pkg)
        let visible = expanded ? stack.notifs : Array(stack.notifs.prefix(1))
        return VStack(alignment: .leading, spacing: 6) {
            // App header: icon + name + count + level menu
            HStack(spacing: 8) {
                appStackIcon(stack.pkg)
                Text(stack.name)
                    .font(.system(size: 12, weight: .semibold))
                    .lineLimit(1)
                Text("\(stack.notifs.count)")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                Spacer()
                appLevelMenu(stack.pkg, current: stack.level)
            }
            .padding(.horizontal, 2)

            ForEach(visible) { notif in
                NotificationCardView(notification: notif, manager: notifManager)
            }

            if stack.notifs.count > 1 {
                Button {
                    if expanded {
                        expandedNotifApps.remove(stack.pkg)
                    } else {
                        expandedNotifApps.insert(stack.pkg)
                    }
                } label: {
                    Text(expanded ? "Show less" : "\(stack.notifs.count - 1) more")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(Color.accentColor)
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 2)
            }
        }
        .padding(8)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color.primary.opacity(0.03)))
    }

    private func appStackIcon(_ pkg: String) -> some View {
        Group {
            if let img = BridgeAppIconCache.shared.image(pkg: pkg) {
                Image(nsImage: img).resizable().aspectRatio(contentMode: .fit)
            } else {
                Image(systemName: "app.fill").foregroundStyle(.secondary)
            }
        }
        .frame(width: 20, height: 20)
    }

    private func appLevelMenu(_ pkg: String, current: String) -> some View {
        Menu {
            ForEach(["high", "normal", "quiet", "muted", "off"], id: \.self) { lvl in
                Button {
                    notifManager.setAppRule(pkg, level: lvl)
                } label: {
                    if lvl == current {
                        Label(levelTitle(lvl), systemImage: "checkmark")
                    } else {
                        Text(levelTitle(lvl))
                    }
                }
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.secondary)
                .frame(width: 22, height: 22)
                .contentShape(Rectangle())
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
    }

    private func levelTitle(_ lvl: String) -> String {
        switch lvl {
        case "high": return "High"
        case "quiet": return "Quiet"
        case "muted": return "Muted"
        case "off": return "Off"
        default: return "Normal"
        }
    }

    // MARK: Files / Transfers

    @ViewBuilder
    private var filesContent: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Send-to target selector
            if !model.connectedDevicesList.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 5) {
                        ForEach(model.connectedDevicesList) { dev in
                            let isSelected = (model.selectedTargetDeviceId == dev.id) ||
                                (model.selectedTargetDeviceId == nil && model.connectedDevicesList.first?.id == dev.id)
                            Button(action: { model.selectedTargetDeviceId = dev.id }) {
                                HStack(spacing: 4) {
                                    Image(systemName: "iphone")
                                        .font(.system(size: 9, weight: .semibold))
                                    Text(dev.name)
                                        .font(.system(size: 11, weight: .medium))
                                        .lineLimit(1)
                                }
                                .foregroundColor(isSelected ? Color.accentColor : .secondary)
                                .padding(.horizontal, 9)
                                .frame(height: 24)
                                .background(
                                    Capsule()
                                        .fill(isSelected ? Color.bridgeAccentSoft : Color.primary.opacity(0.06))
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }

            // Drop zone
            Button(action: {
                if model.isConnected { model.selectFilesToSend() }
            }) {
                VStack(spacing: 5) {
                    Image(systemName: model.isDropTargeted ? "arrow.down.doc.fill" : "arrow.up.doc")
                        .font(.system(size: 20, weight: .medium))
                        .foregroundColor(model.isDropTargeted ? Color.accentColor : (model.isConnected ? Color.accentColor : .secondary))
                    Text(model.isDropTargeted ? "Drop to send to \(model.targetDeviceDisplayName)" : (model.isConnected ? "Drop files or click to browse" : "Connect your phone to transfer files"))
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(.primary)
                    Text(model.isDropTargeted ? "Release to begin transfer" : "Direct Bluetooth / Wi-Fi stream · No size limit")
                        .font(.system(size: 10))
                        .foregroundColor(.secondary)
                    if let fastLinkStatus = fastLinkManager.statusText {
                        Text(fastLinkStatus)
                            .font(.system(size: 10))
                            .foregroundColor(.secondary)
                    }
                }
                .padding(.vertical, 14)
                .frame(maxWidth: .infinity)
                .background(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(model.isDropTargeted ? Color.bridgeAccentSoft : Color.primary.opacity(0.04))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .strokeBorder(
                            model.isDropTargeted ? Color.accentColor : Color.bridgeSeparator,
                            style: StrokeStyle(lineWidth: 1, dash: [5, 4])
                        )
                )
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!model.isConnected)
            .opacity(model.isConnected ? 1.0 : 0.6)
            .onDrop(of: [.fileURL], isTargeted: $model.isDropTargeted) { providers in
                guard model.isConnected else { return false }
                var collectedURLs: [URL] = []
                let group = DispatchGroup()
                let lock = NSLock()
                for provider in providers {
                    if provider.canLoadObject(ofClass: NSURL.self) {
                        group.enter()
                        _ = provider.loadObject(ofClass: NSURL.self) { object, _ in
                            defer { group.leave() }
                            if let url = object as? URL {
                                lock.lock(); collectedURLs.append(url); lock.unlock()
                            }
                        }
                    }
                }
                group.notify(queue: .main) {
                    if !collectedURLs.isEmpty { model.sendFiles(collectedURLs) }
                }
                return true
            }

            // Transfers list
            if !fileTransferManager.transfers.isEmpty {
                ScrollView(.vertical, showsIndicators: true) {
                    fileTransferCardsList
                        .padding(.trailing, 2)
                }
                .frame(maxHeight: 240)
            } else {
                VStack(spacing: 6) {
                    Image(systemName: "tray")
                        .font(.system(size: 22))
                        .foregroundColor(Color.secondary.opacity(0.6))
                        .padding(.top, 8)
                    Text("No transfers yet")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(.primary)
                    Text("Files you send or receive will appear here.")
                        .font(.system(size: 10))
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.bottom, 8)
                }
                .frame(maxWidth: .infinity)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.bridgeCard))
            }
        }
    }

    private var fileTransferCardsList: some View {
        VStack(spacing: 8) {
            ForEach(fileTransferManager.transfers) { item in
                FileTransferCardView(item: item, manager: fileTransferManager)
            }
        }
    }

    // MARK: Settings

    @ViewBuilder
    private var appsSettingsSection: some View {
        settingsSection(title: "Apps") {
            BridgeCard {
                VStack(alignment: .leading, spacing: 0) {
                    let apps = BridgeNotificationManager.appList(
                        displayNames: notifManager.appDisplayNames,
                        rules: notifManager.appRules
                    )
                    ForEach(Array(apps.enumerated()), id: \.element.pkg) { idx, app in
                        appRuleRow(pkg: app.pkg, level: app.level)
                        if idx != apps.count - 1 {
                            Divider().overlay(Color.bridgeSeparator.opacity(0.5))
                        }
                    }
                    if apps.isEmpty {
                        Text("Apps appear here after they send a notification.")
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                            .padding(.vertical, 4)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var settingsContent: some View {
        if Self.snapshotAppsOnly {
            VStack(alignment: .leading, spacing: 14) {
                appsSettingsSection
            }
        } else {
        VStack(alignment: .leading, spacing: 14) {
                settingsSection(title: "Notifications") {
                    BridgeCard {
                        VStack(alignment: .leading, spacing: 10) {
                            settingsToggleRow(
                                title: "Notifications",
                                subtitle: "Sync notifications between your Mac and Android phone.",
                                isOn: $notifManager.isMasterNotificationsEnabled
                            )
                            if !notifManager.isMasterNotificationsEnabled {
                                HStack(spacing: 5) {
                                    Circle().fill(Color.bridgeRed).frame(width: 5, height: 5)
                                    Text("Paused while notification sync is off.")
                                        .font(.system(size: 10, weight: .medium))
                                        .foregroundColor(.bridgeRed)
                                }
                            }
                            Divider().overlay(Color.bridgeSeparator.opacity(0.5))
                            settingsToggleRow(
                                title: "Android Notifications",
                                subtitle: "Show phone notifications on this Mac",
                                isOn: $notifManager.isMirroringEnabled,
                                disabled: !notifManager.isMasterNotificationsEnabled
                            )
                            Divider().overlay(Color.bridgeSeparator.opacity(0.5))
                            settingsToggleRow(
                                title: "Mac Notifications",
                                subtitle: "Show Mac notifications on your phone",
                                isOn: $notifManager.isMacAlertsForwardingEnabled,
                                disabled: !notifManager.isMasterNotificationsEnabled
                            )
                            Divider().overlay(Color.bridgeSeparator.opacity(0.5))
                            settingsToggleRow(
                                title: "Play Notification Sounds",
                                subtitle: "Play a sound when a phone notification arrives.",
                                isOn: $notifManager.isSoundEnabled,
                                disabled: !notifManager.isEffectiveAndroidReceiveEnabled
                            )
                            Divider().overlay(Color.bridgeSeparator.opacity(0.5))
                            settingsToggleRow(
                                title: "Skip Silent Notifications",
                                subtitle: "Don't forward notifications that don't alert on the phone.",
                                isOn: $notifManager.isSkipSilentEnabled
                            )
                        }
                    }
                }

                settingsSection(title: "Phone") {
                    BridgeCard {
                        VStack(alignment: .leading, spacing: 10) {
                            lowBatterySettingsRow
                        }
                    }
                }

                appsSettingsSection

                settingsSection(title: "Transfers") {
                    BridgeCard {
                        VStack(alignment: .leading, spacing: 10) {
                            settingsToggleRow(
                                title: "Auto-Accept Incoming Files",
                                subtitle: "Automatically accept incoming files without prompting.",
                                isOn: $fileTransferManager.autoAcceptIncomingFiles
                            )
                            Divider().overlay(Color.bridgeSeparator.opacity(0.5))
                            settingsToggleRow(
                                title: "Fast Transfers via Phone Hotspot",
                                subtitle: "Your Mac briefly joins your phone's private hotspot for files, then rejoins your Wi-Fi.",
                                isOn: $fastLinkManager.isFastLinkEnabled
                            )
                            Divider().overlay(Color.bridgeSeparator.opacity(0.5))
                            MacSaveLocationRow(fileTransferManager: fileTransferManager)
                        }
                    }
                }

                settingsSection(title: "Permissions") {
                    BridgeCard {
                        VStack(alignment: .leading, spacing: 0) {
                            if !notifManager.isSystemNotificationAllowed {
                                settingsPermissionRow(
                                    icon: "bell.badge.fill",
                                    title: "Mac Banner Notifications",
                                    subtitle: "Allow Bridge in System Settings → Notifications.",
                                    action: { notifManager.openNotificationSettings() }
                                )
                            }
                            if !axObserver.isAccessibilityTrusted {
                                if !notifManager.isSystemNotificationAllowed {
                                    Divider().overlay(Color.bridgeSeparator.opacity(0.5)).padding(.leading, 30)
                                }
                                settingsPermissionRow(
                                    icon: "hand.raised.fill",
                                    title: "Mac Accessibility Permission",
                                    subtitle: "Grant Accessibility permission in System Settings.",
                                    action: { axObserver.promptAccessibilityPermissions() }
                                )
                            }
                            if !fileTransferManager.isFilesAndFoldersAllowed {
                                if !notifManager.isSystemNotificationAllowed || !axObserver.isAccessibilityTrusted {
                                    Divider().overlay(Color.bridgeSeparator.opacity(0.5)).padding(.leading, 30)
                                }
                                settingsPermissionRow(
                                    icon: "folder.badge.gearshape",
                                    title: "Mac Files & Folders Access",
                                    subtitle: "Allow Bridge to send files from your Documents folder.",
                                    action: { fileTransferManager.openFilesAndFoldersSettings() }
                                )
                            }
                            if notifManager.isSystemNotificationAllowed && axObserver.isAccessibilityTrusted && fileTransferManager.isFilesAndFoldersAllowed {
                                HStack(spacing: 6) {
                                    Image(systemName: "checkmark.circle.fill")
                                        .font(.system(size: 12))
                                        .foregroundColor(.bridgeGreen)
                                    Text("All permissions granted")
                                        .font(.system(size: 11))
                                        .foregroundColor(.secondary)
                                }
                                .padding(.vertical, 8)
                                .frame(maxWidth: .infinity)
                            }
                        }
                    }
                }

                settingsSection(title: "About") {
                    BridgeCard {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text("Version")
                                    .font(.system(size: 12))
                                    .foregroundColor(.primary)
                                Spacer()
                                Text(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0")
                                    .font(.system(size: 11, design: .monospaced))
                                    .foregroundColor(.secondary)
                            }
                            HStack {
                                Text("Protocol")
                                    .font(.system(size: 12))
                                    .foregroundColor(.primary)
                                Spacer()
                                Text("Encrypted · E2E")
                                    .font(.system(size: 11))
                                    .foregroundColor(.secondary)
                            }
                        }
                    }
                }
            }
            .padding(.bottom, 4)
        }
    }

    // MARK: - Phone status card

    private var phoneStatusCard: some View {
        let st = deviceStatus.status
        return Group {
            if let st = st {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 12) {
                        batteryIcon(for: st)
                            .font(.system(size: 26))
                            .foregroundStyle(.primary)
                            .frame(width: 34)
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 6) {
                                Text("\(st.battery ?? 0)%")
                                    .font(.system(size: 17, weight: .semibold))
                                if st.charging == true {
                                    Text("Charging")
                                        .font(.system(size: 12))
                                        .foregroundStyle(.secondary)
                                }
                            }
                            if let carrier = st.carrier, !carrier.isEmpty {
                                Text(carrier)
                                    .font(.system(size: 12))
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        HStack(spacing: 6) {
                            if let network = st.network, !network.isEmpty {
                                Text(network)
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundStyle(.secondary)
                            }
                            if let signal = st.signal {
                                Image(systemName: "cellularbars", variableValue: Double(signal) / 4.0)
                                    .symbolRenderingMode(.hierarchical)
                                    .foregroundStyle(.secondary)
                                    .font(.system(size: 18))
                            }
                        }
                    }
                    if st.noService == true {
                        Label("No service", systemImage: "antenna.radiowaves.left.and.right.slash")
                            .font(.system(size: 12))
                            .foregroundStyle(.orange)
                    }
                    if st.airplane == true {
                        Label("Airplane mode", systemImage: "airplane")
                            .font(.system(size: 12))
                            .foregroundStyle(.orange)
                    }
                }
                .padding(14)
                .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 12))
            }
        }
    }

    private func batteryIcon(for st: BridgeDeviceStatus.Status) -> some View {
        let level = st.battery ?? 0
        let name: String
        if st.charging == true {
            name = "battery.100.bolt"
        } else if level >= 90 { name = "battery.100" }
        else if level >= 65 { name = "battery.75" }
        else if level >= 40 { name = "battery.50" }
        else if level >= 15 { name = "battery.25" }
        else { name = "battery.0" }
        return Image(systemName: name)
    }

    // MARK: - Call card

    private func callCard(_ call: BridgeCallManager.CallInfo) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "person.fill")
                .font(.system(size: 16))
                .foregroundStyle(.white)
                .frame(width: 32, height: 32)
                .background(Color.accentColor.opacity(0.85), in: Circle())
            VStack(alignment: .leading, spacing: 1) {
                Text(call.name)
                    .font(.system(size: 13, weight: .semibold))
                    .lineLimit(1)
                if call.state == .active {
                    TimelineView(.periodic(from: .now, by: 1)) { _ in
                        Text("On phone · \(BridgeCallManager.formatElapsed(BridgeCallManager.elapsedSeconds(since: call.startedAt)))")
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                    }
                } else {
                    Text(call.number.isEmpty ? call.direction.capitalized : "\(call.number) · \(call.direction)")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer()
            if call.state == .ringing {
                callActionButton(icon: "phone.down.fill", color: .red) { callManager.declineCall() }
                callActionButton(icon: "phone.fill", color: .green) { callManager.answerCall() }
            } else {
                callActionButton(icon: "phone.down.fill", color: .red) { callManager.endCall() }
            }
        }
        .padding(10)
        .background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 12))
    }

    private func callActionButton(icon: String, color: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 30, height: 30)
                .background(color, in: Circle())
        }
        .buttonStyle(.plain)
    }

    private func appRuleRow(pkg: String, level: String) -> some View {
        HStack(spacing: 10) {
            if let icon = BridgeAppIconCache.shared.image(pkg: pkg) {
                Image(nsImage: icon)
                    .resizable().aspectRatio(contentMode: .fit)
                    .frame(width: 20, height: 20)
            } else {
                Image(systemName: "app.fill")
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
                    .frame(width: 20)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(notifManager.appDisplayName(for: pkg))
                    .font(.system(size: 13, weight: .medium))
                Text(pkg)
                    .font(.system(size: 10))
                    .foregroundStyle(.tertiary)
            }
            Spacer()
            Picker("", selection: Binding(
                get: { level },
                set: { notifManager.setAppRule(pkg, level: $0) }
            )) {
                Text("High").tag("high")
                Text("Normal").tag("normal")
                Text("Quiet").tag("quiet")
                Text("Muted").tag("muted")
                Text("Off").tag("off")
            }
            .labelsHidden()
            .pickerStyle(.menu)
        }
        .padding(.vertical, 4)
    }

    private var lowBatterySettingsRow: some View {
        HStack {
            Text("Low battery alert")
                .font(.system(size: 13))
            Spacer()
            Picker("", selection: Binding(
                get: { UserDefaults.standard.integer(forKey: "lowBatteryThreshold") },
                set: { UserDefaults.standard.set($0, forKey: "lowBatteryThreshold") }
            )) {
                Text("Off").tag(0)
                Text("10%").tag(10)
                Text("20%").tag(20)
                Text("30%").tag(30)
            }
            .labelsHidden()
            .pickerStyle(.menu)
        }
    }

    private func settingsSection<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title.uppercased())
                .font(.system(size: 11, weight: .semibold))
                .foregroundColor(.secondary)
                .tracking(0.6)
                .padding(.horizontal, 4)
            content()
        }
    }

    private func settingsToggleRow(title: String, subtitle: String, isOn: Binding<Bool>, disabled: Bool = false) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(.primary)
                Text(subtitle)
                    .font(.system(size: 10))
                    .foregroundColor(.secondary)
            }
            Spacer()
            Toggle("", isOn: isOn)
                .labelsHidden()
                .toggleStyle(.switch)
                .controlSize(.small)
                .tint(Color.accentColor)
                .disabled(disabled)
        }
        .opacity(disabled ? 0.4 : 1.0)
    }

    private func settingsPermissionRow(icon: String, title: String, subtitle: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: icon)
                    .font(.system(size: 13))
                    .foregroundColor(.bridgeOrange)
                    .frame(width: 20)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(.primary)
                    Text(subtitle)
                        .font(.system(size: 10))
                        .foregroundColor(.secondary)
                }
                Spacer()
                Text("Open Settings")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundColor(Color.accentColor)
                Image(systemName: "chevron.right")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundColor(.secondary)
            }
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: Pairing (not paired)

    @ViewBuilder
    private var pairingContent: some View {
        pairingCard(compact: false)
    }

    private func pairingCard(compact: Bool) -> some View {
        let qrSize: CGFloat = compact ? 140 : 168
        return VStack(spacing: 10) {
            // QR code on white card
            ZStack {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color.white)
                    .frame(width: qrSize, height: qrSize)
                if let qrImg = model.qrCodeImage {
                    Image(nsImage: qrImg)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .padding(10)
                        .frame(width: qrSize, height: qrSize)
                }
            }
            .shadow(color: .black.opacity(0.08), radius: 8, y: 2)

            Text("Scan with Bridge on your Android phone")
                .font(.system(size: 11))
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)

            // Pairing code
            VStack(spacing: 4) {
                Text(model.formattedCode)
                    .font(.system(size: compact ? 20 : 24, weight: .medium, design: .monospaced))
                    .foregroundColor(.primary)
                    .tracking(2)
                Button(action: { model.copyPairingCode() }) {
                    Text(model.copiedNotice ? "Copied" : "Copy code")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundColor(model.copiedNotice ? Color.accentColor : .secondary)
                }
                .buttonStyle(.plain)
            }

            // Other ways to connect disclosure
            DisclosureGroup {
                VStack(spacing: 8) {
                    HStack {
                        Text("IP Address")
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                        Spacer()
                        MaskedIpToggleView(
                            prefix: "",
                            ip: model.localIp,
                            suffix: ":\(model.port)",
                            isRevealed: model.isLocalIpRevealed,
                            onToggle: { model.isLocalIpRevealed.toggle() }
                        )
                    }
                    Divider().overlay(Color.bridgeSeparator.opacity(0.5))
                    HStack {
                        Text("Bluetooth ID")
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                        Spacer()
                        Text(BridgeBluetoothRelay.bluetoothId)
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundColor(.secondary)
                            .lineLimit(1)
                    }
                }
                .padding(.top, 6)
            } label: {
                Text("Other ways to connect")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundColor(Color.accentColor)
            }
            .tint(Color.accentColor)
        }
        .padding(14)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color.bridgeCard))
    }

    // MARK: Footer

    private var footer: some View {
        HStack(spacing: 8) {
            footerButton(title: "Settings", systemName: "gearshape") {
                model.selectedTab = showingSettings ? .devices : .settings
            }
            footerButton(title: "Quit", systemName: "power") {
                NSApp.terminate(nil)
            }
        }
        .padding(.top, 2)
    }

    private func footerButton(title: String, systemName: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Image(systemName: systemName)
                    .font(.system(size: 11, weight: .medium))
                Text(title)
                    .font(.system(size: 12, weight: .medium))
            }
            .foregroundColor(.primary)
            .frame(maxWidth: .infinity)
            .frame(height: 30)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .bridgeGlass(radius: 8)
        .opacity(title == "Settings" && showingSettings ? 0.6 : 1.0)
    }
}
