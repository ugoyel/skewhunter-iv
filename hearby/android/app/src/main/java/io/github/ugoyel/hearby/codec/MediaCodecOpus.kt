package io.github.ugoyel.hearby.codec

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import io.github.ugoyel.hearby.core.OpusFrameDecoder
import io.github.ugoyel.hearby.core.OpusToc
import io.github.ugoyel.hearby.core.Proto
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opus encoder on top of the platform codec (c2.android.opus.encoder, Android 10+).
 *
 * Fed exactly one 20 ms / 960-frame PCM block per call, the platform encoder emits exactly one Opus
 * packet per block, so timestamps are carried alongside in a FIFO rather than through the codec
 * (which re-derives its own from a sample count).
 */
class MediaCodecOpusEncoder(bitrate: Int) {

    fun interface PacketSink {
        /** [data] is positioned at one complete Opus packet of [size] bytes. */
        fun onPacket(data: ByteBuffer, size: Int, timestampUs: Long)
    }

    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private val pendingTs = LongArray(64)
    private var tsHead = 0
    private var tsCount = 0
    private var ptsUs = 0L
    private val tocProbe = ByteArray(2)

    /** Encoder look-ahead in 48 kHz samples: decoded audio lags the input by this much. */
    @Volatile
    var preSkipSamples = DEFAULT_PRE_SKIP
        private set

    /** Blocks dropped because the codec had no input buffer free (should stay 0). */
    @Volatile
    var droppedBlocks = 0L
        private set

    /** Set if the device's encoder does not produce 20 ms packets; the stream is then unusable. */
    @Volatile
    var unsupportedFraming = false
        private set

    init {
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        try {
            configure(bitrate, withComplexity = true)
        } catch (e: Exception) {
            codec.reset()
            configure(bitrate, withComplexity = false)
        }
        codec.start()
    }

    private fun configure(bitrate: Int, withComplexity: Boolean) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, Proto.SAMPLE_RATE, Proto.CHANNELS)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_BLOCK_BYTES * 2)
        // A little below the maximum: indistinguishable for music at these bitrates, noticeably cheaper.
        if (withComplexity) format.setInteger(MediaFormat.KEY_COMPLEXITY, 7)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    /**
     * Encode one block of [PCM_BLOCK_BYTES] bytes of 16-bit little-endian stereo PCM captured at
     * [timestampUs], and deliver every packet that is ready. Packets are stamped with the capture
     * time of the first sample they *decode to*, i.e. with the encoder look-ahead already removed.
     */
    fun encode(pcm: ByteArray, timestampUs: Long, sink: PacketSink) {
        val inIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inIndex >= 0 && tsCount < pendingTs.size) {
            val buffer = codec.getInputBuffer(inIndex)!!
            buffer.clear()
            buffer.put(pcm, 0, PCM_BLOCK_BYTES)
            codec.queueInputBuffer(inIndex, 0, PCM_BLOCK_BYTES, ptsUs, 0)
            ptsUs += Proto.FRAME_US
            pendingTs[(tsHead + tsCount) % pendingTs.size] = timestampUs
            tsCount++
        } else {
            if (inIndex >= 0) codec.queueInputBuffer(inIndex, 0, 0, ptsUs, 0)
            droppedBlocks++
        }

        // Wait briefly for this block's packet, then take whatever else is ready without waiting.
        var timeoutUs = OUTPUT_TIMEOUT_US
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)
            if (outIndex >= 0) {
                val out = codec.getOutputBuffer(outIndex)
                if (out != null && info.size > 0) {
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        parseOpusHead(out)
                    } else if (tsCount > 0) {
                        val ts = pendingTs[tsHead]
                        tsHead = (tsHead + 1) % pendingTs.size
                        tsCount--
                        checkFraming(out, info.size)
                        sink.onPacket(out, info.size, ts - preSkipSamples * 1_000_000L / Proto.SAMPLE_RATE)
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                timeoutUs = 0
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try {
                    codec.outputFormat.getByteBuffer("csd-0")?.let { parseOpusHead(it.duplicate()) }
                } catch (e: Exception) {
                    // keep the default
                }
            } else {
                return // INFO_TRY_AGAIN_LATER
            }
        }
    }

    /** OpusHead (RFC 7845 §5.1): "OpusHead", version, channels, pre-skip (u16 LE) at byte 10. */
    private fun parseOpusHead(buffer: ByteBuffer) {
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val start = b.position()
        if (b.remaining() < 12) return
        val magic = ByteArray(8)
        b.get(magic)
        if (String(magic, Charsets.US_ASCII) != "OpusHead") return
        val preSkip = b.getShort(start + 10).toInt() and 0xFFFF
        if (preSkip in 0..MAX_PRE_SKIP) {
            preSkipSamples = preSkip
            Log.i(TAG, "Opus encoder look-ahead: $preSkip samples")
        }
    }

    private fun checkFraming(packet: ByteBuffer, size: Int) {
        if (unsupportedFraming) return
        val p = packet.position()
        tocProbe[0] = packet.get(p)
        tocProbe[1] = if (size > 1) packet.get(p + 1) else 0
        val durationUs = OpusToc.packetDurationUs(tocProbe, 0, minOf(size, 2))
        if (durationUs != Proto.FRAME_US.toInt()) {
            unsupportedFraming = true
            Log.e(TAG, "Encoder produced a $durationUs us packet; expected ${Proto.FRAME_US}")
        }
    }

    fun release() {
        try {
            codec.stop()
        } catch (e: Exception) {
            // already stopped
        }
        codec.release()
    }

    companion object {
        private const val TAG = "HearbyOpus"
        const val PCM_BLOCK_BYTES = Proto.FRAME_SAMPLES * Proto.CHANNELS * 2
        const val DEFAULT_PRE_SKIP = 312 // libopus look-ahead at 48 kHz (6.5 ms)
        private const val MAX_PRE_SKIP = 4800
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val OUTPUT_TIMEOUT_US = 8_000L
    }
}

