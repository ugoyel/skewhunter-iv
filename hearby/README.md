# Hearby

One phone is the DJ: it plays songs saved on it, and every phone nearby, Android or iPhone, plays
the same music at the same moment, the DJ's own phone included. It works over the local Wi-Fi or
the DJ's own hotspot, through each phone's speaker or its own Bluetooth earbuds or speaker. No
internet, no accounts, no screen sharing, no paid SDKs.

**YouTube mode** (AmpMe style): the DJ pastes a YouTube link, or shares a video from the YouTube
app to Hearby, and every phone plays that video itself, over the internet, in YouTube's official
embedded player. Hearby keeps the players on one shared timeline: they start together, pause and
resume together, and stay in time while they play. See [YouTube mode](#youtube-mode).

A **sound check** then lines every phone up to within about a millisecond: each phone beeps in turn
and the DJ phone's microphone times the beeps.

| | DJ (plays the music) | Guest (plays along) |
|---|---|---|
| Android 10+ (Kotlin, Jetpack Compose) | yes | yes |
| iOS 15+ (Swift, SwiftUI) | not yet | yes |

```
hearby/
  android/   Android Studio project: host + listener app
  ios/       Xcode project: listener app, plus a Swift package for testing the sync core
  docs/      PROTOCOL.md: byte-level packet spec and test vectors
  tools/     hearby_probe.py: a Python peer for debugging (join a host, or act as one)
  store/     Play Store icon and feature graphic
```

In the apps, the phone playing the music is "the DJ", its session is "the party", and listeners
are "the crowd". The code and the protocol keep the plainer words host and listener.

## Look and feel

Hearby is used at parties, so it looks like one: a dark stage with slow-moving pools of neon light,
frosted glass panels, chunky gradient buttons that squish when pressed, a visualizer that moves with
the music actually being played (and settles into a row of dots when it is silent), confetti when
someone joins or when a phone locks into sync, and wording to match ("Be the DJ", "The crowd",
"Finding the beat…").

Android (the DJ playing a song, a sound check running, finding a party, a guest in sync):

![Android screens](docs/screens/android.png)

iPhone (finding a party, syncing, playing in sync, party over):

![iOS screens](docs/screens/ios.png)

The design system lives in one file per platform, `android/.../ui/PartyKit.kt` and
`ios/Hearby/PartyKit.swift`: the palette, the animated backdrop, buttons, visualizer, confetti. The
type is Fredoka on Android (bundled, SIL Open Font License, see `android/licenses/FONTS.md`) and SF
Rounded on iOS. The app is always dark, and looping animations stop when the system's reduce-motion
or animation-off setting is on.

Screens can be rendered without a phone:

- Android: `./gradlew testDebugUnitTest --tests '*ScreenshotTest*'` writes every screen, with sample
  data, to `app/build/outputs/roborazzi/`.
- iOS: launch a debug build with `-demo join`, `-demo playing`, `-demo syncing`, `-demo ended` or
  `-demo youtube` to show a screen with sample data and no network. CI does this in the simulator.

CI publishes both sets as downloadable artifacts (`hearby-android-screens`, `hearby-ios-screens`) on
every run. The images above come from those renders.

## How it works

```
 DJ PHONE (Android)                                                          PartyHostService.kt
 ┌──────────────────────────────────────────────────────────────────────────────────────────────┐
 │ songs picked from the phone (system file picker: MP3, AAC/M4A, FLAC, Ogg, WAV ...)           │
 │      ▼                                                                                       │
 │ MusicPlayer: MediaExtractor + MediaCodec decode ─▶ resample to 48 kHz stereo                 │
 │      ▼                                                                                       │
 │ sender thread: exactly one 20 ms frame every 20 ms, music or silence (pause never stops it)  │
 │      ▼                                                                                       │
 │ MediaCodec "audio/opus" encoder ─▶ ┌ 'H''B' ver type │ seq u32 │ timestamp u64 │ Opus ┐      │
 │                                    └──────────────────────────────────────────────────┘      │
 │      ├──────────────▶ this phone's own PlayoutRunner (plays in sync with everyone)           │
 │      ▼ UDP unicast to each guest (DSCP EF)                                                   │
 │ control thread: JOIN · SYNC (clock sync + keep-alive) · LEAVE · DISCOVER · NOW_PLAYING       │
 │ sound check: CHIRP to each phone, microphone times them, OFFSET corrections back             │
 │ mDNS: NsdManager registers _hearby._udp                                                      │
 └───────────────────────────────────────────────┼──────────────────────────────────────────────┘
                                                 │  Wi-Fi LAN or the DJ's hotspot, port 47800
                 ┌───────────────────────────────┴───────────────────────────────┐
                 ▼                                                               ▼
 GUEST (Android)  AndroidSyncReceiver.kt               GUEST (iOS)  IOSSyncReceiver.swift
 ┌──────────────────────────────────────────┐          ┌──────────────────────────────────────────┐
 │ DatagramSocket (bound to Wi-Fi)          │          │ NWConnection (UDP, no cellular)          │
 │   ├─ SYNC_RESP ─▶ ClockSync              │          │   ├─ SYNC_RESP ─▶ ClockSync              │
 │   ├─ AUDIO ─────▶ JitterBuffer           │          │   ├─ AUDIO ─────▶ JitterBuffer           │
 │   └─ CHIRP / OFFSET ─▶ sound check       │          │   └─ CHIRP / OFFSET ─▶ sound check       │
 │ PlayoutRunner                            │  shared  │ playout thread                           │
 │   PlayoutEngine: which frame is due now? │   core   │   PlayoutEngine: which frame is due now? │
 │   ├─ MediaCodec Opus decode              │ ◀──────▶ │   ├─ AudioToolbox Opus decode            │
 │   ├─ Resampler (±0.5 % drift correction) │  logic   │   ├─ Resampler (±0.5 % drift correction) │
 │   └─ AudioTrack.write (blocking)         │          │   └─ ring buffer ─▶ AVAudioSourceNode    │
 └──────────────────────────────────────────┘          └──────────────────────────────────────────┘

 Every phone, the DJ's included, plays the first sample of a packet at:
     (packet timestamp − clock offset) + playout delay + sound-check correction + its own slider
```

### The pieces that make it stay in sync

1. **One timeline.** The DJ phone produces exactly one 20 ms frame every 20 ms on its monotonic
   clock and stamps each with that time. The timeline never stops while the party is on: a pause
   just sends silence, and a song change just changes what is in the next frame, so nobody ever
   has to re-align.
2. **Clock offset per phone.** An NTP-style exchange (burst of 8 on join, then 1/s) estimates
   `DJ clock − local clock`. Of the last 12 exchanges the one with the shortest round trip is
   trusted, because its error bound (half the round trip) is the smallest.
3. **A common playout delay.** Everyone plays one fixed delay (200 / 350 / 700 / 1200 ms) after the
   timestamp, the DJ phone included. It has to cover the network, each phone's audio buffer and
   its output latency. Pressing pause or skip is heard after that delay, on every phone at once.
4. **Closed-loop playout.** Before each frame a phone asks its audio output when the next written
   sample will actually be *heard* (`AudioTrack.getTimestamp()` on Android; render-cycle host time
   + `AVAudioSession.outputLatency` on iOS) and compares that with when the frame is *due*. The
   error steers a 16-tap polyphase sinc resampler by at most ±0.5 %, which absorbs the drift between
   the clocks. Errors over 30 ms or a setting change trigger a sample-exact re-align instead.
5. **The sound check: the last few milliseconds.** Step 4 is only as good as what the phone
   *reports*, and phones misreport their speaker latency by a few milliseconds, Bluetooth by up to
   hundreds. That residue is the audible "flam" between phones. So the DJ phone asks every phone
   to play a 40 ms chirp (1.5 to 7 kHz sweep) at its own slot on the shared timeline and records
   them all with its microphone. A matched filter (FFT cross-correlation, first strong peak, so
   wall reflections are ignored) times each chirp to a fraction of a millisecond. The DJ phone
   chirps too, first and last in every round: that cancels the microphone's own unknown latency,
   and any clock drift during the round. Each phone gets back its correction, and a second round
   mops up what is left.
6. **Jitter and loss.** Packets wait, still encoded, in a sequence-indexed buffer until the last
   moment. A missing frame is concealed with a 5 ms reversed-tail fade-out and a fade-in on the next
   good frame, so loss sounds like a tiny dip, not a click.
7. **Honest fallback.** If a phone cannot meet the delay (for example a Bluetooth speaker with more
   latency than the cushion allows), it plays later by exactly what is missing, shows that number,
   and reclaims it automatically when headroom returns. The sound check says so too.
8. **The manual slider.** Still there (−500 … +500 ms, remembered per output device) for anyone
   who wants to fine-tune by ear or cannot run a sound check.

`PlayoutEngine`, `ClockSync`, `JitterBuffer`, `Resampler`, the chirp and the packet codec exist
twice, in Kotlin and Swift, deliberately line for line the same and with no platform imports.

### How close is "in sync"?

Sound itself travels about 34 cm per millisecond, so two phones 2 m apart already reach a listener
up to 6 ms apart depending on where they stand. No system can make that 0. What Hearby can control
is when each phone *emits* the sound, and that is what the sound check aligns: in the simulation
below, a phone whose speaker is secretly 8 ms late and a Bluetooth speaker that is 148 ms late both
end up within 0.3 ms of the DJ. For the check itself, hold the phones together, near the DJ phone.

## YouTube mode

Every phone plays the video itself, so YouTube mode needs internet on every phone, but almost no
Wi-Fi bandwidth between them: the DJ only sends a small VIDEO message ("video X, at position P, at
DJ time T, playing or paused", see `docs/PROTOCOL.md`).

How each phone stays in time with the others:

1. **One clock.** The clock sync Hearby already runs for music tells each phone the DJ's time to
   within a fraction of a millisecond, and the DJ announces every start and resume a moment ahead
   (2.5 s for a new video, 0.8 s for a resume). Each phone loads the video and waits, paused, on the
   right frame.
2. **Pressing play early enough.** A YouTube player takes a moment to start. Each phone presses
   play that much early, and learns its own player's delay from how its previous starts landed.
3. **Steering while it plays.** Five times a second each phone compares its player's position with
   where the shared timeline says it should be. Big jumps (joining late, after an ad, after a stall)
   are fixed with a seek that aims ahead by a learned amount. Smaller drift is pulled in with a
   precisely timed speed change (for example 0.75× for 80 ms to drop back 20 ms), the same way a
   DJ nudges a record. The player keeps the pitch.
4. **Per-phone corrections.** The fine-tune slider and the sound check results apply to YouTube too,
   so a phone with Bluetooth earbuds is lined up the same way as for music.

Everything goes through YouTube's IFrame Player API (load, play, pause, seek, speed). Hearby never
downloads, records, or re-streams the video, never hides or covers the player, and never plays it
in the background: the video plays only while the app is on screen with the player visible, as
YouTube's terms require. Ads play as YouTube decides; a phone that gets an ad catches up after it.

In simulations with realistic players (start delays of 150 to 650 ms that vary every time, seeks
that land on a keyframe until that part of the video has loaded, clocks that drift by up to 55 ppm,
a noisy position reading, ads), across 40 randomised runs, phones stay within about 14 ms of the
shared timeline while playing with YouTube's standard speed steps (0.25×, 0.5× … 2×, which is what
the embedded player usually offers), and within about 6 ms if a player offers finer steps (Hearby
uses them when it does). A resume is back in time within a few seconds. That is looser than music
files, which play within about a millisecond after a sound check, and none of it has been measured
on real phones yet.

## Building

### Android

Requirements: JDK 17 or newer, Android SDK platform 36. Open `android/` in Android Studio, or:

```bash
cd android
./gradlew testDebugUnitTest      # protocol vectors, clock sync, resampler, playout simulations
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

Release builds are signed from `android/keystore.properties`, which is git-ignored:

```properties
storeFile=/path/to/upload-keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

```bash
./gradlew bundleRelease          # app/build/outputs/bundle/release/app-release.aab  (upload this to Play)
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk     (sideloading)
```

### iOS

Requirements: Xcode 15 or newer.

1. Open `ios/Hearby.xcodeproj`.
2. Select the Hearby target › Signing & Capabilities, choose your team, and change the bundle
   identifier if you like.
3. Run on a real iPhone. The simulator works for the UI, but audio timing there is meaningless.

The sync core can be tested without a device: `cd ios && swift test`.

### Debugging with a laptop

`tools/hearby_probe.py` needs only Python 3 and speaks the same protocol:

```bash
python3 tools/hearby_probe.py listen 192.168.43.1   # join a host, print packet and clock statistics
python3 tools/hearby_probe.py host                  # be a host that streams Opus silence
python3 tools/hearby_probe.py discover              # find hosts by broadcast
```

Opus decoding uses AudioToolbox's built-in `kAudioFormatOpus` through `AVAudioConverter`, so there
are no package dependencies.

## Using it

1. **DJ:** open Hearby › *Be the DJ* › *Start the party*, then *Add songs* and pick songs saved on
   the phone. Play, pause and skip from the DJ screen.
2. **Guests:** join the same Wi-Fi as the DJ, or the DJ's hotspot. Open Hearby › *Join a party* and
   tap the DJ. If it does not appear, type the address shown on the DJ's screen.
3. **Sound check** (DJ screen, once everyone shows *In sync*): gather the phones near the DJ phone,
   turn their media volume up, and tap *Run sound check*. Allow the microphone when asked. Music
   pauses for a few seconds while each phone beeps; then every phone shows how far off it was.
   Run it again whenever someone switches to Bluetooth.

The DJ phone plays along in sync, so there is no echo between it and the others.

**YouTube:** on the DJ screen, paste a link into *Play from YouTube* and tap *Play*, or in the
YouTube app tap *Share › Hearby*. Every phone with internet loads the video and they start together.
The DJ can pause, resume, or go *Back to music*. Keep Hearby open on screen while the video plays.

## Limitations and edge cases

**Music**

- The DJ plays songs saved on the phone, picked with Android's file picker (no storage permission
  needed). Anything the phone can decode works: MP3, AAC/M4A, FLAC, Ogg/Opus, WAV.
