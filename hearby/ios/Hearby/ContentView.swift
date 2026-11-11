import Network
import SwiftUI

/// The receiver screen: find a host, join with one tap, fine-tune sync with the offset slider.
/// (iOS is listen-only: Apple gives third-party apps no way to capture other apps' audio.)
struct ContentView: View {
    @StateObject private var receiver = IOSSyncReceiver()
    @StateObject private var browser = HostBrowser()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 16) {
                    switch receiver.status.phase {
                    case .idle:
                        JoinView(browser: browser) { endpoint, name in
                            receiver.join(endpoint: endpoint, name: name)
                        }
                    case .ended:
                        StatusCard(
                            symbol: "headphones", active: false, title: "Session ended",
                            detail: receiver.status.message ?? "You left the session.")
                        Button("Find another host") { receiver.dismiss() }
                            .buttonStyle(PrimaryButtonStyle())
                    default:
                        ListeningView(receiver: receiver)
                    }
                }
                .padding(16)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }
            .background(Color(.systemGroupedBackground).ignoresSafeArea())
            .navigationTitle("Hearby")
            .navigationBarTitleDisplayMode(.inline)
        }
        .navigationViewStyle(.stack)
        .onAppear { updateBrowsing() }
        .onChange(of: receiver.status.phase) { _ in updateBrowsing() }
        .onChange(of: scenePhase) { _ in updateBrowsing() }
    }

    /// Browse only while the join screen is actually on display.
    private func updateBrowsing() {
        if receiver.status.phase == .idle && scenePhase == .active {
            browser.start()
        } else {
            browser.stop()
        }
    }
}

// MARK: - Join

private struct JoinView: View {
    @ObservedObject var browser: HostBrowser
    let onJoin: (NWEndpoint, String) -> Void

    @State private var manualAddress = ""
    @State private var manualError: String?

