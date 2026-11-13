import Network
import SwiftUI

/// The iPhone app: find a party, join with one tap, fine-tune the beat.
/// (iPhones are guests only: Apple gives apps no way to capture what other apps are playing.)
struct ContentView: View {
    @StateObject private var receiver = IOSSyncReceiver()
    @StateObject private var browser = HostBrowser()
    @Environment(\.scenePhase) private var scenePhase
    @State private var confetti = 0

    var body: some View {
        Group {
            if let demo = Demo.requested {
                PartyScreen(status: demo.status, confetti: 0) { DemoContent(demo: demo) }
            } else {
                PartyScreen(status: receiver.status, confetti: confetti) { live }
                    .onAppear { updateBrowsing() }
                    .onChange(of: scenePhase) { _ in updateBrowsing() }
                    .onChange(of: receiver.status.phase) { phase in
                        updateBrowsing()
                        if phase == .playing { confetti += 1 } // locked in: celebrate
                    }
            }
        }
        .preferredColorScheme(.dark)
    }

    @ViewBuilder private var live: some View {
        switch receiver.status.phase {
        case .idle:
            JoinView(hosts: browser.hosts, permissionDenied: browser.permissionDenied) { endpoint, name in
                receiver.join(endpoint: endpoint, name: name)
            }
        case .ended:
            PartyOverView(message: receiver.status.message) { receiver.dismiss() }
        default:
            ListeningView(
                status: receiver.status,
                onOffset: { receiver.setOffsetMs($0) },
                onResync: { receiver.resync() },
                onLeave: { receiver.leave() })
        }
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

/// Stage, wordmark and a scrolling column for the current screen, with confetti on top.
private struct PartyScreen<Content: View>: View {
    let status: ReceiverStatus
    let confetti: Int
    @ViewBuilder var content: Content

    var body: some View {
        ZStack {
            PartyBackground(energy: status.phase == .playing ? 1 : 0)
            ScrollView {
                VStack(spacing: 16) {
                    Wordmark()
                    content
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 10)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }
            ConfettiBurst(trigger: confetti)
        }
    }
}

// MARK: - Join

private struct JoinView: View {
    let hosts: [HostBrowser.Host]
    let permissionDenied: Bool
    let onJoin: (NWEndpoint, String) -> Void

    @State private var manualAddress = ""
    @State private var manualError: String?

    var body: some View {
        Hero(symbol: "headphones", active: false, title: "Join a party",
             detail: "Hop on the same Wi-Fi as the DJ, or on their hotspot, and pick a party.")

        GlassCard {
            HStack {
                Text("Parties nearby").font(Party.font(22, .semibold)).foregroundColor(.white)
                Spacer()
                Radar()
            }
            if permissionDenied {
                Text("Hearby isn't allowed to look around your network. Switch it on in Settings › Privacy & Security › Local Network.")
                    .font(Party.font(15))
                    .foregroundColor(Party.sun)
            } else if hosts.isEmpty {
                Text("Looking for parties on this Wi-Fi…")
                    .font(Party.font(15))
                    .foregroundColor(Party.inkSoft)
            }
            ForEach(Array(hosts.enumerated()), id: \.element.id) { index, host in
                if index > 0 { Divider().overlay(Party.glassStroke) }
                HStack(spacing: 12) {
                    Avatar(name: host.name)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(host.name).font(Party.font(17, .medium)).foregroundColor(.white).lineLimit(1)
                        Text("Tap to tune in").font(Party.font(13)).foregroundColor(Party.inkSoft)
                    }
                    Spacer()
                    Button("Join") { onJoin(host.endpoint, host.name) }
                        .buttonStyle(PartyButtonStyle(height: 44, fullWidth: false))
                }
            }
        }

        GlassCard {
            Text("Know the address?").font(Party.font(22, .semibold)).foregroundColor(.white)
            HStack(spacing: 12) {
                TextField("192.168.43.1", text: $manualAddress)
                    .font(Party.font(17))
                    .foregroundColor(.white)
                    .keyboardType(.numbersAndPunctuation)
                    .disableAutocorrection(true)
                    .textInputAutocapitalization(.never)
                    .submitLabel(.go)
                    .onSubmit(joinManually)
                    .padding(.horizontal, 16)
                    .frame(height: 52)
                    .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Party.glass))
                    .overlay(
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .strokeBorder(manualError == nil ? Party.glassStroke : Party.coral, lineWidth: 1))
                Button("Join", action: joinManually)
                    .buttonStyle(PartyButtonStyle(height: 52, fullWidth: false))
                    .disabled(manualAddress.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            Text(manualError ?? "It's on the DJ's screen while the party is on.")
                .font(Party.font(13))
                .foregroundColor(manualError == nil ? Party.inkSoft : Party.coral)
        }

        Text("iPhones join parties. The DJ runs Hearby on an Android phone.")
            .font(Party.font(13))
            .foregroundColor(Party.inkFaint)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 6)
    }

