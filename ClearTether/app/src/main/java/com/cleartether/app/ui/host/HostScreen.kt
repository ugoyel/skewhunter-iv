package com.cleartether.app.ui.host

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cleartether.app.R
import com.cleartether.app.core.share.WifiCredentials
import com.cleartether.app.core.share.WifiQr
import com.cleartether.app.data.HostErrorKind
import com.cleartether.app.data.HostStartStep
import com.cleartether.app.data.HostStatus
import com.cleartether.app.ui.MainUiState
import com.cleartether.app.ui.components.Banner
import com.cleartether.app.ui.components.InfoRow
import com.cleartether.app.ui.components.MeterRow
import com.cleartether.app.ui.components.PowerButton
import com.cleartether.app.ui.components.QrCode
import com.cleartether.app.ui.components.SectionCard
import com.cleartether.app.ui.components.StatusPill
import com.cleartether.app.ui.theme.Brand
import com.cleartether.app.util.Format
import com.cleartether.app.wifi.WifiDirectManager

data class HostActions(
    val onToggle: () -> Unit,
    val onUpgrade: () -> Unit,
    val onCopy: (label: String, value: String) -> Unit,
    val onOpenGuide: () -> Unit,
    val onOpenWifiSettings: () -> Unit,
    val onOpenAppSettings: () -> Unit,
)

