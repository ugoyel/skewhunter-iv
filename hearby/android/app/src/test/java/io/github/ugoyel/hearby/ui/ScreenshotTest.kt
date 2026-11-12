package io.github.ugoyel.hearby.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ugoyel.hearby.client.DiscoveredHost
import io.github.ugoyel.hearby.client.Phase
import io.github.ugoyel.hearby.client.ReceiverUiState
import io.github.ugoyel.hearby.core.Proto
import io.github.ugoyel.hearby.host.CheckResult
import io.github.ugoyel.hearby.host.HostUiState
import io.github.ugoyel.hearby.host.ListenerInfo
import io.github.ugoyel.hearby.host.SongInfo
import io.github.ugoyel.hearby.host.SoundCheckUi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every screen with sample data to app/build/outputs/roborazzi/, on the JVM, no emulator
 * needed. Run `./gradlew testDebugUnitTest --tests '*ScreenshotTest*'` and open the PNGs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h960dp-420dpi")
class ScreenshotTest {

    private fun shot(name: String, tab: Int, energy: Float, overlay: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
        captureRoboImage("build/outputs/roborazzi/$name.png") {
            // Looping animations are frozen: a renderer waiting for "idle" would otherwise wait forever.
            CompositionLocalProvider(LocalMotion provides false) {
                HearbyTheme { PartyScaffold(tab, {}, energy, overlay, content) }
            }
        }
    }

    private val crowd = listOf(
        ListenerInfo(1, "Aarav's iPhone", Proto.PLATFORM_IOS, "192.168.43.21", 2.4f, 190, Proto.STATUS_SYNCED, CheckResult(true, 6.4f, 0.3f)),
        ListenerInfo(2, "Pixel 8", Proto.PLATFORM_ANDROID, "192.168.43.34", 3.1f, 176, Proto.STATUS_SYNCED, CheckResult(true, 148.0f, -0.2f)),
        ListenerInfo(3, "Zoya", Proto.PLATFORM_ANDROID, "192.168.43.52", 6.8f, 60, Proto.STATUS_BUFFERING),
    )

    private val songs = listOf(
        SongInfo("Midnight City", "M83", 243_000),
        SongInfo("Kesariya", "Arijit Singh", 268_000),
        SongInfo("Blinding Lights", "The Weeknd", 200_000),
    )

    @Test
    fun djIdle() = shot("1_dj_idle", tab = 0, energy = 0f) {
        HostIdle(96_000, {}, 350, {}, notice = null, busyListening = false, onStart = {})
    }

    @Test
    fun djLive() = shot("2_dj_live", tab = 0, energy = 1f, overlay = { Confetti(progress = 0.42f, seed = 7) }) {
        HostLive(
            HostUiState(
                running = true, addresses = listOf("192.168.43.1"), listeners = crowd, level = 0.55f, bitrate = 96_000, playoutDelayMs = 350,
                queue = songs, current = 0, playing = true, positionMs = 83_000,
                soundCheck = SoundCheckUi(done = true, message = "Every phone lined up to within 0.3 ms."),
            ),
            DjActions(),
        )
    }

    /** The whole DJ screen in one tall image (the real one scrolls), with sound-check results. */
    @Test
    @Config(qualifiers = "w411dp-h2400dp-420dpi")
    fun djLiveFull() = shot("8_dj_live_full", tab = 0, energy = 1f) {
        HostLive(
            HostUiState(
                running = true, addresses = listOf("192.168.43.1"), listeners = crowd, level = 0.55f, bitrate = 96_000, playoutDelayMs = 350,
                queue = songs, current = 0, playing = true, positionMs = 83_000,
                soundCheck = SoundCheckUi(done = true, message = "Every phone lined up to within 0.3 ms."),
            ),
            DjActions(),
        )
    }

    @Test
    fun djWaiting() = shot("3_dj_no_songs", tab = 0, energy = 1f) {
        HostLive(HostUiState(running = true, addresses = listOf("192.168.43.1")), DjActions())
    }

    @Test
    fun djSoundCheck() = shot("3b_dj_sound_check", tab = 0, energy = 1f) {
        HostLive(
            HostUiState(
                running = true, addresses = listOf("192.168.43.1"), listeners = crowd.map { it.copy(check = null) }, level = 0f,
                queue = songs, current = 1, playing = true, positionMs = 12_000,
                soundCheck = SoundCheckUi(running = true, progress = 0.5f, message = "Listening to every phone…"),
            ),
            DjActions(),
        )
    }

    @Test
    fun join() = shot("4_join", tab = 1, energy = 0f) {
        JoinIdle(
            listOf(DiscoveredHost("Mia's Pixel", "192.168.43.1", Proto.PORT, 3), DiscoveredHost("Kabir", "192.168.1.20", Proto.PORT, 0)),
            busyHosting = false,
        ) { _, _, _ -> }
    }

    @Test
    fun inSync() = shot("5_in_sync", tab = 1, energy = 1f) {
        Listening(
            ReceiverUiState(
                phase = Phase.PLAYING, hostName = "Mia's Pixel", syncErrorMs = 0.3f, rttMs = 2.1f, bufferMs = 184,
                playoutDelayMs = 350, offsetMs = 0, outputName = "Bluetooth: Airdopes 141", outputIsBluetooth = true, level = 0.7f,
                songTitle = "Midnight City", songArtist = "M83", songPlaying = true, soundChecked = true, soundCheckMs = -142f,
            ),
            onOffset = {}, onResync = {}, onLeave = {}, onUnmute = {},
        )
    }

    @Test
    fun findingTheBeat() = shot("6_finding_the_beat", tab = 1, energy = 0f) {
        Listening(
            ReceiverUiState(phase = Phase.SYNCING, hostName = "Mia's Pixel", playoutDelayMs = 350, outputName = "Phone speaker"),
            onOffset = {}, onResync = {}, onLeave = {}, onUnmute = {},
        )
    }

    @Test
    fun partyOver() = shot("7_party_over", tab = 1, energy = 0f) {
        PartyOver("The DJ ended the party.") {}
    }
}
