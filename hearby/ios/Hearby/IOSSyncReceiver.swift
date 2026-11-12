import AVFoundation
import Foundation
import Network
import os
import UIKit

// MARK: - Local clock

/// The local monotonic clock, in the same time base Core Audio stamps render cycles with
/// (`mach_absolute_time`), so audio timestamps and network timestamps are directly comparable.
enum HostClock {
    private static let timebase: mach_timebase_info_data_t = {
        var info = mach_timebase_info_data_t()
        mach_timebase_info(&info)
        return info
    }()

    static func nanoseconds(fromTicks ticks: UInt64) -> Int64 {
        Int64(ticks &* UInt64(timebase.numer) / UInt64(timebase.denom))
    }

    static func nowNs() -> Int64 { nanoseconds(fromTicks: mach_absolute_time()) }
}

// MARK: - Audio output

/// The engine's audio output on iOS: a single-producer / single-consumer ring buffer.
///
/// The playout thread writes resampled PCM in; the AVAudioEngine render callback pulls it out. On
/// every render cycle the callback notes "frame N of my stream starts playing at host time T (+
/// output latency)". That pair is this platform's equivalent of Android's AudioTrack.getTimestamp()
/// and is all the playout engine needs to steer playback onto the shared timeline.
final class RingSink: AudioSink {
    private let capacity: Int // frames, power of two
    private let mask: Int
    private let storage: UnsafeMutablePointer<Float> // interleaved stereo
    private let lock: UnsafeMutablePointer<os_unfair_lock>

    // Guarded by `lock`. Critical sections only copy a few integers; samples are copied outside.
    private var writeIndex: Int64 = 0
    private var readIndex: Int64 = 0
    private var anchorPosition: Int64 = -1
    private var anchorTimeNs: Int64 = 0
    private var anchorTakenNs: Int64 = 0
    private var outputLatencyNs: Int64 = 0
    private var lastRenderFrames = 512
    private var gain: Float = 1

    init(capacityFrames: Int = 65_536) {
        precondition(capacityFrames & (capacityFrames - 1) == 0, "capacity must be a power of two")
        capacity = capacityFrames
        mask = capacityFrames - 1
        storage = .allocate(capacity: capacityFrames * Proto.channels)
        storage.initialize(repeating: 0, count: capacityFrames * Proto.channels)
        lock = .allocate(capacity: 1)
        lock.initialize(to: os_unfair_lock())
    }

    deinit {
        storage.deallocate()
        lock.deallocate()
    }

    // MARK: producer (playout thread)

    func write(_ interleaved: [Float], frames: Int) {
        os_unfair_lock_lock(lock)
        let w = writeIndex
        let r = readIndex
        os_unfair_lock_unlock(lock)

        // The pump never lets the ring get anywhere near full; if it somehow is, drop the excess.
        let n = min(frames, capacity - Int(w - r))
        if n > 0 {
            interleaved.withUnsafeBufferPointer { src in
                for i in 0..<n {
                    let slot = (Int(w) + i) & mask
                    storage[slot * 2] = src[i * 2]
                    storage[slot * 2 + 1] = src[i * 2 + 1]
                }
            }
        }
        os_unfair_lock_lock(lock)
        writeIndex = w + Int64(frames) // count every frame so positions stay in step with the engine
        if n < frames { readIndex = max(readIndex, writeIndex - Int64(capacity)) }
        os_unfair_lock_unlock(lock)
    }

    func getTimestamp(_ out: inout SinkTimestamp) -> Bool {
        os_unfair_lock_lock(lock)
        defer { os_unfair_lock_unlock(lock) }
        // No render cycle for half a second means the audio engine is stopped or interrupted.
        guard anchorPosition >= 0, HostClock.nowNs() - anchorTakenNs < 500_000_000 else { return false }
        out.framePosition = anchorPosition
        out.timeNs = anchorTimeNs
        return true
    }

    var bufferedFrames: Int {
        os_unfair_lock_lock(lock)
        defer { os_unfair_lock_unlock(lock) }
        return Int(writeIndex - readIndex)
    }