@Composable
fun HostScreen(state: MainUiState, nowWallMs: Long, nfcAvailable: Boolean, actions: HostActions) {
    val host = state.host
    val running = host as? HostStatus.Running
    val busy = host is HostStatus.Starting || host is HostStatus.Stopping
    val session = state.session
    val remainingFraction = if (state.premium || running == null) null else
        (1f - maxOf(session.timeProgress, session.dataProgress)).coerceIn(0f, 1f)

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PowerButton(
            active = running != null,
            busy = busy,
            remaining = remainingFraction,
            label = stringResource(R.string.host_toggle_label),
            stateLabel = stringResource(if (running != null) R.string.toggle_state_on else R.string.toggle_state_off),
            onClick = actions.onToggle,
        )
        Spacer(Modifier.height(4.dp))
        Text(hostHeadline(host), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            hostSubline(state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp).padding(horizontal = 24.dp),
        )
        if (running != null) {
            Spacer(Modifier.height(12.dp))
            val clients = maxOf(running.network.wifiClients, state.devices.count { it.activeConnections > 0 })
            StatusPill(
                text = if (running.pausedFor != null) stringResource(R.string.pill_paused)
                else pluralStringResource(R.plurals.pill_devices, clients, WifiDirectManager.bandLabel(running.network.frequencyMhz), clients),
                color = if (running.pausedFor != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }

    Spacer(Modifier.height(20.dp))
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (host is HostStatus.Error) ErrorBanner(host, nowWallMs, actions)

        if (running != null) {
            Banner(
                visible = running.pausedFor != null,
                text = stringResource(R.string.banner_paused),
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Button(onClick = actions.onUpgrade) { Text(stringResource(R.string.action_upgrade)) }
            }
            JoinCard(running, nfcAvailable && state.hostSettings?.nfcSharing == true, actions)
            SessionCard(state, actions.onUpgrade)
            DevicesCard(state)
            SetupCard(running, actions)
        } else {
            if (!state.premium) FreeTierCard(state, nowWallMs, actions.onUpgrade)
            HowItWorksCard()
        }
    }
}

@Composable
private fun hostHeadline(host: HostStatus): String = when (host) {
    HostStatus.Idle -> stringResource(R.string.host_headline_idle)
    is HostStatus.Starting -> stringResource(
        when (host.step) {
            HostStartStep.PREPARING -> R.string.host_step_preparing
            HostStartStep.CREATING_GROUP -> R.string.host_step_group
            HostStartStep.STARTING_PROXY -> R.string.host_step_proxy
            HostStartStep.RECOVERING -> R.string.host_step_recovering
        },
    )
    is HostStatus.Running -> stringResource(if (host.pausedFor != null) R.string.host_headline_paused else R.string.host_headline_running)
    HostStatus.Stopping -> stringResource(R.string.host_headline_stopping)
    is HostStatus.Error -> stringResource(R.string.host_headline_error)
}

@Composable
private fun hostSubline(state: MainUiState): String = when (val host = state.host) {
    is HostStatus.Running -> stringResource(R.string.host_sub_running, host.network.endpoint.networkName)
    else -> stringResource(R.string.host_sub_idle)
}

@Composable
private fun JoinCard(running: HostStatus.Running, nfc: Boolean, actions: HostActions) {
    val endpoint = running.network.endpoint
    var tab by rememberSaveable { mutableIntStateOf(0) }
    SectionCard(title = stringResource(R.string.card_join_title), icon = Icons.Rounded.QrCode2) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val labels = listOf(R.string.tab_join_wifi, R.string.tab_setup_page)
            labels.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = tab == index,
                    onClick = { tab = index },
                    shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                ) { Text(stringResource(label)) }
            }
        }
        Spacer(Modifier.height(16.dp))
        val content = if (tab == 0) WifiQr.encode(WifiCredentials(endpoint.networkName, endpoint.passphrase)) else endpoint.baseUrl + "/"
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            QrCode(
                content = content,
                description = stringResource(if (tab == 0) R.string.cd_qr_wifi else R.string.cd_qr_setup),
                modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth(0.78f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(if (tab == 0) R.string.qr_hint_wifi else R.string.qr_hint_setup),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        val networkLabel = stringResource(R.string.label_network)
        val passwordLabel = stringResource(R.string.label_password)
        val proxyLabel = stringResource(R.string.label_proxy)
        InfoRow(networkLabel, endpoint.networkName, onCopy = { actions.onCopy(networkLabel, it) })
        InfoRow(passwordLabel, endpoint.passphrase, onCopy = { actions.onCopy(passwordLabel, it) }, secret = true)
        InfoRow(proxyLabel, endpoint.proxyAddress, onCopy = { actions.onCopy(proxyLabel, it) })
        if (nfc) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Nfc, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.nfc_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun SessionCard(state: MainUiState, onUpgrade: () -> Unit) {
    val s = state.session
    if (state.premium) {
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        ) {
            Row(
                Modifier.fillMaxWidth().background(Brand.premiumGradient).padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.AllInclusive, null, tint = Color(0xFF2C1A00), modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.premium_unlimited), style = MaterialTheme.typography.titleMedium, color = Color(0xFF2C1A00))
                    Text(
                        stringResource(R.string.session_used_premium, Format.duration(s.elapsedMs), Format.bytes(s.totalBytes)),
                        style = MaterialTheme.typography.bodyMedium, color = Color(0xFF4A2F00),
                    )
                }
            }
        }
        return
    }
    SectionCard(
        title = stringResource(R.string.card_session_title),
        icon = Icons.Rounded.Timer,
        trailing = { FilledTonalButton(onClick = onUpgrade) { Text(stringResource(R.string.action_go_unlimited)) } },
    ) {
        MeterRow(
            label = stringResource(R.string.meter_time_left),
            value = Format.duration(s.remainingMs),
            progress = 1f - s.timeProgress,
            warn = s.remainingMs < 120_000,
        )
        if (state.freeTier.hasDataCap) {
            MeterRow(
                label = stringResource(R.string.meter_data_left),
                value = Format.bytes(s.remainingBytes),
                progress = 1f - s.dataProgress,
                warn = s.remainingBytes < 10L * 1024 * 1024,
            )
        }
        Text(
            pluralStringResource(
                R.plurals.session_free_explainer, state.freeTier.dailyTime.inWholeMinutes.toInt(),
                state.freeTier.dailyTime.inWholeMinutes,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DevicesCard(state: MainUiState) {
    SectionCard(title = stringResource(R.string.card_devices_title), icon = Icons.Rounded.Devices) {
        if (state.devices.isEmpty()) {
            Text(stringResource(R.string.devices_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            state.devices.forEach { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(10.dp).background(
                            if (d.activeConnections > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        ),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.address, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            pluralStringResource(R.plurals.device_connections, d.activeConnections, d.activeConnections),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("↓ " + Format.bytes(d.bytesDownloaded), style = MaterialTheme.typography.bodyMedium)
                        Text("↑ " + Format.bytes(d.bytesUploaded), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(running: HostStatus.Running, actions: HostActions) {
    SectionCard(title = stringResource(R.string.card_setup_title), icon = Icons.Rounded.Laptop) {
        Text(stringResource(R.string.setup_card_body, running.network.endpoint.pacUrl), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        val pacLabel = stringResource(R.string.label_pac_url)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = actions.onOpenGuide) { Text(stringResource(R.string.action_setup_guide)) }
            OutlinedButton(onClick = { actions.onCopy(pacLabel, running.network.endpoint.pacUrl) }) { Text(stringResource(R.string.action_copy_pac)) }
        }
    }
}

@Composable
private fun FreeTierCard(state: MainUiState, nowWallMs: Long, onUpgrade: () -> Unit) {
    val next = state.session.nextFreeSessionAtWallMs
    val coolingDown = state.session.limitReached != null && next != null && next > nowWallMs
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WorkspacePremium, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.free_card_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (coolingDown) stringResource(R.string.free_card_cooldown, Format.duration(next - nowWallMs))
                else stringResource(
                    R.string.free_card_body,
                    Format.allowance(state.freeTier.timeMs),
                    Format.allowance(state.session.remainingMs.coerceAtMost(state.freeTier.timeMs)),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onUpgrade,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary),
            ) {
                Text(stringResource(R.string.action_see_plans))
            }
        }
    }
}

@Composable
private fun HowItWorksCard() {
    SectionCard(title = stringResource(R.string.how_title), icon = Icons.Rounded.Router) {
        listOf(
            Triple(Icons.Rounded.Router, R.string.how_step1_title, R.string.how_step1_body),
            Triple(Icons.Rounded.QrCode2, R.string.how_step2_title, R.string.how_step2_body),
            Triple(Icons.Rounded.Shield, R.string.how_step3_title, R.string.how_step3_body),
            Triple(Icons.Rounded.Speed, R.string.how_step4_title, R.string.how_step4_body),
        ).forEach { (icon, title, body) ->
            Row(Modifier.padding(vertical = 8.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(error: HostStatus.Error, nowWallMs: Long, actions: HostActions) {
    val text = when (error.kind) {
        HostErrorKind.WIFI_DIRECT_UNSUPPORTED -> stringResource(R.string.err_unsupported)
        HostErrorKind.WIFI_DISABLED -> stringResource(R.string.err_wifi_off)
        HostErrorKind.PERMISSION_MISSING -> stringResource(R.string.err_permission)
        HostErrorKind.BUSY -> stringResource(R.string.err_busy)
        HostErrorKind.GROUP_FAILED -> stringResource(R.string.err_group_failed)
        HostErrorKind.GROUP_LOST -> stringResource(R.string.err_group_lost)
        HostErrorKind.PORT_IN_USE -> stringResource(R.string.err_port)
        HostErrorKind.FREE_LIMIT_REACHED -> stringResource(
            R.string.err_cooldown, Format.duration(((error.retryAtWallMs ?: nowWallMs) - nowWallMs).coerceAtLeast(0)),
        )
        HostErrorKind.UNKNOWN -> stringResource(R.string.err_unknown, error.detail.orEmpty())
    }
    Banner(true, text, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) {
        when (error.kind) {
            HostErrorKind.WIFI_DISABLED -> Button(onClick = actions.onOpenWifiSettings) { Text(stringResource(R.string.action_turn_on_wifi)) }
            HostErrorKind.PERMISSION_MISSING -> Button(onClick = actions.onOpenAppSettings) { Text(stringResource(R.string.action_settings)) }
            HostErrorKind.FREE_LIMIT_REACHED -> Button(onClick = actions.onUpgrade) { Text(stringResource(R.string.action_upgrade)) }
            else -> Unit
        }
    }
}
