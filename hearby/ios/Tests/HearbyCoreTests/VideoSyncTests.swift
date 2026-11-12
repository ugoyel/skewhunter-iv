import XCTest
@testable import HearbyCore

/// Mirrors android/.../core/VideoSyncTest.kt: the same links, timeline rules and simulated players.
final class YouTubeLinksTests: XCTestCase {
    func testFindsTheVideoInEveryKindOfLink() {
        let id = "dQw4w9WgXcQ"
        for link in [
            "https://www.youtube.com/watch?v=\(id)",
            "https://youtube.com/watch?v=\(id)&t=42s",
            "https://m.youtube.com/watch?feature=share&v=\(id)",
            "https://music.youtube.com/watch?v=\(id)&si=abc",
            "https://youtu.be/\(id)",
            "https://youtu.be/\(id)?si=xYz123",
            "https://www.youtube.com/shorts/\(id)",
            "https://www.youtube.com/live/\(id)?feature=share",
            "https://www.youtube-nocookie.com/embed/\(id)",
            "Check this out! https://youtu.be/\(id) 🔥",
            "youtube.com/watch?v=\(id)",
            id,
        ] {
            XCTAssertEqual(YouTubeLinks.videoId(link), id, link)
        }
    }

    func testIgnoresEverythingElse() {
        for text in ["", "hello", "https://vimeo.com/123456", "https://www.youtube.com/", "https://youtu.be/short", "dQw4w9WgXc"] {
            XCTAssertNil(YouTubeLinks.videoId(text), text)
        }
    }
}

final class VideoTimelineTests: XCTestCase {
    func testPositionFollowsTheClockOnlyWhilePlaying() {
        let playing = VideoTimeline(videoId: "id", title: "", playing: true, anchorHostUs: 10_000_000, positionUs: 5_000_000, version: 1)
        XCTAssertEqual(playing.positionAt(8_000_000), 5_000_000) // held until the start
        XCTAssertEqual(playing.startsInUs(8_000_000), 2_000_000)
        XCTAssertEqual(playing.positionAt(10_000_000), 5_000_000)
        XCTAssertEqual(playing.positionAt(13_500_000), 8_500_000)
        XCTAssertEqual(playing.startsInUs(13_500_000), 0)
        var paused = playing
        paused.playing = false
        paused.positionUs = 42_000_000
        XCTAssertEqual(paused.positionAt(99_000_000), 42_000_000)
        XCTAssertEqual(paused.startsInUs(0), 0)
    }
}

/// The controller against simulated YouTube players: start and seek delays that vary, seeks that
/// land on the previous keyframe until that part of the video is loaded, playback clocks that
/// drift, and either fine or coarse speed steps.
final class VideoSyncTests: XCTestCase {
    private typealias C = VideoSyncController
    private static let keyframeUs = 2_000_000.0
    private static let never = Int64.min

    /// A rough model of the YouTube IFrame player on a phone.
    private final class SimPlayer {
        private var rng: SeededRandom
        private let driftPpm: Double
        let rates: [Double]
        private let adUs: Int64
        var state = C.unstarted
        var positionUs = 0.0 // the true media position
        var rate = 1.0
        private var videoId = ""
        private var pendingUntil = VideoSyncTests.never // a seek or start is in progress until then
        private var pendingTo = 0.0
        private var pendingPlay = false
        private var wantPlay = false
        private var bufferedFrom = 0.0
        private var bufferedTo = 0.0
        private var adLeft: Int64 = 0
        private var lastUs: Int64 = 0
        private var playAtUs = VideoSyncTests.never
        private var restoreRateAtUs = VideoSyncTests.never

        init(seed: UInt64, driftPpm: Double, rates: [Double], adUs: Int64 = 0) {
            rng = SeededRandom(seed: seed)
            self.driftPpm = driftPpm
            self.rates = rates
            self.adUs = adUs
        }

        func apply(_ a: PlayerAction, _ nowUs: Int64) {
            switch a {
            case .load(let id):
                videoId = id
                state = C.cued
                positionUs = 0
                bufferedFrom = 0
                bufferedTo = 0
                adLeft = adUs
            case .play(let afterUs):
                if afterUs > 0 { playAtUs = nowUs + afterUs } else { play(nowUs) }
            case .pause:
                wantPlay = false
                if state == C.playingState || state == C.buffering { state = C.paused }
                pendingUntil = VideoSyncTests.never
            case .seek(let seconds):
                if adLeft > 0 { return } // YouTube ignores seeks during an ad
                let to = seconds * 1e6
                let loaded = to >= bufferedFrom && to <= bufferedTo
                let lands = loaded ? to : (to / VideoSyncTests.keyframeUs).rounded(.down) * VideoSyncTests.keyframeUs
                pendingUntil = nowUs + Int64(loaded ? 80_000 + rng.int(120_000) : 300_000 + rng.int(400_000))
                pendingTo = lands
                pendingPlay = wantPlay || state == C.playingState
                if !loaded {
                    bufferedFrom = lands
                    bufferedTo = lands
                }
                state = pendingPlay ? C.buffering : C.paused
                positionUs = lands
            case .rate(let wanted, let forUs):
                rate = rates.min { abs($0 - wanted) < abs($1 - wanted) }! // the player rounds to what it has
                restoreRateAtUs = forUs > 0 ? nowUs + forUs : VideoSyncTests.never
            }
        }

