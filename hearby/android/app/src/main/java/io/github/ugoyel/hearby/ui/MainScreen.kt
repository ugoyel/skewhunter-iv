package io.github.ugoyel.hearby.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import io.github.ugoyel.hearby.core.YouTubeLinks
import io.github.ugoyel.hearby.core.VideoTimeline
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
import io.github.ugoyel.hearby.host.PartyHostService
import io.github.ugoyel.hearby.host.HostUiState
import io.github.ugoyel.hearby.host.ListenerInfo
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ---- options ----------------------------------------------------------------------------------------

private class Quality(val bitrate: Int, val name: String)

private class Cushion(val delayMs: Int, val name: String, val blurb: String)

private val QUALITIES = listOf(Quality(64_000, "Lite"), Quality(96_000, "Crisp"), Quality(128_000, "Studio"))

private val CUSHIONS = listOf(
    Cushion(200, "Snappy", "Closest to the video on this screen. Best when everyone is on phone speakers or wired."),
    Cushion(350, "Balanced", "The sweet spot for most parties."),
    Cushion(700, "Bluetooth", "Extra room for Bluetooth earbuds and speakers."),
    Cushion(1200, "Bulletproof", "For shaky Wi-Fi or a room full of Bluetooth."),
)

private val MODES = listOf("Be the DJ" to Icons.Rounded.GraphicEq, "Join a party" to Icons.Rounded.Celebration)

// ---- screen (wires the UI to the two services) ----------------------------------------------------------

@Composable
fun MainScreen() {
    val host by PartyHostService.state.collectAsStateWithLifecycle()
    val listen by ReceiverService.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(if (listen.phase != Phase.IDLE && !host.running) 1 else 0) }
    val djRequests by PartyHostService.djRequests.collectAsStateWithLifecycle()
    LaunchedEffect(djRequests) { if (djRequests > 0) tab = 0 }
    val listening = listen.phase != Phase.IDLE && listen.phase != Phase.ENDED

    // Confetti for the two happy moments: someone joins your party, or you lock in to someone else's.
    var confetti by remember { mutableIntStateOf(0) }
    var crowdSeen by remember { mutableIntStateOf(host.listeners.size) }
    LaunchedEffect(host.listeners.size) {
        if (host.listeners.size > crowdSeen) confetti++
        crowdSeen = host.listeners.size
    }
    var wasPlaying by remember { mutableStateOf(listen.phase == Phase.PLAYING) }
    LaunchedEffect(listen.phase) {
        val playing = listen.phase == Phase.PLAYING
        if (playing && !wasPlaying) confetti++
        // "Finding the beat" blips during a re-sync should not re-trigger the celebration.
        if (listen.phase == Phase.IDLE || listen.phase == Phase.ENDED || playing) wasPlaying = playing
    }

    PartyScaffold(
        tab = tab,
        onTab = { tab = it },
        energy = if (host.running || listen.phase == Phase.PLAYING) 1f else 0f,
        overlay = { ConfettiBurst(confetti) },
    ) {
        if (tab == 0) HostPane(host, listening) else ListenPane(listen, host.running)
    }
}

/** Stage, wordmark, mode switch and a scrolling column for the current pane. */
@Composable
internal fun PartyScaffold(
    tab: Int,
    onTab: (Int) -> Unit,
    energy: Float,
    overlay: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    PartyBackground(energy) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Wordmark(Modifier.align(Alignment.CenterHorizontally))
                ModeSwitch(tab, onTab, MODES)
                content()
                Spacer(Modifier.height(4.dp))
            }
        }
        overlay()
    }
}

// ---- DJ (host) ----------------------------------------------------------------------------------------

