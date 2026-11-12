import Foundation

/// YouTube mode, AmpMe style: every phone plays the same YouTube video itself, in YouTube's own
/// embedded player, and Hearby keeps the players in time.
///
/// The DJ phone owns a `VideoTimeline` ("video X was at position P at host time T, playing or
/// paused") and sends it to everyone. Each phone converts it to its own clock with the clock sync
/// Hearby already runs, and a `VideoSyncController` steers its player toward that position through
/// the official IFrame Player API only: load, play, pause, seek and (when the player offers fine
/// speeds) a slight speed change. The player itself is never modified.
///
/// Mirrors android/.../core/VideoSync.kt.
enum YouTubeLinks {
    private static let idPattern = try! NSRegularExpression(pattern: "^[A-Za-z0-9_-]{11}$")
    private static let patterns: [NSRegularExpression] = [
        #"(?:https?://)?(?:www\.|m\.|music\.)?youtube\.com/watch\?(?:[^#\s]*&)?v=([A-Za-z0-9_-]{11})"#,
        #"(?:https?://)?(?:www\.|m\.)?youtu\.be/([A-Za-z0-9_-]{11})"#,
        #"(?:https?://)?(?:www\.|m\.|music\.)?youtube(?:-nocookie)?\.com/(?:shorts|live|embed|v)/([A-Za-z0-9_-]{11})"#,
    ].map { try! NSRegularExpression(pattern: $0) }

    /// The video ID in a YouTube link (or a bare ID), or nil. Text around the link is ignored.
    static func videoId(_ text: String) -> String? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let whole = NSRange(t.startIndex..., in: t)
        if idPattern.firstMatch(in: t, range: whole) != nil { return t }
        for p in patterns {
            if let m = p.firstMatch(in: t, range: whole), let r = Range(m.range(at: 1), in: t) { return String(t[r]) }
        }
        return nil
    }
}

/// The party's video: what plays, and where it is on the DJ phone's clock.
struct VideoTimeline: Equatable {
    var videoId: String
    var title: String
    var playing: Bool
    /// DJ clock, µs. While playing, the video starts (or resumes) from `positionUs` at this moment.
    var anchorHostUs: Int64
    /// Video position at `anchorHostUs`, µs.
    var positionUs: Int64
    /// Bumped on every change, so phones can tell a new instruction from a repeat.
    var version: UInt32

    /// Where the video should be at DJ-clock time `hostUs`: held at `positionUs` until the anchor.
    func positionAt(_ hostUs: Int64) -> Int64 { playing ? positionUs + max(0, hostUs - anchorHostUs) : positionUs }

    /// How long until playback (re)starts, µs; 0 if it already has or the video is paused.
    func startsInUs(_ hostUs: Int64) -> Int64 { playing && hostUs < anchorHostUs ? anchorHostUs - hostUs : 0 }
}

/// What a player reports about itself (YouTube IFrame Player API values).
struct PlayerReport {
    var ready: Bool
    /// -1 unstarted, 0 ended, 1 playing, 2 paused, 3 buffering, 5 cued.
    var state: Int
    /// Player position, µs, as of `measuredAtUs`.
    var positionUs: Int64
    /// Local clock, µs.
    var measuredAtUs: Int64
    var rate = 1.0
    var availableRates: [Double] = [1.0]
    var videoId = ""
    /// YouTube error code, 0 if none (2 bad id, 5 HTML5 error, 100 not found, 101/150 not embeddable).
    var error = 0
}

/// One instruction for the player.
enum PlayerAction: Equatable {
    case load(String)
    /// Press play `afterUs` from now (scheduled precisely by the caller, not at the next check).
    case play(afterUs: Int64)
    case pause
    case seek(Double)
    /// Play at `rate` for `forUs`, then back to normal speed (timed precisely by the caller); 0 = until told.
    case rate(Double, forUs: Int64)
}

/// Steers one YouTube player onto the shared timeline. Pure logic: call `step` a few times a second
/// with the latest report and the target position, and carry out the returned actions.
///
/// Big errors (joining late, after an ad, after a pause) are fixed by seeking. A seek takes a
/// moment to take effect, so the controller aims ahead by a lead it learns from its own previous
/// seeks. How long a seek takes varies by tens of ms, though, so smaller errors are pulled in with
/// an exactly timed speed change instead: a gentle one (within 10 %) when the player offers it,
/// otherwise a short one at up to 25 %. Pitch is preserved by the player either way.
final class VideoSyncController {
    static let unstarted = -1
    static let ended = 0
    static let playingState = 1
    static let paused = 2
    static let buffering = 3
    static let cued = 5