/**
 * Opus decoder on top of the platform codec. One packet in, 960 stereo frames out.
 *
 * The decoder is configured with a pre-skip of zero so it never trims samples: packet N always maps
 * to output frames [960 N, 960 N + 960), which keeps the sample timeline trivially aligned with the
 * packet timestamps (the host has already folded the encoder look-ahead into those).
 */
class MediaCodecOpusDecoder : OpusFrameDecoder {
    private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L
    private var floatOutput = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, Proto.SAMPLE_RATE, Proto.CHANNELS)
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        head.put("OpusHead".toByteArray(Charsets.US_ASCII))
        head.put(1)                         // version
        head.put(Proto.CHANNELS.toByte())   // channel count
        head.putShort(0)                    // pre-skip
        head.putInt(Proto.SAMPLE_RATE)      // input sample rate (informational)
        head.putShort(0)                    // output gain
        head.put(0)                         // mapping family 0 (mono / stereo)
        head.flip()
        format.setByteBuffer("csd-0", head)
        format.setByteBuffer("csd-1", nativeLong(0))               // codec delay, ns
        format.setByteBuffer("csd-2", nativeLong(80_000_000))      // seek pre-roll, ns
        codec.configure(format, null, null, 0)
        codec.start()
    }

    private fun nativeLong(value: Long): ByteBuffer =
        ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).also { it.flip() }

    override fun decode(packet: ByteArray, length: Int, out: FloatArray): Boolean {
        val inIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inIndex < 0) return false
        val myPts = ptsUs
        ptsUs += Proto.FRAME_US
        val input = codec.getInputBuffer(inIndex)!!
        input.clear()
        input.put(packet, 0, length)
        codec.queueInputBuffer(inIndex, 0, length, myPts, 0)

        var waitedUs = 0L
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, POLL_US)
            if (outIndex >= 0) {
                // Anything older than our packet is a straggler from a decode that timed out earlier.
                val mine = info.presentationTimeUs >= myPts
                if (mine) copyOut(codec.getOutputBuffer(outIndex), out)
                codec.releaseOutputBuffer(outIndex, false)
                if (mine) return true
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                floatOutput = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            } else {
                waitedUs += POLL_US
                if (waitedUs >= OUTPUT_TIMEOUT_US) return false
            }
        }
    }

    private fun copyOut(buffer: ByteBuffer?, out: FloatArray) {
        java.util.Arrays.fill(out, 0f)
        if (buffer == null || info.size <= 0) return
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.order(ByteOrder.nativeOrder())
        val wanted = Proto.FRAME_SAMPLES * Proto.CHANNELS
        if (floatOutput) {
            val fb = buffer.asFloatBuffer()
            val n = minOf(fb.remaining(), wanted)
            // If the codec ever returns a short block, keep it aligned to the end of the frame.
            fb.get(out, wanted - n, n)
        } else {
            val sb = buffer.asShortBuffer()
            val n = minOf(sb.remaining(), wanted)
            val base = wanted - n
            for (i in 0 until n) out[base + i] = sb.get(i) * (1f / 32768f)
        }
    }

    fun release() {
        try {
            codec.stop()
        } catch (e: Exception) {
            // already stopped
        }
        codec.release()
    }

    companion object {
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val POLL_US = 5_000L
        private const val OUTPUT_TIMEOUT_US = 40_000L
    }
}
