package com.cleartether.app.ui.help

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoneyOff
import androidx.compose.material.icons.rounded.PhonelinkSetup
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cleartether.app.R
import com.cleartether.app.core.proxy.ClientPlatform
import com.cleartether.app.core.proxy.SetupInstructions
import com.cleartether.app.core.proxy.TetherEndpoint
import com.cleartether.app.ui.theme.MonoStyle

enum class HelpTab { CONNECT, WHY, FAQ }

/**
 * Help centre: step-by-step connection guides for every kind of device, why ClearTether instead
 * of the built-in hotspot, and an FAQ (Android Auto, VPNs, limits). Works whether or not
 * sharing is on. Without a live network it shows the saved name, password and address.
 */
@Composable
fun HelpScreen(endpoint: TetherEndpoint, initialTab: HelpTab, onCopy: (String, String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab.ordinal) }
    Column {
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf(R.string.help_tab_connect, R.string.help_tab_why, R.string.help_tab_faq).forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label)) })
            }
        }
        Spacer(Modifier.height(16.dp))
        when (HelpTab.entries[tab]) {
            HelpTab.CONNECT -> ConnectGuide(endpoint, onCopy)
            HelpTab.WHY -> WhyClearTether()
            HelpTab.FAQ -> Faq()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectGuide(endpoint: TetherEndpoint, onCopy: (String, String) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(ClientPlatform.ANDROID_APP_NFC) }
    Text(stringResource(R.string.help_connect_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        ClientPlatform.entries.forEach { p ->
            FilterChip(selected = p == selected, onClick = { selected = p }, label = { Text(p.shortName) })
        }
    }
    Spacer(Modifier.height(12.dp))
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(selected.displayName, style = MaterialTheme.typography.titleMedium)
            val steps = SetupInstructions.steps(selected, endpoint)
            val commandLabel = stringResource(R.string.label_command)
            var number = 0
            steps.forEach { step ->
                Row {
                    if (step.isTip) {
                        Icon(Icons.Rounded.Lightbulb, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(24.dp))
                    } else {
                        number++
                        Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                            Text("$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary, textAlign = TextAlign.Center)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            step.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (step.isTip) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                        step.command?.let { cmd ->
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)).padding(start = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(cmd, style = MonoStyle, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                                IconButton(onClick = { onCopy(commandLabel, cmd) }) {
                                    Icon(Icons.Rounded.ContentCopy, stringResource(R.string.cd_copy, commandLabel))
                                }
                            }
                        }
                        step.linkPath?.let { path ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.guide_link, step.linkLabel ?: "", endpoint.baseUrl + path),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.help_connect_footer, endpoint.baseUrl),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private data class Point(val icon: ImageVector, val title: Int, val body: Int)

@Composable
private fun WhyClearTether() {
    Text(stringResource(R.string.why_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    val pros = listOf(
        Point(Icons.Rounded.WifiTethering, R.string.why_hotspot_title, R.string.why_hotspot_body),
        Point(Icons.Rounded.Wifi, R.string.why_wifi_title, R.string.why_wifi_body),
        Point(Icons.Rounded.Share, R.string.why_any_title, R.string.why_any_body),
        Point(Icons.Rounded.VpnKey, R.string.why_vpn_title, R.string.why_vpn_body),
        Point(Icons.Rounded.Business, R.string.why_work_title, R.string.why_work_body),
        Point(Icons.Rounded.PhonelinkSetup, R.string.why_android_title, R.string.why_android_body),
        Point(Icons.Rounded.QrCode2, R.string.why_easy_title, R.string.why_easy_body),
        Point(Icons.Rounded.Lock, R.string.why_private_title, R.string.why_private_body),
        Point(Icons.Rounded.MoneyOff, R.string.why_free_title, R.string.why_free_body),
    )
    InfoCard(title = stringResource(R.string.why_pros_title)) {
        pros.forEach { p ->
            Row(Modifier.padding(vertical = 8.dp)) {
                Icon(p.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(p.title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(p.body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    InfoCard(title = stringResource(R.string.why_tradeoffs_title)) {
        listOf(R.string.why_tradeoff_1, R.string.why_tradeoff_2, R.string.why_tradeoff_3, R.string.why_tradeoff_4).forEach {
            Row(Modifier.padding(vertical = 6.dp)) {
                Text("•", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.why_use_hotspot), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Faq() {
    val items = listOf(
        R.string.faq_auto_q to R.string.faq_auto_a,
        R.string.faq_devices_q to R.string.faq_devices_a,
        R.string.faq_noapp_q to R.string.faq_noapp_a,
        R.string.faq_nfc_q to R.string.faq_nfc_a,
        R.string.faq_vpn_q to R.string.faq_vpn_a,
        R.string.faq_nointernet_q to R.string.faq_nointernet_a,
        R.string.faq_screen_q to R.string.faq_screen_a,
        R.string.faq_data_q to R.string.faq_data_a,
        R.string.faq_safe_q to R.string.faq_safe_a,
        R.string.faq_tv_q to R.string.faq_tv_a,
    )
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            items.forEachIndexed { index, (q, a) ->
                var open by rememberSaveable { mutableStateOf(index == 0) }
                Row(
                    Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(q), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
                AnimatedVisibility(open) {
                    Text(
                        stringResource(a), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                    )
                }
                if (index < items.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
