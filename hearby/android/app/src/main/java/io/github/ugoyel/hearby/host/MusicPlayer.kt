package io.github.ugoyel.hearby.host

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import io.github.ugoyel.hearby.core.Proto
import io.github.ugoyel.hearby.core.Resampler
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

/** A song picked from the phone. */
data class Song(val uri: Uri, val title: String, val artist: String, val durationMs: Long)

/**
 * The DJ's music: a queue of songs saved on the phone, decoded to the 48 kHz stereo stream the
 * party runs on.
 *
 * The party host pulls one 20 ms frame at a time from [pull] on its sending thread, music or
 * silence. Opening a song (which can take a moment) happens on a background thread, and the switch
 * happens on the sending thread, so a frame is never late because of it. Controls may be called
 * from any thread.
 */
class MusicPlayer(context: Context) {
    data class Snapshot(
        val queue: List<Song> = emptyList(),
        val index: Int = -1,
        val playing: Boolean = false,
        val positionMs: Long = 0,
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val appContext = context.applicationContext
    private val loader = Executors.newSingleThreadExecutor { Thread(it, "hearby-loader") }
    private val lock = Any()

    // Guarded by lock.
    private val queue = ArrayList<Song>()
    private var index = -1
    private var playing = false
    private var wanted = 0 // generation of the song the user asked for
    private var opening = false // the loader is working on `wanted`
    private var stale = false // the current song must be (re)opened before it can play
    private var error: String? = null

    private class Opened(val generation: Int, val decoder: TrackDecoder?, val error: String?)

    private val pending = AtomicReference<Opened?>(null)

    // Sending thread only.
    private var decoder: TrackDecoder? = null
    private var decoderGeneration = -1

    @Volatile private var positionFrames = 0L

    /** Silences the music without changing what is queued or playing (used by the sound check). */
    @Volatile var hold = false

    /** Asked when a song ends: how many silent 20 ms frames to leave before the next one (usually 0). */
    @Volatile var gapBetweenSongs: () -> Int = { 0 }
    private var gapFrames = 0 // sending thread only

    /** Resolve titles in the background, then append. Starts playing if nothing was queued. */
    fun addUris(uris: List<Uri>) {
        loader.execute {
            val songs = uris.mapNotNull { describe(it) }
            synchronized(lock) {
                val wasEmpty = queue.isEmpty()
                queue.addAll(songs)
                if (wasEmpty && songs.isNotEmpty()) {
                    index = 0
                    playing = true
                    openLocked()
                }
            }
        }
    }

    fun play(i: Int) = synchronized(lock) {
        if (i !in queue.indices) return
        index = i
        playing = true
        openLocked()
    }

    fun toggle() = synchronized(lock) {
        if (index < 0 && queue.isNotEmpty()) {
            index = 0
            openLocked()
        }
        playing = !playing && index >= 0
        if (playing && stale) openLocked() // e.g. after a song failed to open: try again on request
    }

    /** Pause (for example when the DJ switches to a YouTube video). */
    fun pause() = synchronized(lock) { playing = false }

    fun next() = synchronized(lock) { advanceLocked() }

    /** Restart the song, or go to the previous one if it has only just started. */
    fun previous() = synchronized(lock) {
        if (index < 0) return
        if (positionFrames < 3 * Proto.SAMPLE_RATE && index > 0) index--
        openLocked()
    }

    fun remove(i: Int) = synchronized(lock) {
        if (i !in queue.indices) return
        queue.removeAt(i)
        when {
            queue.isEmpty() -> {
                index = -1
                playing = false
                openLocked()
            }
            i < index -> index--
            i == index -> {
                if (index >= queue.size) {
                    index = queue.size - 1
                    playing = false
                }
                openLocked()
            }
        }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(queue.toList(), index, playing, positionFrames * 1000 / Proto.SAMPLE_RATE, loading = opening, error = error)
    }

    /**
     * Next song, or stop at the end of the queue. After a song that could not be played, stopping
     * must not reopen it, or a broken last song would be retried forever.
     */
    private fun advanceLocked(afterFailure: Boolean = false) {
        if (index + 1 < queue.size) {
            index++
            openLocked()
        } else {
            playing = false
            if (afterFailure) {
                stale = true
                ++wanted // drop the failed song's decoder (if any)
            } else {
                openLocked() // ready to replay the last song from the start
            }
        }
    }

    /** Ask the loader for the current song; whatever it opens is adopted only if still wanted. */
    private fun openLocked() {
        val generation = ++wanted
        positionFrames = 0
        stale = false
        val song = queue.getOrNull(index)
        opening = song != null
        if (song == null) return
        loader.execute {
            val opened = try {
                Opened(generation, TrackDecoder(appContext, song.uri), null)
            } catch (e: Exception) {
                Log.w(TAG, "could not open ${song.uri}", e)
                Opened(generation, null, "Couldn't play \"${song.title}\". Skipping it.")
            }
            pending.getAndSet(opened)?.decoder?.close()
        }
    }

    /**
     * Fill [out] with the next 20 ms (960 stereo frames, interleaved float). Returns true if it
     * contains music. Sending thread only.
     */
    fun pull(out: FloatArray): Boolean {
        adoptPending()
        val (isPlaying, gen) = synchronized(lock) { playing to wanted }
        val d = decoder
        java.util.Arrays.fill(out, 0f)
        if (gapFrames > 0) {
            gapFrames--
            return false
        }
        if (!isPlaying || hold || d == null || decoderGeneration != gen) return false
        val n = try {
            d.read(out, Proto.FRAME_SAMPLES)
        } catch (e: Exception) {
            Log.w(TAG, "decode failed", e)
            -1
        }
        if (n > 0) positionFrames += n
        if (n < Proto.FRAME_SAMPLES) {
            // Song over (or broken): fill with silence and move on.
            synchronized(lock) {
                if (n < 0) error = "Couldn't play \"${queue.getOrNull(index)?.title}\". Skipping it."
                if (wanted == gen) advanceLocked(afterFailure = n < 0)
            }
            gapFrames = gapBetweenSongs()
        }
        return n > 0
    }

    private fun adoptPending() {
        val opened = pending.getAndSet(null) ?: return
        synchronized(lock) {
            if (opened.generation != wanted) {
                opened.decoder?.close()
                return
            }
            opening = false
            if (opened.decoder != null) error = null
        }
        decoder?.close()
        decoder = opened.decoder
        decoderGeneration = opened.generation
        if (opened.decoder == null) {
            synchronized(lock) {
                error = opened.error
                if (wanted == opened.generation) advanceLocked(afterFailure = true)
            }
        }
    }

    fun release() {
        loader.shutdownNow()
        pending.getAndSet(null)?.decoder?.close()
        decoder?.close()
        decoder = null
    }

    private fun describe(uri: Uri): Song? {
        var title: String? = null
        var artist: String? = null
        var durationMs = 0L
        try {
            MediaMetadataRetriever().apply {
                setDataSource(appContext, uri)
                title = extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                artist = extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                durationMs = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "no metadata for $uri", e)
        }
        if (title.isNullOrBlank()) {
            title = try {
                appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
                }
            } catch (e: Exception) {
                null
            }
        }
        return Song(uri, title?.takeIf { it.isNotBlank() } ?: "Untitled", artist?.takeIf { it.isNotBlank() } ?: "", durationMs)
    }