- Streaming apps (Spotify, YouTube Music, Apple Music) are not sources: their songs are protected
  and only their own apps can play them. That is also why Hearby does not need screen capture.
- Picking songs is per party; the queue is not saved yet.

**YouTube mode**

- Every phone needs internet. A phone that cannot reach YouTube says so; the others carry on.
- Some videos cannot play in other apps (their owner turned off embedding, or they are age-
  restricted or private); every phone then shows why, and the DJ picks another one.
- Playback runs only while Hearby is on screen with the player visible. Switching apps or locking
  the phone pauses that phone; it rejoins the timeline when it comes back.
- Ads are YouTube's: a phone that gets one is late until the ad ends, then catches up.
- Sync is looser than with music files (see [YouTube mode](#youtube-mode)), and it depends on each
  player reporting its position accurately, which has not been measured on real phones yet.
- iPhones follow along as guests; the DJ is an Android phone.

**Sync accuracy**

- Without a sound check, the remaining error is each phone's own output-latency reporting, typically
  a few milliseconds and up to hundreds for Bluetooth. The sound check measures and removes it.
- The sound check needs a quiet-ish moment (it pauses the music for a few seconds), phones near the
  DJ phone, and audible volume. A phone it cannot hear is reported, and keeps its old setting.
- The microphone is used only during the sound check, only on the DJ phone, and only while the app
  is on screen. Nothing is recorded to storage or sent anywhere.
- A phone whose total latency exceeds the playout delay plays late by the shown amount. Pick the
  *Bluetooth* (700 ms) cushion when guests use Bluetooth speakers. AirPlay adds about two seconds
  and will not fit any cushion.

**Network**

- Client isolation ("AP isolation", common on guest and public Wi-Fi) blocks device-to-device
  traffic entirely. Use the host's hotspot instead.
- A hotspot is usually not the listener's default network (mobile data stays the default). The
  Android listener binds its sockets to the Wi-Fi network explicitly for that reason.
- No encryption or authentication in protocol v1: anyone on the same network can join.
- Each listener costs roughly 85 to 150 kbit/s depending on quality. A phone hotspot is comfortable
  with about 8 to 10 listeners.

**iOS**

- Guest only for now. An iPhone could be a DJ for its own song files in a future version; songs
  bought through Apple Music are protected and cannot be shared by any app.
- **Local Network permission.** iOS shows a one-time prompt the first time the app browses for hosts
  (text from `NSLocalNetworkUsageDescription`). If the user declines, nothing is found and the app
  says so; it is re-enabled under Settings › Privacy & Security › Local Network. The Bonjour type
  must also be listed in `NSBonjourServices`, which it is.
- Broadcast discovery is not used on iOS (it needs Apple's multicast entitlement); iOS relies on
  Bonjour plus manual address entry.
- Background playback needs the `audio` background mode, which is set. With the screen locked iOS
  may switch to much larger audio buffers; the listener adapts its queue depth, which can require a
  larger playout delay.

## Publishing on Google Play

Play accepts new apps as an **App Bundle** (`.aab`), not an APK. Besides uploading the bundle:

- `applicationId` is `io.github.ugoyel.hearby`. It can never be changed after the first upload, so
  change it now in `android/app/build.gradle.kts` if you want a different one.
- Enrol in Play App Signing and keep the upload keystore and its passwords somewhere safe.
- Hearby uses no screen capture. Its only foreground service type is `mediaPlayback` (playing the
  party's music in the background), which you declare in Play Console (App content › Foreground
  service permissions).
- `RECORD_AUDIO` (for the optional sound check) requires a privacy policy URL and a Data safety
  form. Hearby collects nothing; the microphone is used on the DJ phone for a few seconds to time
  beeps, in memory only.
- Only share songs you have the rights to play to your guests.
- Target API level is 36.

## Verification status

| Area | How it was checked | Result |
|---|---|---|
| Wire format, Android ↔ iOS | The same hex vectors asserted by Kotlin and Swift unit tests; a third implementation in Python (`tools/hearby_probe.py`) interoperates with the Android app | Pass |
| Clock sync, jitter buffer, resampler | Unit tests on both platforms | Pass |
| Playout engine | Simulations with clock drift, DAC drift, jitter, 3 % loss, delay spikes, host pauses, slider changes and over-budget latency, in Kotlin and in Swift | Sync error stays under 1.5 ms |
| Sound check: detector | Chirps placed at known fractional positions in a simulated room (reflections, reverb tail, crowd noise, a loud bass line, a clipped microphone) | Found within 0.1 ms |
| Sound check: end to end | Three simulated phones running the real engines and clock sync: the DJ, a phone whose speaker is secretly 8 ms late, and a Bluetooth speaker 150 ms late, plus a microphone with its own 17 ms latency | Before: 6.4 ms and 148 ms off. After: within 0.3 ms, and stays there |
| Same chirp on both platforms | Kotlin and Swift tests check the same reference samples | Pass |
| Android build | Unit tests, lint, signed and 16 KB-aligned release build; also built on a clean GitHub runner | Pass |
| iOS build | `swift test` and an `xcodebuild` simulator build on a macOS runner | Pass |
| Screen design | Every Android screen rendered on the JVM with sample data; every iOS screen screenshotted in the iOS Simulator through the demo mode | Reviewed by eye |
| YouTube sync (controller) | Simulated YouTube players on both platforms, Kotlin and Swift: start, pause and resume together, a late joiner, an ad on one phone, fine and coarse speed steps, and 40 randomised runs | Within 14 ms with YouTube's standard speed steps, 6.5 ms with fine steps, 6.3 ms a few seconds after a resume |
| YouTube links | Watch, share (`youtu.be`), Shorts, live, embed, YouTube Music and mobile links, with or without surrounding text, on both platforms | Pass |
| DJ on an Android 14 emulator | Release APK 1.2.0: party starts as a plain `mediaPlayback` service (no screen-capture prompt); a 44.1 kHz MP3 opened with Hearby was decoded, resampled and played, with its title and artist; a Python guest received the Opus stream with no loss plus "now playing", pause and resume; the sound check sent the guest its chirp request and reported that it could not hear itself (the emulator has no speaker-to-microphone path) | Pass |

**Not verified yet, and needed before a store release:**

- Anything on physical phones. The emulator had no hardware acceleration and was badly overloaded
  (the DJ's sending thread got a fraction of the CPU it needs), so it shows that the pipelines work,
  not how they perform or how tightly phones sync.
- **The sound check with real speakers and microphones.** The method is proven in simulation, but
  real phone microphones, speakers, rooms and Bluetooth stacks have not been tried.
- Real Wi-Fi and hotspot behaviour, including discovery across devices.
- The iOS app at runtime. It compiles and its sync core passes the same tests as Android, but the
  AudioToolbox Opus decoder and AVAudioEngine timing have not been exercised on a device.
- **YouTube mode with real YouTube.** YouTube was not reachable from the build machine, so the
  embedded player itself (its start and seek delays, how accurately it reports its position, which
  speeds it offers) has only been simulated. Try it on two or three real phones first.

`.github/workflows/hearby.yml` runs the Android and iOS builds and tests on every change under
`hearby/`.
