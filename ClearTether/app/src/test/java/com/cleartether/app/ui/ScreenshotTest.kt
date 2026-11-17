package com.cleartether.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleartether.app.core.proxy.ClientStats
import com.cleartether.app.core.proxy.TetherEndpoint
import com.cleartether.app.core.session.SessionState
import com.cleartether.app.data.AppMode
import com.cleartether.app.data.ClientSettings
import com.cleartether.app.data.ClientStatus
import com.cleartether.app.data.ClientTraffic
import com.cleartether.app.data.HostNetwork
import com.cleartether.app.data.HostSettings
import com.cleartether.app.data.HostStatus
import com.cleartether.app.ui.client.ClientActions
import com.cleartether.app.ui.client.ClientScreen
import com.cleartether.app.ui.help.HelpScreen
import com.cleartether.app.ui.help.HelpTab
import com.cleartether.app.ui.host.HostActions
import com.cleartether.app.ui.host.HostScreen
import com.cleartether.app.ui.theme.ClearTetherTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the main screens to PNG (docs/screenshots) for design review and the README. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w400dp-h1500dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val dir = System.getProperty("ct.screenshot.dir") ?: "build/screenshots"
    private val mb = 1024L * 1024
    private val endpoint = TetherEndpoint("DIRECT-CT-ClearTether-4F2A", "k7Qm-x9Tp-R2vw", "192.168.49.1", 8228)
    private val hostSettings = HostSettings("ClearTether-4F2A", "k7Qm-x9Tp-R2vw")
    private val noopHost = HostActions({}, { _, _ -> }, {}, {}, {})

    @Before
    fun gate() = assumeTrue("set -Pscreenshots to render", System.getProperty("ct.screenshots") == "true")

    private fun render(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            ClearTetherTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage("$dir/$name.png")
    }

    @Test
    fun hostIdle() = render("01-host-idle", dark = false) {
        HostScreen(MainUiState(hostSettings = hostSettings), true, noopHost)
    }

    @Test
    fun hostSharing() = render("02-host-sharing", dark = true) {
        HostScreen(
            MainUiState(
                host = HostStatus.Running(HostNetwork(endpoint, 5180, "p2p-wlan0-0", 3)),
                hostSettings = hostSettings,
                session = SessionState(active = true, elapsedMs = 2 * 3_600_000L + 14 * 60_000, bytesUploaded = 210 * mb, bytesDownloaded = 1_900 * mb),
                devices = listOf(
                    ClientStats("192.168.49.23", 160 * mb, 1_500 * mb, 14, 0, 0),
                    ClientStats("192.168.49.57", 40 * mb, 300 * mb, 4, 0, 0),
                    ClientStats("192.168.49.104", 10 * mb, 100 * mb, 0, 0, 0),
                ),
            ),
            true, noopHost,
        )
    }

    @Test
    fun clientConnected() = render("03-client-connected", dark = true) {
        ClientScreen(
            MainUiState(
                mode = AppMode.CLIENT,
                client = ClientStatus.Connected("DIRECT-CT-ClearTether-4F2A", "192.168.49.1:8228", true, true, 0),
                clientSettings = ClientSettings("DIRECT-CT-ClearTether-4F2A", "k7Qm-x9Tp-R2vw"),
                clientTraffic = ClientTraffic(uploaded = 12 * mb, downloaded = 318 * mb, tcpConnections = 37, udpFlows = 4),
            ),
            true, ClientActions({}, {}, {}),
        )
    }

    @Test
    fun helpConnect() = render("04-help-connect", dark = false) { HelpScreen(endpoint, HelpTab.CONNECT) { _, _ -> } }

    @Test
    fun helpWhy() = render("05-help-why", dark = false) { HelpScreen(endpoint, HelpTab.WHY) { _, _ -> } }

    @Test
    fun helpFaq() = render("06-help-faq", dark = true) { HelpScreen(endpoint, HelpTab.FAQ) { _, _ -> } }
}
