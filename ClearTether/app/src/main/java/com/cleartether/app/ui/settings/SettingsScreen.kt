package com.cleartether.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cleartether.app.BuildConfig
import com.cleartether.app.R
import com.cleartether.app.data.HostSettings
import com.cleartether.app.data.WifiBand
import com.cleartether.app.ui.MainUiState

data class SettingsActions(
    val onBand: (WifiBand) -> Unit,
    val onNfc: (Boolean) -> Unit,
    val onCaptive: (Boolean) -> Unit,
    val onSuffix: (String) -> Unit,
    val onPassphrase: (String) -> Unit,
    val onRegenerate: () -> Unit,
    val onPort: (Int) -> Unit,
    val onBatterySettings: () -> Unit,
    val onUpgrade: () -> Unit,
    val onRestore: () -> Unit,
    val onManageSubscription: () -> Unit,
    val onPrivacy: () -> Unit,
    val onDebugPremium: (Boolean) -> Unit,
)

private enum class EditField { NAME, PASSWORD, PORT, BAND }

@Composable
fun SettingsScreen(state: MainUiState, hostRunning: Boolean, debugPremium: Boolean, actions: SettingsActions) {
    val host = state.hostSettings ?: return
    var editing by rememberSaveable { mutableStateOf<EditField?>(null) }
    val locked = hostRunning

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (locked) {
            Text(
                stringResource(R.string.settings_locked), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp, 8.dp),
            )
        }
        Header(stringResource(R.string.settings_network))
        Item(Icons.Rounded.Wifi, stringResource(R.string.settings_name), host.networkName, enabled = !locked) { editing = EditField.NAME }
        Item(Icons.Rounded.Key, stringResource(R.string.settings_password), "•".repeat(12), enabled = !locked) { editing = EditField.PASSWORD }
        Item(Icons.Rounded.Refresh, stringResource(R.string.settings_regenerate), stringResource(R.string.settings_regenerate_hint), enabled = !locked) {
            actions.onRegenerate()
        }
        Item(Icons.Rounded.Router, stringResource(R.string.settings_band), bandLabel(host.band), enabled = !locked) { editing = EditField.BAND }
        Item(Icons.Rounded.Bolt, stringResource(R.string.settings_port), host.port.toString(), enabled = !locked) { editing = EditField.PORT }

        Header(stringResource(R.string.settings_sharing))
        Toggle(Icons.Rounded.Nfc, stringResource(R.string.settings_nfc), stringResource(R.string.settings_nfc_hint), host.nfcSharing, actions.onNfc)
        Toggle(Icons.Rounded.Policy, stringResource(R.string.settings_captive), stringResource(R.string.settings_captive_hint), host.captivePortalAssist, actions.onCaptive)
        Item(Icons.Rounded.BatteryChargingFull, stringResource(R.string.settings_battery), stringResource(R.string.settings_battery_hint)) { actions.onBatterySettings() }

        Header(stringResource(R.string.settings_premium))
        if (state.premium) {
            Item(Icons.Rounded.WorkspacePremium, stringResource(R.string.settings_premium_active), stringResource(R.string.settings_premium_thanks)) {}
        } else {
            Item(Icons.Rounded.WorkspacePremium, stringResource(R.string.settings_premium_upgrade), stringResource(R.string.settings_premium_plans)) {
                actions.onUpgrade()
            }
        }
        if (state.billing.subscribed) {
            Item(Icons.Rounded.ManageAccounts, stringResource(R.string.settings_manage_sub), stringResource(R.string.settings_manage_sub_hint)) {
                actions.onManageSubscription()
            }
        }
        Item(Icons.Rounded.Refresh, stringResource(R.string.action_restore), stringResource(R.string.settings_restore_hint)) { actions.onRestore() }

        Header(stringResource(R.string.settings_about))
        Item(Icons.Rounded.Policy, stringResource(R.string.settings_privacy), stringResource(R.string.settings_privacy_hint)) { actions.onPrivacy() }
        Item(Icons.Rounded.Info, stringResource(R.string.settings_version), BuildConfig.VERSION_NAME) {}
        if (BuildConfig.DEBUG) {
            Toggle(Icons.Rounded.BugReport, stringResource(R.string.settings_debug_premium), stringResource(R.string.settings_debug_premium_hint), debugPremium, actions.onDebugPremium)
        }
    }

    when (editing) {
        EditField.NAME -> TextDialog(
            title = stringResource(R.string.settings_name),
            prefix = HostSettings.NETWORK_PREFIX,
            initial = host.networkSuffix,
            hint = pluralStringResource(R.plurals.settings_name_hint, HostSettings.MAX_SUFFIX_LENGTH, HostSettings.MAX_SUFFIX_LENGTH),
            validate = HostSettings::isValidSuffix,
            onDismiss = { editing = null },
            onSave = { actions.onSuffix(it); editing = null },
        )
        EditField.PASSWORD -> TextDialog(
            title = stringResource(R.string.settings_password),
            initial = host.passphrase,
            hint = stringResource(R.string.settings_password_hint),
            validate = HostSettings::isValidPassphrase,
            onDismiss = { editing = null },
            onSave = { actions.onPassphrase(it); editing = null },
        )
        EditField.PORT -> TextDialog(
            title = stringResource(R.string.settings_port),
            initial = host.port.toString(),
            hint = stringResource(R.string.settings_port_hint),
            numeric = true,
            validate = { it.toIntOrNull() in 1024..65535 },
            onDismiss = { editing = null },
            onSave = { actions.onPort(it.toInt()); editing = null },
        )
        EditField.BAND -> AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.settings_band)) },
            text = {
                Column {
                    WifiBand.entries.forEach { band ->
                        Row(
                            Modifier.fillMaxWidth().clickable { actions.onBand(band); editing = null }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = host.band == band, onClick = { actions.onBand(band); editing = null })
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(bandLabel(band))
                                Text(bandHint(band), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.action_close)) } },
        )
        null -> Unit
    }
}

@Composable
private fun bandLabel(band: WifiBand) = stringResource(
    when (band) {
        WifiBand.AUTO -> R.string.band_auto
        WifiBand.BAND_2_4_GHZ -> R.string.band_24
        WifiBand.BAND_5_GHZ -> R.string.band_5
    },
)

@Composable
private fun bandHint(band: WifiBand) = stringResource(
    when (band) {
        WifiBand.AUTO -> R.string.band_auto_hint
        WifiBand.BAND_2_4_GHZ -> R.string.band_24_hint
        WifiBand.BAND_5_GHZ -> R.string.band_5_hint
    },
)

@Composable
private fun Header(text: String) {
    Column {
        Spacer(Modifier.padding(top = 12.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun Item(icon: ImageVector, title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.45f),
    )
}

@Composable
private fun Toggle(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun TextDialog(
    title: String,
    initial: String,
    hint: String,
    validate: (String) -> Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    prefix: String? = null,
    numeric: Boolean = false,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    val valid = validate(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    isError = !valid,
                    prefix = prefix?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
                    supportingText = { Text(hint) },
                )
            }
        },
        confirmButton = { FilledTonalButton(enabled = valid, onClick = { onSave(value) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