    companion object {
        private const val TAG = "HearbyMusic"
    }
}

/**
 * Decodes one song (MP3, AAC/M4A, FLAC, Ogg/Opus, WAV, ... whatever the phone can decode) to
 * interleaved 48 kHz stereo float, resampling and up/down-mixing as needed.
 */
class TrackDecoder(context: Context, uri: Uri) : Closeable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false

    private var sourceRate = Proto.SAMPLE_RATE
    private var sourceChannels = 2
    private var floatPcm = false
    private var resampler: Resampler? = null
    private var speed = 1.0
    private var chunkIn = FloatArray(CHUNK * 2)
    private var chunkOut = FloatArray(0)

    private val fifo = FloatArray(FIFO_FRAMES * 2)
    private var fifoStart = 0
    private var fifoFrames = 0

    init {
        extractor.setDataSource(context, uri, null)
        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            throw IllegalArgumentException("no audio track")
        }
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            extractor.release()
            throw e
        }
        applyFormat(format)
    }

    /** Read up to [frames] frames into [out]. Returns how many; fewer than asked means the song ended. */
    fun read(out: FloatArray, frames: Int): Int {
        var guard = 0
        while (fifoFrames < frames && !outputDone && guard++ < 400) step()
        val n = minOf(frames, fifoFrames)
        for (i in 0 until n) {
            val at = ((fifoStart + i) % FIFO_FRAMES) * 2
            out[2 * i] = fifo[at]
            out[2 * i + 1] = fifo[at + 1]
        }
        fifoStart = (fifoStart + n) % FIFO_FRAMES
        fifoFrames -= n
        return n
    }

    private fun step() {
        if (!inputDone) {
            val inIndex = codec.dequeueInputBuffer(0)
            if (inIndex >= 0) {
                val buffer = codec.getInputBuffer(inIndex)!!
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        val outIndex = codec.dequeueOutputBuffer(info, 2_000)
        when {
            outIndex >= 0 -> {
                if (info.size > 0) codec.getOutputBuffer(outIndex)?.let { convert(it) }
                codec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> applyFormat(codec.outputFormat)
        }
    }

    private fun applyFormat(format: MediaFormat) {
        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        floatPcm = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
        if (sourceRate != Proto.SAMPLE_RATE && sourceRate > 0) {
            speed = sourceRate.toDouble() / Proto.SAMPLE_RATE
            resampler = Resampler(2, CHUNK, cutoff = minOf(1.0, 1.0 / speed))
            chunkOut = FloatArray((ceil(CHUNK / speed).toInt() + 8) * 2)
        } else {
            resampler = null
        }
    }

    /** Decoded PCM (any channel count, 16-bit or float) -> stereo float -> 48 kHz -> fifo. */
    private fun convert(buffer: ByteBuffer) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val b = buffer.slice().order(ByteOrder.nativeOrder())
        val bytesPerSample = if (floatPcm) 4 else 2
        val frames = info.size / (bytesPerSample * sourceChannels)
        var done = 0
        while (done < frames) {
            val n = minOf(CHUNK, frames - done)
            for (i in 0 until n) {
                val base = ((done + i) * sourceChannels) * bytesPerSample
                val l = sample(b, base)
                val r = if (sourceChannels > 1) sample(b, base + bytesPerSample) else l
                chunkIn[2 * i] = l
                chunkIn[2 * i + 1] = r
            }
            val r = resampler
            if (r == null) {
                push(chunkIn, n)
            } else {
                push(chunkOut, r.process(chunkIn, n, speed, chunkOut))
            }
            done += n
        }
    }

    private fun sample(b: ByteBuffer, at: Int): Float =
        if (floatPcm) b.getFloat(at) else b.getShort(at) * (1f / 32768f)

    private fun push(src: FloatArray, frames: Int) {
        val n = minOf(frames, FIFO_FRAMES - fifoFrames) // never happens with sane codecs; drop rather than overflow
        for (i in 0 until n) {
            val at = ((fifoStart + fifoFrames + i) % FIFO_FRAMES) * 2
            fifo[at] = src[2 * i]
            fifo[at + 1] = src[2 * i + 1]
        }
        fifoFrames += n
    }

    override fun close() {
        try {
            codec.stop()
        } catch (e: Exception) {
            // not started
        }
        codec.release()
        extractor.release()
    }

    companion object {
        private const val CHUNK = 1024
        private const val FIFO_FRAMES = 96_000 // 2 s
    }
}
