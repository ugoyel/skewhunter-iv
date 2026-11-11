@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.ugoyel.hearby.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ugoyel.hearby.client.DiscoveredHost
import io.github.ugoyel.hearby.client.HostDiscovery
import io.github.ugoyel.hearby.client.Phase
import io.github.ugoyel.hearby.client.ReceiverService
import io.github.ugoyel.hearby.client.ReceiverUiState
import io.github.ugoyel.hearby.core.Proto
import io.github.ugoyel.hearby.host.AudioCaptureAndOpusService
import io.github.ugoyel.hearby.host.HostUiState
import io.github.ugoyel.hearby.host.ListenerInfo
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ---- theme ------------------------------------------------------------------------------------------

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B57D0), onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E3FF), onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF00696E), secondaryContainer = Color(0xFFC4EEF0), onSecondaryContainer = Color(0xFF002022),
    background = Color(0xFFF7F9FC), surface = Color(0xFFF7F9FC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFABC7FF), onPrimary = Color(0xFF002F66),
    primaryContainer = Color(0xFF00458F), onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFF80D4D9), secondaryContainer = Color(0xFF004F53), onSecondaryContainer = Color(0xFFC4EEF0),
    background = Color(0xFF101418), surface = Color(0xFF101418),
)

@Composable
fun HearbyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

// ---- screen -----------------------------------------------------------------------------------------

private val BITRATES = listOf(64_000 to "Data saver", 96_000 to "Balanced", 128_000 to "High")
private val DELAYS = listOf(200 to "Low delay", 350 to "Balanced", 700 to "Bluetooth", 1200 to "Maximum")

