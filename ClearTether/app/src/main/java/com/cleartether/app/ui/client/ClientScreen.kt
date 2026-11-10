package com.cleartether.app.ui.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cleartether.app.R
import com.cleartether.app.core.net.InetAddresses
import com.cleartether.app.data.ClientErrorKind
import com.cleartether.app.data.ClientSettings
import com.cleartether.app.data.ClientStartStep
import com.cleartether.app.data.ClientStatus
import com.cleartether.app.ui.MainUiState
import com.cleartether.app.ui.components.Banner
import com.cleartether.app.ui.components.InfoRow
import com.cleartether.app.ui.components.PowerButton
import com.cleartether.app.ui.components.SectionCard
import com.cleartether.app.ui.components.StatusPill
import com.cleartether.app.util.Format

data class ClientActions(
    val onToggle: () -> Unit,
    val onScanQr: () -> Unit,
    val onSaveSettings: (ClientSettings) -> Unit,
)

@Composable
fun ClientScreen(state: MainUiState, nfcAvailable: Boolean, actions: ClientActions) {
    val client = state.client
    val connected = client as? ClientStatus.Connected
    val busy = client is ClientStatus.Connecting
    var editing by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PowerButton(
            active = connected != null,
            busy = busy,
            remaining = null,
            label = stringResource(R.string.client_toggle_label),
            stateLabel = stringResource(if (connected != null) R.string.toggle_state_on else R.string.toggle_state_off),
            onClick = actions.onToggle,
        )
        Spacer(Modifier.height(4.dp))
        Text(clientHeadline(client), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(if (connected != null) R.string.client_sub_connected else R.string.client_sub_idle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp).padding(horizontal = 24.dp),
        )
        if (connected != null) {
            Spacer(Modifier.height(12.dp))
            StatusPill(
                stringResource(if (connected.udpRelay) R.string.pill_client_full else R.string.pill_client_tcp),
                MaterialTheme.colorScheme.primary,
            )
        }
    }
    Spacer(Modifier.height(20.dp))

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (client is ClientStatus.Error) {
            Banner(true, clientError(client), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        }
        if (connected != null) {
            SectionCard(title = stringResource(R.string.card_traffic_title), icon = Icons.Rounded.VpnLock) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TrafficStat(Icons.Rounded.Download, stringResource(R.string.traffic_down), Format.bytes(state.clientTraffic.downloaded))
                    TrafficStat(Icons.Rounded.Upload, stringResource(R.string.traffic_up), Format.bytes(state.clientTraffic.uploaded))
                }
                Spacer(Modifier.height(12.dp))
                InfoRow(stringResource(R.string.label_host), connected.proxy)
                InfoRow(stringResource(R.string.label_connections), state.clientTraffic.tcpConnections.toString())
                if (!connected.hostIsClearTether) {
                    Text(stringResource(R.string.client_generic_proxy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        SectionCard(
            title = stringResource(R.string.card_host_title),
            icon = Icons.Rounded.Wifi,
            trailing = {
                if (connected == null) TextButton(onClick = { editing = true }) {
                    Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_edit))
                }
            },
        ) {
            val settings = state.clientSettings
            if (settings.hasCredentials) {
                InfoRow(stringResource(R.string.label_network), settings.ssid.orEmpty())
                InfoRow(stringResource(R.string.label_proxy), "${settings.hostIp}:${settings.port}")
            } else {
                Text(stringResource(R.string.client_no_host), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (connected == null) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = actions.onScanQr) {
                        Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_scan_qr))
                    }
                    OutlinedButton(onClick = { editing = true }) { Text(stringResource(R.string.action_enter_manually)) }
                }
                if (nfcAvailable) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Nfc, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.client_nfc_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }

        SectionCard(title = stringResource(R.string.client_how_title), icon = Icons.Rounded.VpnLock) {
            Text(stringResource(R.string.client_how_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (editing) {
        ManualHostDialog(state.clientSettings, onDismiss = { editing = false }) {
            actions.onSaveSettings(it)
            editing = false
        }
    }
}

@Composable
private fun TrafficStat(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun clientHeadline(status: ClientStatus): String = when (status) {
    ClientStatus.Idle -> stringResource(R.string.client_headline_idle)
    is ClientStatus.Connecting -> stringResource(
        when (status.step) {
            ClientStartStep.JOINING_WIFI -> R.string.client_step_wifi
            ClientStartStep.REACHING_HOST -> R.string.client_step_host
            ClientStartStep.STARTING_VPN -> R.string.client_step_vpn
            ClientStartStep.RECONNECTING -> R.string.client_step_reconnecting
        },
    )
    is ClientStatus.Connected -> stringResource(R.string.client_headline_connected)
    is ClientStatus.Error -> stringResource(R.string.client_headline_error)
}

@Composable
private fun clientError(error: ClientStatus.Error): String = when (error.kind) {
    ClientErrorKind.NOT_CONFIGURED -> stringResource(R.string.cerr_not_configured)
    ClientErrorKind.NETWORK_NOT_FOUND -> stringResource(R.string.cerr_network)
    ClientErrorKind.HOST_UNREACHABLE -> stringResource(R.string.cerr_host, error.detail.orEmpty())
    ClientErrorKind.VPN_DENIED -> stringResource(R.string.cerr_vpn_denied)
    ClientErrorKind.VPN_FAILED -> stringResource(R.string.cerr_vpn_failed)
    ClientErrorKind.UNKNOWN -> stringResource(R.string.err_unknown, error.detail.orEmpty())
}

@Composable
private fun ManualHostDialog(initial: ClientSettings, onDismiss: () -> Unit, onSave: (ClientSettings) -> Unit) {
    var ssid by rememberSaveable { mutableStateOf(initial.ssid.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(initial.passphrase.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(initial.hostIp) }
    var port by rememberSaveable { mutableStateOf(initial.port.toString()) }
    var joinFromApp by rememberSaveable { mutableStateOf(initial.joinFromApp) }
    val portValue = port.toIntOrNull()
    val valid = InetAddresses.parseLiteral(host) != null && portValue != null && portValue in 1..65535 &&
        (!joinFromApp || ssid.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manual_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(ssid, { ssid = it }, label = { Text(stringResource(R.string.label_network)) }, singleLine = true)
                OutlinedTextField(
                    password, { password = it }, label = { Text(stringResource(R.string.label_password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(host, { host = it.trim() }, label = { Text(stringResource(R.string.label_host_ip)) }, singleLine = true, modifier = Modifier.weight(2f))
                    OutlinedTextField(
                        port, { port = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.label_port)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.manual_join_from_app), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.manual_join_from_app_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(joinFromApp, { joinFromApp = it })
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                enabled = valid,
                onClick = {
                    onSave(ClientSettings(ssid.trim().ifEmpty { null }, password.ifEmpty { null }, host, portValue ?: 8228, joinFromApp))
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