@Composable
private fun HostPane(state: HostUiState, listening: Boolean) {
    val context = LocalContext.current
    var bitrate by rememberSaveable { mutableIntStateOf(96_000) }
    var delayMs by rememberSaveable { mutableIntStateOf(350) }
    var micNotice by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerMissing by rememberSaveable { mutableStateOf(false) }
    val waitingSongs by PartyHostService.pendingSongs.collectAsStateWithLifecycle()
    val waitingVideo by PartyHostService.pendingVideoId.collectAsStateWithLifecycle()

    // Only for the "you're the DJ" notification; the party starts either way.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        PartyHostService.start(context, bitrate, delayMs)
    }
    // Songs come from the system file picker, which grants access to exactly the files chosen:
    // no storage permission needed.
    val songPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            for (uri in uris) {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    // the picker's own grant is enough while the party runs
                }
            }
            PartyHostService.addSongs(uris)
        }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            micNotice = null
            PartyHostService.soundCheck()
        } else {
            micNotice = "The sound check uses this phone's microphone for a few seconds to hear each phone's beep. " +
                "Nothing is recorded or sent. You can still fine-tune each phone by hand."
        }
    }

    fun start() {
        PartyHostService.clearMessage()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            PartyHostService.start(context, bitrate, delayMs)
        }
    }

    fun pickSongs() {
        try {
            songPicker.launch(arrayOf("audio/*"))
        } catch (e: ActivityNotFoundException) {
            pickerMissing = true
        }
    }

    fun soundCheck() {
        if (context.hasPermission(Manifest.permission.RECORD_AUDIO)) PartyHostService.soundCheck()
        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    if (state.running) {
        HostLive(
            state,
            DjActions(
                onAddSongs = ::pickSongs,
                onToggle = { PartyHostService.togglePlay() },
                onNext = { PartyHostService.next() },
                onPrevious = { PartyHostService.previous() },
                onPlay = { PartyHostService.playSong(it) },
                onRemove = { PartyHostService.removeSong(it) },
                onSoundCheck = ::soundCheck,
                onStop = { PartyHostService.stop(context) },
                onPlayVideo = { PartyHostService.playVideo(it) },
                onToggleVideo = { PartyHostService.toggleVideo() },
                onStopVideo = { PartyHostService.stopVideo() },
                onUnmute = { PartyHostService.unmute() },
                onVideoTitle = { id, title -> PartyHostService.videoTitle(id, title) },
                onVideoEnded = { PartyHostService.videoEnded(it) },
            ),
            micNotice = micNotice,
            notice = if (pickerMissing) {
                "This phone has no file picker. Open a song in your file manager and choose Share or Open with › Hearby."
            } else {
                null
            },
        )
    } else {
        HostIdle(
            bitrate = bitrate, onBitrate = { bitrate = it },
            delayMs = delayMs, onDelay = { delayMs = it },
            notice = state.message ?: when {
                waitingVideo != null -> "A YouTube video is ready. Start the party to play it on every phone."
                waitingSongs == 1 -> "1 song is ready. Start the party to play it."
                waitingSongs > 1 -> "$waitingSongs songs are ready. Start the party to play them."
                else -> null
            },
            busyListening = listening,
            onStart = ::start,
        )
    }
}

internal class DjActions(
    val onAddSongs: () -> Unit = {},
    val onToggle: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onPlay: (Int) -> Unit = {},
    val onRemove: (Int) -> Unit = {},
    val onSoundCheck: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onPlayVideo: (String) -> Unit = {},
    val onToggleVideo: () -> Unit = {},
    val onStopVideo: () -> Unit = {},
    val onUnmute: () -> Unit = {},
    val onVideoTitle: (String, String) -> Unit = { _, _ -> },
    val onVideoEnded: (String) -> Unit = {},
)

