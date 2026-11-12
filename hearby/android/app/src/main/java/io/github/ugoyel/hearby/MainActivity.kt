package io.github.ugoyel.hearby

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import io.github.ugoyel.hearby.core.YouTubeLinks
import io.github.ugoyel.hearby.host.PartyHostService
import io.github.ugoyel.hearby.ui.HearbyTheme
import io.github.ugoyel.hearby.ui.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so the system bars always get light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (savedInstanceState == null) takeSharedSongs(intent)
        setContent {
            HearbyTheme {
                MainScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeSharedSongs(intent)
    }

    /** Songs shared to Hearby or opened with it go to the DJ's queue; a YouTube link plays on every phone. */
    private fun takeSharedSongs(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val id = YouTubeLinks.videoId(text)
            if (id != null) {
                PartyHostService.queueVideo(id)
            } else {
                Toast.makeText(this, "Hearby can play YouTube links. That doesn't look like one.", Toast.LENGTH_LONG).show()
            }
            return
        }
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) PartyHostService.queueSongs(uris)
    }
}