@Composable
fun MainScreen() {
    val host by AudioCaptureAndOpusService.state.collectAsStateWithLifecycle()
    val listen by ReceiverService.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(if (listen.phase != Phase.IDLE && !host.running) 1 else 0) }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("Hearby", fontWeight = FontWeight.SemiBold) }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = tab == 0, onClick = { tab = 0 },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        icon = { SegmentedButtonDefaults.Icon(tab == 0) { Icon(Icons.Rounded.Podcasts, null, Modifier.size(18.dp)) } },
                    ) { Text("Share audio") }
                    SegmentedButton(
                        selected = tab == 1, onClick = { tab = 1 },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        icon = { SegmentedButtonDefaults.Icon(tab == 1) { Icon(Icons.Rounded.Headphones, null, Modifier.size(18.dp)) } },
                    ) { Text("Listen") }
                }
                if (tab == 0) HostPane(host, listening = listen.phase != Phase.IDLE && listen.phase != Phase.ENDED)
                else ListenPane(listen, hosting = host.running)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ---- host -------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HostPane(state: HostUiState, listening: Boolean) {
    val context = LocalContext.current
    var bitrate by rememberSaveable { mutableIntStateOf(96_000) }
    var delayMs by rememberSaveable { mutableIntStateOf(350) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var micDenied by rememberSaveable { mutableStateOf(false) }

    // Step 2: Android's "start recording or casting?" dialog. Its result is the capture token.
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            notice = null
            AudioCaptureAndOpusService.start(context, result.resultCode, data, bitrate, delayMs)
        } else {
            notice = "Sharing needs your OK on Android's capture dialog."
        }
    }
    // Step 1: runtime permissions. Playback capture is guarded by RECORD_AUDIO.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (context.hasPermission(Manifest.permission.RECORD_AUDIO)) {
            micDenied = false
            projectionLauncher.launch(captureIntent(context))
        } else {
            micDenied = true
            notice = "Android guards audio capture with the Microphone permission. Hearby never opens the microphone."
        }
    }

    fun startSharing() {
        AudioCaptureAndOpusService.clearMessage()
        val needed = buildList {
            if (!context.hasPermission(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) projectionLauncher.launch(captureIntent(context))
        else permissionLauncher.launch(needed.toTypedArray())
    }

    StatusCard(
        icon = Icons.Rounded.Podcasts,
        active = state.running,
        title = if (state.running) "Sharing this phone's audio" else "Share this phone's audio",
        body = if (state.running) {
            "Play something in any app. Everyone who joins hears it in sync."
        } else {
            "Whatever this phone plays (YouTube, music, a movie) is sent to nearby phones over Wi-Fi or your hotspot. No internet needed."
        },
    ) {
        if (state.running) {
            val level by animateFloatAsState(state.level.coerceIn(0f, 1f), label = "level")
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
            if (state.silentMs > 6_000) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "No sound is being captured. Start playback, or note that some apps (Netflix, Prime Video, Spotify, calls) block capture.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (address in state.addresses) {
                    AssistChip(
                        onClick = {},
                        label = { Text(if (state.port == Proto.PORT) address else "$address:${state.port}") },
                        leadingIcon = { Icon(Icons.Rounded.Wifi, null, Modifier.size(16.dp)) },
                    )
                }
                AssistChip(onClick = {}, label = { Text("${state.bitrate / 1000} kbps Opus") })
                AssistChip(onClick = {}, label = { Text("${state.playoutDelayMs} ms buffer") })
            }
        }
    }

    (state.message ?: notice)?.let { NoticeCard(it) }
    if (micDenied && !state.running) {
        TextButton(onClick = { context.openAppSettings() }) { Text("Open app settings") }
    }

    if (!state.running) {
        SettingCard("Audio quality", "Higher sounds better; lower leaves more room when many phones share one hotspot.") {
            for ((value, label) in BITRATES) {
                FilterChip(selected = bitrate == value, onClick = { bitrate = value }, label = { Text("$label · ${value / 1000} kbps") })
            }
        }
        SettingCard(
            "Sync buffer",
            "How far behind this phone everyone plays. Larger survives weak Wi-Fi and Bluetooth listeners; " +
                "smaller keeps sound closer to the video on this screen.",
        ) {
            for ((value, label) in DELAYS) {
                FilterChip(selected = delayMs == value, onClick = { delayMs = value }, label = { Text("$label · $value ms") })
            }
        }
        Button(
            onClick = { startSharing() },
            enabled = !listening,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.Rounded.Podcasts, null)
            Spacer(Modifier.width(8.dp))
            Text("Start sharing")
        }
        if (listening) {
            Text(
                "Leave the session you are listening to before sharing.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        FilledTonalButton(
            onClick = { AudioCaptureAndOpusService.stop(context) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Icon(Icons.Rounded.Stop, null)
            Spacer(Modifier.width(8.dp))
            Text("Stop sharing")
        }
        ListenersCard(state.listeners)
        Text(
            "Tip: this phone keeps playing live while listeners are ${state.playoutDelayMs} ms behind. " +
                "Turn its media volume down to avoid an echo; that does not affect what listeners hear.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ListenersCard(listeners: List<ListenerInfo>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                if (listeners.isEmpty()) "Waiting for listeners" else "Listeners (${listeners.size})",
                style = MaterialTheme.typography.titleMedium,
            )
            if (listeners.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "On the other phones, open Hearby, choose Listen and tap this phone. They must be on the same Wi-Fi or connected to this phone's hotspot.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            listeners.forEachIndexed { index, l ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (l.platform == Proto.PLATFORM_IOS) Icons.Rounded.PhoneIphone else Icons.Rounded.Android,
                        contentDescription = if (l.platform == Proto.PLATFORM_IOS) "iPhone" else "Android",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(l.name.ifBlank { l.address }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${l.address} · ${fmt1(l.rttMs)} ms round trip · ${l.bufferMs} ms buffered",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    val (label, color) = when (l.status) {
                        Proto.STATUS_SYNCED -> "In sync" to MaterialTheme.colorScheme.primary
                        Proto.STATUS_LATE -> "Behind" to MaterialTheme.colorScheme.error
                        else -> "Syncing" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(label, style = MaterialTheme.typography.labelLarge, color = color)
                }
            }
        }
    }
}

// ---- listen -----------------------------------------------------------------------------------------

