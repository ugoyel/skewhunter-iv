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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleartether.app.billing.BillingState
import com.cleartether.app.core.proxy.ClientStats
import com.cleartether.app.core.proxy.TetherEndpoint
import com.cleartether.app.core.session.FreeTierPolicy
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
import com.cleartether.app.ui.host.HostActions
import com.cleartether.app.ui.host.HostScreen
import com.cleartether.app.ui.paywall.PaywallDialog
import com.cleartether.app.ui.theme.ClearTetherTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the main screens to PNG (docs/screenshots) for design review and the store listing. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w400dp-h1500dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val dir = System.getProperty("ct.screenshot.dir") ?: "build/screenshots"
    private val mb = 1024L * 1024
    private val endpoint = TetherEndpoint("DIRECT-CT-ClearTether-4F2A", "k7Qm-x9Tp-R2vw", "192.168.49.1", 8228)
    private val hostSettings = HostSettings("ClearTether-4F2A", "k7Qm-x9Tp-R2vw")
    private val noopHost = HostActions({}, {}, { _, _ -> }, {}, {}, {})

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
        HostScreen(MainUiState(hostSettings = hostSettings, session = freeIdle(), billing = BillingState(formattedPrice = "$9.99")), 0, true, noopHost)
    }

    @Test
    fun hostRunningFree() = render("02-host-sharing-free", dark = true) {
        HostScreen(
            MainUiState(
                host = HostStatus.Running(HostNetwork(endpoint, 5180, "p2p-wlan0-0", 3)),
                hostSettings = hostSettings,
                session = SessionState(
                    active = true, elapsedMs = 6 * 60_000, bytesUploaded = 9 * mb, bytesDownloaded = 47 * mb,
                    remainingMs = 9 * 60_000 + 12_000, remainingBytes = 44 * mb,
                    timeLimitMs = 15 * 60_000, dataLimitBytes = 100 * mb,
                ),
                devices = listOf(
                    ClientStats("192.168.49.23", 6 * mb, 38 * mb, 14, 0, 0),
                    ClientStats("192.168.49.57", 2 * mb, 8 * mb, 4, 0, 0),
                    ClientStats("192.168.49.104", 1 * mb, 1 * mb, 0, 0, 0),
                ),
                billing = BillingState(formattedPrice = "$9.99"),
            ),
            0, true, noopHost,
        )
    }

    @Test
    fun hostRunningPremium() = render("03-host-sharing-premium", dark = false) {
        HostScreen(
            MainUiState(
                host = HostStatus.Running(HostNetwork(endpoint, 2437, "p2p-wlan0-0", 2)),
                hostSettings = hostSettings, premium = true,
                session = SessionState(active = true, premium = true, elapsedMs = 2 * 3_600_000L + 14 * 60_000, bytesDownloaded = 1_900 * mb, bytesUploaded = 210 * mb),
                devices = listOf(ClientStats("192.168.49.12", 210 * mb, 1_900 * mb, 22, 0, 0)),
            ),
            0, true, noopHost,
        )
    }

    @Test
    fun clientConnected() = render("04-client-connected", dark = true) {
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

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun paywall() {
        compose.setContent {
            ClearTetherTheme(darkTheme = false) {
                PaywallDialog(PaywallReason.TIME_LIMIT, BillingState(formattedPrice = "$9.99"), FreeTierPolicy(), null, {}, {}, {})
            }
        }
        captureScreenRoboImage("$dir/05-paywall.png")
    }

    private fun freeIdle() = SessionState(remainingMs = 15 * 60_000, remainingBytes = 100 * mb, timeLimitMs = 15 * 60_000, dataLimitBytes = 100 * mb)
}
