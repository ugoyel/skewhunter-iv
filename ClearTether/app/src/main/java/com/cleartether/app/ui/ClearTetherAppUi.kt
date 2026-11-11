package com.cleartether.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleartether.app.R
import com.cleartether.app.billing.PremiumPlan
import com.cleartether.app.data.AppMode
import com.cleartether.app.data.ClientStatus
import com.cleartether.app.data.HostStatus
import com.cleartether.app.ui.client.ClientActions
import com.cleartether.app.ui.client.ClientScreen
import com.cleartether.app.ui.guide.SetupGuideSheet
import com.cleartether.app.ui.host.HostActions
import com.cleartether.app.ui.host.HostScreen
import com.cleartether.app.ui.paywall.PaywallDialog
import com.cleartether.app.ui.settings.SettingsActions
import com.cleartether.app.ui.settings.SettingsScreen
import com.cleartether.app.ui.theme.Brand
import kotlinx.coroutines.delay

/** Things only the Activity can do (permissions, system screens, Play Billing, clipboard). */
interface PlatformActions {
    val nfcAvailable: Boolean
    fun requestHostStart()
    fun requestClientStart()
    fun scanQr()
    fun purchase(plan: PremiumPlan)
    fun openManageSubscription()
    fun copy(label: String, value: String)
    fun openWifiSettings()
    fun openAppSettings()
    fun openBatterySettings()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClearTetherAppUi(viewModel: MainViewModel, platform: PlatformActions, debugPremium: Boolean) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val paywall by viewModel.paywall.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showGuide by rememberSaveable { mutableStateOf(false) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    LaunchedEffect(Unit) {
        viewModel.events.collect { if (it is UiEvent.Message) snackbar.showSnackbar(it.text) }
    }
    BackHandler(enabled = showSettings) { showSettings = false }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(if (showSettings) R.string.settings_title else R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    if (showSettings) {
                        IconButton(onClick = { showSettings = false }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back))
                        }
                    } else if (state.premium) {
                        PremiumBadge(Modifier.padding(start = 12.dp))
                    }
                },
                actions = {
                    if (!showSettings) {
                        if (!state.premium) {
                            IconButton(onClick = { viewModel.showPaywall() }) {
                                Icon(Icons.Rounded.WorkspacePremium, stringResource(R.string.action_upgrade), tint = Brand.GoldDeep)
                            }
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Rounded.Settings, stringResource(R.string.settings_title))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (showSettings) {
                    SettingsScreen(
                        state = state,
                        hostRunning = state.host !is HostStatus.Idle && state.host !is HostStatus.Error,
                        debugPremium = debugPremium,
                        actions = SettingsActions(
                            onBand = viewModel::setBand,
                            onNfc = viewModel::setNfcSharing,
                            onCaptive = viewModel::setCaptiveAssist,
                            onSuffix = viewModel::setNetworkSuffix,
                            onPassphrase = viewModel::setPassphrase,
                            onRegenerate = { viewModel.regeneratePassphrase() },
                            onPort = viewModel::setPort,
                            onBatterySettings = platform::openBatterySettings,
                            onUpgrade = { viewModel.showPaywall() },
                            onRestore = { viewModel.restorePurchases() },
                            onManageSubscription = platform::openManageSubscription,
                            onPrivacy = { showPrivacy = true },
                            onDebugPremium = viewModel::setDebugPremium,
                        ),
                    )
                } else {
                    ModeSwitch(state.mode, enabled = state.host is HostStatus.Idle || state.host is HostStatus.Error, onMode = { viewModel.setMode(it) })
                    Spacer(Modifier.height(8.dp))
                    when (state.mode) {
                        AppMode.HOST -> HostScreen(
                            state = state,
                            nowWallMs = now,
                            nfcAvailable = platform.nfcAvailable,
                            actions = HostActions(
                                onToggle = {
                                    when (state.host) {
                                        is HostStatus.Running -> viewModel.stopHost()
                                        is HostStatus.Starting -> viewModel.stopHost()
                                        else -> platform.requestHostStart()
                                    }
                                },
                                onUpgrade = { viewModel.showPaywall() },
                                onCopy = platform::copy,
                                onOpenGuide = { showGuide = true },
                                onOpenWifiSettings = platform::openWifiSettings,
                                onOpenAppSettings = platform::openAppSettings,
                            ),
                        )
                        AppMode.CLIENT -> ClientScreen(
                            state = state,
                            nfcAvailable = platform.nfcAvailable,
                            actions = ClientActions(
                                onToggle = {
                                    when (state.client) {
                                        is ClientStatus.Connected, is ClientStatus.Connecting -> viewModel.stopClient()
                                        else -> platform.requestClientStart()
                                    }
                                },
                                onScanQr = platform::scanQr,
                                onSaveSettings = viewModel::saveClientSettings,
                            ),
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    paywall?.let { reason ->
        val next = state.session.nextFreeSessionAtWallMs
        PaywallDialog(
            reason = reason,
            billing = state.billing,
            freeTier = state.freeTier,
            cooldownRemainingMs = next?.let { (it - now).coerceAtLeast(0) },
            onPurchase = platform::purchase,
            onRestore = { viewModel.restorePurchases() },
            onDismiss = { viewModel.dismissPaywall(); viewModel.clearBillingMessage() },
        )
    }
    val running = state.host as? HostStatus.Running
    if (showGuide && running != null) {
        SetupGuideSheet(running.network.endpoint, onCopy = platform::copy, onDismiss = { showGuide = false })
    }
    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text(stringResource(R.string.settings_privacy)) },
            text = { Text(stringResource(R.string.privacy_body)) },
            confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@Composable
private fun ModeSwitch(mode: AppMode, enabled: Boolean, onMode: (AppMode) -> Unit) {
    val options = listOf(AppMode.HOST to R.string.mode_host, AppMode.CLIENT to R.string.mode_client)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onMode(value) },
                enabled = enabled || mode == value,
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(stringResource(label)) }
        }
    }
}

@Composable
private fun PremiumBadge(modifier: Modifier = Modifier) {
    Row(
        modifier.background(Brand.premiumGradient, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.WorkspacePremium, null, tint = Color(0xFF2C1A00), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.badge_premium), style = MaterialTheme.typography.labelMedium, color = Color(0xFF2C1A00))
    }
}
