package io.github.ugoyel.hearby.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
    private val appContext = context.applicationContext

    /** What goes on screen. The WebView inside is replaced if its renderer process dies. */
    val container = FrameLayout(appContext).apply {
        setBackgroundColor(Color.BLACK)
        // YouTube may only play while the player is on screen, and a phone lying on a table would
        // lock itself after a minute and pause the video. So the screen stays on while it shows.
        keepScreenOn = true
    }
    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var controller = VideoSyncController()
    private var running = false
    private var resumed = true
    private var startedAtMs = 0L
    private var everReady = false
    private var lastAnswerMs = 0L
    private val rendererLosses = ArrayDeque<Long>()

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
    private fun newWebView(): WebView {
        // Debug builds can be inspected from a desktop Chrome (chrome://inspect).
        if (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        val view = WebView(appContext)
        view.setBackgroundColor(Color.BLACK)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        // Guests' players must start when the DJ presses play, not when each guest taps.
        view.settings.mediaPlaybackRequiresUserGesture = false
        view.webChromeClient = WebChromeClient()
        view.webViewClient = object : WebViewClient() {
            // A tap on YouTube's logo or title opens YouTube itself, never a page inside Hearby.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                try {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    // no browser
                }
                return true
            }

            // The page's process crashed or the system reclaimed it. Unhandled, Android would take
            // the whole app (and the party's music) down with it; instead, start a fresh player.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                handler.post { replace(view) }
                return true
            }
        }
        view.addJavascriptInterface(Bridge(), "HearbyAndroid")
        val html = appContext.assets.open("youtube_player.html").bufferedReader().use { it.readText() }
        // An https origin of our own: the player's requests then carry a Referer naming the app.
        view.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null)
        container.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (!resumed) view.onPause()
        return view
    }

    init {
        webView = newWebView()
    }

    /** Swap a WebView whose renderer is gone for a new one, unless it keeps happening. */
    private fun replace(dead: WebView) {
        if (dead !== webView) return
        container.removeView(dead)
        dead.destroy()
        webView = null
        val now = SystemClock.elapsedRealtime()
        rendererLosses.addLast(now)
        while (rendererLosses.first() < now - RENDERER_WINDOW_MS) rendererLosses.removeFirst()
        if (!running) return
        if (rendererLosses.size > MAX_RENDERER_LOSSES) {
            showNotice(PlayerNotice(error = "The video player keeps stopping on this phone. Update Android System WebView, then restart Hearby."))
            return
        }
        controller = VideoSyncController() // a new player starts from nothing
        everReady = false
        startedAtMs = System.currentTimeMillis()
        webView = newWebView()
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
            webView?.onResume()
        } else {
            js("hearbyPause()") // YouTube does not allow playing in the background
            webView?.onPause()
        }
    }

    fun release() {
        running = false
        handler.removeCallbacksAndMessages(null)
        (container.parent as? ViewGroup)?.removeView(container)
        container.removeAllViews()
        webView?.destroy()
        webView = null
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            // A page that never answers at all (its process hung) cannot report anything itself.
            val now = System.currentTimeMillis()
            if (timeline != null && !everReady && now - startedAtMs > OFFLINE_AFTER_MS && now - lastAnswerMs > NO_ANSWER_MS) {
                showNotice(PlayerNotice(error = NOT_STARTED))
            }
            webView?.evaluateJavascript("hearbyStatus()") { raw -> onStatus(raw) }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun onStatus(raw: String?) {
        if (raw == null || raw == "null") {
            // The page itself never came up (it is bundled with the app, so this is not the network).
            if (timeline != null && System.currentTimeMillis() - startedAtMs > OFFLINE_AFTER_MS) showNotice(PlayerNotice(error = NOT_STARTED))
            return
        }
        lastAnswerMs = System.currentTimeMillis()
        val json = try {
            JSONObject(JSONArray("[$raw]").getString(0)) // evaluateJavascript returns a JSON-encoded string
        } catch (e: Exception) {
            return
        }
        val ready = json.optBoolean("ready")
        if (ready) everReady = true
        if (!everReady && System.currentTimeMillis() - startedAtMs > OFFLINE_AFTER_MS && timeline != null) {
            showNotice(PlayerNotice(error = OFFLINE))
            return
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
        showNotice(notice)
    }

    /** Tell the screen, but only when something changed. */
    private fun showNotice(notice: PlayerNotice) {
        if (notice == lastNotice) return
        lastNotice = notice
        onNotice(notice)
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

    private fun js(code: String) = webView?.evaluateJavascript(code, null)

    /** YouTube allows automatic playback only while at least half of the player is on screen. */
    private fun visibleEnough(): Boolean {
        val r = Rect()
        if (!container.isShown || !container.getGlobalVisibleRect(r)) return false
        val area = container.width.toLong() * container.height
        return area > 0 && r.width().toLong() * r.height() * 2 >= area
    }

    private inner class Bridge {
        @JavascriptInterface
        fun event(type: String, data: String) {
            if (type == "state" && data == "0") handler.post { onEnded() }
            if (type == "offline") handler.post { showNotice(PlayerNotice(error = OFFLINE)) }
        }
    }

    companion object {
        const val BASE_URL = "https://io.github.ugoyel.hearby/"
        private const val TICK_MS = 200L
        private const val OFFLINE_AFTER_MS = 12_000L
        private const val NO_ANSWER_MS = 2_000L
        private const val NOT_STARTED = "The video player didn't start on this phone. Update Android System WebView and try again."
        private const val RENDERER_WINDOW_MS = 60_000L
        private const val MAX_RENDERER_LOSSES = 3
        private const val OFFLINE = "Can't reach YouTube. Every phone needs internet for YouTube mode."

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
    AndroidView(factory = { player.container }, modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f))
}
