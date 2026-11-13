package io.github.ugoyel.hearby.host

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Process
import android.util.Log
import io.github.ugoyel.hearby.core.Proto
import io.github.ugoyel.hearby.core.VoiceProcessor
import io.github.ugoyel.hearby.core.VoiceTape

/**
 * The DJ phone's microphone for karaoke: captures the voice, cleans it up ([VoiceProcessor]) and
 * keeps it on a [VoiceTape] with the moment each sample was captured, for the sender to mix in.
 * Only used while Hearby is on screen.
 */
class KaraokeMic(private val context: Context) {
    val tape = VoiceTape()
    private val processor = VoiceProcessor()
    @Volatile private var running = false
    private var thread: Thread? = null
    private var recorder: AudioRecord? = null
    private var echo: AcousticEchoCanceler? = null

    /** Voice level, 0..1, for the DJ screen. */
    @Volatile var level = 0f
        private set

    /** The voice goes out only while open (push-to-talk); in between, the tape holds silence. */
    @Volatile var open = false
    private var openGain = 0f

    /** False if the microphone could not be opened (no permission, or busy). */
    fun start(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val r = open() ?: return false
        recorder = r
        // Where the phone has one, the echo canceller takes this phone's own speaker out of the voice.
        echo = try {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true } else null
        } catch (e: Exception) {
            null
        }
        running = true
        thread = Thread(::loop, "hearby-karaoke").also { it.start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // already stopped
        }
        recorder?.release()
        recorder = null
        echo?.release()
        echo = null
        level = 0f
    }

    @Suppress("MissingPermission") // checked in start()
    private fun open(): AudioRecord? {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(Proto.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBytes = AudioRecord.getMinBufferSize(Proto.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        // VOICE_PERFORMANCE is Android's source for live performance (karaoke): low latency, no
        // processing that would make a singing voice pump. Plain MIC where a phone lacks it.
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_PERFORMANCE, MediaRecorder.AudioSource.MIC)) {
            try {
                val r = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minBytes, CHUNK * 4 * 4))
                    .build()
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    r.startRecording()
                    if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) return r
                }
                r.release()
            } catch (e: Exception) {
                Log.w(TAG, "mic source $source failed", e)
            }
        }
        return null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val r = recorder ?: return
        val chunk = FloatArray(CHUNK)
        val stamp = AudioTimestamp()
        var stamped = false
        while (running) {
            val n = r.read(chunk, 0, CHUNK, AudioRecord.READ_BLOCKING)
            if (n <= 0) break
            processor.process(chunk, n)
            // Opening and closing glide over 10 ms, so pressing the button never clicks.
            val target = if (open) 1f else 0f
            for (i in 0 until n) {
                openGain += (target - openGain).coerceIn(-OPEN_STEP, OPEN_STEP)
                chunk[i] *= openGain
            }
            tape.write(chunk, n)
            // Tie samples to the clock the timeline uses: the recorder's own capture timestamps,
            // or (on phones without them) "the newest sample was captured just now".
            if (r.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                tape.anchor(stamp.framePosition, stamp.nanoTime / 1000)
                stamped = true
            } else if (!stamped) {
                tape.anchor(tape.samplesWritten, System.nanoTime() / 1000)
            }
            var peak = 0f
            for (i in 0 until n) peak = maxOf(peak, kotlin.math.abs(chunk[i]))
            level = maxOf(peak.coerceAtMost(1f), level * 0.8f)
        }
    }

    private companion object {
        const val TAG = "HearbyKaraoke"
        const val CHUNK = 480 // 10 ms
        const val OPEN_STEP = 1f / 480
    }
}