    private func joinManually() {
        guard let parsed = AddressParser.parse(manualAddress) else {
            manualError = "That doesn't look right. Use the address on the DJ's screen, like 192.168.43.1"
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
    let status: ReceiverStatus
    let onOffset: (Int) -> Void
    let onResync: () -> Void
    let onLeave: () -> Void

    var body: some View {
        let playing = status.phase == .playing
        if let video = status.video, status.phase != .connecting {
            VideoCard(status: status, video: video)
        } else {
            GlassCard(lit: playing) {
                HeroRow(symbol: status.outputIsBluetooth ? "airpodspro" : "headphones", active: playing, level: status.level,
                        title: title, detail: detail)
                if playing {
                    if !status.songTitle.isEmpty {
                        HStack(spacing: 8) {
                            Image(systemName: "music.note").foregroundColor(Party.pink)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(status.songTitle).font(Party.font(17, .semibold)).foregroundColor(.white).lineLimit(1)
                                let sub = status.songPlaying ? status.songArtist : "Paused by the DJ"
                                if !sub.isEmpty {
                                    Text(sub).font(Party.font(13)).foregroundColor(Party.inkSoft).lineLimit(1)
                                }
                            }
                        }
                    }
                    Equalizer(level: status.level)
                    if status.soundChecked {
                        Text("Sound-checked by the DJ")
                            .font(Party.font(12, .semibold))
                            .foregroundColor(Party.lime)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 4)
                            .background(Capsule().fill(Party.lime.opacity(0.18)))
                            .overlay(Capsule().strokeBorder(Party.lime.opacity(0.5), lineWidth: 1))
                    }
                    HStack(spacing: 8) {
                        let tight = abs(status.syncErrorMs) < 5 && status.lateBiasMs == 0
                        Stat(value: signed(status.syncErrorMs) + " ms", label: "sync", accent: tight ? Party.lime : Party.sun)
                        Stat(value: String(format: "%.1f ms", status.rttMs), label: "ping")
                        Stat(value: "\(status.bufferMs) ms", label: "buffer")
                        Stat(value: String(format: "%.1f%%", status.lossPercent), label: "drops", accent: status.lossPercent < 1 ? .white : Party.sun)
                    }
                }
            }
        }

        if status.djOnMic {
            Notice(text: "The DJ is on the mic.", symbol: "mic.fill", color: Party.pink)
        }
        if let message = status.message {
            Notice(text: message)
        }
        if status.lateBiasMs > 0 && playing {
            Notice(text: lateNotice)
        }

        OffsetCard(status: status, onOffset: onOffset)

        HStack(spacing: 12) {
            Button(action: onResync) { Label("Re-sync", systemImage: "arrow.triangle.2.circlepath") }
                .buttonStyle(GhostButtonStyle())
            Button(action: onLeave) { Label("Leave", systemImage: "rectangle.portrait.and.arrow.right") }
                .buttonStyle(PartyButtonStyle(fill: Party.stop, glow: Party.coral, height: 56))
        }
    }

    private var title: String {
        switch status.phase {
        case .connecting: return "Knocking on the door…"
        case .syncing: return "Finding the beat…"
        case .reconnecting: return "Lost the DJ, reconnecting…"
        default: return status.lateBiasMs > 0 ? "Playing, \(status.lateBiasMs) ms behind" : "You're in sync!"
        }
    }

    private var detail: String {
        // The icon already says Bluetooth.
        let output = status.outputName.hasPrefix("Bluetooth: ") ? String(status.outputName.dropFirst(11)) : status.outputName
        return output.isEmpty ? "DJ: \(status.hostName)" : "DJ: \(status.hostName) · \(output)"
    }

    private var lateNotice: String {
        var text = "This phone needs more runway than the DJ's \(status.playoutDelayMs) ms sync cushion, "
        text += "so it's \(status.lateBiasMs) ms behind the crowd. Ask the DJ for a bigger cushion"
        text += status.offsetMs < 0 ? ", or slide the offset back toward 0." : "."
        return text
    }

