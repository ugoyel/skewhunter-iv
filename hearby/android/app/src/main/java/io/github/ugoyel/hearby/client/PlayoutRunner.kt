package io.github.ugoyel.hearby.client

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.github.ugoyel.hearby.codec.MediaCodecOpusDecoder
import io.github.ugoyel.hearby.core.AudioSink
import io.github.ugoyel.hearby.core.ClockSync
import io.github.ugoyel.hearby.core.JitterBuffer
import io.github.ugoyel.hearby.core.PlayoutEngine
import io.github.ugoyel.hearby.core.Proto
import io.github.ugoyel.hearby.core.SinkTimestamp
import kotlin.math.abs

/**
 * The playback half of a Hearby phone, shared by guests and by the DJ phone itself:
 *
 *   JitterBuffer ─▶ PlayoutEngine.pump() ─▶ MediaCodec Opus decode ─▶ drift-correcting resampler
 *                                        ─▶ AudioTrack (blocking write)
 *
 * AudioTrack.getTimestamp() tells the engine when written frames really reach the speaker or
 * Bluetooth link, which closes the loop. Whoever owns the runner fills the jitter buffer and keeps
 * the clock in sync; the runner handles output routing, focus, per-output slider memory, the
 * sound-check correction and chirps.
 */
class PlayoutRunner(
    context: Context,
    private val jitter: JitterBuffer,
    private val clock: ClockSync,
    /** Called (on the playout thread) when the output device changes. */
    private val onRouteChanged: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("hearby", Context.MODE_PRIVATE)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var track: AudioTrack? = null
    private var playThread: Thread? = null
    private var focusRequest: AudioFocusRequest? = null
    private var noisyRegistered = false

    /** The engine while playback is running (read-only use from other threads). */
    @Volatile var engine: PlayoutEngine? = null
        private set

    /** The host's common playout delay. */
    @Volatile var playoutDelayMs = 350

    /** Sound-check correction for this phone in this party, µs (positive plays later). */
    @Volatile var calibrationUs = 0L

    @Volatile var userOffsetMs = 0
        private set
    @Volatile var muted = false
        private set
    @Volatile var routeName = ""
        private set
    @Volatile var routeIsBluetooth = false
        private set
    @Volatile private var routeKey = ""

    /** Set if playback could not run on this device; the owner should end the session. */
    @Volatile var failure: String? = null
        private set

    fun start() {
        if (running) return
        running = true
        requestFocus()
        ContextCompat.registerReceiver(
            appContext, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
        playThread = Thread(::playoutLoop, "hearby-playout").also { it.start() }
    }

    fun stop() {
        if (!running) return
        running = false
        playThread?.join(1500)
        playThread = null
        if (noisyRegistered) {
            noisyRegistered = false
            try {
                appContext.unregisterReceiver(noisyReceiver)
            } catch (e: Exception) {
                // not registered
            }
        }
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** The manual sync slider. Remembered per output device (speaker, each Bluetooth device, ...). */
    fun setUserOffsetMs(ms: Int) {
        val v = ms.coerceIn(-500, 500)
        userOffsetMs = v
        if (routeKey.isNotEmpty()) prefs.edit { putInt(OFFSET_PREFIX + routeKey, v) }
    }

    /** Throw away any fallback delay and line up from scratch. */
    fun resync() {
        engine?.requestRealign(resetLateBias = true)
    }

    fun setMuted(mute: Boolean) {
        if (!mute) requestFocus()
        muted = mute
    }

    /** Play the sound-check chirp at this point of the shared timeline. */
    fun scheduleTone(mediaTimeUs: Long) {
        engine?.tones?.schedule(mediaTimeUs)
    }

    /** A new party: forget the old timeline and the old sound-check correction. */
    fun resetStream() {
        calibrationUs = 0
        engine?.requestStreamReset()
    }

    /** Everything this phone adds to the host's playout delay, µs (reported to the host). */
    fun adjustUs(): Long = engine?.adjustUs() ?: (userOffsetMs * 1000L + calibrationUs)

    private fun playoutLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var decoder: MediaCodecOpusDecoder? = null
        var sink: TrackSink? = null
        try {
            decoder = MediaCodecOpusDecoder()
            sink = TrackSink()
            val e = PlayoutEngine(sink, decoder, jitter, clock)
            e.playoutDelayMs = playoutDelayMs
            e.userOffsetMs = userOffsetMs
            e.calibrationUs = calibrationUs
            engine = e
            var appliedMute = false
            var lastRouteCheckNs = 0L
            while (running) {
                // Settings are owned by other threads; apply them here, where the engine lives.
                e.playoutDelayMs = playoutDelayMs
                e.userOffsetMs = userOffsetMs
                e.calibrationUs = calibrationUs
                if (muted != appliedMute) {
                    appliedMute = muted
                    sink.setVolume(if (appliedMute) 0f else 1f)
                }
                e.pump()
                if (sink.consumeRebuilt()) e.requestRealign(resetLateBias = true)
                val now = System.nanoTime()
                if (now - lastRouteCheckNs > 500_000_000L) {
                    lastRouteCheckNs = now
                    checkRoute()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "playout failed", e)
            if (running) failure = "Audio playback failed on this device (${e.javaClass.simpleName})."
        } finally {
            engine = null
            sink?.release()
            decoder?.release()
        }
    }

    /**
     * AudioTrack as the engine's output. write() blocks while the track's buffer is full, which is
     * what paces the playout thread. getTimestamp() reports when frames actually hit the output
     * device, including whatever latency the audio HAL (or Bluetooth stack) knows about.
     */
    private inner class TrackSink : AudioSink {
        private var bufferFrames = 0 // set by build(); must be declared before audioTrack
        private var audioTrack: AudioTrack = build()
        private val stamp = AudioTimestamp()
        private var totalFrames = 0L   // everything ever written (the engine's frame counter)
        private var trackBase = 0L     // totalFrames when the current AudioTrack was created
        private var trackStartNs = System.nanoTime()
        private var lastPollNs = 0L
        private var lastGoodNs = 0L
        private var haveStamp = false     // a real AudioTrack timestamp
        private var haveEstimate = false  // a playback-head based estimate (fallback)
        private var cachedPosition = 0L
        private var cachedTimeNs = 0L
        private var rebuilt = false

        private fun build(): AudioTrack {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                // Nobody else gets to record what Hearby plays.
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(Proto.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            val minBytes = AudioTrack.getMinBufferSize(
                Proto.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT,
            )
            // Small enough to keep latency (and so the needed playout delay) low, large enough to
            // ride out scheduling hiccups of this thread.
            val bytes = maxOf(minBytes, TRACK_BUFFER_FRAMES * BYTES_PER_FRAME)
            val t = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            check(t.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack could not be created" }
            bufferFrames = t.bufferSizeInFrames
            t.play()
            track = t
            return t
        }

        override fun write(interleaved: FloatArray, frames: Int) {
            val wanted = frames * Proto.CHANNELS
            var done = 0
            while (done < wanted && running) {
                val n = audioTrack.write(interleaved, done, wanted - done, AudioTrack.WRITE_BLOCKING)
                if (n < 0) {
                    // ERROR_DEAD_OBJECT: the output device went away (e.g. Bluetooth disconnected).
                    Log.w(TAG, "AudioTrack.write error $n; rebuilding the track")
                    rebuild()
                    break
                }
                if (n == 0) {
                    // Should not happen with a playing track and a blocking write; never spin on it.
                    try {
                        Thread.sleep(5)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
                done += n
            }
            totalFrames += frames
        }

        private fun rebuild() {
            try {
                audioTrack.release()
            } catch (e: Exception) {
                // already dead
            }
            audioTrack = build()
            trackBase = totalFrames
            trackStartNs = System.nanoTime()
            haveStamp = false
            haveEstimate = false
            lastPollNs = 0
            rebuilt = true
        }

        fun consumeRebuilt(): Boolean = rebuilt.also { rebuilt = false }

        fun setVolume(v: Float) {
            audioTrack.setVolume(v)
        }

        override fun getTimestamp(out: SinkTimestamp): Boolean {
            val now = System.nanoTime()
            if (now - lastPollNs >= TIMESTAMP_POLL_NS) {
                lastPollNs = now
                val ok = try {
                    audioTrack.getTimestamp(stamp)
                } catch (e: Exception) {
                    false
                }
                val written = totalFrames - trackBase
                if (ok && stamp.framePosition > 0 && stamp.framePosition <= written &&
                    abs(now - stamp.nanoTime) < MAX_TIMESTAMP_AGE_NS
                ) {
                    cachedPosition = trackBase + stamp.framePosition
                    cachedTimeNs = stamp.nanoTime
                    lastGoodNs = now
                    haveStamp = true
                    haveEstimate = false
                } else if (haveStamp && now - lastGoodNs > STALE_TIMESTAMP_NS) {
                    haveStamp = false
                }
                if (!haveStamp && now - trackStartNs > FALLBACK_AFTER_NS) {
                    // Some routes never report timestamps. Estimate from the playback head instead;
                    // the manual offset slider absorbs whatever latency this cannot see.
                    val head = audioTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    if (head in 1..written) {
                        cachedPosition = trackBase + head
                        cachedTimeNs = now + hiddenLatencyNs()
                        haveEstimate = true
                    }
                }
            }
            if (!haveStamp && !haveEstimate) return false
            out.framePosition = cachedPosition
            out.timeNs = cachedTimeNs
            return true
        }

        /** Latency below the track buffer, from the non-SDK AudioTrack.getLatency() when available. */
        private fun hiddenLatencyNs(): Long {
            val total = try {
                (GET_LATENCY?.invoke(audioTrack) as? Int) ?: 0
            } catch (e: Exception) {
                0
            }
            val bufferMs = bufferFrames * 1000L / Proto.SAMPLE_RATE
            return maxOf(0L, total - bufferMs) * 1_000_000L
        }

        fun release() {
            track = null
            try {
                audioTrack.pause()
                audioTrack.flush()
            } catch (e: Exception) {
                // not playing
            }
            audioTrack.release()
        }
    }

    // ---- output route, focus, noisy ------------------------------------------------------------------

    @SuppressLint("InlinedApi") // newer device-type constants are only compared, never passed to the platform
    private fun checkRoute() {
        val device = try {
            track?.routedDevice
        } catch (e: Exception) {
            null
        } ?: return
        val name = device.productName?.toString().orEmpty()
        val key = "${device.type}:$name"
        if (key == routeKey) return
        val bluetooth = device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
            device.type == AudioDeviceInfo.TYPE_HEARING_AID
        routeKey = key
        routeIsBluetooth = bluetooth
        routeName = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "Phone speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
            else -> if (bluetooth) "Bluetooth: ${name.ifBlank { "device" }}" else name.ifBlank { "Audio output" }
        }
        // Every output has its own latency, so every output remembers its own slider position.
        userOffsetMs = prefs.getInt(OFFSET_PREFIX + key, 0)
        engine?.requestRealign(resetLateBias = true)
        onRouteChanged()
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> muted = true
            AudioManager.AUDIOFOCUS_GAIN -> muted = false
        }
    }

    private fun requestFocus() {
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener, mainHandler)
            .build()
            .also { focusRequest = it }
        audioManager.requestAudioFocus(request)
    }

    /** Headphones unplugged / Bluetooth dropped: do not suddenly blast from the speaker. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            muted = true
        }
    }

    companion object {
        private const val TAG = "HearbyPlayout"
        private const val OFFSET_PREFIX = "offset:"
        private const val BYTES_PER_FRAME = Proto.CHANNELS * 4
        private const val TRACK_BUFFER_FRAMES = 2880 // 60 ms

        private const val TIMESTAMP_POLL_NS = 100_000_000L
        private const val MAX_TIMESTAMP_AGE_NS = 1_000_000_000L
        private const val STALE_TIMESTAMP_NS = 1_500_000_000L
        private const val FALLBACK_AFTER_NS = 2_000_000_000L

        @Suppress("DiscouragedPrivateApi")
        private val GET_LATENCY = try {
            AudioTrack::class.java.getMethod("getLatency")
        } catch (e: Exception) {
            null
        }
    }
}
