package app.pttradio.ui

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pttradio.App
import app.pttradio.R
import app.pttradio.RadioService
import app.pttradio.core.chName
import org.json.JSONArray

/**
 * Home-screen widget, one per group. Android widgets can only report a tap, never "finger down / finger up",
 * so the widget opens the talk key over the home screen and the key there is held to speak.
 */
class PttWidget : AppWidgetProvider() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == ACTION_PINNED) {
            val id = i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val gid = i.getStringExtra("gid")
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID && gid != null) {
                prefs(c).edit().putString("w_$id", gid).apply()
                render(c, AppWidgetManager.getInstance(c), id)
            }
            return
        }
        super.onReceive(c, i)
    }

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) { ids.forEach { render(c, m, it) } }

    override fun onDeleted(c: Context, ids: IntArray) { prefs(c).edit().apply { ids.forEach { remove("w_$it") } }.apply() }

    companion object {
        const val ACTION_PINNED = "app.pttradio.WIDGET_PINNED"
        private fun prefs(c: Context) = c.getSharedPreferences("radio", Context.MODE_PRIVATE)

        private fun groupName(c: Context, gid: String?): String? {
            if (gid == null) return null
            (c.applicationContext as App).core.state.value.groups.firstOrNull { it.id == gid }?.let { return it.name }
            return try {
                val a = JSONArray(prefs(c).getString("groups", "[]"))
                (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.getString("id") == gid }?.getString("name")
            } catch (e: Exception) { null }
        }

        fun render(c: Context, m: AppWidgetManager, id: Int) {
            val gid = prefs(c).getString("w_$id", null)
            val v = RemoteViews(c.packageName, R.layout.widget_ptt)
            v.setTextViewText(R.id.widget_group, groupName(c, gid) ?: "Radio")
            val open = Intent(c, QuickTalkActivity::class.java).putExtra("gid", gid)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            m.updateAppWidget(id, v)
        }

        /** Asks the launcher to place a widget for [gid]. Returns false when the launcher cannot do that. */
        fun pin(c: Context, gid: String): Boolean {
            val m = AppWidgetManager.getInstance(c)
            if (!m.isRequestPinAppWidgetSupported) return false
            val done = PendingIntent.getBroadcast(c, gid.hashCode(), Intent(c, PttWidget::class.java).setAction(ACTION_PINNED).putExtra("gid", gid),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            return m.requestPinAppWidget(ComponentName(c, PttWidget::class.java), null, done)
        }
    }
}

/** The talk key shown over the home screen when a widget is tapped. Hold to speak, tap outside to close. */
class QuickTalkActivity : ComponentActivity() {
    private val app get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (app.prefs.getString("me", null) == null || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // not set up yet, or no microphone permission: the main app handles both
            startActivity(Intent(this, MainActivity::class.java)); finish(); return
        }
        tune(intent)
        setContent {
            val s by app.core.state.collectAsState()
            val g = s.group
            Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { finish() }, contentAlignment = Alignment.BottomCenter) {
                Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(C.bg).border(1.dp, C.line, RoundedCornerShape(16.dp))
                    .clickable(remember { MutableInteractionSource() }, null) {}.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g?.name ?: "No group", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Label(if (g == null) "Open the app to pick a group" else chName(s.tunedCh), color = C.amber, size = 11.sp)
                        }
                        val (col, text) = when {
                            s.transmitting -> C.red to "Transmitting"
                            s.talker != null -> C.green to "RX ${s.talker}"
                            s.notice != null -> C.amber to s.notice!!
                            s.netUp -> C.green to "Internet"
                            s.nearbyUp -> C.amber to "Nearby"
                            else -> C.red to "No link"
                        }
                        Dot(col); Spacer(Modifier.width(8.dp)); Label(text, color = C.text, size = 11.sp)
                    }
                    if (g != null) PttKey(s.transmitting, s.talker != null, Modifier.fillMaxWidth(), { app.core.pttDown() }, { app.core.pttUp() })
                    else Btn("Open PTT Radio", Modifier.fillMaxWidth(), primary = true) { startActivity(Intent(this@QuickTalkActivity, MainActivity::class.java)); finish() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); tune(intent) }

    private fun tune(i: Intent?) { i?.getStringExtra("gid")?.let { app.core.tuneGroup(it) } }

    override fun onResume() { super.onResume(); app.onScreen.value = true; RadioService.start(this, mic = true) }
    override fun onPause() { app.core.pttUp(); app.onScreen.value = false; super.onPause() }
    override fun onStop() { super.onStop(); finish() }
}
