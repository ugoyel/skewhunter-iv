package io.github.ugoyel.hearby.host

import io.github.ugoyel.hearby.core.Proto
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Guests without the app: the DJ phone serves a web page and phones join the party in their browser
 * (iPhones in Safari, which can't install the app yet).
 *
 *   GET /               the guest page (assets/web/index.html)
 *   GET /hearby-web.js  its sync logic (the same file is unit-tested with Node)
 *   GET /ws             a WebSocket for everything else:
 *     host → guest  text    hello, sync replies, state (song or YouTube timeline), chirp, offset, bye
 *                   binary  one audio frame: u32 seq, u64 timestampUs (big-endian, as AUDIO), then
 *                           960 stereo samples, 16-bit little-endian PCM (browsers can't decode Opus
 *                           packets on their own without a secure page)
 *     guest → host  text    hello {name}, sync {c}, stat {buf, st, adj, err}, leave
 *
 * Plain JVM code (no Android APIs), so it is tested on the desktop. docs/PROTOCOL.md has the details.
 */
class WebGuests(
    private val preferredPort: Int = PORT,
    /** The bundled page and script, by file name. */
    private val files: (String) -> ByteArray?,
    /** The hello sent to a new guest, as JSON, given its id. */
    private val helloJson: (Long) -> String,
    private val nowUs: () -> Long = { System.nanoTime() / 1000 },
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val random = SecureRandom()
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var all: Array<WebGuest> = emptyArray()
    private val lock = Any()

    /** What is playing, as JSON; sent to every newcomer and on every change. */
    @Volatile var stateJson: String = """{"t":"state"}"""
        private set

    /** The port actually listened on, or 0 before [start]. */
    @Volatile var port = 0
        private set

    val guests: List<WebGuest> get() = all.filter { it.joined }

    fun start(): Int {
        val s = ServerSocket()
        s.reuseAddress = true
        try {
            s.bind(InetSocketAddress(preferredPort))
        } catch (e: IOException) {
            s.bind(InetSocketAddress(0)) // taken: any port; the DJ screen shows whichever we got
        }
        server = s
        port = s.localPort
        running = true
        acceptThread = Thread(::acceptLoop, "hearby-web").also { it.isDaemon = true; it.start() }
        return port
    }

    fun stop(goodbye: String? = null) {
        running = false
        try {
            server?.close()
        } catch (e: IOException) {
            // closing anyway
        }
        val list = synchronized(lock) { all.also { all = emptyArray() } }
        for (g in list) g.close(goodbye?.let { """{"t":"bye","msg":${FlatJson.quote(it)}}""" })
        acceptThread?.join(1000)
    }

    /** Update what is playing; sent to everyone when it changes (and on [refresh]). */
    fun setState(json: String) {
        if (json == stateJson) return
        stateJson = json
        broadcast(json)
    }

    /** Send the current state again (a guest may have missed nothing over TCP, but cheap insurance). */
    fun refresh() = broadcast(stateJson)

    fun broadcast(json: String) {
        for (g in all) if (g.joined) g.sendText(json)
    }

    fun send(guestId: Long, json: String) {
        all.firstOrNull { it.id == guestId }?.sendText(json)
    }

    /** One 20 ms frame of what every phone plays: [pcm] is 960 stereo samples, 16-bit little-endian. */
    fun pushAudio(seq: Long, timestampUs: Long, pcm: ByteArray, length: Int = pcm.size) {
        val list = all
        if (list.none { it.joined }) return
        val frame = ByteArray(AUDIO_HEADER + length)
        putU32(frame, 0, seq)
        putU64(frame, 4, timestampUs)
        System.arraycopy(pcm, 0, frame, AUDIO_HEADER, length)
        for (g in list) if (g.joined) g.sendAudio(frame)
    }

    /** Forget guests that went quiet (closed their browser, lost the Wi-Fi). */
    fun dropSilent(timeoutNs: Long, nowNs: Long = System.nanoTime()) {
        for (g in all) if (nowNs - g.lastSeenNs > timeoutNs) g.close(null)
    }

    private fun acceptLoop() {
        val s = server ?: return
        while (running) {
            val socket = try {
                s.accept()
            } catch (e: IOException) {
                if (running) log("web accept failed", e)
                break
            }
            Thread({ serve(socket) }, "hearby-web-guest").also { it.isDaemon = true; it.start() }
        }
    }

    private fun serve(socket: Socket) {
        var upgraded = false
        try {
            socket.tcpNoDelay = true // sync replies must not wait for more data
            socket.soTimeout = REQUEST_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val request = readRequest(input) ?: return
            val out = socket.getOutputStream()
            when {
                request.path == "/ws" && request.header("upgrade").equals("websocket", ignoreCase = true) -> {
                    val key = request.header("sec-websocket-key") ?: return respond(out, 400, "text/plain", "Bad request".toByteArray())
                    out.write(
                        ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: ${acceptKey(key)}\r\n\r\n").toByteArray(),
                    )
                    out.flush()
                    upgraded = true
                    guest(socket, input) // closes the socket when the guest leaves
                }
                request.path == "/" || request.path == "/index.html" -> file(out, "index.html", "text/html; charset=utf-8")
                request.path == "/hearby-web.js" -> file(out, "hearby-web.js", "text/javascript; charset=utf-8")
                request.path == "/favicon.ico" -> respond(out, 204, "text/plain", ByteArray(0))
                else -> respond(out, 404, "text/plain; charset=utf-8", "Not found".toByteArray())
            }
        } catch (e: IOException) {
            // the browser went away
        } finally {
            if (!upgraded) {
                try {
                    socket.close()
                } catch (ignored: IOException) {
                }
            }
        }
    }

    private fun file(out: OutputStream, name: String, type: String) {
        val body = files(name) ?: return respond(out, 404, "text/plain; charset=utf-8", "Not found".toByteArray())
        respond(out, 200, type, body)
    }

    private fun respond(out: OutputStream, status: Int, type: String, body: ByteArray) {
        val reason = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            else -> "Not Found"
        }
        out.write(
            ("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        out.write(body)
        out.flush()
    }

    private fun guest(socket: Socket, input: InputStream) {
        val address = socket.inetAddress?.hostAddress ?: "?"
        val g = WebGuest(random.nextInt().toLong() and 0xFFFFFFFFL, address, socket, nowUs)
        val full = synchronized(lock) {
            if (all.size >= MAX_GUESTS) {
                true
            } else {
                all = all + g
                false
            }
        }
        g.startWriter()
        if (full) {
            g.close("""{"t":"full"}""")
            return
        }
        g.sendText(helloJson(g.id))
        try {
            socket.soTimeout = GUEST_TIMEOUT_MS // a guest sends sync or stat every second
            val reader = FrameReader(input)
            while (running && !g.closed) {
                val msg = reader.next() ?: break
                g.lastSeenNs = System.nanoTime()
                when (msg.opcode) {
                    OP_TEXT -> onText(g, String(msg.payload, Charsets.UTF_8), System.nanoTime() / 1000)
                    OP_PING -> g.sendControl(OP_PONG, msg.payload)
                    OP_CLOSE -> break
                }
            }
        } catch (e: SocketTimeoutException) {
            // went quiet
        } catch (e: IOException) {
            // connection dropped
        } finally {
            g.close(null)
            synchronized(lock) { all = all.filter { it !== g }.toTypedArray() }
        }
    }

    private fun onText(g: WebGuest, text: String, rxUs: Long) {
        val m = FlatJson.parse(text) ?: return
        when (m["t"]) {
            "sync" -> m["c"]?.takeIf { NUMBER.matches(it) }?.let { g.replySync(it, rxUs) }
            "hello" -> {
                g.name = m["name"].orEmpty().filter { !it.isISOControl() }.trim().take(MAX_NAME).ifBlank { "iPhone" }
                g.joined = true
                g.sendText(stateJson)
            }
            "stat" -> {
                m["buf"]?.toDoubleOrNull()?.let { g.bufferMs = it.toInt().coerceIn(0, 60_000) }
                m["st"]?.toIntOrNull()?.let { g.status = it.coerceIn(Proto.STATUS_CONNECTING, Proto.STATUS_LATE) }
                m["adj"]?.toDoubleOrNull()?.let { g.adjustUs = it.toLong() }
                m["rtt"]?.toDoubleOrNull()?.let { g.rttUs = it.toLong().coerceAtLeast(0) }
            }
            "leave" -> g.close(null)
        }
    }

    companion object {
        const val PORT = 47801
        const val MAX_GUESTS = 16
        const val AUDIO_HEADER = 12
        private const val MAX_NAME = 32
        private const val REQUEST_TIMEOUT_MS = 10_000
        private const val GUEST_TIMEOUT_MS = 15_000
        private const val MAX_REQUEST_BYTES = 16 * 1024
        private val NUMBER = Regex("^-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?$")

        internal const val OP_CONT = 0
        internal const val OP_TEXT = 1
        internal const val OP_BINARY = 2
        internal const val OP_CLOSE = 8
        internal const val OP_PING = 9
        internal const val OP_PONG = 10

        /** RFC 6455 §4.2.2: the key the browser must get back to accept the upgrade. */
        fun acceptKey(key: String): String {
            val sha1 = MessageDigest.getInstance("SHA-1").digest((key.trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
            return Base64.getEncoder().encodeToString(sha1)
        }

        internal fun putU32(b: ByteArray, off: Int, v: Long) {
            for (i in 0 until 4) b[off + i] = (v ushr (24 - 8 * i)).toByte()
        }

        internal fun putU64(b: ByteArray, off: Int, v: Long) {
            for (i in 0 until 8) b[off + i] = (v ushr (56 - 8 * i)).toByte()
        }

        /** One server-to-browser frame (never masked, RFC 6455 §5.1). */
        internal fun frameHeader(opcode: Int, length: Int): ByteArray = when {
            length < 126 -> byteArrayOf((0x80 or opcode).toByte(), length.toByte())
            length < 65536 -> byteArrayOf((0x80 or opcode).toByte(), 126, (length shr 8).toByte(), length.toByte())
            else -> ByteArray(10).also {
                it[0] = (0x80 or opcode).toByte()
                it[1] = 127
                putU64(it, 2, length.toLong())
            }
        }

        internal class Request(val path: String, private val headers: Map<String, String>) {
            fun header(name: String): String? = headers[name.lowercase()]
        }

        /** The request line and headers of one HTTP/1.1 request, or null if malformed. */
        internal fun readRequest(input: InputStream): Request? {
            val lines = ArrayList<String>()
            val line = StringBuilder()
            var total = 0
            while (true) {
                val c = input.read()
                if (c < 0) return null
                if (++total > MAX_REQUEST_BYTES) return null
                if (c == '\n'.code) {
                    val text = line.toString().trimEnd('\r')
                    line.setLength(0)
                    if (text.isEmpty()) break
                    lines += text
                } else {
                    line.append(c.toChar())
                }
            }
            val first = lines.firstOrNull()?.split(' ') ?: return null
            if (first.size < 3 || first[0] != "GET") return null
            val path = first[1].substringBefore('?')
            val headers = HashMap<String, String>()
            for (h in lines.drop(1)) {
                val i = h.indexOf(':')
                if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            }
            return Request(path, headers)
        }
    }

    /** Reads browser-to-server frames: masked, possibly fragmented (RFC 6455 §5.2). */
    internal class FrameReader(private val input: InputStream) {
        class Message(val opcode: Int, val payload: ByteArray)

        // A fragmented message in progress (a ping may arrive between its pieces).
        private var opcode = -1
        private var data = ByteArray(0)

        fun next(): Message? {
            while (true) {
                val b0 = input.read()
                val b1 = input.read()
                if (b0 < 0 || b1 < 0) return null
                val fin = b0 and 0x80 != 0
                val op = b0 and 0x0F
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = ((readByte() shl 8) or readByte()).toLong()
                else if (len == 127L) {
                    len = 0
                    repeat(8) { len = (len shl 8) or readByte().toLong() }
                }
                if (len > MAX_MESSAGE) throw IOException("message too large")
                val mask = if (b1 and 0x80 != 0) ByteArray(4) { readByte().toByte() } else null
                val payload = ByteArray(len.toInt())
                var read = 0
                while (read < payload.size) {
                    val n = input.read(payload, read, payload.size - read)
                    if (n < 0) return null
                    read += n
                }
                if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                if (op >= OP_CLOSE) return Message(op, payload) // control frames are never fragmented
                if (op != OP_CONT) {
                    opcode = op
                    data = ByteArray(0)
                }
                data += payload
                if (data.size > MAX_MESSAGE) throw IOException("message too large")
                if (fin) {
                    val message = Message(opcode, data)
                    opcode = -1
                    data = ByteArray(0)
                    return message
                }
            }
        }

        private fun readByte(): Int {
            val b = input.read()
            if (b < 0) throw IOException("closed")
            return b
        }

        private companion object {
            const val MAX_MESSAGE = 64 * 1024
        }
    }
}

/** One browser at the party. Everything it is sent goes through its own writer thread. */
class WebGuest internal constructor(
    val id: Long,
    val address: String,
    private val socket: Socket,
    private val nowUs: () -> Long,
) {
    @Volatile var name = "iPhone"
    @Volatile var joined = false
    @Volatile var lastSeenNs = System.nanoTime()
    @Volatile var rttUs = 0L
    @Volatile var bufferMs = 0
    @Volatile var status = Proto.STATUS_CONNECTING
    /** Its current adjustments (slider, sound check, fallback) in µs, as last reported. */
    @Volatile var adjustUs = 0L
    @Volatile var check: CheckResult? = null
    @Volatile var closed = false
        private set

    private sealed interface Out {
        class Frame(val opcode: Int, val data: ByteArray) : Out
        /** A clock-sync reply, stamped with the host time at the moment it is written. */
        class Sync(val clientTime: String, val rxUs: Long) : Out
        class Close(val goodbye: String?) : Out
    }

    private val queue = LinkedBlockingDeque<Out>()
    private val audioQueued = AtomicInteger()
    private var writer: Thread? = null

    internal fun startWriter() {
        writer = Thread(::writeLoop, "hearby-web-writer").also { it.isDaemon = true; it.start() }
    }

    fun sendText(json: String) {
        if (!closed) queue.offerLast(Out.Frame(WebGuests.OP_TEXT, json.toByteArray(Charsets.UTF_8)))
    }

    internal fun sendControl(opcode: Int, payload: ByteArray) {
        if (!closed) queue.offerFirst(Out.Frame(opcode, payload))
    }

    /** Audio is dropped rather than queued without bound if this guest's Wi-Fi can't keep up. */
    internal fun sendAudio(frame: ByteArray) {
        if (closed) return
        if (audioQueued.get() >= MAX_AUDIO_BACKLOG) return
        audioQueued.incrementAndGet()
        queue.offerLast(Out.Frame(WebGuests.OP_BINARY, frame))
    }

    /** Clock sync jumps the queue, so audio waiting to go out does not skew the measurement. */
    internal fun replySync(clientTime: String, rxUs: Long) {
        if (!closed) queue.offerFirst(Out.Sync(clientTime, rxUs))
    }

    internal fun close(goodbye: String?) {
        if (closed) return
        closed = true
        queue.offerLast(Out.Close(goodbye))
        if (writer == null) closeSocket()
    }

    private fun writeLoop() {
        val out = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
        try {
            while (true) {
                val item = queue.poll(1, TimeUnit.SECONDS) ?: if (closed) break else continue
                when (item) {
                    is Out.Frame -> {
                        if (item.opcode == WebGuests.OP_BINARY) audioQueued.decrementAndGet()
                        write(out, item.opcode, item.data)
                    }
                    is Out.Sync -> {
                        val sent = nowUs()
                        write(out, WebGuests.OP_TEXT, """{"t":"sync","c":${item.clientTime},"r":${item.rxUs},"s":$sent}""".toByteArray())
                        out.flush()
                        continue
                    }
                    is Out.Close -> {
                        item.goodbye?.let { write(out, WebGuests.OP_TEXT, it.toByteArray(Charsets.UTF_8)) }
                        write(out, WebGuests.OP_CLOSE, ByteArray(0))
                        out.flush()
                        break
                    }
                }
                if (queue.isEmpty()) out.flush()
            }
        } catch (e: IOException) {
            closed = true
        } finally {
            closeSocket()
        }
    }

    private fun write(out: OutputStream, opcode: Int, data: ByteArray) {
        out.write(WebGuests.frameHeader(opcode, data.size))
        out.write(data)
    }

    private fun closeSocket() {
        try {
            socket.close()
        } catch (e: IOException) {
            // already closed
        }
    }

    private companion object {
        const val MAX_AUDIO_BACKLOG = 25 // half a second
    }
}

/**
 * The little JSON the guest page sends: one flat object of strings, numbers and booleans. Values are
 * returned as their raw text (numbers) or unescaped (strings). Nested values are rejected.
 */
object FlatJson {
    fun parse(s: String): Map<String, String>? {
        var i = skip(s, 0)
        if (i >= s.length || s[i] != '{') return null
        i = skip(s, i + 1)
        val out = HashMap<String, String>()
        if (i < s.length && s[i] == '}') return out
        while (i < s.length) {
            val key = readString(s, i) ?: return null
            i = skip(s, key.second)
            if (i >= s.length || s[i] != ':') return null
            i = skip(s, i + 1)
            if (i >= s.length) return null
            val value = if (s[i] == '"') {
                readString(s, i) ?: return null
            } else {
                var j = i
                while (j < s.length && s[j] != ',' && s[j] != '}') j++
                val raw = s.substring(i, j).trim()
                if (raw.isEmpty() || raw[0] == '{' || raw[0] == '[') return null
                raw to j
            }
            out[key.first] = value.first
            i = skip(s, value.second)
            if (i < s.length && s[i] == ',') {
                i = skip(s, i + 1)
                continue
            }
            return if (i < s.length && s[i] == '}') out else null
        }
        return null
    }

    fun quote(s: String): String {
        val b = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> if (c < ' ' || c == ' ' || c == ' ') b.append("\\u%04x".format(c.code)) else b.append(c)
            }
        }
        return b.append('"').toString()
    }

    private fun skip(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    /** The string starting at [from] (which must be a quote) and the index after it. */
    private fun readString(s: String, from: Int): Pair<String, Int>? {
        if (from >= s.length || s[from] != '"') return null
        val b = StringBuilder()
        var i = from + 1
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> return b.toString() to i + 1
                c == '\\' -> {
                    if (i + 1 >= s.length) return null
                    when (val e = s[i + 1]) {
                        '"', '\\', '/' -> b.append(e)
                        'b' -> b.append('\b')
                        'f' -> b.append('\u000C')
                        'n' -> b.append('\n')
                        'r' -> b.append('\r')
                        't' -> b.append('\t')
                        'u' -> {
                            if (i + 5 >= s.length) return null
                            b.append(s.substring(i + 2, i + 6).toIntOrNull(16)?.toChar() ?: return null)
                            i += 4
                        }
                        else -> return null
                    }
                    i += 2
                }
                else -> {
                    b.append(c)
                    i++
                }
            }
        }
        return null
    }
}