    static let hardErrorUs: Int64 = 1_000_000
    static let seekThresholdUs: Int64 = 150_000
    static let coarseNudgeThresholdUs: Int64 = 15_000
    static let nudgeThresholdUs: Int64 = 8_000
    static let nudgeSettleUs: Int64 = 150_000
    static let startWindowUs: Int64 = 400_000
    static let pausedToleranceUs: Int64 = 300_000
    static let parkedToleranceUs: Int64 = 50_000
    static let parkCooldownUs: Int64 = 600_000
    static let initialSeekLeadUs: Int64 = 250_000
    static let initialPlayLeadUs: Int64 = 150_000
    static let maxLeadUs: Int64 = 2_000_000
    /// Only landings this close teach anything about delay: a seek into a part of the video that is
    /// not loaded yet lands on an earlier keyframe, which says nothing about timing.
    static let learnLimitUs: Int64 = 300_000
    static let loadCooldownUs: Int64 = 1_000_000
    static let seekCooldownUs: Int64 = 1_500_000
    static let startCooldownUs: Int64 = 1_200_000
    static let maxFineNudge = 0.10
    static let maxCoarseNudge = 0.25

    private var loadedId: String?
    private var cooldownUntilUs = Int64.min
    private var nudgeUntilUs = Int64.min
    private var nudging = false
    private var seekLeadUs = VideoSyncController.initialSeekLeadUs
    private var playLeadUs = VideoSyncController.initialPlayLeadUs
    private var awaitingSeekResult = false
    private var leadAtSeek: Int64 = 0
    private var awaitingStartResult = false
    private var errors: [Int64] = []

    /// Last measured error (player − target), µs, or nil while not measuring. Positive = ahead.
    private(set) var lastErrorUs: Int64?
    private(set) var seeks = 0
    private(set) var nudges = 0