    /// How much the playout thread should keep queued: comfortably more than one render cycle.
    /// (iOS switches to very large I/O buffers when the screen locks.)
    var targetFillFrames: Int {
        os_unfair_lock_lock(lock)
        defer { os_unfair_lock_unlock(lock) }
        return max(2880, lastRenderFrames * 2 + Proto.frameSamples)
    }

    func setOutputLatency(seconds: TimeInterval) {
        os_unfair_lock_lock(lock)
        outputLatencyNs = Int64(max(0, seconds) * 1e9)
        os_unfair_lock_unlock(lock)
    }

    func setGain(_ value: Float) {
        os_unfair_lock_lock(lock)
        gain = value
        os_unfair_lock_unlock(lock)
    }

    // MARK: consumer (real-time render thread)

    /// Fill one render cycle. `hostTimeNs` is when the first frame of this cycle starts leaving the
    /// audio hardware; adding the session's output latency gives the moment it is actually heard.
    func render(frameCount: Int, left: UnsafeMutablePointer<Float>, right: UnsafeMutablePointer<Float>?, hostTimeNs: Int64) {
        os_unfair_lock_lock(lock)
        let w = writeIndex
        let r = readIndex
        let g = gain
        anchorPosition = r
        anchorTimeNs = hostTimeNs + outputLatencyNs
        anchorTakenNs = hostTimeNs
        lastRenderFrames = frameCount
        os_unfair_lock_unlock(lock)

        let n = min(frameCount, max(0, Int(w - r)))
        for i in 0..<n {
            let slot = (Int(r) + i) & mask
            left[i] = storage[slot * 2] * g
            right?[i] = storage[slot * 2 + 1] * g
        }
        if n < frameCount { // underrun: silence now, the stream simply resumes later
            for i in n..<frameCount {
                left[i] = 0
                right?[i] = 0
            }
        }

        os_unfair_lock_lock(lock)
        readIndex = max(readIndex, r + Int64(n))
        os_unfair_lock_unlock(lock)
    }
}

// MARK: - Opus decoder

/// Opus decoding through AudioToolbox (`kAudioFormatOpus`, built into iOS) via AVAudioConverter.
/// One 20 ms packet in, 960 stereo frames out; no third-party codec library needed.
final class AppleOpusDecoder: OpusFrameDecoder {
    private let converter: AVAudioConverter
    private let compressed: AVAudioCompressedBuffer
    private let pcm: AVAudioPCMBuffer

    init?() {
        var description = AudioStreamBasicDescription(
            mSampleRate: Double(Proto.sampleRate),
            mFormatID: kAudioFormatOpus,
            mFormatFlags: 0,
            mBytesPerPacket: 0,
            mFramesPerPacket: UInt32(Proto.frameSamples),
            mBytesPerFrame: 0,
            mChannelsPerFrame: UInt32(Proto.channels),
            mBitsPerChannel: 0,
            mReserved: 0)
        guard
            let input = AVAudioFormat(streamDescription: &description),
            let output = AVAudioFormat(
                commonFormat: .pcmFormatFloat32, sampleRate: Double(Proto.sampleRate),
                channels: AVAudioChannelCount(Proto.channels), interleaved: false),
            let converter = AVAudioConverter(from: input, to: output),
            let pcm = AVAudioPCMBuffer(pcmFormat: output, frameCapacity: AVAudioFrameCount(Proto.frameSamples))
        else { return nil }
        self.converter = converter
        self.pcm = pcm
        compressed = AVAudioCompressedBuffer(format: input, packetCapacity: 1, maximumPacketSize: Proto.maxPayload)
    }