    private func signed(_ value: Float) -> String {
        abs(value) < 0.05 ? "±0.0" : String(format: "%+.1f", value)
    }
}

/// The DJ's YouTube video, playing on this iPhone in time with every other phone.
private struct VideoCard: View {
    let status: ReceiverStatus
    let video: VideoTimeline
    @State private var notice = PlayerNotice()

    var body: some View {
        GlassCard(lit: video.playing) {
            HStack(spacing: 10) {
                pill("YouTube", Party.coral)
                Text(video.playing ? "Playing on every phone" : "Paused by the DJ")
                    .font(Party.font(14, .semibold)).foregroundColor(Party.inkSoft).lineLimit(1)
                Spacer(minLength: 0)
                if let off = notice.offErrorMs, notice.error == nil, video.playing {
                    pill(abs(off) < 1 ? "in sync" : String(format: "%+.0f ms", Double(off)), abs(off) < 15 ? Party.lime : Party.sun)
                }
            }
            Color.black
                .aspectRatio(16.0 / 9.0, contentMode: .fit)
                .overlay(player)
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            Text(title).font(Party.font(20, .bold)).foregroundColor(.white).lineLimit(2)
            if let error = notice.error {
                Text(error).font(Party.font(15)).foregroundColor(Party.sun)
            }
            Text("YouTube plays only while Hearby is open, so this screen stays on. If you leave the app, only this iPhone pauses, and it catches up when you come back.")
                .font(Party.font(13)).foregroundColor(Party.inkSoft)
        }
    }

    private var title: String {
        if !video.title.isEmpty { return video.title }
        return notice.title ?? "Loading video…"
    }

    @ViewBuilder private var player: some View {
        if Demo.requested != nil {
            // Screenshots: no network, so a stand-in for YouTube's player.
            ZStack {
                LinearGradient(colors: [Color(red: 0.10, green: 0.06, blue: 0.19), Color(red: 0.19, green: 0.09, blue: 0.28)],
                               startPoint: .topLeading, endPoint: .bottomTrailing)
                Image(systemName: "play.circle.fill").font(.system(size: 56)).foregroundColor(.white.opacity(0.85))
            }
        } else {
            SyncedYouTubePlayer(
                timeline: video,
                hostOffsetUs: { status.clockOffsetUs },
                correctionUs: { status.correctionUs },
                duck: { status.videoDuck },
                onNotice: { notice = $0 })
        }
    }

    private func pill(_ text: String, _ color: Color) -> some View {
        Text(text)
            .font(Party.font(12, .semibold))
            .foregroundColor(color)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Capsule().fill(color.opacity(0.18)))
            .overlay(Capsule().strokeBorder(color.opacity(0.5), lineWidth: 1))
    }
}

/// The manual delay slider: -500...+500 ms, applied on release, plus fine nudges.
private struct OffsetCard: View {
    let status: ReceiverStatus
    let onOffset: (Int) -> Void
    @State private var dragging = false
    @State private var pending = 0.0

    var body: some View {
        // While the thumb is being dragged show the pending value; otherwise follow the receiver
        // (which also switches the value when the output device changes).
        let applied = status.offsetMs
        let shown = dragging ? Int(pending.rounded()) : applied
        let slider = Binding<Double>(
            get: { dragging ? pending : Double(applied) },
            set: { pending = $0 })

        GlassCard {
            HStack {
                Image(systemName: "slider.horizontal.3").foregroundColor(Party.blue)
                Text("Fine-tune the beat").font(Party.font(22, .semibold)).foregroundColor(.white).lineLimit(1).minimumScaleFactor(0.8)
                Spacer()
                Text(verbatim: "\(shown > 0 ? "+" : "")\(shown) ms")
                    .font(Party.font(24, .bold))
                    .monospacedDigit()
                    .foregroundStyle(LinearGradient(colors: [Party.pink, Party.violet, Party.blue], startPoint: .leading, endPoint: .trailing))
            }
            Slider(value: slider, in: -500...500, step: 1) { editing in
                if editing {
                    pending = Double(applied)
                    dragging = true
                } else {
                    onOffset(Int(pending.rounded()))
                    dragging = false
                }
            }
            .tint(Party.pink)
            HStack {
                Text("−500 earlier")
                Spacer()
                Text("later +500")
            }
            .font(Party.font(11))
            .foregroundColor(Party.inkFaint)

            HStack(spacing: 8) {
                ForEach([-10, -1], id: \.self) { step in
                    Button("\(step)") { onOffset(applied + step) }
                }
                Button("Reset") { onOffset(0) }
                    .disabled(applied == 0)
                ForEach([1, 10], id: \.self) { step in
                    Button("+\(step)") { onOffset(applied + step) }
                }
            }
            .buttonStyle(GhostButtonStyle(height: 46, sidePadding: 2)) // five share one row

            Text(verbatim: hint)
                .font(Party.font(13))
                .foregroundColor(Party.inkSoft)
        }
    }