        private func play(_ nowUs: Int64) {
            wantPlay = true
            if pendingUntil != VideoSyncTests.never {
                pendingPlay = true // a seek in progress: play when it lands
                return
            }
            if state != C.playingState {
                pendingUntil = nowUs + Int64(150_000 + rng.int(250_000) + (bufferedTo <= bufferedFrom ? 250_000 : 0))
                pendingTo = positionUs
                pendingPlay = true
                state = C.buffering
            }
        }

        /// Advance in small steps so timed play and speed changes happen at their exact moment.
        func advance(_ toUs: Int64) {
            while lastUs < toUs {
                var next = min(toUs, lastUs + 1_000)
                if playAtUs > lastUs && playAtUs <= next { next = playAtUs }
                if restoreRateAtUs > lastUs && restoreRateAtUs <= next { next = restoreRateAtUs }
                step(next)
                if next == playAtUs {
                    playAtUs = VideoSyncTests.never
                    play(next)
                }
                if next == restoreRateAtUs {
                    restoreRateAtUs = VideoSyncTests.never
                    rate = 1.0
                }
            }
        }

        private func step(_ toUs: Int64) {
            let dt = toUs - lastUs
            lastUs = toUs
            if pendingUntil != VideoSyncTests.never && toUs >= pendingUntil {
                positionUs = pendingTo + (pendingPlay ? Double(toUs - pendingUntil) : 0)
                state = pendingPlay ? C.playingState : C.paused
                pendingUntil = VideoSyncTests.never
                return
            }
            if state == C.playingState {
                if adLeft > 0 {
                    adLeft -= dt
                    return
                }
                positionUs += Double(dt) * rate * (1 + driftPpm * 1e-6)
                bufferedTo = max(bufferedTo, positionUs + 30_000_000) // it keeps loading ahead
            }
        }

        func report(_ nowUs: Int64) -> PlayerReport {
            // The API's reading is a little noisy (Box-Muller, σ = 1.5 ms).
            let u1 = max(rng.unit(), 1e-12)
            let u2 = rng.unit()
            let noise = (-2 * log(u1)).squareRoot() * cos(2 * Double.pi * u2) * 1_500
            return PlayerReport(
                ready: true, state: state, positionUs: Int64(positionUs + noise), measuredAtUs: nowUs,
                rate: rate, availableRates: rates, videoId: videoId)
        }
    }

    private let fineRates = (0..<36).map { 0.25 + 0.05 * Double($0) } // 0.25 ... 2.0 in 0.05 steps
    private let coarseRates = [0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0]

    private struct Phone {
        let player: SimPlayer
        let controller = VideoSyncController()
    }

    /// Run the phones for `seconds`, 5 ticks per second, recording the true error once settled.
    @discardableResult
    private func run(
        _ phones: [Phone], _ timeline: (Int64) -> VideoTimeline?, from: Int64, seconds: Double, settleUs: Int64,
        worst: inout [Int64]
    ) -> Int64 {
        var now = from
        let end = from + Int64(seconds * 1e6)
        while now < end {
            now += 200_000
            let t = timeline(now)
            for (i, ph) in phones.enumerated() {
                ph.player.advance(now)
                let target = t?.positionAt(now) ?? 0
                let startsIn = t?.startsInUs(now) ?? 0
                for a in ph.controller.step(nowUs: now, target: t, targetPositionUs: target, startsInUs: startsIn,
                                            report: ph.player.report(now), mayStart: true) {
                    ph.player.apply(a, now)
                }
                if let t, t.playing, now > settleUs, target > 0 {
                    worst[i] = max(worst[i], abs(Int64(ph.player.positionUs) - target))
                }
            }
        }
        return now
    }

    private func phones(_ rates: [Double], ad: Int64 = 0) -> [Phone] {
        [
            Phone(player: SimPlayer(seed: 1, driftPpm: 40, rates: rates)),
            Phone(player: SimPlayer(seed: 2, driftPpm: -55, rates: rates, adUs: ad)),
            Phone(player: SimPlayer(seed: 3, driftPpm: 10, rates: rates)),
        ]
    }

    private let start = VideoTimeline(videoId: "vid", title: "Song", playing: true, anchorHostUs: 2_000_000, positionUs: 0, version: 1)

    func testStartsTogetherAndStaysWithinAFewMillisecondsWithFineSpeeds() {
        let phones = phones(fineRates)
        var worst = [Int64](repeating: 0, count: 3)
        run(phones, { _ in self.start }, from: 0, seconds: 180, settleUs: 10_000_000, worst: &worst)
        for (i, w) in worst.enumerated() {
            XCTAssertLessThan(w, 15_000, "phone \(i) worst \(Double(w) / 1000) ms after settling")
            XCTAssertLessThanOrEqual(phones[i].controller.seeks, 6, "phone \(i) seeks")
        }
        print("fine speeds: worst \(worst.map { Double($0) / 1000 }) ms, seeks \(phones.map { $0.controller.seeks })")
    }