    func decode(_ packet: [UInt8], length: Int, into out: inout [Float]) -> Bool {
        guard length > 0, length <= Proto.maxPayload else { return false }
        packet.withUnsafeBytes { src in
            compressed.data.copyMemory(from: src.baseAddress!, byteCount: length)
        }
        compressed.byteLength = UInt32(length)
        compressed.packetCount = 1
        compressed.packetDescriptions?.pointee = AudioStreamPacketDescription(
            mStartOffset: 0, mVariableFramesInPacket: 0, mDataByteSize: UInt32(length))
        pcm.frameLength = 0

        var fed = false
        var error: NSError?
        let status = converter.convert(to: pcm, error: &error) { [compressed] _, inputStatus in
            if fed {
                inputStatus.pointee = .noDataNow // exactly one packet per call
                return nil
            }
            fed = true
            inputStatus.pointee = .haveData
            return compressed
        }
        guard status != .error, error == nil, let channels = pcm.floatChannelData else { return false }

        // Normally exactly 960 frames. The very first packet may come out short (the decoder trims
        // its priming samples); keep whatever we get aligned to the END of the 20 ms slot so the
        // sample timeline stays locked to the packet timestamps.
        let wanted = Proto.frameSamples
        let produced = Int(pcm.frameLength)
        let n = min(produced, wanted)
        let skip = produced - n
        let lead = wanted - n
        let left = channels[0]
        let right = pcm.format.channelCount > 1 ? channels[1] : channels[0]
        for i in 0..<(lead * 2) { out[i] = 0 }
        for i in 0..<n {
            out[(lead + i) * 2] = left[skip + i]
            out[(lead + i) * 2 + 1] = right[skip + i]
        }
        return produced > 0
    }
}

// MARK: - Status shown by the UI

struct ReceiverStatus: Equatable {
    enum Phase { case idle, connecting, syncing, playing, reconnecting, ended }

    var phase: Phase = .idle
    var hostName = ""
    var message: String?
    /// Measured "heard late (+) / early (-)" error against the shared timeline, ms.
    var syncErrorMs: Float = 0
    var rttMs: Float = 0
    /// Received audio waiting ahead of the play cursor, ms.
    var bufferMs = 0
    /// Non-zero when this device cannot meet the host's playout delay and plays this much late.
    var lateBiasMs = 0
    var playoutDelayMs = 0
    /// The manual sync slider, -500...+500 ms. Positive plays later.
    var offsetMs = 0
    var outputName = ""
    var outputIsBluetooth = false
    var lossPercent: Float = 0
    /// Peak level of what is playing right now, 0...1. Drives the visualizer.
    var level: Float = 0
}

// MARK: - Receiver

/// LISTENER (iOS): receives the host's Opus stream over UDP and plays every frame at the instant
/// the shared timeline says it is due.
///
///   NWConnection (UDP) ─▶ network queue ─▶ JitterBuffer (encoded, by sequence number)
///                                    └──▶ ClockSync (NTP offset to the host clock)
///   playout thread: PlayoutEngine.pump() ─▶ AudioToolbox Opus decode ─▶ drift-correcting resampler
///                                        ─▶ RingSink ─▶ AVAudioSourceNode ─▶ AVAudioEngine output
///   The render callback's host time + AVAudioSession.outputLatency tells the engine when frames
///   really reach the speaker / AirPods.   target time = host timestamp + playout delay + slider.
final class IOSSyncReceiver: ObservableObject {
    @Published private(set) var status = ReceiverStatus()

    private var session: ListenSession?
    private var sessionToken: NSObject?

    /// Join a host found by Bonjour (a service endpoint) or typed in by hand (host + port).
    func join(endpoint: NWEndpoint, name: String) {
        session?.stop()
        session = nil
        status = ReceiverStatus(phase: .connecting, hostName: name)
        let token = NSObject()
        sessionToken = token
        do {
            let created = try ListenSession(endpoint: endpoint, hostLabel: name) { [weak self] newStatus in
                DispatchQueue.main.async {
                    // Ignore late updates from a session that has been replaced or left.
                    guard let self, self.sessionToken === token else { return }
                    self.status = newStatus
                    if newStatus.phase == .ended {
                        self.session?.stop()
                        self.session = nil
                        self.sessionToken = nil
                    }
                }
            }
            session = created
            created.start()
        } catch {
            sessionToken = nil
            status = ReceiverStatus(phase: .ended, hostName: name, message: "Audio could not be started on this device.")
        }
    }

