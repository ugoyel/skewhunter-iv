package io.github.ugoyel.hearby.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.ugoyel.hearby.core.PlayerAction
import io.github.ugoyel.hearby.core.PlayerReport
import io.github.ugoyel.hearby.core.VideoSyncController
import io.github.ugoyel.hearby.core.VideoTimeline
import org.json.JSONArray
import org.json.JSONObject

/** What the player wants to tell the screen. */
data class PlayerNotice(val title: String? = null, val error: String? = null, val offErrorMs: Float? = null)

/**
 * YouTube's official embedded player, steered onto the party's timeline by [VideoSyncController].
 *
 * Only YouTube's own IFrame Player API is used (load, play, pause, seek, speed), and the player is
 * shown on screen. Playback stops when the app leaves the screen, as YouTube's rules require.
 */
class YouTubeWebPlayer(context: Context) {
    val webView = WebView(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    private val controller = VideoSyncController()
    private var running = false
    private var resumed = true
    private var startedAtMs = 0L
    private var everReady = false

    /** The party's video, as last received. */
    var timeline: VideoTimeline? = null

    /** DJ clock minus this phone's clock, µs (0 on the DJ phone). */
    var hostOffsetUs: () -> Long = { 0L }

    /** This phone's own correction (slider + sound check), µs; positive plays later. */
    var correctionUs: () -> Long = { 0L }

    var onNotice: (PlayerNotice) -> Unit = {}

    /** The player ended the video (used on the DJ phone). */
    var onEnded: () -> Unit = {}

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUp() {
        webView.setBackgroundColor(Color.BLACK)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // Guests' players must start when the DJ presses play, not when each guest taps.
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            // A tap on YouTube's logo or title opens YouTube itself, never a page inside Hearby.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                try {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    // no browser
                }
                return true
            }
        }
        webView.addJavascriptInterface(Bridge(), "HearbyAndroid")
        val html = webView.context.assets.open("youtube_player.html").bufferedReader().use { it.readText() }
        // An https origin of our own: the player's requests then carry a Referer naming the app.
        webView.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null)
    }

    init {
        setUp()
    }

    fun start() {
        if (running) return
        running = true
        startedAtMs = System.currentTimeMillis()
        handler.post(tick)
    }

    fun setResumed(on: Boolean) {
        resumed = on
        if (on) {
            webView.onResume()
        } else {
            js("hearbyPause()") // YouTube does not allow playing in the background
            webView.onPause()
        }
    }

    fun release() {
        running = false
        handler.removeCallbacksAndMessages(null)
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            webView.evaluateJavascript("hearbyStatus()") { raw -> onStatus(raw) }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun onStatus(raw: String?) {
        if (raw == null || raw == "null") return
        val json = try {
            JSONObject(JSONArray("[$raw]").getString(0)) // evaluateJavascript returns a JSON-encoded string
        } catch (e: Exception) {
            return
        }
        val ready = json.optBoolean("ready")
        if (ready) everReady = true
        if (!everReady && System.currentTimeMillis() - startedAtMs > OFFLINE_AFTER_MS && timeline != null) {
            onNotice(PlayerNotice(error = "Can't reach YouTube. Every phone needs internet for YouTube mode."))
        }
        // When the page read the player, on this phone's monotonic clock.
        val ageUs = (System.currentTimeMillis() - json.optDouble("at", System.currentTimeMillis().toDouble())) * 1000
        val nowUs = System.nanoTime() / 1000
        val rates = json.optJSONArray("rates")?.let { a -> DoubleArray(a.length()) { a.optDouble(it, 1.0) } } ?: doubleArrayOf(1.0)
        val report = PlayerReport(
            ready = ready,
            state = json.optInt("s", VideoSyncController.UNSTARTED),
            positionUs = (json.optDouble("t", 0.0) * 1e6).toLong(),
            measuredAtUs = nowUs - ageUs.toLong().coerceAtLeast(0),
            rate = json.optDouble("r", 1.0),
            availableRates = rates,
            videoId = json.optString("id", ""),
            error = json.optInt("err", 0),
        )
        val t = timeline
        // The player's clock already reports what is audible now (browsers delay the picture by the
        // speaker or Bluetooth latency for lip-sync), so no output latency is added here. What the
        // device under-reports is what the sound check and the slider measured for music: the same
        // audio hardware, so the same correction.
        val hostNow = nowUs + hostOffsetUs() - correctionUs() // playing later = looking at an earlier moment
        val target = t?.positionAt(hostNow) ?: 0L
        val startsIn = t?.startsInUs(hostNow) ?: 0L
        for (a in controller.step(nowUs, t, target, startsIn, report, mayStart = resumed && visibleEnough())) execute(a)
        val notice = if (report.error != 0) {
            PlayerNotice(error = describe(report.error))
        } else {
            PlayerNotice(title = json.optString("title").takeIf { it.isNotBlank() }, offErrorMs = controller.lastErrorUs?.let { Math.round(it / 1000.0).toFloat() })
        }
        if (notice != lastNotice) {
            lastNotice = notice
            onNotice(notice)
        }
    }

    private var lastNotice: PlayerNotice? = null

    private fun execute(a: PlayerAction) {
        when (a) {
            is PlayerAction.Load -> js("hearbyLoad('${a.videoId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }}')")
            is PlayerAction.Play -> if (a.afterUs > 0) handler.postDelayed({ js("hearbyPlay()") }, a.afterUs / 1000) else js("hearbyPlay()")
            PlayerAction.Pause -> js("hearbyPause()")
            is PlayerAction.Seek -> js("hearbySeek(${a.seconds})")
            is PlayerAction.Rate -> {
                js("hearbyRate(${a.rate})")
                if (a.forUs > 0) handler.postDelayed({ js("hearbyRate(1)") }, a.forUs / 1000)
            }
        }
    }

    private fun js(code: String) = webView.evaluateJavascript(code, null)

    /** YouTube allows automatic playback only while at least half of the player is on screen. */
    private fun visibleEnough(): Boolean {
        val r = Rect()
        if (!webView.isShown || !webView.getGlobalVisibleRect(r)) return false
        val area = webView.width.toLong() * webView.height
        return area > 0 && r.width().toLong() * r.height() * 2 >= area
    }

    private inner class Bridge {
        @JavascriptInterface
        fun event(type: String, data: String) {
            if (type == "state" && data == "0") handler.post { onEnded() }
            if (type == "offline") handler.post { onNotice(PlayerNotice(error = "Can't reach YouTube. Every phone needs internet for YouTube mode.")) }
        }
    }

    companion object {
        const val BASE_URL = "https://io.github.ugoyel.hearby/"
        private const val TICK_MS = 200L
        private const val OFFLINE_AFTER_MS = 12_000L

        fun describe(code: Int): String = when (code) {
            2 -> "That doesn't look like a valid YouTube video."
            5 -> "This phone can't play that video."
            100 -> "That video was removed or is private."
            101, 150 -> "The video's owner doesn't allow it to play in other apps. Pick another one."
            152, 153 -> "YouTube wouldn't play inside Hearby on this phone. Update Android System WebView and try again."
            else -> "YouTube couldn't play that video (error $code)."
        }
    }
}

/**
 * The party's YouTube video, playing in sync on this phone. Shown 16:9 at full width (YouTube asks
 * for at least 200×200 px).
 */
@Composable
fun SyncedYouTubePlayer(
    timeline: VideoTimeline?,
    hostOffsetUs: () -> Long,
    correctionUs: () -> Long,
    modifier: Modifier = Modifier,
    onNotice: (PlayerNotice) -> Unit = {},
    onEnded: () -> Unit = {},
) {
    val context = LocalContext.current
    val player = remember { YouTubeWebPlayer(context) }
    SideEffect {
        player.timeline = timeline
        player.hostOffsetUs = hostOffsetUs
        player.correctionUs = correctionUs
        player.onNotice = onNotice
        player.onEnded = onEnded
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> player.setResumed(true)
                Lifecycle.Event.ON_PAUSE -> player.setResumed(false)
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        player.start()
        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
        }
    }
    AndroidView(factory = { player.webView }, modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f))
}