@Composable
internal fun HostIdle(
    bitrate: Int,
    onBitrate: (Int) -> Unit,
    delayMs: Int,
    onDelay: (Int) -> Unit,
    notice: String?,
    busyListening: Boolean,
    onStart: () -> Unit,
) {
    Hero(
        icon = Icons.Rounded.Speaker, active = false,
        title = "Be the DJ",
        body = "Play songs from this phone. Every phone at the party plays them at the same moment, this one included. No internet needed.",
    )

    notice?.let { Notice(it) }

    GlassCard {
        SectionTitle("Sound quality", "Lite goes easy on a busy hotspot. Studio sounds best.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (q in QUALITIES) {
                ChoiceTile(q.name, "${q.bitrate / 1000} kbps", bitrate == q.bitrate, { onBitrate(q.bitrate) }, Modifier.weight(1f))
            }
        }
    }

    GlassCard {
        SectionTitle("Sync cushion", CUSHIONS.firstOrNull { it.delayMs == delayMs }?.blurb ?: "How far ahead Hearby plans the music.")
        for (row in CUSHIONS.chunked(2)) {
            Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in row) {
                    ChoiceTile(c.name, "${c.delayMs} ms", delayMs == c.delayMs, { onDelay(c.delayMs) }, Modifier.weight(1f))
                }
            }
        }
    }

    GradientButton("Start the party", onStart, Modifier.fillMaxWidth(), icon = Icons.Rounded.Celebration, enabled = !busyListening)
    if (busyListening) {
        Caption("You're at someone else's party. Leave it first to start your own.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HostLive(state: HostUiState, actions: DjActions, micNotice: String? = null, notice: String? = null) {
    val video = state.video
    if (video != null) {
        val correction by rememberUpdatedState(state.correctionUs)
        VideoCard(
            video = video, hostOffsetUs = { 0L }, correctionUs = { correction },
            onTitle = { actions.onVideoTitle(video.videoId, it) },
            onEnded = { actions.onVideoEnded(video.videoId) },
        ) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayButton(video.playing, actions.onToggleVideo)
                Spacer(Modifier.width(4.dp))
                GhostButton("Back to music", actions.onStopVideo, Modifier.weight(1f), icon = Icons.Rounded.LibraryMusic, height = 52.dp)
            }
        }
        Crowd(state.listeners)
        GradientButton("End the party", actions.onStop, Modifier.fillMaxWidth(), icon = Icons.Rounded.Stop, brush = Party.Stop, glow = Party.Coral)
        return
    }
    val song = state.queue.getOrNull(state.current)
    GlassCard(lit = true) {
        HeroRow(
            Icons.Rounded.GraphicEq, active = true, level = state.level,
            title = "You're live!",
            body = if (state.queue.isEmpty()) "Add songs from this phone to get the party going." else "Every phone plays this in sync, this one too.",
        )
        if (song != null) {
            Spacer(Modifier.height(16.dp))
            Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    state.loading -> "Loading…"
                    song.artist.isNotBlank() -> song.artist
                    else -> " "
                },
                style = MaterialTheme.typography.bodyMedium, color = Party.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Equalizer(if (state.playing) state.level else 0f)
            Spacer(Modifier.height(8.dp))
            SongProgress(state.positionMs, song.durationMs)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.Rounded.SkipPrevious, "Previous", actions.onPrevious)
                Spacer(Modifier.width(22.dp))
                PlayButton(state.playing, actions.onToggle)
                Spacer(Modifier.width(22.dp))
                RoundButton(Icons.Rounded.SkipNext, "Next", actions.onNext)
            }
        } else {
            Spacer(Modifier.height(16.dp))
            GradientButton("Add songs", actions.onAddSongs, Modifier.fillMaxWidth(), icon = Icons.Rounded.LibraryMusic)
        }
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (address in state.addresses) {
                InfoPill(if (state.port == Proto.PORT) address else "$address:${state.port}", Icons.Rounded.Wifi)
            }
            InfoPill("${state.bitrate / 1000} kbps")
            InfoPill("${state.playoutDelayMs} ms cushion")
            if (state.outputIsBluetooth) InfoPill(state.outputName.removePrefix("Bluetooth: "), Icons.Rounded.Bluetooth)
        }
    }

    state.songError?.let { Notice(it) }
    notice?.let { Notice(it) }

    if (state.muted) {
        Notice(
            "Muted on this phone: another app took over the sound, or your headphones disconnected. " +
                "Everyone else is still playing.",
            icon = Icons.AutoMirrored.Rounded.VolumeOff,
        ) {
            Spacer(Modifier.height(10.dp))
            GhostButton("Unmute", actions.onUnmute, height = 44.dp)
        }
    }

    if (state.queue.isNotEmpty()) Queue(state, actions)

    YouTubeCard(actions.onPlayVideo)

    SoundCheckCard(state, actions.onSoundCheck, micNotice)

    Crowd(state.listeners)

    GradientButton("End the party", actions.onStop, Modifier.fillMaxWidth(), icon = Icons.Rounded.Stop, brush = Party.Stop, glow = Party.Coral)
}