@Composable
private fun ListenPane(state: ReceiverUiState, hosting: Boolean) {
    when (state.phase) {
        Phase.IDLE -> JoinPane(hosting)
        Phase.ENDED -> {
            StatusCard(Icons.Rounded.Headphones, active = false, title = "Session ended", body = state.message ?: "You left the session.")
            Button(onClick = { ReceiverService.dismiss() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Find another host") }
        }
        else -> ListeningPane(state)
    }
}

@Composable
private fun JoinPane(hosting: Boolean) {
    val context = LocalContext.current
    val discovery = remember { HostDiscovery(context) }
    val hosts by discovery.hosts.collectAsStateWithLifecycle()
    var manual by rememberSaveable { mutableStateOf("") }
    var manualError by rememberSaveable { mutableStateOf<String?>(null) }

    DisposableEffect(discovery) {
        discovery.start()
        onDispose { discovery.stop() }
    }
    // Only needed so the "listening" notification can be shown; joining works either way.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun join(address: String, port: Int, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ReceiverService.join(context, address, port, name)
    }

    fun joinManual() {
        val parsed = parseAddress(manual)
        if (parsed == null) {
            manualError = "Enter the address shown on the host, for example 192.168.43.1"
        } else {
            manualError = null
            join(parsed.first, parsed.second, parsed.first)
        }
    }

    if (hosting) {
        NoticeCard("This phone is sharing its audio. Stop sharing before listening to someone else.")
        return
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Nearby hosts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (hosts.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Looking on this Wi-Fi network… Connect to the same Wi-Fi as the host, or to the host's hotspot.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            hosts.forEachIndexed { index, h ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                HostRow(h) { join(h.address, h.port, h.name) }
            }
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Join by address", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it.trim(); manualError = null },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("192.168.43.1") },
                    isError = manualError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { joinManual() }),
                )
                Spacer(Modifier.width(12.dp))
                Button(onClick = { joinManual() }, enabled = manual.isNotBlank()) { Text("Join") }
            }
            Text(
                manualError ?: "The host's address is shown on its screen while it is sharing.",
                style = MaterialTheme.typography.bodySmall,
                color = if (manualError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HostRow(host: DiscoveredHost, onJoin: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Podcasts, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(host.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val others = when (host.listeners) {
                0 -> "no one listening yet"
                1 -> "1 listening"
                else -> "${host.listeners} listening"
            }
            Text(
                "${host.address} · $others",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onJoin) { Text("Join") }
    }
}

@Composable
private fun ListeningPane(state: ReceiverUiState) {
    val context = LocalContext.current
    val title = when (state.phase) {
        Phase.CONNECTING -> "Connecting…"
        Phase.SYNCING -> "Syncing clocks…"
        Phase.RECONNECTING -> "Reconnecting…"
        else -> if (state.lateBiasMs > 0) "Playing, ${state.lateBiasMs} ms behind" else "Playing in sync"
    }
    val hostLabel = state.hostName.ifBlank { state.hostAddress }
    StatusCard(
        icon = if (state.outputIsBluetooth) Icons.Rounded.Bluetooth else Icons.Rounded.Headphones,
        active = state.phase == Phase.PLAYING,
        title = title,
        body = "Listening to $hostLabel" + if (state.outputName.isNotBlank()) " on ${state.outputName}" else "",
    ) {
        if (state.phase == Phase.PLAYING) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("Sync error", "${signed1(state.syncErrorMs)} ms")
                Metric("Network", "${fmt1(state.rttMs)} ms")
                Metric("Buffer", "${state.bufferMs} ms")
                Metric("Loss", "${fmt1(state.lossPercent)} %")
            }
        }
    }

    state.message?.let { NoticeCard(it) }

    if (state.muted) {
        NoticeCard("Muted because another app took over audio or the output was disconnected.") {
            TextButton(onClick = { ReceiverService.setMuted(false) }) {
                Icon(Icons.AutoMirrored.Rounded.VolumeOff, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Unmute")
            }
        }
    }
    if (state.lateBiasMs > 0 && state.phase == Phase.PLAYING) {
        NoticeCard(
            "This device needs more time than the host's ${state.playoutDelayMs} ms sync buffer allows, so it plays " +
                "${state.lateBiasMs} ms behind the others. Ask the host to pick a larger sync buffer" +
                if (state.offsetMs < 0) ", or move the offset slider back toward 0." else ".",
        )
    }

    OffsetCard(state)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { ReceiverService.resync() }, modifier = Modifier.weight(1f).height(52.dp)) {
            Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Re-sync")
        }
        FilledTonalButton(
            onClick = { ReceiverService.leave(context) },
            modifier = Modifier.weight(1f).height(52.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) { Text("Leave") }
    }
}

