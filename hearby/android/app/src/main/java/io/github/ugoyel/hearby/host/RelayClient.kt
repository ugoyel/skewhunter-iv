package io.github.ugoyel.hearby.host

import android.util.Log
import io.github.ugoyel.hearby.core.RelayClock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The DJ phone's line to the internet relay (hearby/relay), so phones on other networks can join
 * YouTube parties at [partyUrl]. Syncs to the relay's clock ([clock]) and passes on the party's
 * state, which the caller gives with times already on that clock. Reconnects by itself.
 */
class RelayClient(
    baseUrl: String,
    private val streamId: Long,
    private val hostName: String,
    private val nowUs: () -> Long,
) {
    private val base = baseUrl.trimEnd('/')
    val code: String = randomCode()
    private val key: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    val partyUrl = "$base/p/$code"

    val clock = RelayClock()
    @Volatile var connected = false
        private set

    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "hearby-relay") }
    @Volatile private var socket: WebSocket? = null
    @Volatile private var running = false
    private var lastState: String? = null
    private var lastSentNs = 0L

    fun start() {
        running = true
        timer.execute(::open)
        timer.scheduleWithFixedDelay(::tick, 250, 250, TimeUnit.MILLISECONDS)
    }

    /** The party's state (relay clock), sent on every change and every few seconds. Any thread. */
    fun publish(stateJson: String) {
        timer.execute {
            val now = System.nanoTime()
            if (stateJson == lastState && now - lastSentNs < RESEND_NS) return@execute
            if (socket?.send(stateJson) == true && connected) {
                lastState = stateJson
                lastSentNs = now
            }
        }
    }

    fun stop(goodbye: String?) {
        running = false
        timer.execute {
            socket?.let { s ->
                goodbye?.let { s.send("""{"t":"bye","msg":${FlatJson.quote(it)}}""") }
                s.close(1000, "party over")
            }
            socket = null
        }
        timer.shutdown()
        timer.awaitTermination(2, TimeUnit.SECONDS)
        http.dispatcher.executorService.shutdown()
    }

    private var ticks = 0

    private fun tick() {
        if (!running) return
        ticks++
        // Clock: quickly at first, then every 2 s.
        if (connected && (clock.count < 8 || ticks % 8 == 0)) socket?.send("""{"t":"sync","c":${nowUs()}}""")
    }

    private fun open() {
        if (!running) return
        val request = Request.Builder().url("${base.replaceFirst("http", "ws")}/ws/$code?role=host&key=$key").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                timer.execute {
                    connected = true
                    lastState = null
                    webSocket.send("""{"t":"info","stream":$streamId,"host":${FlatJson.quote(hostName)}}""")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val received = nowUs()
                val m = FlatJson.parse(text) ?: return
                if (m["t"] != "sync") return
                val c = m["c"]?.toLongOrNull() ?: return
                val s = m["s"]?.toDoubleOrNull()?.toLong() ?: return
                timer.execute { clock.addSample(c, s, received) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket, t.message)

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, reason)
        })
    }

    private fun lost(webSocket: WebSocket, why: String?) {
        timer.execute {
            if (socket !== webSocket) return@execute
            connected = false
            socket = null
            if (running) {
                Log.i(TAG, "relay connection lost ($why); retrying")
                timer.schedule(::open, RETRY_S, TimeUnit.SECONDS)
            }
        }
    }

    companion object {
        private const val TAG = "HearbyRelay"
        private const val RESEND_NS = 3_000_000_000L
        private const val RETRY_S = 3L
        private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

        fun randomCode(): String {
            val r = SecureRandom()
            return String(CharArray(8) { ALPHABET[r.nextInt(ALPHABET.length)] })
        }
    }
}
