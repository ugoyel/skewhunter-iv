package io.github.ugoyel.hearby.host

import io.github.ugoyel.hearby.core.Proto
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FlatJsonTest {
    @Test
    fun parsesTheGuestMessages() {
        assertEquals(mapOf("t" to "sync", "c" to "123.5"), FlatJson.parse("""{"t":"sync","c":123.5}"""))
        assertEquals(
            mapOf("t" to "stat", "buf" to "350", "st" to "2", "adj" to "-1200", "rtt" to "2100"),
            FlatJson.parse(""" { "t" : "stat", "buf":350 ,"st":2,"adj":-1200,"rtt":2100 } """),
        )
        assertEquals("Riya's \"iPhone\" ✨\n", FlatJson.parse("""{"name":"Riya's \"iPhone\" ✨\n"}""")!!["name"])
        assertEquals(emptyMap<String, String>(), FlatJson.parse("{}"))
        assertEquals("true", FlatJson.parse("""{"a":true}""")!!["a"])
    }

    @Test
    fun rejectsWhatItDoesNotUnderstand() {
        for (bad in listOf("", "[]", "{", """{"a"}""", """{"a":}""", """{"a":{"b":1}}""", """{"a":[1]}""", """{"a":"x""", """{"a":1,}x""")) {
            assertNull(bad, FlatJson.parse(bad))
        }
    }

    @Test
    fun quotesForJavaScript() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\\u2028\"", FlatJson.quote("a\"b\\c\nd\u0001 "))
    }
}

class WebGuestsTest {
    private var server: WebGuests? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    @Test
    fun acceptKeyMatchesTheRfcExample() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebGuests.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun frameHeadersUseTheShortestLength() {
        assertArrayEquals(byteArrayOf(0x81.toByte(), 5), WebGuests.frameHeader(1, 5))
        assertArrayEquals(byteArrayOf(0x82.toByte(), 126, 0x0F, 0x0C), WebGuests.frameHeader(2, 3852))
        assertArrayEquals(byteArrayOf(0x82.toByte(), 127, 0, 0, 0, 0, 0, 1, 0, 0), WebGuests.frameHeader(2, 65536))
    }

    @Test
    fun readsMaskedAndFragmentedBrowserFrames() {
        fun masked(fin: Boolean, op: Int, payload: ByteArray): ByteArray {
            val mask = byteArrayOf(1, 2, 3, 4)
            return byteArrayOf(((if (fin) 0x80 else 0) or op).toByte(), (0x80 or payload.size).toByte()) + mask +
                ByteArray(payload.size) { (payload[it].toInt() xor mask[it and 3].toInt()).toByte() }
        }
        val bytes = masked(false, 1, "{\"t\":".toByteArray()) + masked(true, 9, "hi".toByteArray()) + masked(true, 0, "\"x\"}".toByteArray())
        val reader = WebGuests.FrameReader(ByteArrayInputStream(bytes))
        val ping = reader.next()!!
        assertEquals(WebGuests.OP_PING, ping.opcode)
        assertEquals("hi", String(ping.payload))
        val text = reader.next()!!
        assertEquals(WebGuests.OP_TEXT, text.opcode)
        assertEquals("{\"t\":\"x\"}", String(text.payload))
        assertNull(reader.next())
    }

    private fun start(): WebGuests {
        var now = 5_000_000L
        val s = WebGuests(
            preferredPort = 0,
            files = { name -> if (name == "index.html") "<html>party</html>".toByteArray() else null },
            helloJson = { id -> """{"t":"hello","id":$id,"delay":350}""" },
            nowUs = { now += 10; now },
        )
        s.start()
        server = s
        return s
    }

    @Test
    fun servesThePageAndNothingElse() {
        val s = start()
        assertTrue(get(s.port, "/").startsWith("HTTP/1.1 200 OK"))
        assertTrue(get(s.port, "/").endsWith("<html>party</html>"))
        assertTrue(get(s.port, "/../etc/passwd").startsWith("HTTP/1.1 404"))
        assertTrue(get(s.port, "/hearby-web.js").startsWith("HTTP/1.1 404")) // not bundled in this test
    }

