package com.cleartether.app.core.session

import com.cleartether.app.core.proxy.TrafficMeter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

/** UI-facing snapshot of the current (or last) sharing session. */
data class SessionState(
    val active: Boolean = false,
    /** How long the current session has been running. */
    val elapsedMs: Long = 0,
    val bytesUploaded: Long = 0,
    val bytesDownloaded: Long = 0,
) {
    val totalBytes: Long get() = bytesUploaded + bytesDownloaded
}

/**
 * Tracks cumulative throughput and connection duration of a sharing session. ClearTether is free,
 * so nothing is limited. This only powers the "this session" stats and the notification.
 *
 * Hot path: [onTraffic] is called from every relay pump, so it only touches atomics. A 1 Hz
 * ticker publishes [state].
 */
class TetherSessionManager(private val elapsedClock: () -> Long) : TrafficMeter {

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val uploaded = AtomicLong()
    private val downloaded = AtomicLong()
    @Volatile private var active = false
    @Volatile private var startedAt = 0L
    private var ticker: Job? = null

    /** Starts a new session; [scope] hosts the 1 Hz ticker (the tether service scope). */
    fun start(scope: CoroutineScope) {
        if (active) return
        uploaded.set(0)
        downloaded.set(0)
        startedAt = elapsedClock()
        active = true
        publish()
        ticker = scope.launch {
            while (isActive && active) {
                delay(TICK_MS)
                publish()
            }
        }
    }

    /** Stops the session, keeping its final totals visible. Safe to call twice. */
    fun stop() {
        if (!active) return
        publish()
        active = false
        ticker?.cancel()
        ticker = null
        _state.value = _state.value.copy(active = false)
    }

    override fun onTraffic(client: InetAddress, uploaded: Long, downloaded: Long) {
        if (!active) return
        if (uploaded > 0) this.uploaded.addAndGet(uploaded)
        if (downloaded > 0) this.downloaded.addAndGet(downloaded)
    }

    private fun publish() {
        _state.value = SessionState(
            active = active,
            elapsedMs = if (active) elapsedClock() - startedAt else _state.value.elapsedMs,
            bytesUploaded = uploaded.get(),
            bytesDownloaded = downloaded.get(),
        )
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