    private var hint: String {
        var text = "Sounds late next to the others (hello, Bluetooth)? Slide toward −. Sounds early? Slide toward +. "
        text += "Saved for each speaker or pair of earbuds"
        text += status.outputName.isEmpty ? "." : " (now: \(status.outputName))."
        return text
    }
}

private struct PartyOverView: View {
    let message: String?
    let onDismiss: () -> Void

    var body: some View {
        Hero(symbol: "sparkles", active: false, title: "Party's over", detail: message ?? "You left the party.")
        Button(action: onDismiss) { Label("Find another party", systemImage: "headphones") }
            .buttonStyle(PartyButtonStyle())
    }
}

// MARK: - Shared pieces

private struct Hero: View {
    let symbol: String
    let active: Bool
    let title: String
    let detail: String

    var body: some View {
        GlassCard(lit: active) { HeroRow(symbol: symbol, active: active, level: 0, title: title, detail: detail) }
    }
}

private struct HeroRow: View {
    let symbol: String
    let active: Bool
    let level: Float
    let title: String
    let detail: String

    var body: some View {
        HStack(spacing: 8) {
            PulseOrb(symbol: symbol, active: active, level: level)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(Party.font(24, .bold)).foregroundColor(.white)
                Text(detail).font(Party.font(15)).foregroundColor(Party.inkSoft)
            }
        }
    }
}

// MARK: - Demo mode

/// Launch with `-demo join|playing|syncing|ended` to show a screen with sample data and no network.
/// Used to take screenshots in the simulator; compiled into debug builds only.
enum Demo: String {
    case join, playing, syncing, ended, youtube

    static var requested: Demo? {
        #if DEBUG
        return UserDefaults.standard.string(forKey: "demo").flatMap(Demo.init(rawValue:))
        #else
        return nil
        #endif
    }

    var status: ReceiverStatus {
        switch self {
        case .join: return ReceiverStatus()
        case .playing:
            return ReceiverStatus(
                phase: .playing, hostName: "Mia's Pixel", syncErrorMs: 0.3, rttMs: 2.1, bufferMs: 184,
                playoutDelayMs: 350, offsetMs: 0, outputName: "Bluetooth: AirPods Pro", outputIsBluetooth: true, level: 0.7,
                songTitle: "Midnight City", songArtist: "M83", songPlaying: true, soundChecked: true)
        case .syncing:
            return ReceiverStatus(phase: .syncing, hostName: "Mia's Pixel", playoutDelayMs: 350, outputName: "iPhone speaker")
        case .youtube:
            return ReceiverStatus(
                phase: .playing, hostName: "Mia's Pixel", syncErrorMs: 0.3, rttMs: 2.1, bufferMs: 184,
                playoutDelayMs: 350, offsetMs: 0, outputName: "iPhone speaker", level: 0.5,
                songTitle: "Kesariya | Brahmāstra | Official Video", songArtist: "YouTube", songPlaying: true,
                video: VideoTimeline(videoId: "dQw4w9WgXcQ", title: "Kesariya | Brahmāstra | Official Video", playing: true,
                                     anchorHostUs: 0, positionUs: 0, version: 1))
        case .ended:
            return ReceiverStatus(phase: .ended, message: "The DJ ended the party.")
        }
    }
}

private struct DemoContent: View {
    let demo: Demo

    var body: some View {
        switch demo {
        case .join:
            let endpoint = NWEndpoint.hostPort(host: "192.168.43.1", port: NWEndpoint.Port(rawValue: Proto.port)!)
            JoinView(
                hosts: [
                    HostBrowser.Host(id: "1", name: "Mia's Pixel", endpoint: endpoint),
                    HostBrowser.Host(id: "2", name: "Kabir", endpoint: endpoint),
                ],
                permissionDenied: false) { _, _ in }
        case .ended:
            PartyOverView(message: demo.status.message) {}
        default:
            ListeningView(status: demo.status, onOffset: { _ in }, onResync: {}, onLeave: {})
        }
    }
}