    func leave() {
        session?.stop()
        session = nil
        sessionToken = nil
        status = ReceiverStatus()
    }

    /// Dismiss the "session ended" notice.
    func dismiss() {
        if status.phase == .ended { status = ReceiverStatus() }
    }

    /// The manual sync slider. Remembered per output (speaker, each pair of AirPods, ...).
    func setOffsetMs(_ ms: Int) {
        session?.setUserOffsetMs(min(500, max(-500, ms)))
    }

    /// Throw away any fallback delay and line up from scratch.
    func resync() {
        session?.resync()
    }
}

/// One listening session: audio output, playout thread, UDP connection and control loop.
private final class ListenSession {
    private static let joinIntervalNs: Int64 = 400_000_000
    private static let syncBurst = 8
    private static let syncBurstIntervalNs: Int64 = 40_000_000
    private static let syncIntervalNs: Int64 = 1_000_000_000
    private static let hostTimeoutNs: Int64 = 4_000_000_000
    private static let offsetKeyPrefix = "offset:"

    private let endpoint: NWEndpoint
    private let onStatus: (ReceiverStatus) -> Void
    private let netQueue = DispatchQueue(label: "hearby.net", qos: .userInteractive)

    // Sync core (shared with Android).
    private let jitter = JitterBuffer()
    private let clock = ClockSync()
    private let sink = RingSink()
    private let engine: PlayoutEngine

    // Audio.
    private let audioEngine = AVAudioEngine()
    private var observers: [NSObjectProtocol] = []
    private let pumpRunning = RunFlag()

    // Guarded by `stateLock` (touched from the main thread and the network queue).
    private let stateLock = NSLock()
    private var userOffsetMs = 0
    private var routeKey = ""
    private var routeName = ""
    private var routeIsBluetooth = false

    // Network-queue state.
    private let clientId = UInt32.random(in: 1...UInt32.max)
    private let deviceName = UIDevice.current.name
    private var connection: NWConnection?
    private var timer: DispatchSourceTimer?
    private var stopped = false
    private var joined = false
    private var everJoined = false
    private var ended = false
    private var streamId: UInt32?
    private var hostName: String
    private var playoutDelayMs = 350
    private var message: String?
    private var syncsSent = 0
    private var startNs: Int64 = 0
    private var lastPacketNs: Int64 = 0
    private var lastJoinNs: Int64 = 0
    private var lastSyncNs: Int64 = 0
    private var lastPublishNs: Int64 = 0
    private var lastReconnectNs: Int64 = 0
    private var lossWindowNs: Int64 = 0
    private var lossPlayed: Int64 = 0
    private var lossConcealed: Int64 = 0
    private var lossPercent: Float = 0

    init(endpoint: NWEndpoint, hostLabel: String, onStatus: @escaping (ReceiverStatus) -> Void) throws {
        guard let decoder = AppleOpusDecoder() else {
            throw NSError(domain: "Hearby", code: 1, userInfo: [NSLocalizedDescriptionKey: "Opus decoder unavailable"])
        }
        self.endpoint = endpoint
        self.hostName = hostLabel
        self.onStatus = onStatus
        engine = PlayoutEngine(sink: sink, decoder: decoder, jitter: jitter, clock: clock)
        try startAudio()
    }

    // MARK: lifecycle (main thread)

    func start() {
        startPump()
        netQueue.async { [self] in
            startNs = HostClock.nowNs()
            lastPacketNs = startNs
            openConnection()
            let t = DispatchSource.makeTimerSource(queue: netQueue)
            t.schedule(deadline: .now(), repeating: .milliseconds(20), leeway: .milliseconds(2))
            t.setEventHandler { [weak self] in self?.tick() }
            t.resume()
            timer = t
        }
    }