    var body: some View {
        Card {
            HStack {
                Text("Nearby hosts").font(.headline)
                Spacer()
                ProgressView()
            }
            if browser.permissionDenied {
                Text("Hearby is not allowed to look for devices on your network. Turn it on under Settings › Privacy & Security › Local Network.")
                    .font(.subheadline)
                    .foregroundColor(.orange)
            } else if browser.hosts.isEmpty {
                Text("Looking on this Wi-Fi network… Connect to the same Wi-Fi as the host, or to the host's hotspot.")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
            ForEach(browser.hosts) { host in
                Divider()
                HStack(spacing: 12) {
                    Image(systemName: "dot.radiowaves.left.and.right")
                        .foregroundColor(.accentColor)
                    Text(host.name).lineLimit(1)
                    Spacer()
                    Button("Join") { onJoin(host.endpoint, host.name) }
                        .buttonStyle(.borderedProminent)
                }
            }
        }

        Card {
            Text("Join by address").font(.headline)
            HStack(spacing: 12) {
                TextField("192.168.43.1", text: $manualAddress)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.numbersAndPunctuation)
                    .disableAutocorrection(true)
                    .textInputAutocapitalization(.never)
                    .submitLabel(.go)
                    .onSubmit(joinManually)
                Button("Join", action: joinManually)
                    .buttonStyle(.borderedProminent)
                    .disabled(manualAddress.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            Text(manualError ?? "The host's address is shown on its screen while it is sharing.")
                .font(.footnote)
                .foregroundColor(manualError == nil ? .secondary : .red)
        }
    }

    private func joinManually() {
        guard let parsed = AddressParser.parse(manualAddress) else {
            manualError = "Enter the address shown on the host, for example 192.168.43.1"
            return
        }
        manualError = nil
        let endpoint = NWEndpoint.hostPort(
            host: NWEndpoint.Host(parsed.address),
            port: NWEndpoint.Port(rawValue: parsed.port) ?? NWEndpoint.Port(rawValue: Proto.port)!)
        onJoin(endpoint, parsed.address)
    }
}

/// "192.168.1.20" or "192.168.1.20:47800"; literal IPv4 only.
enum AddressParser {
    static func parse(_ text: String) -> (address: String, port: UInt16)? {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        let hostAndPort = trimmed.split(separator: ":", omittingEmptySubsequences: false)
        guard hostAndPort.count == 1 || hostAndPort.count == 2 else { return nil }
        let octets = hostAndPort[0].split(separator: ".", omittingEmptySubsequences: false)
        guard octets.count == 4 else { return nil }
        var values: [Int] = []
        for octet in octets {
            guard !octet.isEmpty, octet.count <= 3, octet.allSatisfy({ $0.isASCII && $0.isNumber }),
                  let value = Int(octet), value <= 255 else { return nil }
            values.append(value)
        }
        var port = Proto.port
        if hostAndPort.count == 2 {
            guard let value = UInt16(hostAndPort[1]), value > 0 else { return nil }
            port = value
        }
        return (values.map(String.init).joined(separator: "."), port)
    }
}

// MARK: - Listening

private struct ListeningView: View {
    @ObservedObject var receiver: IOSSyncReceiver

    var body: some View {
        let status = receiver.status
        StatusCard(
            symbol: status.outputIsBluetooth ? "airpodspro" : "headphones",
            active: status.phase == .playing,
            title: title(for: status),
            detail: detail(for: status)
        ) {
            if status.phase == .playing {
                HStack {
                    Metric(label: "Sync error", value: signed(status.syncErrorMs) + " ms")
                    Spacer()
                    Metric(label: "Network", value: String(format: "%.1f ms", status.rttMs))
                    Spacer()
                    Metric(label: "Buffer", value: "\(status.bufferMs) ms")
                    Spacer()
                    Metric(label: "Loss", value: String(format: "%.1f %%", status.lossPercent))
                }
                .padding(.top, 8)
            }
        }

        if let message = status.message {
            Notice(text: message)
        }
        if status.lateBiasMs > 0 && status.phase == .playing {
            Notice(text: lateNotice(for: status))
        }

        OffsetCard(receiver: receiver)

        HStack(spacing: 12) {
            Button {
                receiver.resync()
            } label: {
                Label("Re-sync", systemImage: "arrow.triangle.2.circlepath")
            }
            .buttonStyle(SecondaryButtonStyle())
            Button("Leave", role: .destructive) { receiver.leave() }
                .buttonStyle(SecondaryButtonStyle(tint: .red))
        }
    }

    private func detail(for status: ReceiverStatus) -> String {
        status.outputName.isEmpty ? "Listening to \(status.hostName)" : "Listening to \(status.hostName) on \(status.outputName)"
    }

    private func lateNotice(for status: ReceiverStatus) -> String {
        var text = "This device needs more time than the host's \(status.playoutDelayMs) ms sync buffer allows, "
        text += "so it plays \(status.lateBiasMs) ms behind the others. Ask the host to pick a larger sync buffer"
        text += status.offsetMs < 0 ? ", or move the offset slider back toward 0." : "."
        return text
    }

    private func title(for status: ReceiverStatus) -> String {
        switch status.phase {
        case .connecting: return "Connecting…"
        case .syncing: return "Syncing clocks…"
        case .reconnecting: return "Reconnecting…"
        default: return status.lateBiasMs > 0 ? "Playing, \(status.lateBiasMs) ms behind" : "Playing in sync"
        }
    }

    private func signed(_ value: Float) -> String {
        abs(value) < 0.05 ? "±0.0" : String(format: "%+.1f", value)
    }
}

/// The manual delay slider: -500...+500 ms, applied on release, plus fine nudges.
private struct OffsetCard: View {
    @ObservedObject var receiver: IOSSyncReceiver
    @State private var dragging = false
    @State private var pending = 0.0

    var body: some View {
        // While the thumb is being dragged show the pending value; otherwise follow the receiver
        // (which also switches the value when the output device changes).
        let applied = receiver.status.offsetMs
        let shown = dragging ? Int(pending.rounded()) : applied
        let slider = Binding<Double>(
            get: { dragging ? pending : Double(applied) },
            set: { pending = $0 })

        Card {
            HStack {
                Image(systemName: receiver.status.outputIsBluetooth ? "airpodspro" : "speaker.wave.2")
                    .foregroundColor(.secondary)
                Text("Sync offset").font(.headline)
                Spacer()
                Text("\(shown > 0 ? "+" : "")\(shown) ms")
                    .font(.title3.weight(.semibold))
                    .monospacedDigit()
                    .foregroundColor(.accentColor)
            }
            Slider(value: slider, in: -500...500, step: 1) { editing in
                if editing {
                    pending = Double(applied)
                    dragging = true
                } else {
                    receiver.setOffsetMs(Int(pending.rounded()))
                    dragging = false
                }
            }
            HStack {
                Text("−500 earlier")
                Spacer()
                Text("later +500")
            }
            .font(.caption2)
            .foregroundColor(.secondary)

            HStack(spacing: 8) {
                ForEach([-10, -1], id: \.self) { step in
                    Button("\(step)") { receiver.setOffsetMs(applied + step) }
                }
                Button("Reset") { receiver.setOffsetMs(0) }
                    .disabled(applied == 0)
                ForEach([1, 10], id: \.self) { step in
                    Button("+\(step)") { receiver.setOffsetMs(applied + step) }
                }
            }
            .buttonStyle(.bordered)
            .frame(maxWidth: .infinity)

            Text(verbatim: hint)
                .font(.footnote)
                .foregroundColor(.secondary)
        }
    }

    private var hint: String {
        var text = "If this device sounds late next to the others (typical for AirPods and Bluetooth speakers), move toward −. "
        text += "If it sounds early, move toward +. Remembered separately for each output"
        text += receiver.status.outputName.isEmpty ? "." : " (now: \(receiver.status.outputName))."
        return text
    }
}

// MARK: - Shared pieces

private struct Card<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) { content }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

private struct StatusCard<Extra: View>: View {
    let symbol: String
    let active: Bool
    let title: String
    let detail: String
    @ViewBuilder var extra: Extra

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 16) {
                Image(systemName: symbol)
                    .font(.title2)
                    .foregroundColor(active ? .white : .secondary)
                    .frame(width: 48, height: 48)
                    .background(active ? Color.accentColor : Color(.tertiarySystemFill))
                    .clipShape(Circle())
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.title3.weight(.semibold))
                    Text(detail).font(.subheadline).foregroundColor(.secondary)
                }
            }
            extra
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(active ? Color.accentColor.opacity(0.14) : Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

extension StatusCard where Extra == EmptyView {
    init(symbol: String, active: Bool, title: String, detail: String) {
        self.init(symbol: symbol, active: active, title: title, detail: detail) { EmptyView() }
    }
}

private struct Metric: View {
    let label: String
    let value: String

    var body: some View {
        VStack(spacing: 2) {
            Text(value).font(.subheadline.weight(.semibold)).monospacedDigit()
            Text(label).font(.caption2).foregroundColor(.secondary)
        }
    }
}

private struct Notice: View {
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundColor(.orange)
            Text(text).font(.subheadline)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.orange.opacity(0.14))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

private struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .frame(maxWidth: .infinity, minHeight: 52)
            .foregroundColor(.white)
            .background(Color.accentColor.opacity(configuration.isPressed ? 0.7 : 1))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

private struct SecondaryButtonStyle: ButtonStyle {
    var tint: Color = .accentColor

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .frame(maxWidth: .infinity, minHeight: 52)
            .foregroundColor(tint)
            .background(tint.opacity(configuration.isPressed ? 0.24 : 0.14))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}