    @Test
    fun aBrowserJoinsSyncsAndHearsTheParty() {
        val s = start()
        s.setState("""{"t":"state","title":"Song"}""")
        val ws = TestSocket(s.port)

        val hello = ws.readText()
        assertTrue(hello, hello.startsWith("""{"t":"hello","id":"""))
        assertEquals(0, s.guests.size) // not counted until it says hello

        ws.sendText("""{"t":"hello","name":"Riya\u0007"}""")
        assertEquals("""{"t":"state","title":"Song"}""", ws.readText())
        assertEquals(1, s.guests.size)
        assertEquals("Riya", s.guests[0].name)

        // Clock sync: the guest's time comes back as sent, with the host's receive and send times.
        ws.sendText("""{"t":"sync","c":123456.25}""")
        val sync = FlatJson.parse(ws.readText())!!
        assertEquals("sync", sync["t"])
        assertEquals("123456.25", sync["c"])
        assertTrue(sync["r"]!!.toLong() > 0)
        assertTrue(sync["s"]!!.toLong() >= 5_000_000)

        // Audio: seq and timestamp big-endian, then the PCM as given.
        val pcm = ByteArray(3840) { it.toByte() }
        s.pushAudio(7, 0x0102030405L, pcm)
        val (op, frame) = ws.readFrame()
        assertEquals(WebGuests.OP_BINARY, op)
        assertEquals(WebGuests.AUDIO_HEADER + 3840, frame.size)
        val header = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        assertEquals(7, header.getInt(0))
        assertEquals(0x0102030405L, header.getLong(4))
        assertArrayEquals(pcm, frame.copyOfRange(WebGuests.AUDIO_HEADER, frame.size))

        // Its stats show up for the DJ's crowd list and the sound check.
        ws.sendText("""{"t":"stat","buf":352.4,"st":2,"adj":-1500,"rtt":2100}""")
        waitFor { s.guests[0].status == Proto.STATUS_SYNCED }
        assertEquals(352, s.guests[0].bufferMs)
        assertEquals(-1500L, s.guests[0].adjustUs)
        assertEquals(2100L, s.guests[0].rttUs)

        // A sound-check request reaches just this guest.
        s.send(s.guests[0].id, """{"t":"chirp","media":42,"check":3}""")
        assertEquals("""{"t":"chirp","media":42,"check":3}""", ws.readText())

        // The party ends: a goodbye, then the close frame.
        s.stop("The DJ ended the party.")
        assertEquals("""{"t":"bye","msg":"The DJ ended the party."}""", ws.readText())
        assertEquals(WebGuests.OP_CLOSE, ws.readFrame().first)
        ws.close()
    }

    @Test
    fun aGuestThatLeavesIsForgotten() {
        val s = start()
        val ws = TestSocket(s.port)
        ws.readText()
        ws.sendText("""{"t":"hello","name":"A"}""")
        ws.readText()
        assertEquals(1, s.guests.size)
        ws.sendText("""{"t":"leave"}""")
        waitFor { s.guests.isEmpty() }
        ws.close()
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3000
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
    }

    private fun get(port: Int, path: String): String = Socket("127.0.0.1", port).use { sock ->
        sock.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        sock.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    /** Just enough of a browser's WebSocket client. */
    private class TestSocket(port: Int) {
        private val socket = Socket("127.0.0.1", port).apply { soTimeout = 3000 }
        private val input: InputStream = socket.getInputStream()
        private val out: OutputStream = socket.getOutputStream()

        init {
            out.write(
                ("GET /ws HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(),
            )
            val response = StringBuilder()
            while (!response.endsWith("\r\n\r\n")) response.append(input.read().toChar())
            assertTrue(response.toString(), response.startsWith("HTTP/1.1 101"))
            assertTrue(response.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))
        }

        fun sendText(text: String) {
            val payload = text.toByteArray()
            val mask = byteArrayOf(0x37, 0xFA.toByte(), 0x21, 0x3D)
            val head = if (payload.size < 126) {
                byteArrayOf(0x81.toByte(), (0x80 or payload.size).toByte())
            } else {
                byteArrayOf(0x81.toByte(), (0x80 or 126).toByte(), (payload.size shr 8).toByte(), payload.size.toByte())
            }
            out.write(head + mask + ByteArray(payload.size) { (payload[it].toInt() xor mask[it and 3].toInt()).toByte() })
            out.flush()
        }

        fun readFrame(): Pair<Int, ByteArray> {
            val d = DataInputStream(input)
            val b0 = d.readUnsignedByte()
            var len = d.readUnsignedByte().toLong()
            if (len == 126L) len = d.readUnsignedShort().toLong() else if (len == 127L) len = d.readLong()
            val payload = ByteArray(len.toInt())
            d.readFully(payload)
            return (b0 and 0x0F) to payload
        }

        fun readText(): String {
            val (op, payload) = readFrame()
            assertEquals(WebGuests.OP_TEXT, op)
            return String(payload, Charsets.UTF_8)
        }

        fun close() = socket.close()
    }
}

class WebGuestsLimitTest {
    @Test
    fun aFullPartySaysSo() {
        val s = WebGuests(preferredPort = 0, files = { null }, helloJson = { """{"t":"hello"}""" })
        s.start()
        try {
            val sockets = (0 until WebGuests.MAX_GUESTS).map { Socket("127.0.0.1", s.port) }
            for (sock in sockets) handshake(sock)
            for (sock in sockets) assertNotNull(readText(sock)) // each one's hello
            val late = Socket("127.0.0.1", s.port)
            handshake(late)
            assertEquals("""{"t":"full"}""", readText(late))
            (sockets + late).forEach { it.close() }
        } finally {
            s.stop()
        }
    }

    private fun handshake(sock: Socket) {
        sock.soTimeout = 3000
        sock.getOutputStream().write(
            "GET /ws HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: a2V5\r\n\r\n".toByteArray(),
        )
        val response = StringBuilder()
        while (!response.endsWith("\r\n\r\n")) response.append(sock.getInputStream().read().toChar())
    }

    private fun readText(sock: Socket): String {
        val d = DataInputStream(sock.getInputStream())
        d.readUnsignedByte()
        val len = d.readUnsignedByte()
        val payload = ByteArray(len)
        d.readFully(payload)
        return String(payload)
    }
}
