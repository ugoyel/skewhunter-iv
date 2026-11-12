import SwiftUI
import UIKit
import WebKit

/// What the player wants to tell the screen.
struct PlayerNotice: Equatable {
    var title: String?
    var error: String?
    var offErrorMs: Float?
}

/// YouTube's official embedded player, steered onto the party's timeline by `VideoSyncController`.
///
/// Only YouTube's own IFrame Player API is used (load, play, pause, seek, speed), and the player is
/// shown on screen. Playback stops when the app leaves the screen, as YouTube's rules require.
/// Mirrors android/.../ui/YouTubePlayer.kt.
@MainActor
final class YouTubeWebPlayer: NSObject, WKNavigationDelegate, WKUIDelegate {
    static let baseURL = URL(string: "https://io.github.ugoyel.hearby/")!
    private static let tickInterval = 0.2
    private static let offlineAfter = 12.0
    private static let notStarted = "The video player didn't start on this iPhone. Restart Hearby and try again."

    let webView: WKWebView
    private var controller = VideoSyncController()
    private var html: String?
    private var timer: Timer?
    private var active = true
    private var startedAt = Date()
    private var everReady = false
    private var lastAnswer = Date.distantPast
    private var lastNotice: PlayerNotice?
    /// Timed play presses and speed restores (selector timers fire on the main run loop, to the ms).
    private var scheduled: [Timer] = []

    /// The party's video, as last received.
    var timeline: VideoTimeline?
    /// DJ clock minus this phone's clock, µs.
    var hostOffsetUs: () -> Int64 = { 0 }
    /// This phone's own correction (slider + sound check), µs; positive plays later.
    var correctionUs: () -> Int64 = { 0 }
    var onNotice: (PlayerNotice) -> Void = { _ in }

    override init() {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true // never take over the screen
        // Guests' players must start when the DJ presses play, not when each guest taps.
        config.mediaTypesRequiringUserActionForPlayback = []
        config.allowsPictureInPictureMediaPlayback = false
        webView = WKWebView(frame: .zero, configuration: config)
        super.init()
        config.userContentController.add(MessageRelay(self), name: "hearby")
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.isOpaque = false
        webView.backgroundColor = .black
        webView.scrollView.isScrollEnabled = false
        webView.scrollView.bounces = false
        if let url = Bundle.main.url(forResource: "YouTubePlayer", withExtension: "html") {
            html = try? String(contentsOf: url, encoding: .utf8)
        }
        loadPage()
        active = UIApplication.shared.applicationState == .active
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(resignActive), name: UIApplication.willResignActiveNotification, object: nil)
        center.addObserver(self, selector: #selector(becomeActive), name: UIApplication.didBecomeActiveNotification, object: nil)
    }

    private func loadPage() {
        // An https origin of our own: the player's requests then carry a Referer naming the app.
        if let html { webView.loadHTMLString(html, baseURL: YouTubeWebPlayer.baseURL) }
    }

