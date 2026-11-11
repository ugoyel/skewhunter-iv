package com.cleartether.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleartether.app.billing.BillingState
import com.cleartether.app.billing.PlanPeriod
import com.cleartether.app.billing.PremiumPlan
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
import com.cleartether.app.ui.guide.SetupGuideSheet
import com.cleartether.app.ui.host.HostActions
import com.cleartether.app.ui.host.HostScreen
import com.cleartether.app.ui.paywall.PaywallDialog
import com.cleartether.app.ui.theme.Brand
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

/** Phone-sized (1080x2160) renders used to build the Google Play store screenshots. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w360dp-h720dp-xxhdpi")
class StoreScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val dir = (System.getProperty("ct.screenshot.dir") ?: "build/screenshots") + "/../play-store/raw"
    private val mb = 1024L * 1024
    private val endpoint = TetherEndpoint("DIRECT-CT-ClearTether-4F2A", "k7Qm-x9Tp-R2vw", "192.168.49.1", 8228)
    private val hostSettings = HostSettings("ClearTether-4F2A", "k7Qm-x9Tp-R2vw")
    private val noopHost = HostActions({}, {}, { _, _ -> }, {}, {}, {})
    private val plans = BillingState(
        loaded = true,
        plans = listOf(
            PremiumPlan(PlanPeriod.MONTH, "$2.99", 2_990_000, "USD"),
            PremiumPlan(PlanPeriod.YEAR, "$14.99", 14_990_000, "USD", freeTrialDays = 3),
        ),
    )

    @Before
    fun gate() = assumeTrue("set -Pscreenshots to render", System.getProperty("ct.screenshots") == "true")

    @Composable
    private fun AppFrame(mode: AppMode, premium: Boolean, content: @Composable () -> Unit) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("ClearTether", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (!premium) Icon(Icons.Rounded.WorkspacePremium, null, tint = Brand.GoldDeep, modifier = Modifier.padding(end = 20.dp))
                    Icon(Icons.Rounded.Settings, null)
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(AppMode.HOST to "Share internet", AppMode.CLIENT to "Use internet").forEachIndexed { i, (m, label) ->
                        SegmentedButton(selected = mode == m, onClick = {}, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }

    private fun render(name: String, dark: Boolean, mode: AppMode = AppMode.HOST, premium: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { ClearTetherTheme(darkTheme = dark) { AppFrame(mode, premium, content) } }
        compose.onRoot().captureRoboImage("$dir/$name.png")
    }

    private val sharing = MainUiState(
        host = HostStatus.Running(HostNetwork(endpoint, 5180, "p2p-wlan0-0", 3)),
        hostSettings = hostSettings,
        session = SessionState(
            active = true, elapsedMs = 6 * 60_000, bytesUploaded = 9 * mb, bytesDownloaded = 47 * mb,
            remainingMs = 24 * 60_000, remainingBytes = Long.MAX_VALUE, timeLimitMs = 30 * 60_000,
        ),
        devices = listOf(
            ClientStats("192.168.49.23", 6 * mb, 38 * mb, 14, 0, 0),
            ClientStats("192.168.49.57", 2 * mb, 8 * mb, 4, 0, 0),
        ),
        billing = plans,
    )

    @Test
    fun s1Sharing() = render("1-sharing", dark = true) { HostScreen(sharing, 0, true, noopHost) }

    @Test
    fun s2Idle() = render("2-idle", dark = false) {
        HostScreen(
            MainUiState(hostSettings = hostSettings, billing = plans, session = SessionState(remainingMs = 30 * 60_000, timeLimitMs = 30 * 60_000)),
            0, true, noopHost,
        )
    }

    @Test
    fun s3Client() = render("3-client", dark = true, mode = AppMode.CLIENT) {
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
    fun s4Premium() {
        compose.setContent {
            ClearTetherTheme(darkTheme = false) {
                AppFrame(AppMode.HOST, false) { HostScreen(sharing, 0, true, noopHost) }
                PaywallDialog(PaywallReason.UPGRADE, plans, FreeTierPolicy(), null, {}, {}, {})
            }
        }
        captureScreenRoboImage("$dir/4-premium.png")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun s5Guide() {
        compose.setContent {
            ClearTetherTheme(darkTheme = false) {
                AppFrame(AppMode.HOST, false) { HostScreen(sharing, 0, true, noopHost) }
                SetupGuideSheet(endpoint, onCopy = { _, _ -> }, onDismiss = {})
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("$dir/5-guide.png")
    }
}
