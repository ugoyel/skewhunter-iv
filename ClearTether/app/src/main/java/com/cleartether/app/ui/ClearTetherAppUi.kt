package com.cleartether.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleartether.app.R
import com.cleartether.app.data.AppMode
import com.cleartether.app.data.ClientStatus
import com.cleartether.app.data.HostStatus
import com.cleartether.app.ui.client.ClientActions
import com.cleartether.app.ui.client.ClientScreen
import com.cleartether.app.ui.help.HelpScreen
import com.cleartether.app.ui.help.HelpTab
import com.cleartether.app.ui.host.HostActions
import com.cleartether.app.ui.host.HostScreen
import com.cleartether.app.ui.settings.SettingsActions
import com.cleartether.app.ui.settings.SettingsScreen

/** Things only the Activity can do (permissions, system screens, clipboard). */
interface PlatformActions {
    val nfcAvailable: Boolean
    fun requestHostStart()
    fun requestClientStart()
    fun scanQr()
    fun copy(label: String, value: String)
    fun openWifiSettings()
    fun openAppSettings()
    fun openBatterySettings()
}

private enum class Screen { HOME, SETTINGS, HELP }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClearTetherAppUi(viewModel: MainViewModel, platform: PlatformActions) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var helpTab by rememberSaveable { mutableStateOf(HelpTab.CONNECT) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val openHelp: (HelpTab) -> Unit = { helpTab = it; screen = Screen.HELP }

    LaunchedEffect(Unit) {
        viewModel.events.collect { if (it is UiEvent.Message) snackbar.showSnackbar(it.text) }
    }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(
                            when (screen) {
                                Screen.HOME -> R.string.app_name
                                Screen.SETTINGS -> R.string.settings_title
                                Screen.HELP -> R.string.help_title
                            },
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    if (screen != Screen.HOME) {
                        IconButton(onClick = { screen = Screen.HOME }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back))
                        }
                    }
                },
                actions = {
                    if (screen == Screen.HOME) {
                        IconButton(onClick = { openHelp(HelpTab.CONNECT) }) {
                            Icon(Icons.AutoMirrored.Rounded.HelpOutline, stringResource(R.string.help_title))
                        }
                        IconButton(onClick = { screen = Screen.SETTINGS }) {
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
                when (screen) {
                    Screen.SETTINGS -> SettingsScreen(
                        state = state,
                        hostRunning = state.host !is HostStatus.Idle && state.host !is HostStatus.Error,
                        actions = SettingsActions(
                            onBand = viewModel::setBand,
                            onNfc = viewModel::setNfcSharing,
                            onCaptive = viewModel::setCaptiveAssist,
                            onSuffix = viewModel::setNetworkSuffix,
                            onPassphrase = viewModel::setPassphrase,
                            onRegenerate = { viewModel.regeneratePassphrase() },
                            onPort = viewModel::setPort,
                            onBatterySettings = platform::openBatterySettings,
                            onHelp = openHelp,
                            onPrivacy = { showPrivacy = true },
                        ),
                    )
                    Screen.HELP -> state.endpoint?.let { HelpScreen(it, helpTab, onCopy = platform::copy) }
                    Screen.HOME -> {
                        ModeSwitch(state.mode, enabled = state.host is HostStatus.Idle || state.host is HostStatus.Error, onMode = { viewModel.setMode(it) })
                        Spacer(Modifier.height(8.dp))
                        when (state.mode) {
                            AppMode.HOST -> HostScreen(
                                state = state,
                                nfcAvailable = platform.nfcAvailable,
                                actions = HostActions(
                                    onToggle = {
                                        when (state.host) {
                                            is HostStatus.Running, is HostStatus.Starting -> viewModel.stopHost()
                                            else -> platform.requestHostStart()
                                        }
                                    },
                                    onCopy = platform::copy,
                                    onOpenHelp = openHelp,
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
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
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
