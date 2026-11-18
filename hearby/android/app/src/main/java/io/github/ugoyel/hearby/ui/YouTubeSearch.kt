package io.github.ugoyel.hearby.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.ugoyel.hearby.core.YouTubeLinks

/**
 * YouTube itself (its mobile site), right inside the DJ screen: the same search, suggestions,
 * filters and results as the YouTube app. Tapping a video doesn't open it here: it goes to the
 * party ([onPick]) and the page steps back to the results. Signing in to YouTube here (optional)
 * makes the results personal, like in the app.
 */
@Composable
fun YouTubeSearch(onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pick by rememberUpdatedState(onPick)
    val web = remember { searchWebView(context) { id -> pick(id) } }
    DisposableEffect(web) {
        onDispose {
            web.stopLoading()
            web.destroy()
        }
    }
    AndroidView(factory = { web }, modifier = modifier.fillMaxWidth().height(560.dp))
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun searchWebView(context: Context, onPick: (String) -> Unit): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = true
    CookieManager.getInstance().setAcceptCookie(true)
    // Scrolling the results scrolls the results, not the DJ screen around them.
    setOnTouchListener { v, e ->
        if (e.action == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
        false
    }
    webViewClient = object : WebViewClient() {
        private var lastPicked: String? = null

        /** A video was opened (YouTube changes pages without reloading): send it to the party. */
        private fun check(view: WebView, url: String?): Boolean {
            val id = pickedVideo(url) ?: return false
            view.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.muted=true;v.pause();})", null)
            if (view.canGoBack()) view.goBack() else view.loadUrl(HOME)
            if (id != lastPicked) onPick(id)
            lastPicked = id
            view.postDelayed({ lastPicked = null }, 1500)
            return true
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (check(view, url.toString())) return true
            val host = url.host.orEmpty()
            // Stay on YouTube (and Google's sign-in); no jumping out to the YouTube app or ads.
            return !(url.scheme == "https" && (host.endsWith("youtube.com") || host.endsWith("google.com") || host.endsWith("gstatic.com")))
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (!check(view, url)) super.onPageStarted(view, url, favicon)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            check(view, url)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean = true // the screen keeps going
    }
    loadUrl(HOME)
}

private const val HOME = "https://m.youtube.com/"

/** The video a YouTube page address opens (a watch page or a short), or null for anything else. */
internal fun pickedVideo(url: String?): String? {
    if (url == null || !(url.contains("/watch") || url.contains("/shorts/"))) return null
    return YouTubeLinks.videoId(url)
}