    /// - Parameters:
    ///   - target: the party's timeline, or nil when no video is on.
    ///   - targetPositionUs: where the video should be at `nowUs` on this phone (corrections included).
    ///   - startsInUs: how long until it should (re)start playing on this phone, 0 if it should be playing now.
    ///   - mayStart: false while the player is not on screen: YouTube's rules do not allow starting
    ///     playback of a player that is not visible.
    func step(
        nowUs: Int64, target: VideoTimeline?, targetPositionUs: Int64, startsInUs: Int64,
        report: PlayerReport, mayStart: Bool
    ) -> [PlayerAction] {
        typealias C = VideoSyncController
        guard let target else {
            reset()
            return report.ready && (report.state == C.playingState || report.state == C.buffering) ? [.pause] : []
        }
        guard report.ready, report.error == 0 else { return [] }
        if loadedId != target.videoId {
            reset()
            loadedId = target.videoId
            cooldownUntilUs = nowUs + C.loadCooldownUs
            return [.load(target.videoId)]
        }
        if nowUs < cooldownUntilUs { return [] }

        let playing = report.state == C.playingState
        let position = report.positionUs + (playing ? Int64(Double(nowUs - report.measuredAtUs) * report.rate) : 0)

        if !target.playing {
            if let restore = restoreRate(report) { return [restore] }
            if playing || report.state == C.buffering { return [.pause] }
            if abs(position - targetPositionUs) > C.pausedToleranceUs && targetPositionUs >= 0 {
                cooldownUntilUs = nowUs + C.seekCooldownUs
                return [.seek(Double(targetPositionUs) / 1e6)]
            }
            return []
        }

        // A start or resume is scheduled: wait at the right spot, then press play just early enough.
        if startsInUs > 0 {
            if playing || report.state == C.buffering { return [.pause] }
            if abs(position - targetPositionUs) > C.parkedToleranceUs {
                cooldownUntilUs = nowUs + C.parkCooldownUs
                return [.seek(Double(targetPositionUs) / 1e6)]
            }
            if startsInUs <= playLeadUs + C.startWindowUs && mayStart {
                awaitingStartResult = true
                errors.removeAll()
                let after = max(0, startsInUs - playLeadUs)
                cooldownUntilUs = nowUs + after + C.startCooldownUs
                return [.play(afterUs: after)]
            }
            return []
        }

        if report.state == C.buffering { return [] }
        if !playing {
            if !mayStart { return [] }
            // Joined late, resumed, or the player stopped: jump to where everyone is, aiming ahead.
            return seekTo(nowUs: nowUs, targetPositionUs: targetPositionUs, play: true)
        }

        // Playing: measure.
        let error = position - targetPositionUs
        if nudging {
            if nowUs < nudgeUntilUs + C.nudgeSettleUs { return [] }
            nudging = false // the caller has already gone back to normal speed
            errors.removeAll()
        }
        if let restore = restoreRate(report) { return [restore] }

        // Learn from the last seek or start: aiming at target + lead landed us `error` off.
        if awaitingSeekResult {
            awaitingSeekResult = false
            if abs(error) < C.learnLimitUs { seekLeadUs = (seekLeadUs + clampLead(leadAtSeek - error)) / 2 }
        }
        if awaitingStartResult {
            awaitingStartResult = false
            // Pressed play `playLead` early and landed `error` ahead: the real start delay is playLead − error.
            if abs(error) < C.learnLimitUs { playLeadUs = (playLeadUs + clampLead(playLeadUs - error)) / 2 }
        }

        errors.append(error)
        if errors.count > 3 { errors.removeFirst(errors.count - 3) }
        let e = errors.sorted()[errors.count / 2] // median of the last three: one odd sample cannot trigger a seek
        lastErrorUs = e

        if abs(error) > C.hardErrorUs || (errors.count >= 3 && abs(e) > C.seekThresholdUs) {
            return seekTo(nowUs: nowUs, targetPositionUs: targetPositionUs, play: false)
        }
        if errors.count < 3 { return [] }
        let rate: Double?
        if abs(e) > C.coarseNudgeThresholdUs {
            rate = C.nudgeRate(report.availableRates, slower: e > 0, maxChange: C.maxFineNudge)
                ?? C.nudgeRate(report.availableRates, slower: e > 0, maxChange: C.maxCoarseNudge)
        } else if abs(e) > C.nudgeThresholdUs {
            rate = C.nudgeRate(report.availableRates, slower: e > 0, maxChange: C.maxFineNudge)
        } else {
            rate = nil
        }
        guard let rate else { return [] }
        let forUs = Int64(Double(abs(e)) / abs(rate - 1.0))
        nudging = true
        nudges += 1
        nudgeUntilUs = nowUs + forUs
        return [.rate(rate, forUs: forUs)]
    }

    private func clampLead(_ v: Int64) -> Int64 { min(max(v, 0), VideoSyncController.maxLeadUs) }

    private func seekTo(nowUs: Int64, targetPositionUs: Int64, play: Bool) -> [PlayerAction] {
        seeks += 1
        leadAtSeek = seekLeadUs
        awaitingSeekResult = true
        errors.removeAll()
        cooldownUntilUs = nowUs + VideoSyncController.seekCooldownUs
        let seek = PlayerAction.seek(Double(targetPositionUs + seekLeadUs) / 1e6)
        return play ? [seek, .play(afterUs: 0)] : [seek]
    }

    private func restoreRate(_ report: PlayerReport) -> PlayerAction? {
        !nudging && abs(report.rate - 1.0) > 1e-3 ? .rate(1.0, forUs: 0) : nil
    }

    private func reset() {
        loadedId = nil
        nudging = false
        awaitingSeekResult = false
        awaitingStartResult = false
        errors.removeAll()
        lastErrorUs = nil
    }

    /// The available speed closest to normal, within `maxChange`, on the requested side.
    static func nudgeRate(_ rates: [Double], slower: Bool, maxChange: Double = maxFineNudge) -> Double? {
        rates
            .filter { slower ? ($0 < 1.0 && $0 >= 1.0 - maxChange - 1e-9) : ($0 > 1.0 && $0 <= 1.0 + maxChange + 1e-9) }
            .min { abs($0 - 1.0) < abs($1 - 1.0) }
    }
}
