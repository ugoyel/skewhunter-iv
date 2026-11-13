package io.github.ugoyel.hearby.host

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * The real guest server with a fake party, for checking the real page in a real browser:
 *
 *   HEARBY_LIVE_WEB=60 ./gradlew testDebugUnitTest --tests '*WebGuestsLiveTest*'
 *
 * then open http://127.0.0.1:47811/ (a script can drive it, e.g. with Playwright). Skipped otherwise.
 * It plays a 440 Hz tone on the shared timeline, asks every guest for a sound-check chirp after 12 s
 * and sends a correction, raises the cushion to 500 ms after 18 s, switches to a YouTube video after 25 s, and writes what it saw of its
 * guests to build/web-live.txt once a second.
 */
class WebGuestsLiveTest {
    @Test
    fun serveAFakeParty() {
        val seconds = System.getenv("HEARBY_LIVE_WEB")?.toIntOrNull()
        assumeTrue("set HEARBY_LIVE_WEB=<seconds> to run", seconds != null)
        val assets = File("src/main/assets/web")
        val streamId = 0x0A0B0C0DL
        val web = WebGuests(
            preferredPort = 47811,
            files = { name -> File(assets, name).takeIf { it.isFile }?.readBytes() },
            helloJson = { id -> """{"t":"hello","id":$id,"stream":$streamId,"host":"Test DJ","delay":350}""" },
        )
        web.start()
        val report = File("build/web-live.txt").apply { writeText("") }
        val startNs = System.nanoTime()
        var seq = 0L
        var nextNs = startNs
        val pcm = ByteArray(960 * 2 * 2)
        var phase = 0.0
        var chirped = false
        var videoOn = false
        var raised = false
        var ducked = false
        var videoAnchor = 0L
        var lastReportNs = 0L
        web.setState("""{"t":"state","title":"Test tone","artist":"Hearby","playing":true,"video":null}""")
        while (System.nanoTime() - startNs < seconds!! * 1_000_000_000L) {
            val now = System.nanoTime()
            if (now < nextNs) {
                Thread.sleep(1)
                continue
            }
            for (i in 0 until 960) {
                val v = (sin(phase) * 8000).toInt()
                phase += 2 * PI * 440 / 48_000
                for (ch in 0..1) {
                    pcm[(2 * i + ch) * 2] = v.toByte()
                    pcm[(2 * i + ch) * 2 + 1] = (v shr 8).toByte()
                }
            }
            if (!videoOn) web.pushAudio(seq, nextNs / 1000, pcm)
            seq++
            nextNs += 20_000_000L
            val elapsedS = (now - startNs) / 1e9
            if (!chirped && elapsedS > 12) {
                chirped = true
                for (g in web.guests) {
                    web.send(g.id, """{"t":"chirp","media":${nextNs / 1000 + 500_000},"check":1}""")
                    web.send(g.id, """{"t":"offset","adjust":-7000,"check":1,"err":7000}""")
                }
            }
            if (!raised && elapsedS > 18) {
                raised = true // the DJ phone's tuner raised the cushion
                web.setState("""{"t":"state","title":"Test tone","artist":"Hearby","playing":true,"delay":500,"video":null}""")
            }
            if (!videoOn && elapsedS > 25) {
                videoOn = true
                val anchor = now / 1000 + 2_500_000
                videoAnchor = anchor
                web.setState(
                    """{"t":"state","title":"Video","artist":"YouTube","playing":true,""" +
                        """"delay":500,"video":{"id":"dQw4w9WgXcQ","title":"Test video","playing":true,"anchor":$anchor,"pos":0,"v":1}}""",
                )
            }
            if (videoOn && !ducked && elapsedS > 35) {
                ducked = true // the DJ turned the karaoke mic on: every player glides quieter
                web.setState(
                    """{"t":"state","title":"Video","artist":"YouTube","playing":true,"delay":500,"duck":true,""" +
                        """"video":{"id":"dQw4w9WgXcQ","title":"Test video","playing":true,"anchor":$videoAnchor,"pos":0,"v":1}}""",
                )
            }
            if (now - lastReportNs > 1_000_000_000L) {
                lastReportNs = now
                val line = web.guests.joinToString(";") { g -> "${g.name},st=${g.status},rtt=${g.rttUs},buf=${g.bufferMs},adj=${g.adjustUs}" }
                report.appendText("t=${"%.1f".format(elapsedS)} guests=${web.guests.size} $line\n")
            }
        }
        web.stop("The DJ ended the party.")
    }
}