/** Pick a YouTube video for every phone: paste a link, or share it from the YouTube app. */
@Composable
private fun YouTubeCard(onPlay: (String) -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    fun play() {
        val id = YouTubeLinks.videoId(link)
        if (id == null) {
            error = "That doesn't look like a YouTube link."
        } else {
            error = null
            link = ""
            onPlay(id)
        }
    }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SmartDisplay, null, tint = Party.Coral)
            Spacer(Modifier.width(10.dp))
            Text("Play from YouTube", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Every phone plays the video itself, over the internet, and Hearby keeps them in time. " +
                "Paste a link, or in the YouTube app tap Share › Hearby.",
            style = MaterialTheme.typography.bodyMedium, color = Party.InkSoft,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = link,
                onValueChange = {
                    link = it
                    error = null
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("youtu.be/…", color = Party.InkFaint) },
                isError = error != null,
                shape = RoundedCornerShape(18.dp),
                colors = partyFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { play() }),
            )
            Spacer(Modifier.width(12.dp))
            GradientButton("Play", ::play, enabled = link.isNotBlank(), height = 52.dp)
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Party.Coral)
        }
    }
}

/** The party's YouTube video on this phone: the synced player, its title, and how far off it is. */
@Composable
internal fun VideoCard(
    video: VideoTimeline,
    hostOffsetUs: () -> Long,
    correctionUs: () -> Long,
    onTitle: (String) -> Unit = {},
    onEnded: () -> Unit = {},
    controls: @Composable () -> Unit = {},
) {
    var notice by remember { mutableStateOf(PlayerNotice()) }
    val title = video.title.ifBlank { notice.title.orEmpty() }
    GlassCard(lit = video.playing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag("YouTube", Party.Coral)
            Spacer(Modifier.width(10.dp))
            Text(
                if (video.playing) "Playing on every phone" else "Paused",
                style = MaterialTheme.typography.labelLarge, color = Party.InkSoft, modifier = Modifier.weight(1f),
            )
            notice.offErrorMs?.let { off ->
                if (notice.error == null && video.playing) {
                    Tag(if (abs(off) < 1f) "in sync" else "${signed1(off)} ms", if (abs(off) < 15f) Party.Lime else Party.Sun)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))) {
            if (LocalMotion.current) {
                SyncedYouTubePlayer(
                    video, hostOffsetUs, correctionUs,
                    onNotice = { n ->
                        notice = n
                        n.title?.let(onTitle)
                    },
                    onEnded = onEnded,
                )
            } else {
                VideoPlaceholder()
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            title.ifBlank { "Loading video…" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        notice.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Party.Sun)
        }
        Spacer(Modifier.height(8.dp))
        Text(VIDEO_ON_SCREEN_HINT, style = MaterialTheme.typography.bodySmall, color = Party.InkSoft)
        controls()
    }
}

/** YouTube's rule, said once so nobody is surprised when a phone that leaves the app goes quiet. */
private const val VIDEO_ON_SCREEN_HINT =
    "YouTube plays only while Hearby is open, so this screen stays on. If you leave the app, only this " +
        "phone pauses, and it catches up when you come back."

/** What screenshots show instead of the live YouTube player. */
@Composable
private fun VideoPlaceholder() {
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Brush.linearGradient(listOf(Color(0xFF1A1030), Color(0xFF301848)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.PlayCircle, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(64.dp))
    }
}

@Composable
private fun partyFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Party.Pink, unfocusedBorderColor = Party.GlassStroke, cursorColor = Party.Pink,
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    focusedContainerColor = Party.Glass, unfocusedContainerColor = Party.Glass,
    errorBorderColor = Party.Coral, errorContainerColor = Party.Glass, errorTextColor = Color.White,
)