    func stop() {
        pumpRunning.clear()
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers.removeAll()
        audioEngine.stop()
        netQueue.async { [self] in
            guard !stopped else { return }
            stopped = true
            timer?.cancel()
            timer = nil
            if let c = connection {
                if joined {
                    // Best-effort goodbye so the host drops us immediately instead of timing out.
                    c.send(content: Data(Wire.encodeLeave(clientId: clientId)), completion: .contentProcessed { _ in c.cancel() })
                } else {
                    c.cancel()
                }
            }
            connection = nil
        }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    func setUserOffsetMs(_ ms: Int) {
        stateLock.lock()
        userOffsetMs = ms
        let key = routeKey
        stateLock.unlock()
        if !key.isEmpty { UserDefaults.standard.set(ms, forKey: ListenSession.offsetKeyPrefix + key) }
        engine.setUserOffsetMs(ms)
    }

    func resync() {
        engine.requestRealign(resetLateBias: true)
    }

    // MARK: audio (main thread)

    private func startAudio() throws {
        let audioSession = AVAudioSession.sharedInstance()
        try audioSession.setCategory(.playback, mode: .default, options: [])
        try? audioSession.setPreferredSampleRate(Double(Proto.sampleRate))
        try? audioSession.setPreferredIOBufferDuration(0.01)
        try audioSession.setActive(true)

        // 48 kHz stereo float, non-interleaved. If the hardware runs at another rate the engine's
        // mixer converts; the playout loop then corrects for it like for any other clock difference.
        guard let format = AVAudioFormat(
            standardFormatWithSampleRate: Double(Proto.sampleRate), channels: AVAudioChannelCount(Proto.channels))
        else {
            throw NSError(domain: "Hearby", code: 2, userInfo: [NSLocalizedDescriptionKey: "Audio format unavailable"])
        }
        let sink = self.sink
        let source = AVAudioSourceNode(format: format) { _, timestamp, frameCount, audioBufferList -> OSStatus in
            let buffers = UnsafeMutableAudioBufferListPointer(audioBufferList)
            guard buffers.count > 0, let left = buffers[0].mData?.assumingMemoryBound(to: Float.self) else { return noErr }
            let right = buffers.count > 1 ? buffers[1].mData?.assumingMemoryBound(to: Float.self) : nil
            let stamp = timestamp.pointee
            let hostNs = stamp.mFlags.contains(.hostTimeValid)
                ? HostClock.nanoseconds(fromTicks: stamp.mHostTime) : HostClock.nowNs()
            sink.render(frameCount: Int(frameCount), left: left, right: right, hostTimeNs: hostNs)
            return noErr
        }
        audioEngine.attach(source)
        audioEngine.connect(source, to: audioEngine.mainMixerNode, format: format)
        audioEngine.prepare()
        try audioEngine.start()
        updateRoute()

        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.updateRoute()
        })
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            // A phone call or Siri took the audio session; pick up again when it is handed back.
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  AVAudioSession.InterruptionType(rawValue: raw) == .ended else { return }
            try? AVAudioSession.sharedInstance().setActive(true)
            self?.restartAudioEngine()
        })
        observers.append(center.addObserver(forName: .AVAudioEngineConfigurationChange, object: audioEngine, queue: .main) { [weak self] _ in
            // The output hardware changed (e.g. AirPods connected); the engine stops itself.
            self?.restartAudioEngine()
        })
    }

    private func restartAudioEngine() {
        guard pumpRunning.isSet else { return }
        if !audioEngine.isRunning { try? audioEngine.start() }
        updateRoute()
    }

    /// Every output has its own latency, so every output remembers its own slider position.
    private func updateRoute() {
        let audioSession = AVAudioSession.sharedInstance()
        sink.setOutputLatency(seconds: audioSession.outputLatency)
        guard let output = audioSession.currentRoute.outputs.first else { return }
        let bluetooth = output.portType == .bluetoothA2DP || output.portType == .bluetoothLE || output.portType == .bluetoothHFP
        let name: String
        switch output.portType {
        case .builtInSpeaker: name = "iPhone speaker"
        case .builtInReceiver: name = "Earpiece"
        case .headphones: name = "Wired headphones"
        case .airPlay: name = "AirPlay: \(output.portName)"
        default: name = bluetooth ? "Bluetooth: \(output.portName)" : output.portName
        }
        let key = "\(output.portType.rawValue):\(output.portName)"

        stateLock.lock()
        let changed = key != routeKey
        routeKey = key
        routeName = name
        routeIsBluetooth = bluetooth
        if changed { userOffsetMs = UserDefaults.standard.integer(forKey: ListenSession.offsetKeyPrefix + key) }
        let offset = userOffsetMs
        stateLock.unlock()

        engine.setUserOffsetMs(offset)
        engine.requestRealign(resetLateBias: true)
    }

    private func startPump() {
        let flag = pumpRunning
        let engine = self.engine
        let sink = self.sink
        let thread = Thread {
            while flag.isSet {
                let target = sink.targetFillFrames
                var rounds = 0
                while flag.isSet && sink.bufferedFrames < target && rounds < 32 {
                    engine.pump()
                    rounds += 1
                }
                usleep(4_000)
            }
        }
        thread.name = "hearby.playout"
        thread.qualityOfService = .userInteractive
        thread.start()
    }

    // MARK: network (network queue)

    private func openConnection() {
        let parameters = NWParameters.udp
        parameters.prohibitedInterfaceTypes = [.cellular] // local network only
        parameters.serviceClass = .interactiveVoice
        let c = NWConnection(to: endpoint, using: parameters)
        connection = c
        c.stateUpdateHandler = { [weak self, weak c] state in
            guard let self, let c, self.connection === c else { return }
            if case .failed = state {
                // The socket is dead (network changed, host unreachable). Open a fresh one shortly.
                c.cancel()
                self.connection = nil
                self.joined = false
            }
        }
        c.start(queue: netQueue)
        receive(on: c)
    }

    private func receive(on c: NWConnection) {
        c.receiveMessage { [weak self] data, _, _, error in
            let rxNs = HostClock.nowNs() // t3: taken before anything else
            guard let self, self.connection === c else { return }
            if let data, !data.isEmpty { self.handle([UInt8](data), rxNs: rxNs) }
            if error == nil { self.receive(on: c) }
        }
    }

    private func send(_ bytes: [UInt8]) {
        connection?.send(content: Data(bytes), completion: .idempotent)
    }

    private func handle(_ b: [UInt8], rxNs: Int64) {
        guard let type = Wire.typeOf(b) else { return }
        switch type {
        case Proto.typeAudio:
            guard joined, b.count > Proto.audioHeaderSize else { return }
            lastPacketNs = rxNs
            jitter.put(
                seq32: Wire.audioSeq(b), timestampUs: Int64(bitPattern: Wire.audioTimestampUs(b)),
                src: b, offset: Proto.audioHeaderSize, length: b.count - Proto.audioHeaderSize)

        case Proto.typeSyncResp:
            guard let m = Wire.decodeSyncResp(b) else { return }
            lastPacketNs = rxNs
            let t3 = rxNs / 1000
            let t0 = Int64(bitPattern: m.t0Us)
            if m.streamId == streamId, t3 - t0 >= 0, t3 - t0 <= 1_000_000 {
                clock.addSample(t0Us: t0, t1Us: Int64(bitPattern: m.t1Us), t2Us: Int64(bitPattern: m.t2Us), t3Us: t3)
                playoutDelayMs = Int(m.playoutDelayMs)
                engine.setPlayoutDelayMs(playoutDelayMs)
            }

        case Proto.typeJoinAck:
            guard let m = Wire.decodeJoinAck(b) else { return }
            lastPacketNs = rxNs
            guard Int(m.sampleRate) == Proto.sampleRate, Int(m.channels) == Proto.channels, Int(m.frameMs) == Proto.frameMs else {
                message = "This party uses an audio format this version of Hearby can't play. Try updating."
                ended = true
                return
            }
            if m.streamId != streamId {
                // New session on the host: old audio and old sequence numbers are meaningless.
                streamId = m.streamId
                jitter.clear()
                clock.reset()
                syncsSent = 0
                engine.requestStreamReset()
            }
            if !m.hostName.isEmpty { hostName = m.hostName }
            playoutDelayMs = Int(m.playoutDelayMs)
            engine.setPlayoutDelayMs(playoutDelayMs)
            message = nil
            joined = true
            everJoined = true

        case Proto.typeBye:
            if let id = Wire.decodeU32Body(b), id == streamId {
                message = "The DJ ended the party."
                ended = true
            }

        default:
            break
        }
    }

    /// Join retries, clock-sync cadence, keep-alive and watchdog. Runs every 20 ms.
    private func tick() {
        guard !stopped else { return }
        let now = HostClock.nowNs()
        if ended {
            publish(now: now)
            timer?.cancel()
            timer = nil
            return
        }
        if connection == nil, now - lastReconnectNs >= 1_000_000_000 {
            lastReconnectNs = now
            openConnection()
        }
        if !joined {
            if now - lastJoinNs >= ListenSession.joinIntervalNs {
                lastJoinNs = now
                send(Wire.encodeJoin(clientId: clientId, platform: Proto.platformIOS, name: deviceName))
            }
            if !everJoined, message == nil, now - startNs > 5_000_000_000 {
                message = "No answer from the DJ yet. Check you are both on the same Wi-Fi (or on their hotspot), "
                    + "and that Hearby is allowed under Settings › Privacy & Security › Local Network."
            }
        } else {
            // A quick burst to get a clock fix, then once a second to follow drift (and as keep-alive).
            let interval = syncsSent < ListenSession.syncBurst ? ListenSession.syncBurstIntervalNs : ListenSession.syncIntervalNs
            if now - lastSyncNs >= interval {
                lastSyncNs = now
                syncsSent += 1
                let snap = engine.snapshot()
                let state: UInt8 = snap.state != .playing ? Proto.statusBuffering
                    : (snap.lateBiasMs > 0 ? Proto.statusLate : Proto.statusSynced)
                send(Wire.encodeSyncReq(
                    clientId: clientId, t0Us: UInt64(HostClock.nowNs() / 1000),
                    rttUs: UInt32(clamping: clock.rttUs), bufferMs: UInt16(clamping: snap.cushionMs), status: state))
            }
            if now - lastPacketNs > ListenSession.hostTimeoutNs {
                joined = false // host went quiet: fall back to knocking until it answers again
                lastJoinNs = 0
            }
        }
        if now - lastPublishNs >= 120_000_000 {
            lastPublishNs = now
            publish(now: now)
        }
    }

    private func publish(now: Int64) {
        let snap = engine.snapshot()
        if now - lossWindowNs > 2_000_000_000 {
            let total = (snap.playedFrames - lossPlayed) + (snap.concealedFrames - lossConcealed)
            lossPercent = total > 0 && snap.state == .playing
                ? 100 * Float(snap.concealedFrames - lossConcealed) / Float(total) : 0
            lossPlayed = snap.playedFrames
            lossConcealed = snap.concealedFrames
            lossWindowNs = now
        }
        let phase: ReceiverStatus.Phase
        if ended {
            phase = .ended
        } else if !joined {
            phase = everJoined ? .reconnecting : .connecting
        } else {
            phase = snap.state == .playing ? .playing : .syncing
        }
        stateLock.lock()
        let offset = userOffsetMs
        let output = routeName
        let bluetooth = routeIsBluetooth
        stateLock.unlock()
        onStatus(ReceiverStatus(
            phase: phase, hostName: hostName, message: message,
            syncErrorMs: snap.syncErrorMs, rttMs: Float(clock.rttUs) / 1000, bufferMs: snap.cushionMs,
            lateBiasMs: snap.lateBiasMs, playoutDelayMs: playoutDelayMs, offsetMs: offset,
            outputName: output, outputIsBluetooth: bluetooth, lossPercent: lossPercent, level: snap.level))
    }
}

/// A thread-safe "keep running" flag for the playout thread.
private final class RunFlag {
    private let lock = NSLock()
    private var value = true

    var isSet: Bool {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func clear() {
        lock.lock()
        value = false
        lock.unlock()
    }
}
