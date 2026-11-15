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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
 * Home-screen widget, one per group. Android widgets only report taps, so a tap starts talking and opens
 * the talk key over the home screen to confirm, stop or keep holding.
 */
class PttWidget : AppWidgetProvider() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == ACTION_PINNED) {
            val id = i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val gid = i.getStringExtra("gid")
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID && gid != null) assign(c, id, gid)
            return
        }
        super.onReceive(c, i)
    }

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) { ids.forEach { render(c, m, it) } }

    override fun onDeleted(c: Context, ids: IntArray) { prefs(c).edit().apply { ids.forEach { remove("w_$it") } }.apply() }

    companion object {
        const val ACTION_PINNED = "app.pttradio.WIDGET_PINNED"
        private fun prefs(c: Context) = c.getSharedPreferences("radio", Context.MODE_PRIVATE)

        /** The chat's name as the person sees it: the group name, or the other person's name for a one-to-one chat. */
        private fun groupName(c: Context, gid: String?): String? {
            if (gid == null) return null
            val st = (c.applicationContext as App).core.state.value
            st.groups.firstOrNull { it.id == gid }?.let { return st.titleOf(it) }
            return try {
                val me = org.json.JSONObject(prefs(c).getString("me", "{}")).optString("id")
                val a = JSONArray(prefs(c).getString("groups", "[]"))
                val o = (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.getString("id") == gid } ?: return null
                if (o.optBoolean("direct")) {
                    val m = o.getJSONArray("members")
                    (0 until m.length()).map { m.getJSONObject(it) }.firstOrNull { it.getString("id") != me }?.getString("name") ?: o.getString("name")
                } else o.getString("name")
            } catch (e: Exception) { null }
        }

        /** Links a widget to a chat and redraws it. */
        fun assign(c: Context, id: Int, gid: String) {
            prefs(c).edit().putString("w_$id", gid).apply()
            render(c, AppWidgetManager.getInstance(c), id)
        }

        fun isAssigned(c: Context, id: Int) = prefs(c).getString("w_$id", null) != null

        /** Redraw every widget, e.g. after a group was renamed. */
        fun refreshAll(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            m.getAppWidgetIds(ComponentName(c, PttWidget::class.java)).forEach { render(c, m, it) }
        }

        fun render(c: Context, m: AppWidgetManager, id: Int) {
            val gid = prefs(c).getString("w_$id", null)
            val v = RemoteViews(c.packageName, R.layout.widget_ptt)
            v.setTextViewText(R.id.widget_group, groupName(c, gid) ?: "Choose chat")
            val open = Intent(c, QuickTalkActivity::class.java).putExtra("gid", gid).putExtra("start", true)
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

/**
 * Opened by a widget tap. Talking starts at once; tap the key within 5 seconds to keep the microphone open,
 * otherwise it closes by itself (protects against pocket taps). After that a tap on the key stops, a tap
 * starts again, and holding the key works as a normal push-to-talk.
 */
class QuickTalkActivity : ComponentActivity() {
    private val app get() = application as App
    private var armedUntil by mutableLongStateOf(0L)
    private var closing by mutableStateOf(false)

    companion object { const val CONFIRM_MS = 5_000L; const val HOLD_MS = 600L }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (app.prefs.getString("me", null) == null || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // not set up yet, or no microphone permission: the main app handles both
            startActivity(Intent(this, MainActivity::class.java)); finish(); return
        }
        handle(intent)
        darkBars()
        setContent {
            val s by app.core.state.collectAsState()
            val g = s.group
            val now by produceState(System.currentTimeMillis()) { while (true) { delay(100); value = System.currentTimeMillis() } }
            val left = ((armedUntil - now).coerceAtLeast(0) + 999) / 1000
            // nobody confirmed in time: close the microphone and the panel
            LaunchedEffect(armedUntil, now >= armedUntil) {
                if (armedUntil > 0 && System.currentTimeMillis() >= armedUntil) {
                    armedUntil = 0
                    app.core.pttUp()
                    closing = true
                    delay(1200)
                    finish()
                }
            }
            var downAt by remember { mutableLongStateOf(0L) }
            var wasTx by remember { mutableStateOf(false) }
            var wasArmed by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { app.core.pttUp(); finish() }.safeDrawingPadding(), contentAlignment = Alignment.Center) {
                Column(Modifier.fillMaxWidth().padding(20.dp).clip(RoundedCornerShape(18.dp)).background(C.bg).border(1.dp, C.line, RoundedCornerShape(18.dp))
                    .clickable(remember { MutableInteractionSource() }, null) {}.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g?.let { s.titleOf(it) } ?: "No chat", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            if (g == null) Label("Open the app to pick a chat", color = C.dim, size = 11.sp)
                            else if (s.tunedCh != 0) Label("Channel ${s.tunedCh}", color = C.accent, size = 11.sp)
                        }
                        val (col, text) = when {
                            s.recording -> C.amber to "No link"
                            s.transmitting -> C.red to "On air"
                            s.talker != null -> C.green to "RX ${s.talker}"
                            s.netUp -> C.green to "Internet"
                            s.nearbyUp -> C.amber to "Nearby"
                            else -> C.red to "No link"
                        }
                        Dot(col); Spacer(Modifier.width(8.dp)); Label(text, color = C.text, size = 11.sp)
                    }
                    if (g == null) Btn("Open ZeroBar", Modifier.fillMaxWidth(), primary = true) { startActivity(Intent(this@QuickTalkActivity, MainActivity::class.java)); finish() }
                    else PttKey(
                        s.transmitting, s.talker != null, Modifier.fillMaxWidth(), app.core.level, s.recording,
                        title = when { closing -> "MIC\nCLOSED"; s.recording -> "RECORDING"; s.transmitting -> "TALKING"; s.talker != null -> "LISTENING"; else -> "TAP\nTO TALK" },
                        hint = when {
                            closing -> "No tap within 5 s"
                            armedUntil > 0 && s.transmitting -> "Tap now to keep talking · $left"
                            s.transmitting -> "Tap to stop"
                            s.talker != null -> "Channel busy"
                            else -> "Tap to talk · hold for push-to-talk"
                        },
                        onDown = {
                            downAt = System.currentTimeMillis()
                            wasTx = app.core.state.value.transmitting
                            wasArmed = armedUntil > 0
                            armedUntil = 0
                            closing = false
                            if (!wasTx) app.core.pttDown()
                        },
                        onUp = {
                            val held = System.currentTimeMillis() - downAt >= HOLD_MS
                            when {
                                !wasTx -> if (held) app.core.pttUp()          // held from silence: classic push-to-talk
                                wasArmed -> if (held) app.core.pttUp()        // this tap confirmed the widget's transmission
                                else -> app.core.pttUp()                      // tap while talking: stop
                            }
                        })
                    Label(if (g != null) "Tap outside to close" else "", Modifier.align(Alignment.CenterHorizontally), color = C.faint, size = 10.sp)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: Intent?) {
        val gid = i?.getStringExtra("gid")
        if (gid != null) app.core.tuneGroup(gid)
        if (i?.getBooleanExtra("start", false) == true && !app.core.state.value.transmitting) {
            closing = false
            app.core.pttDown()
            armedUntil = System.currentTimeMillis() + CONFIRM_MS
        }
        i?.removeExtra("start")
    }

    override fun onResume() { super.onResume(); app.onScreen.value = true; RadioService.start(this, mic = true) }
    override fun onPause() { app.core.pttUp(); app.onScreen.value = false; super.onPause() }
    override fun onStop() { super.onStop(); finish() }
}

/**
 * Shown when a widget is added from the home screen's widget list: asks which group or person the widget is for.
 * Widgets placed from inside the app already know their chat and skip this.
 */
class WidgetConfigActivity : ComponentActivity() {
    private val app get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        if (PttWidget.isAssigned(this, id)) { done(id); return }
        if (app.prefs.getString("me", null) == null) { startActivity(Intent(this, MainActivity::class.java)); finish(); return }
        darkBars()
        setContent {
            val s by app.core.state.collectAsState()
            val near by app.nearby.info.collectAsState()
            val now by produceState(System.currentTimeMillis()) { while (true) { delay(1000); value = System.currentTimeMillis() } }
            Column(Modifier.fillMaxSize().background(C.bg).safeDrawingPadding()) {
                Text("Widget for which chat?", Modifier.padding(start = 18.dp, top = 18.dp, bottom = 4.dp), color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Tapping the widget starts talking to this group or person.", Modifier.padding(start = 18.dp, bottom = 10.dp), color = C.dim, fontSize = 13.sp)
                if (s.groups.isEmpty() && onAppNow(s, now).isEmpty())
                    Text("No chats yet. Open ZeroBar, start a chat, then add the widget again.", Modifier.padding(18.dp), color = C.dim, fontSize = 14.sp)
                ChatPicker(s, near, now,
                    onChat = { g -> PttWidget.assign(this@WidgetConfigActivity, id, g.id); done(id) },
                    onPerson = { u -> app.core.openDirect(u.id, tune = false) { gid -> runOnUiThread { PttWidget.assign(this@WidgetConfigActivity, id, gid); done(id) } } })
            }
        }
    }

    private fun done(id: Int) {
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }
}