@Composable
private fun Queue(state: HostUiState, actions: DjActions) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Up next", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
            GhostButton("Add songs", actions.onAddSongs, icon = Icons.Rounded.Add, height = 42.dp)
        }
        Spacer(Modifier.height(6.dp))
        state.queue.forEachIndexed { index, song ->
            if (index > 0) HorizontalDivider(color = Party.GlassStroke)
            val current = index == state.current
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { actions.onPlay(index) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
                    if (current) {
                        Icon(Icons.Rounded.GraphicEq, "Playing", tint = Party.Pink, modifier = Modifier.size(20.dp))
                    } else {
                        Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = Party.InkFaint)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium, color = if (current) Party.Pink else Color.White,
                    )
                    val sub = listOf(song.artist, duration(song.durationMs)).filter { it.isNotBlank() }.joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Party.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { actions.onRemove(index) }) {
                    Icon(Icons.Rounded.Close, "Remove ${song.title}", tint = Party.InkFaint)
                }
            }
        }
    }
}

@Composable
private fun SoundCheckCard(state: HostUiState, onRun: () -> Unit, micNotice: String?) {
    val check = state.soundCheck
    GlassCard(lit = check.running) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Hearing, null, tint = Party.Lime)
            Spacer(Modifier.width(10.dp))
            Text("Sound check", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
            if (check.done && !check.running) Tag("Done", Party.Lime)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Lines every phone up to the millisecond, Bluetooth speakers included. Gather the phones near this one, " +
                "turn their volume up, and Hearby beeps each one in turn while this phone listens.",
            style = MaterialTheme.typography.bodyMedium, color = Party.InkSoft,
        )
        Spacer(Modifier.height(12.dp))
        if (check.running) {
            LinearProgressIndicator(
                progress = { check.progress.coerceIn(0.05f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                color = Party.Lime, trackColor = Party.GlassStroke,
            )
            Spacer(Modifier.height(10.dp))
            Text(check.message ?: "Listening…", style = MaterialTheme.typography.bodyMedium, color = Color.White)
        } else {
            check.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = if (check.done) Party.Lime else Party.Sun)
                Spacer(Modifier.height(12.dp))
            }
            micNotice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Party.Sun)
                Spacer(Modifier.height(12.dp))
            }
            GradientButton(
                if (check.done) "Run it again" else "Run sound check", onRun, Modifier.fillMaxWidth(),
                icon = Icons.Rounded.Hearing, height = 52.dp,
                enabled = state.listeners.any { it.status == Proto.STATUS_SYNCED || it.status == Proto.STATUS_LATE },
            )
            if (state.listeners.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Available once friends have joined.", style = MaterialTheme.typography.bodySmall, color = Party.InkFaint)
            }
        }
    }
}

@Composable
private fun Crowd(listeners: List<ListenerInfo>) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("The crowd", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.width(10.dp))
            if (listeners.isNotEmpty()) Tag("${listeners.size}", Party.Lime)
        }
        if (listeners.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "No one's here yet. Friends open Hearby, tap Join a party and pick this phone. They need to be on the same Wi-Fi, or on this phone's hotspot.",
                style = MaterialTheme.typography.bodyMedium, color = Party.InkSoft,
            )
        }
        listeners.forEachIndexed { index, l ->
            if (index > 0) HorizontalDivider(color = Party.GlassStroke)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(l.name.ifBlank { l.address })
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.name.ifBlank { l.address }, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (l.platform == Proto.PLATFORM_IOS) Icons.Rounded.PhoneIphone else Icons.Rounded.Android,
                            contentDescription = if (l.platform == Proto.PLATFORM_IOS) "iPhone" else "Android",
                            modifier = Modifier.size(14.dp), tint = Party.InkFaint,
                        )
                        Spacer(Modifier.width(4.dp))
                        val detail = l.check?.takeIf { it.heard }?.let { "was ${signed1(it.beforeMs)} ms off, fixed" }
                            ?: "${fmt1(l.rttMs)} ms ping · ${l.bufferMs} ms buffered"
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = Party.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                val check = l.check
                when {
                    check != null && check.heard && l.status != Proto.STATUS_LATE -> Tag("${signed1(check.afterMs)} ms", Party.Lime)
                    check != null && !check.heard -> Tag("Not heard", Party.Coral)
                    l.status == Proto.STATUS_SYNCED -> Tag("In sync", Party.Lime)
                    l.status == Proto.STATUS_LATE -> Tag("Behind", Party.Coral)
                    else -> Tag("Tuning in", Party.Sun)
                }
            }
        }
    }
}

