package io.github.ugoyel.hearby.host

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.content.pm.ApplicationInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
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

    /** Voice level, 0..1, for the DJ screen. */
    @Volatile var level = 0f
        private set

    /** The voice goes out only while open (push-to-talk); in between, the tape holds silence. */
    @Volatile var open = false
    private var openGain = 0f

    /** True while the microphone hears someone (the noise gate is open). */
    @Volatile var hearing = false
        private set

    /** Which microphone: "Phone mic", or the headset's when a wired or USB headset is plugged in. */
    @Volatile var inputName = "Phone mic"
        private set

    // Debug builds only: `adb shell run-as io.github.ugoyel.hearby touch cache/test-voice` adds a
    // 330 Hz tone to the microphone, to check the whole path on an emulator (which has no voice).
    private val testTone = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
        java.io.File(context.cacheDir, "test-voice").exists()
    private var tonePhase = 0.0

    /** False if the microphone could not be opened (no permission, or busy). */
    fun start(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val r = open() ?: return false
        recorder = r
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
                    headset()?.let { device ->
                        r.preferredDevice = device // a wired or USB headset's mic, when one is plugged in
                        inputName = "Headset mic"
                    }
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

    /** A wired or USB headset with a microphone. (Bluetooth earbuds' mics only work in phone-call mode.) */
    private fun headset(): AudioDeviceInfo? {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
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
            if (testTone) {
                for (i in 0 until n) {
                    chunk[i] += (0.05 * kotlin.math.sin(tonePhase)).toFloat()
                    tonePhase += 2 * Math.PI * 330 / Proto.SAMPLE_RATE
                }
            }
            processor.process(chunk, n)
            hearing = processor.open
            // Opening and closing glide over 10 ms, so pressing the button never clicks.
            val target = if (open) 1f else 0f
            for (i in 0 until n) {
                openGain += (target - openGain).coerceIn(-OPEN_STEP, OPEN_STEP)
                chunk[i] *= openGain
            }
            tape.write(chunk, n)
            // Tie samples to the clock the timeline uses: the recorder's own capture timestamps when
            // they make sense, or else "the newest sample was captured just now".
            val ok = r.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS &&
                kotlin.math.abs(stamp.framePosition - tape.samplesWritten) < Proto.SAMPLE_RATE / 2
            if (ok) {
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