    /// The page's process crashed or iOS reclaimed it: the view is blank now, so start over.
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        controller = VideoSyncController()
        everReady = false
        startedAt = Date()
        loadPage()
    }

    @objc private func resignActive() {
        active = false
        js("hearbyPause()") // YouTube does not allow playing in the background
    }

    @objc private func becomeActive() { active = true }

    func start() {
        guard timer == nil else { return }
        startedAt = Date()
        let t = Timer(timeInterval: YouTubeWebPlayer.tickInterval, target: self, selector: #selector(tick), userInfo: nil, repeats: true)
        RunLoop.main.add(t, forMode: .common) // keep steering while the page scrolls
        timer = t
    }

    func release() {
        timer?.invalidate()
        timer = nil
        scheduled.forEach { $0.invalidate() }
        scheduled.removeAll()
        NotificationCenter.default.removeObserver(self)
        js("hearbyPause()")
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "hearby")
        webView.stopLoading()
    }

    /// Run `code` in the page `afterUs` from now.
    private func later(_ afterUs: Int64, _ code: String) {
        let t = Timer(timeInterval: Double(afterUs) / 1e6, target: self, selector: #selector(runScheduled), userInfo: code, repeats: false)
        t.tolerance = 0
        RunLoop.main.add(t, forMode: .common)
        scheduled.removeAll { !$0.isValid }
        scheduled.append(t)
    }

    @objc private func runScheduled(_ timer: Timer) {
        if let code = timer.userInfo as? String { js(code) }
    }

    @objc private func tick() {
        // A page that never answers at all (its process hung) cannot report anything itself.
        if timeline != nil, !everReady, Date().timeIntervalSince(startedAt) > YouTubeWebPlayer.offlineAfter,
           Date().timeIntervalSince(lastAnswer) > 2 {
            notify(PlayerNotice(error: YouTubeWebPlayer.notStarted))
        }
        webView.evaluateJavaScript("hearbyStatus()") { [weak self] result, _ in
            self?.onStatus(result as? String)
        }
    }

    private func onStatus(_ raw: String?) {
        guard let data = raw?.data(using: .utf8),
              let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            // The page itself never came up (it is bundled with the app, so this is not the network).
            if timeline != nil, Date().timeIntervalSince(startedAt) > YouTubeWebPlayer.offlineAfter {
                notify(PlayerNotice(error: YouTubeWebPlayer.notStarted))
            }
            return
        }
        lastAnswer = Date()
        let ready = json["ready"] as? Bool ?? false
        if ready { everReady = true }
        if !everReady, timeline != nil, Date().timeIntervalSince(startedAt) > YouTubeWebPlayer.offlineAfter {
            notify(PlayerNotice(error: "Can't reach YouTube. Every phone needs internet for YouTube mode."))
            return
        }
        // When the page read the player, on this phone's monotonic clock.
        let nowUs = HostClock.nowNs() / 1000
        let nowMs = Date().timeIntervalSince1970 * 1000
        let ageUs = max(0, (nowMs - (json["at"] as? Double ?? nowMs)) * 1000)
        let report = PlayerReport(
            ready: ready,
            state: json["s"] as? Int ?? VideoSyncController.unstarted,
            positionUs: Int64((json["t"] as? Double ?? 0) * 1e6),
            measuredAtUs: nowUs - Int64(ageUs),
            rate: json["r"] as? Double ?? 1,
            availableRates: (json["rates"] as? [Double]).flatMap { $0.isEmpty ? nil : $0 } ?? [1],
            videoId: json["id"] as? String ?? "",
            error: json["err"] as? Int ?? 0)
        // The player's clock already reports what is audible now (the picture is delayed by the
        // speaker or AirPods latency for lip-sync), so no output latency is added here.
        let hostNow = nowUs + hostOffsetUs() - correctionUs() // playing later = looking at an earlier moment
        let t = timeline
        let target = t?.positionAt(hostNow) ?? 0
        let startsIn = t?.startsInUs(hostNow) ?? 0
        for action in controller.step(nowUs: nowUs, target: t, targetPositionUs: target, startsInUs: startsIn,
                                      report: report, mayStart: active && visibleEnough()) {
            execute(action)
        }
        if report.error != 0 {
            notify(PlayerNotice(error: YouTubeWebPlayer.describe(report.error)))
        } else {
            let title = (json["title"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            notify(PlayerNotice(title: title, offErrorMs: controller.lastErrorUs.map { Float((Double($0) / 1000).rounded()) }))
        }
    }

    private func notify(_ notice: PlayerNotice) {
        guard notice != lastNotice else { return }
        lastNotice = notice
        onNotice(notice)
    }

    private func execute(_ action: PlayerAction) {
        switch action {
        case .load(let id):
            let safe = id.filter { $0.isLetter || $0.isNumber || $0 == "_" || $0 == "-" }
            js("hearbyLoad('\(safe)')")
        case .play(let afterUs):
            if afterUs > 0 { later(afterUs, "hearbyPlay()") } else { js("hearbyPlay()") }
        case .pause:
            js("hearbyPause()")
        case .seek(let seconds):
            js("hearbySeek(\(seconds))")
        case .rate(let rate, let forUs):
            js("hearbyRate(\(rate))")
            if forUs > 0 { later(forUs, "hearbyRate(1)") }
        }
    }

    private func js(_ code: String) { webView.evaluateJavaScript(code, completionHandler: nil) }

    /// YouTube allows automatic playback only while at least half of the player is on screen.
    private func visibleEnough() -> Bool {
        guard let window = webView.window, !webView.isHidden, webView.bounds.width > 0, webView.bounds.height > 0 else { return false }
        let frame = webView.convert(webView.bounds, to: window)
        let visible = frame.intersection(window.bounds)
        guard !visible.isNull else { return false }
        return visible.width * visible.height * 2 >= frame.width * frame.height
    }

    fileprivate func event(type: String, data: String) {
        if type == "offline" {
            notify(PlayerNotice(error: "Can't reach YouTube. Every phone needs internet for YouTube mode."))
        }
    }

    // A tap on YouTube's logo or title opens YouTube itself, never a page inside Hearby.
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void) {
        if navigationAction.targetFrame?.isMainFrame == true, let url = navigationAction.request.url,
           url.host != YouTubeWebPlayer.baseURL.host, url.scheme == "https" || url.scheme == "http" {
            UIApplication.shared.open(url)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = navigationAction.request.url { UIApplication.shared.open(url) }
        return nil
    }

    static func describe(_ code: Int) -> String {
        switch code {
        case 2: return "That doesn't look like a valid YouTube video."
        case 5: return "This iPhone can't play that video."
        case 100: return "That video was removed or is private."
        case 101, 150: return "The video's owner doesn't allow it to play in other apps. Ask the DJ to pick another one."
        case 152, 153: return "YouTube wouldn't play inside Hearby on this iPhone. Try updating iOS."
        default: return "YouTube couldn't play that video (error \(code))."
        }
    }
}

/// Relays page messages without the content controller keeping the player alive.
@MainActor
private final class MessageRelay: NSObject, WKScriptMessageHandler {
    private weak var player: YouTubeWebPlayer?

    init(_ player: YouTubeWebPlayer) { self.player = player }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any] else { return }
        player?.event(type: body["type"] as? String ?? "", data: body["data"] as? String ?? "")
    }
}

/// The party's YouTube video, playing in sync on this iPhone. Shown 16:9 at full width (YouTube asks
/// for at least 200×200 pt).
struct SyncedYouTubePlayer: UIViewRepresentable {
    let timeline: VideoTimeline?
    let hostOffsetUs: () -> Int64
    let correctionUs: () -> Int64
    var onNotice: (PlayerNotice) -> Void = { _ in }

    func makeCoordinator() -> YouTubeWebPlayer { YouTubeWebPlayer() }

    func makeUIView(context: Context) -> WKWebView {
        let player = context.coordinator
        configure(player)
        player.start()
        return player.webView
    }

    func updateUIView(_ view: WKWebView, context: Context) { configure(context.coordinator) }

    static func dismantleUIView(_ view: WKWebView, coordinator: YouTubeWebPlayer) { coordinator.release() }

    private func configure(_ player: YouTubeWebPlayer) {
        player.timeline = timeline
        player.hostOffsetUs = hostOffsetUs
        player.correctionUs = correctionUs
        player.onNotice = onNotice
    }
}