@Composable
private fun SongProgress(positionMs: Long, durationMs: Long) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
        color = Party.Pink, trackColor = Party.GlassStroke,
    )
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(duration(positionMs), style = MaterialTheme.typography.labelSmall, color = Party.InkFaint)
        Text(if (durationMs > 0) duration(durationMs) else "", style = MaterialTheme.typography.labelSmall, color = Party.InkFaint)
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(72.dp)
            .shadow(18.dp, CircleShape, ambientColor = Party.Pink, spotColor = Party.Violet)
            .clip(CircleShape)
            .background(Party.Brand)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(40.dp))
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Party.Glass)
            .border(1.dp, Party.GlassStroke, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

private fun duration(ms: Long): String {
    if (ms <= 0) return ""
    val s = ms / 1000
    return "%d:%02d".format(Locale.US, s / 60, s % 60)
}

// ---- guest (listener) -----------------------------------------------------------------------------------

@Composable
private fun ListenPane(state: ReceiverUiState, hosting: Boolean) {
    val context = LocalContext.current
    when (state.phase) {
        Phase.IDLE -> {
            val discovery = remember { HostDiscovery(context) }
            val hosts by discovery.hosts.collectAsStateWithLifecycle()
            DisposableEffect(discovery) {
                discovery.start()
                onDispose { discovery.stop() }
            }
            // Only needed so the "listening" notification can be shown; joining works either way.
            val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            JoinIdle(hosts, busyHosting = hosting) { address, port, name ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                ReceiverService.join(context, address, port, name)
            }
        }
        Phase.ENDED -> PartyOver(state.message) { ReceiverService.dismiss() }
        else -> Listening(
            state,
            onOffset = { ReceiverService.setOffsetMs(it) },
            onResync = { ReceiverService.resync() },
            onLeave = { ReceiverService.leave(context) },
            onUnmute = { ReceiverService.setMuted(false) },
        )
    }
}

@Composable
internal fun JoinIdle(hosts: List<DiscoveredHost>, busyHosting: Boolean, onJoin: (address: String, port: Int, name: String) -> Unit) {
    var manual by rememberSaveable { mutableStateOf("") }
    var manualError by rememberSaveable { mutableStateOf<String?>(null) }

    fun joinManual() {
        val parsed = parseAddress(manual)
        if (parsed == null) {
            manualError = "That doesn't look right. Use the address on the DJ's screen, like 192.168.43.1"
        } else {
            manualError = null
            onJoin(parsed.first, parsed.second, parsed.first)
        }
    }

    Hero(
        icon = Icons.Rounded.Headphones, active = false,
        title = "Join a party",
        body = "Hop on the same Wi-Fi as the DJ, or on their hotspot, and pick a party.",
    )

    if (busyHosting) {
        Notice("You're the DJ right now. End your party before joining someone else's.")
        return
    }

    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Parties nearby", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
            Radar()
        }
        if (hosts.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Looking for parties on this Wi-Fi…", style = MaterialTheme.typography.bodyMedium, color = Party.InkSoft)
        }
        hosts.forEachIndexed { index, h ->
            if (index > 0) HorizontalDivider(color = Party.GlassStroke)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(h.name)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(h.name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val crowd = when (h.listeners) {
                        0 -> "Be the first one in"
                        1 -> "1 friend listening"
                        else -> "${h.listeners} friends listening"
                    }
                    Text(crowd, style = MaterialTheme.typography.bodySmall, color = Party.InkSoft)
                }
                Spacer(Modifier.width(8.dp))
                GradientButton("Join", { onJoin(h.address, h.port, h.name) }, height = 44.dp)
            }
        }
    }

    GlassCard {
        SectionTitle("Know the address?", null)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = manual,
                onValueChange = {
                    manual = it.trim()
                    manualError = null
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("192.168.43.1", color = Party.InkFaint) },
                isError = manualError != null,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Party.Pink, unfocusedBorderColor = Party.GlassStroke, cursorColor = Party.Pink,
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedContainerColor = Party.Glass, unfocusedContainerColor = Party.Glass,
                    errorBorderColor = Party.Coral, errorContainerColor = Party.Glass, errorTextColor = Color.White,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { joinManual() }),
            )
            Spacer(Modifier.width(12.dp))
            GradientButton("Join", ::joinManual, enabled = manual.isNotBlank(), height = 52.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            manualError ?: "It's on the DJ's screen while the party is on.",
            style = MaterialTheme.typography.bodySmall,
            color = if (manualError != null) Party.Coral else Party.InkSoft,
        )
    }
}