    func testStaysWithinTheSeekThresholdWithOnlyCoarseSpeeds() {
        let phones = phones(coarseRates)
        var worst = [Int64](repeating: 0, count: 3)
        run(phones, { _ in self.start }, from: 0, seconds: 180, settleUs: 10_000_000, worst: &worst)
        for (i, w) in worst.enumerated() {
            XCTAssertLessThan(w, 20_000, "phone \(i) worst \(Double(w) / 1000) ms after settling")
            XCTAssertLessThanOrEqual(phones[i].controller.seeks, 6, "phone \(i) seeks")
        }
        print("coarse speeds: worst \(worst.map { Double($0) / 1000 }) ms, seeks \(phones.map { $0.controller.seeks })")
    }

    func testResumesFromThePausePointTogether() {
        let phones = phones(fineRates)
        let playing = start
        var paused = playing
        paused.playing = false
        paused.anchorHostUs = 20_000_000
        paused.positionUs = playing.positionAt(20_000_000)
        paused.version = 2
        var resumed = paused
        resumed.playing = true
        resumed.anchorHostUs = 26_000_000 // "resume in 1 s", sent at 25 s
        resumed.version = 3
        let timeline = { (now: Int64) -> VideoTimeline? in now < 20_000_000 ? playing : now < 25_000_000 ? paused : resumed }
        // Right after the resume a phone can be off by however much its player's start delay varied...
        var early = [Int64](repeating: 0, count: 3)
        let now = run(phones, timeline, from: 0, seconds: 27.5, settleUs: 26_500_000, worst: &early)
        for (i, w) in early.enumerated() { XCTAssertLessThan(w, 250_000, "phone \(i) right after resuming") }
        // ... and a few seconds later everyone is back together.
        var later = [Int64](repeating: 0, count: 3)
        run(phones, timeline, from: now, seconds: 30, settleUs: 33_000_000, worst: &later)
        for (i, w) in later.enumerated() { XCTAssertLessThan(w, 15_000, "phone \(i) 7 s after resuming") }
    }

    func testAPhoneThatGetsAnAdCatchesUpAfterIt() {
        let phones = phones(fineRates, ad: 6_000_000)
        var worst = [Int64](repeating: 0, count: 3)
        run(phones, { _ in self.start }, from: 0, seconds: 60, settleUs: 20_000_000, worst: &worst)
        XCTAssertLessThan(worst[1], 15_000, "phone with the ad, after catching up")
    }

    func testPausesAndResumesWithEveryone() {
        let phones = phones(fineRates)
        let playing = start
        var paused = playing
        paused.playing = false
        paused.anchorHostUs = 30_000_000
        paused.positionUs = playing.positionAt(30_000_000)
        paused.version = 2
        var resumed = paused
        resumed.playing = true
        resumed.anchorHostUs = 41_000_000
        resumed.version = 3
        let timeline = { (now: Int64) -> VideoTimeline? in now < 30_000_000 ? playing : now < 40_000_000 ? paused : resumed }
        var worst = [Int64](repeating: 0, count: 3)
        let now = run(phones, timeline, from: 0, seconds: 34, settleUs: 12_000_000, worst: &worst)
        for ph in phones {
            XCTAssertEqual(ph.player.state, C.paused)
            XCTAssertLessThan(abs(ph.player.positionUs - Double(paused.positionUs)), 400_000, "parked near the pause point")
        }
        var after = [Int64](repeating: 0, count: 3)
        run(phones, timeline, from: now, seconds: 60, settleUs: 50_000_000, worst: &after)
        for (i, w) in after.enumerated() { XCTAssertLessThan(w, 15_000, "phone \(i) after resuming") }
    }

    func testALateJoinerJumpsStraightIn() {
        let phone = Phone(player: SimPlayer(seed: 9, driftPpm: 20, rates: fineRates))
        let timeline = VideoTimeline(videoId: "vid", title: "Song", playing: true, anchorHostUs: 0, positionUs: 0, version: 1) // started long ago
        var worst: [Int64] = [0]
        run([phone], { _ in timeline }, from: 95_000_000, seconds: 30, settleUs: 105_000_000, worst: &worst)
        XCTAssertLessThan(worst[0], 15_000, "late joiner")
    }

    func testNudgesWithTheGentlestSpeedOnTheRightSide() {
        XCTAssertEqual(C.nudgeRate(fineRates, slower: true)!, 0.95, accuracy: 1e-9)
        XCTAssertEqual(C.nudgeRate(fineRates, slower: false)!, 1.05, accuracy: 1e-9)
        XCTAssertNil(C.nudgeRate(coarseRates, slower: true))
        XCTAssertEqual(C.nudgeRate(coarseRates, slower: true, maxChange: C.maxCoarseNudge)!, 0.75, accuracy: 1e-9)
    }
}