/** The manual delay slider: -500..+500 ms, applied on release, plus fine nudges. */
@Composable
private fun OffsetCard(state: ReceiverUiState) {
    // While the thumb is being dragged show the pending value; otherwise follow the receiver
    // (which also switches the value when the output device changes).
    var dragging by remember { mutableStateOf(false) }
    var pending by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) pending.roundToInt() else state.offsetMs

    fun apply(ms: Int) = ReceiverService.setOffsetMs(ms.coerceIn(-500, 500))

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (state.outputIsBluetooth) Icons.Rounded.Bluetooth else Icons.Rounded.Speaker, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text("Sync offset", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    (if (shown > 0) "+" else "") + "$shown ms",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = shown.toFloat(),
                onValueChange = {
                    dragging = true
                    pending = it
                },
                onValueChangeFinished = {
                    apply(pending.roundToInt())
                    dragging = false
                },
                valueRange = -500f..500f,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("−500 earlier", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("later +500", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (step in intArrayOf(-10, -1)) {
                    OutlinedButton(onClick = { apply(state.offsetMs + step) }, modifier = Modifier.weight(1f)) { Text("$step") }
                }
                OutlinedButton(onClick = { apply(0) }, enabled = state.offsetMs != 0, modifier = Modifier.weight(1.2f)) { Text("Reset") }
                for (step in intArrayOf(1, 10)) {
                    OutlinedButton(onClick = { apply(state.offsetMs + step) }, modifier = Modifier.weight(1f)) { Text("+$step") }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "If this device sounds late next to the others (typical for Bluetooth earbuds and speakers), move toward −. " +
                    "If it sounds early, move toward +. Remembered separately for each output" +
                    (if (state.outputName.isNotBlank()) " (now: ${state.outputName})." else "."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- shared pieces ----------------------------------------------------------------------------------

@Composable
private fun StatusCard(
    icon: ImageVector,
    active: Boolean,
    title: String,
    body: String,
    extra: @Composable () -> Unit = {},
) {
    val container = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Card(colors = CardDefaults.cardColors(containerColor = container, contentColor = content)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(icon, null) }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(body, style = MaterialTheme.typography.bodyMedium)
                }
            }
            extra()
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingCard(title: String, description: String, chips: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
        }
    }
}

@Composable
private fun NoticeCard(text: String, action: @Composable () -> Unit = {}) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Warning, null, Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
            action()
        }
    }
}

// ---- helpers ----------------------------------------------------------------------------------------

private fun Context.hasPermission(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * The system capture-consent intent. On Android 14+ we ask for the whole display rather than
 * offering "a single app": audio capture is what we are after, and it should cover whichever app
 * the user switches to.
 */
private fun captureIntent(context: Context): Intent {
    val manager = context.getSystemService(MediaProjectionManager::class.java)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
    } else {
        manager.createScreenCaptureIntent()
    }
}

private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?::(\d{1,5}))?$""")

/** "192.168.1.20" or "192.168.1.20:47800" -> (address, port); null if it is not a literal IPv4 address. */
internal fun parseAddress(text: String): Pair<String, Int>? {
    val m = IPV4.matchEntire(text.trim()) ?: return null
    val octets = (1..4).map { m.groupValues[it].toInt() }
    if (octets.any { it > 255 }) return null
    val port = m.groupValues[5].takeIf { it.isNotEmpty() }?.toInt() ?: Proto.PORT
    if (port !in 1..65535) return null
    return octets.joinToString(".") to port
}

private fun fmt1(v: Float): String = String.format(Locale.US, "%.1f", v)

private fun signed1(v: Float): String = (if (v >= 0.05f) "+" else if (abs(v) < 0.05f) "±" else "") + String.format(Locale.US, "%.1f", v)