@Composable
internal fun Listening(
    state: ReceiverUiState,
    onOffset: (Int) -> Unit,
    onResync: () -> Unit,
    onLeave: () -> Unit,
    onUnmute: () -> Unit,
) {
    val playing = state.phase == Phase.PLAYING
    val title = when (state.phase) {
        Phase.CONNECTING -> "Knocking on the door…"
        Phase.SYNCING -> "Finding the beat…"
        Phase.RECONNECTING -> "Lost the DJ, reconnecting…"
        else -> if (state.lateBiasMs > 0) "Playing, ${state.lateBiasMs} ms behind" else "You're in sync!"
    }
    val dj = state.hostName.ifBlank { state.hostAddress }
    val output = state.outputName.removePrefix("Bluetooth: ") // the icon already says Bluetooth
    val video = state.video
    if (video != null && state.phase != Phase.CONNECTING) {
        val latest by rememberUpdatedState(state)
        VideoCard(video, hostOffsetUs = { latest.clockOffsetUs }, correctionUs = { latest.correctionUs })
    } else GlassCard(lit = playing) {
        HeroRow(
            icon = if (state.outputIsBluetooth) Icons.Rounded.Bluetooth else Icons.Rounded.Headphones,
            active = playing, level = state.level, title = title,
            body = "DJ: $dj" + if (output.isNotBlank()) " · $output" else "",
        )
        if (playing) {
            if (state.songTitle.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Party.Pink, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(state.songTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = if (state.songPlaying) state.songArtist else "Paused by the DJ"
                        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Party.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Equalizer(state.level)
            if (state.soundChecked) {
                Spacer(Modifier.height(12.dp))
                Tag("Sound-checked by the DJ", Party.Lime)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val tight = abs(state.syncErrorMs) < 5f && state.lateBiasMs == 0
                Stat("${signed1(state.syncErrorMs)} ms", "sync", Modifier.weight(1f), accent = if (tight) Party.Lime else Party.Sun)
                Stat("${fmt1(state.rttMs)} ms", "ping", Modifier.weight(1f))
                Stat("${state.bufferMs} ms", "buffer", Modifier.weight(1f))
                Stat("${fmt1(state.lossPercent)}%", "drops", Modifier.weight(1f), accent = if (state.lossPercent < 1f) Color.White else Party.Sun)
            }
        }
    }

    state.message?.let { Notice(it) }

    if (state.muted) {
        Notice("Muted: another app took over the sound, or your headphones disconnected.", icon = Icons.AutoMirrored.Rounded.VolumeOff) {
            Spacer(Modifier.height(10.dp))
            GhostButton("Unmute", onUnmute, height = 44.dp)
        }
    }
    if (state.lateBiasMs > 0 && playing) {
        Notice(
            "This phone needs more runway than the DJ's ${state.playoutDelayMs} ms sync cushion, so it's " +
                "${state.lateBiasMs} ms behind the crowd. Ask the DJ for a bigger cushion" +
                if (state.offsetMs < 0) ", or slide the offset back toward 0." else ".",
        )
    }

    OffsetCard(state, onOffset)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GhostButton("Re-sync", onResync, Modifier.weight(1f), icon = Icons.Rounded.Sync)
        GradientButton("Leave", onLeave, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.Logout, brush = Party.Stop, glow = Party.Coral, height = 56.dp)
    }
}

/** The manual delay slider: -500..+500 ms, applied on release, plus fine nudges. */
@Composable
private fun OffsetCard(state: ReceiverUiState, onOffset: (Int) -> Unit) {
    // While the thumb is being dragged show the pending value; otherwise follow the receiver
    // (which also switches the value when the output device changes).
    var dragging by remember { mutableStateOf(false) }
    var pending by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) pending.roundToInt() else state.offsetMs

    fun apply(ms: Int) = onOffset(ms.coerceIn(-500, 500))

    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Tune, null, tint = Party.Blue)
            Spacer(Modifier.width(10.dp))
            Text("Fine-tune the beat", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
            Text(
                (if (shown > 0) "+" else "") + "$shown ms",
                style = MaterialTheme.typography.headlineSmall.copy(brush = Party.Brand), fontWeight = FontWeight.Bold,
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
            colors = SliderDefaults.colors(
                thumbColor = Color.White, activeTrackColor = Party.Pink, inactiveTrackColor = Color.White.copy(alpha = 0.16f),
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("−500 earlier", style = MaterialTheme.typography.labelSmall, color = Party.InkFaint)
            Text("later +500", style = MaterialTheme.typography.labelSmall, color = Party.InkFaint)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (step in intArrayOf(-10, -1)) Nudge("$step", Modifier.weight(1f)) { apply(state.offsetMs + step) }
            Nudge("Reset", Modifier.weight(1.3f), enabled = state.offsetMs != 0) { apply(0) }
            for (step in intArrayOf(1, 10)) Nudge("+$step", Modifier.weight(1f)) { apply(state.offsetMs + step) }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Sounds late next to the others (hello, Bluetooth)? Slide toward −. Sounds early? Slide toward +. " +
                "Saved for each speaker or pair of earbuds" +
                (if (state.outputName.isNotBlank()) " (now: ${state.outputName})." else "."),
            style = MaterialTheme.typography.bodySmall, color = Party.InkSoft,
        )
    }
}

