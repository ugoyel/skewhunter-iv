package app.pttradio.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import app.pttradio.core.VoiceCodec
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Low-data voice: Opus at 16 kbit/s through the phone's built-in codec (about a quarter of the data of the
 * standard codec, and far more tolerant of weak links). A loop-back self test decides whether this phone
 * can really use it; when it cannot, the radio stays on the standard codec.
 */
class OpusCodec : VoiceCodec {
    private val mime = MediaFormat.MIMETYPE_AUDIO_OPUS
    private var enc: MediaCodec? = null
    private var dec: MediaCodec? = null
    private var decRate = 48000
    private var decCh = 1
    private var encPts = 0L
    private var decPts = 0L
    private val info = MediaCodec.BufferInfo()

    override val ok: Boolean by lazy { selfTest() }

    private fun newEnc(): MediaCodec {
        val f = MediaFormat.createAudioFormat(mime, 16000, 1)
        f.setInteger(MediaFormat.KEY_BIT_RATE, 16000)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        return MediaCodec.createEncoderByType(mime).apply { configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
    }

    private fun newDec(): MediaCodec {
        val f = MediaFormat.createAudioFormat(mime, 48000, 1)
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        head.put("OpusHead".toByteArray()).put(1).put(1).putShort(312).putInt(16000).putShort(0).put(0)
        head.flip()
        f.setByteBuffer("csd-0", head)
        f.setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(6_500_000L).apply { flip() })
        f.setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000L).apply { flip() })
        return MediaCodec.createDecoderByType(mime).apply { configure(f, null, null, 0); start() }
    }

    override fun resetEnc() { try { enc?.flush() } catch (e: Exception) { releaseEnc() }; encPts = 0 }
    override fun resetDec() { try { dec?.flush() } catch (e: Exception) { releaseDec() }; decPts = 0 }
    private fun releaseEnc() { try { enc?.release() } catch (e: Exception) { }; enc = null }
    private fun releaseDec() { try { dec?.release() } catch (e: Exception) { }; dec = null }

    /** @return zero or more Opus packets as [count][len16 data]..., or null when the encoder failed. */
    override fun encode(pcm: ShortArray): ByteArray? = try {
        val c = enc ?: newEnc().also { enc = it; encPts = 0 }
        val i = c.dequeueInputBuffer(20_000)
        if (i >= 0) {
            val b = c.getInputBuffer(i)!!
            b.clear(); b.order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm) b.putShort(s)
            c.queueInputBuffer(i, 0, pcm.size * 2, encPts, 0)
            encPts += pcm.size * 1_000_000L / 16000
        }
        val out = ByteArrayOutputStream()
        var n = 0
        var wait = 15_000L
        while (n < 12) {
            val o = c.dequeueOutputBuffer(info, wait)
            if (o >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size in 1..1500) {
                    val d = ByteArray(info.size)
                    c.getOutputBuffer(o)!!.apply { position(info.offset); get(d) }
                    out.write(info.size shr 8); out.write(info.size and 0xff); out.write(d)
                    n++
                    wait = 0
                }
                c.releaseOutputBuffer(o, false)
            } else if (o != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) break
        }
        byteArrayOf(n.toByte()) + out.toByteArray()
    } catch (e: Exception) { releaseEnc(); null }

    /** Decodes a bundle made by [encode] to 16 kHz mono. */
    override fun decode(data: ByteArray, off: Int, len: Int): ShortArray = try {
        val c = dec ?: newDec().also { dec = it; decPts = 0 }
        val pcm = ArrayList<Short>(1024)
        var p = off + 1
        val end = off + len
        repeat(if (len > 0) data[off].toInt() and 0xff else 0) {
            if (p + 2 > end) return@repeat
            val l = ((data[p].toInt() and 0xff) shl 8) or (data[p + 1].toInt() and 0xff)
            p += 2
            if (l <= 0 || p + l > end) return@repeat
            val i = c.dequeueInputBuffer(20_000)
            if (i >= 0) {
                c.getInputBuffer(i)!!.apply { clear(); put(data, p, l) }
                c.queueInputBuffer(i, 0, l, decPts, 0)
                decPts += 20_000
            }
            p += l
            drain(c, pcm, 15_000)
        }
        ShortArray(pcm.size) { pcm[it] }
    } catch (e: Exception) { releaseDec(); ShortArray(0) }

    private fun drain(c: MediaCodec, pcm: ArrayList<Short>, firstWait: Long) {
        var wait = firstWait
        var guard = 0
        while (guard++ < 16) {
            val o = c.dequeueOutputBuffer(info, wait)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                decRate = c.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                decCh = c.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                continue
            }
            if (o < 0) break
            val b = c.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
            b.position(info.offset); b.limit(info.offset + info.size)
            val sb = b.asShortBuffer()
            // the decoder works at 48 kHz: average each group down to the radio's 16 kHz, first channel only
            val step = (decRate / 16000).coerceAtLeast(1) * decCh
            val frames = sb.remaining() / step
            for (f in 0 until frames) {
                var sum = 0
                var k = 0
                while (k < step) { sum += sb.get(f * step + k); k += decCh }
                pcm.add((sum / (step / decCh)).toShort())
            }
            c.releaseOutputBuffer(o, false)
            wait = 0
        }
    }

    /** Encodes and decodes a second of tone; passes only if roughly the same amount of real sound comes back. */
    private fun selfTest(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            var got = 0
            var energy = 0L
            val frames = 20
            for (f in 0 until frames + 6) {
                val pcm = ShortArray(960) { if (f < frames) (9000 * sin(2 * PI * 440 * (it + f * 960) / 16000)).toInt().toShort() else 0 }
                val e = encode(pcm) ?: return false
                val d = decode(e, 0, e.size)
                got += d.size
                for (s in d) energy += abs(s.toInt())
            }
            val pass = got > frames * 960 * 7 / 10 && got < (frames + 8) * 960 && energy / got.coerceAtLeast(1) > 500
            resetEnc(); resetDec()
            pass
        } catch (e: Throwable) { releaseEnc(); releaseDec(); false }
    }
}
