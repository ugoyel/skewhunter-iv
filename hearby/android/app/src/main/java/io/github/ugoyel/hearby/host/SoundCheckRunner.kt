package io.github.ugoyel.hearby.host

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import io.github.ugoyel.hearby.core.ChirpDetector
import io.github.ugoyel.hearby.core.Proto
import io.github.ugoyel.hearby.core.SoundCheckPlan
import kotlin.math.abs
import kotlin.math.roundToLong

/** One phone taking part in a sound check. */
class CheckParticipant(
    val id: Long,
    val name: String,
    /** Its current adjustments (slider, correction, fallback) in µs, as last reported. */
    val adjustUs: () -> Long,
)

/** What a sound check found for one phone. */
data class CheckResult(
    val heard: Boolean,
    /** How far off it was relative to the DJ phone before the sound check, ms (positive = late). */
    val beforeMs: Float,
    /** How far off it still was in the final round, before the last small fix, ms. */
    val afterMs: Float,
)

/**
 * Runs the sound check on the DJ phone (see core/SoundCheck.kt for the method).
 *
 * The microphone records for the length of the check only, into memory; nothing is stored or sent.
 * It is started from the app's own screen, so Android's while-in-use microphone rule is satisfied
 * without declaring a microphone foreground service.
 */
class SoundCheckRunner(
    context: Context,
    private val referenceId: Long,
    private val playoutDelayMs: Int,
    /** Media time (DJ clock, µs) of the frame being sent right now. */
    private val mediaNowUs: () -> Long,
    /** Ask phone [id] to chirp at [mediaTimeUs]. */
    private val requestChirp: (id: Long, mediaTimeUs: Long, checkId: Int) -> Unit,
    /** Tell phone [id] to shift by [adjustUs] (relative). */
    private val applyCorrection: (id: Long, adjustUs: Long, checkId: Int, errorUs: Int) -> Unit,
    private val onProgress: (String, Float) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    class Outcome(val results: Map<Long, CheckResult>, val failure: String?)

    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /** Blocking; run on a background thread. */
    @SuppressLint("MissingPermission") // the UI asks for RECORD_AUDIO before starting a sound check
    fun run(guests: List<CheckParticipant>, reference: CheckParticipant, checkIdBase: Int): Outcome {
        if (guests.isEmpty()) return Outcome(emptyMap(), "Nobody is listening yet. Let your friends join first.")
        val recorder = openRecorder() ?: return Outcome(emptyMap(), "Couldn't open the microphone.")
        val mic = MicTape(recorder)
        val reader = Thread({ mic.capture { !cancelled } }, "hearby-mic").also { it.start() }

        val before = HashMap<Long, Double>()
        val last = HashMap<Long, Double>()
        val heard = HashMap<Long, Boolean>()
        var failure: String? = null
        try {
            Thread.sleep(300) // let the microphone settle and produce its first timestamp
            for (round in 1..SoundCheckPlan.ROUNDS) {
                if (cancelled) break
                val checkId = (checkIdBase + round) and 0xFFFF
                onProgress(if (round == 1) "Listening to every phone…" else "Double-checking…", (round - 1f) / SoundCheckPlan.ROUNDS)
                val everyone = guests + reference
                val adjust = everyone.associate { it.id to it.adjustUs() }
                val start = mediaNowUs() + SoundCheckPlan.START_LEAD_US
                val slots = SoundCheckPlan.round(referenceId, guests.map { it.id }, start)
                repeat(3) { // UDP: say it three times; phones ignore repeats
                    for (s in slots) requestChirp(s.clientId, s.mediaTimeUs, checkId)
                    Thread.sleep(30)
                }

                // Wait until the last chirp must have been heard, then look for every one.
                val lastExpected = slots.maxOf { it.mediaTimeUs + playoutDelayMs * 1000L + adjust.getValue(it.clientId) }
                sleepUntilUs(lastExpected + SoundCheckPlan.WINDOW_AFTER_US + 200_000)
                val measurements = slots.map { slot ->
                    val nominal = slot.mediaTimeUs + playoutDelayMs * 1000L
                    val lookAt = nominal + adjust.getValue(slot.clientId)
                    val arrival = mic.find(lookAt - SoundCheckPlan.WINDOW_BEFORE_US, lookAt + SoundCheckPlan.WINDOW_AFTER_US)
                    SoundCheckPlan.Measurement(slot, arrival?.let { (it - nominal).toDouble() })
                }
                val corrections = SoundCheckPlan.corrections(measurements)
                if (corrections == null) {
                    failure = "Couldn't hear this phone's own beep. Turn its media volume up and try again."
                    break
                }
                for ((id, c) in corrections) {
                    heard[id] = c != null
                    if (c == null) continue
                    if (round == 1) before[id] = c
                    last[id] = c
                    applyCorrection(id, c.roundToLong(), checkId, abs(c).roundToLong().coerceAtMost(65_535).toInt())
                }
                Thread.sleep(1_200) // phones re-align with their new correction
            }
        } catch (e: InterruptedException) {
            failure = "Sound check stopped."
        } finally {
            cancelled = true
            reader.join(500)
            try {
                recorder.stop()
            } catch (e: Exception) {
                // not recording
            }
            recorder.release()
        }
        val results = guests.associate { g ->
            g.id to CheckResult(
                heard = heard[g.id] == true,
                beforeMs = (-(before[g.id] ?: 0.0) / 1000).toFloat(),
                afterMs = (-(last[g.id] ?: 0.0) / 1000).toFloat(),
            )
        }
        return Outcome(results, failure)
    }

    private fun sleepUntilUs(hostUs: Long) {
        while (!cancelled) {
            val wait = hostUs - System.nanoTime() / 1000
            if (wait <= 0) return
            Thread.sleep(minOf(wait / 1000 + 1, 100))
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord? {
        // Prefer the raw microphone: no automatic gain, noise suppression or echo cancellation,
        // all of which could smear or remove the chirps.
        val unprocessed = audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = listOfNotNull(
            if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else null,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        val minBytes = AudioRecord.getMinBufferSize(Proto.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        for (source in sources) {
            try {
                val r = AudioRecord(source, Proto.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBytes, 48_000))
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

    /** The microphone recording, with a mapping from sample index to the DJ phone's clock. */
    private class MicTape(private val recorder: AudioRecord) {
        private val samples = ShortArray(MAX_SECONDS * Proto.SAMPLE_RATE)
        @Volatile private var length = 0

        // index -> time mapping: AudioRecord's own capture timestamps when available ...
        private val stamp = AudioTimestamp()
        @Volatile private var stampFrame = -1L
        @Volatile private var stampNs = 0L
        // ... otherwise the earliest "read returned" time seen, which is consistent across the check.
        @Volatile private var fallbackStartNs = Long.MAX_VALUE

        fun capture(keepGoing: () -> Boolean) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val chunk = ShortArray(960)
            while (keepGoing() && length < samples.size) {
                val n = recorder.read(chunk, 0, minOf(chunk.size, samples.size - length))
                if (n <= 0) break
                val now = System.nanoTime()
                System.arraycopy(chunk, 0, samples, length, n)
                length += n
                fallbackStartNs = minOf(fallbackStartNs, now - length * 1_000_000_000L / Proto.SAMPLE_RATE)
                if (recorder.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    stampFrame = stamp.framePosition
                    stampNs = stamp.nanoTime
                }
            }
        }

        private fun indexAt(hostUs: Long): Double {
            val ns = hostUs * 1000
            return if (stampFrame >= 0) {
                stampFrame + (ns - stampNs) * Proto.SAMPLE_RATE / 1e9
            } else {
                (ns - fallbackStartNs) * Proto.SAMPLE_RATE / 1e9
            }
        }

        private fun timeAtUs(index: Double): Long {
            val ns = if (stampFrame >= 0) {
                stampNs + (index - stampFrame) * 1e9 / Proto.SAMPLE_RATE
            } else {
                fallbackStartNs + index * 1e9 / Proto.SAMPLE_RATE
            }
            return (ns / 1000).toLong()
        }

        /** When (DJ clock, µs) a chirp arrived between [fromUs] and [toUs], or null. */
        fun find(fromUs: Long, toUs: Long): Long? {
            val from = indexAt(fromUs).toInt().coerceAtLeast(0)
            val to = indexAt(toUs).toInt().coerceAtMost(length)
            if (to - from < 4_800) return null
            val window = FloatArray(to - from) { samples[from + it] / 32768f }
            val hit = ChirpDetector.find(window, 0, window.size) ?: return null
            return timeAtUs(from + hit.position)
        }
    }

    companion object {
        private const val TAG = "HearbySoundCheck"
        private const val MAX_SECONDS = 60
    }
}