@Composable
internal fun PartyOver(message: String?, onDismiss: () -> Unit) {
    Hero(Icons.Rounded.Celebration, active = false, title = "Party's over", body = message ?: "You left the party.")
    GradientButton("Find another party", onDismiss, Modifier.fillMaxWidth(), icon = Icons.Rounded.Headphones)
}

// ---- small shared pieces --------------------------------------------------------------------------------

@Composable
private fun Hero(icon: ImageVector, active: Boolean, title: String, body: String) {
    GlassCard(lit = active) { HeroRow(icon, active, level = 0f, title = title, body = body) }
}

@Composable
private fun HeroRow(icon: ImageVector, active: Boolean, level: Float, title: String, body: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PulseOrb(icon, active, size = 60.dp, level = level)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Party.InkSoft)
        }
    }
}

@Composable
private fun SectionTitle(title: String, blurb: String?) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color.White)
    if (blurb != null) {
        Spacer(Modifier.height(2.dp))
        Text(blurb, style = MaterialTheme.typography.bodySmall, color = Party.InkSoft)
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Caption(text: String) {
    Text(text, Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.bodySmall, color = Party.InkSoft)
}

@Composable
private fun InfoPill(text: String, icon: ImageVector? = null) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Party.Glass)
            .border(1.dp, Party.GlassStroke, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(14.dp), tint = Party.Blue)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun Nudge(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    GhostButton(label, onClick, modifier, enabled = enabled, height = 46.dp)
}

// ---- helpers ----------------------------------------------------------------------------------------

private fun Context.hasPermission(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

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
