package io.github.ugoyel.hearby.core

/**
 * Hearby wire protocol, version 1.
 *
 * This file is mirrored byte-for-byte by ios/Hearby/Core/Protocol.swift, and both sides are checked
 * against the same hex test vectors (see ProtocolTest / ProtocolTests). The full spec lives in
 * docs/PROTOCOL.md.
 *
 * Every datagram starts with a 4-byte prelude:  'H' 'B' <version> <type>.
 * All multi-byte integers are unsigned big-endian (network byte order).
 */
object Proto {
    const val MAGIC0 = 0x48 // 'H'
    const val MAGIC1 = 0x42 // 'B'
    const val VERSION = 1

    /** Well-known UDP port of a host. Advertised over mDNS too, so a fallback port still works. */
    const val PORT = 47800
    const val SERVICE_TYPE = "_hearby._udp"

    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val FRAME_MS = 20
    const val FRAME_SAMPLES = 960
    const val FRAME_US = 20_000L

    const val HEADER_SIZE = 4
    const val AUDIO_HEADER_SIZE = 16

    /** Largest possible single-frame Opus packet (RFC 6716 §3.2.1). */
    const val MAX_PAYLOAD = 1275
    const val MAX_DATAGRAM = 1500
    const val MAX_NAME_BYTES = 48

    const val TYPE_AUDIO = 0x01
    const val TYPE_JOIN = 0x02
    const val TYPE_JOIN_ACK = 0x03
    const val TYPE_SYNC_REQ = 0x04
    const val TYPE_SYNC_RESP = 0x05
    const val TYPE_LEAVE = 0x06
    const val TYPE_BYE = 0x07
    const val TYPE_DISCOVER = 0x08
    const val TYPE_DISCOVER_RESP = 0x09
    const val TYPE_NOW_PLAYING = 0x0A
    const val TYPE_CHIRP = 0x0B
    const val TYPE_OFFSET = 0x0C
    const val TYPE_VIDEO = 0x0D

    const val PLATFORM_ANDROID = 1
    const val PLATFORM_IOS = 2
    /** A browser on the DJ phone's guest page (host/WebGuests.kt); never sent over UDP. */
    const val PLATFORM_WEB = 3

    const val STATUS_CONNECTING = 0
    const val STATUS_BUFFERING = 1
    const val STATUS_SYNCED = 2
    const val STATUS_LATE = 3
}

class JoinMsg(val clientId: Long, val platform: Int, val name: String)

class JoinAckMsg(
    val streamId: Long,
    val sampleRate: Int,
    val channels: Int,
    val frameMs: Int,
    val playoutDelayMs: Int,
    val hostName: String,
)

class SyncReqMsg(
    val clientId: Long,
    val t0Us: Long,
    val rttUs: Long,
    val bufferMs: Int,
    val status: Int,
    /** Everything the listener adds to the playout delay (slider + calibration + fallback), µs. */
    val adjustUs: Long = 0,
    /** Byte 23: bit 0 plays through Bluetooth, bits 1-7 audio lost recently in % (0 from older apps). */
    val flags: Int = 0,
) {
    val bluetooth: Boolean get() = flags and 1 != 0
    val lossPercent: Int get() = (flags ushr 1).coerceAtMost(100)
}

class SyncRespMsg(val streamId: Long, val t0Us: Long, val t1Us: Long, val t2Us: Long, val playoutDelayMs: Int)

class DiscoverRespMsg(val nonce: Long, val streamId: Long, val listeners: Int, val hostName: String)

class NowPlayingMsg(val streamId: Long, val playing: Boolean, val title: String, val artist: String)

class ChirpMsg(val streamId: Long, val clientId: Long, val mediaTimeUs: Long, val checkId: Int)

class OffsetMsg(val streamId: Long, val clientId: Long, val adjustUs: Long, val checkId: Int, val errorUs: Int)

/** The party's YouTube video, or [timeline] null when no video is on. */
/** [duck]: the DJ's karaoke mic is on, so YouTube players play a little quieter. */
class VideoMsg(val streamId: Long, val timeline: VideoTimeline?, val duck: Boolean = false)

/** Stateless encode/decode helpers. Decoders return null on anything malformed. */
object Wire {
    const val SYNC_REQ_SIZE = 28
    /** SYNC_REQ before the adjust field was added; still accepted. */
    const val SYNC_REQ_MIN_SIZE = 24
    const val CHIRP_SIZE = 24
    const val OFFSET_SIZE = 20
    const val SYNC_RESP_SIZE = 36
    private const val SYNC_RESP_T2_OFFSET = 24

    // ---- primitives ---------------------------------------------------------------------------

    fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte()
        b[off + 1] = v.toByte()
    }

    fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    fun putU64(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = (v ushr (56 - 8 * i)).toByte()
    }

    fun getU8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF

    fun getU16(b: ByteArray, off: Int): Int = (getU8(b, off) shl 8) or getU8(b, off + 1)

    fun getU32(b: ByteArray, off: Int): Long =
        (getU8(b, off).toLong() shl 24) or (getU8(b, off + 1).toLong() shl 16) or
            (getU8(b, off + 2).toLong() shl 8) or getU8(b, off + 3).toLong()

    fun getU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or getU8(b, off + i).toLong()
        return v
    }

    private fun header(size: Int, type: Int): ByteArray {
        val b = ByteArray(size)
        writeHeader(b, type)
        return b
    }

    fun writeHeader(b: ByteArray, type: Int) {
        b[0] = Proto.MAGIC0.toByte()
        b[1] = Proto.MAGIC1.toByte()
        b[2] = Proto.VERSION.toByte()
        b[3] = type.toByte()
    }

    /** Packet type, or -1 if this is not a Hearby v1 datagram. */
    fun typeOf(b: ByteArray, len: Int): Int {
        if (len < Proto.HEADER_SIZE) return -1
        if (getU8(b, 0) != Proto.MAGIC0 || getU8(b, 1) != Proto.MAGIC1) return -1
        if (getU8(b, 2) != Proto.VERSION) return -1
        return getU8(b, 3)
    }

    /** A length-prefixed UTF-8 string field. */
    fun nameField(name: String): ByteArray {
        val nb = nameBytes(name)
        return byteArrayOf(nb.size.toByte()) + nb
    }

    fun readNameField(b: ByteArray, off: Int, len: Int): String? = readName(b, off, len)

    private fun nameBytes(name: String): ByteArray {
        val raw = name.toByteArray(Charsets.UTF_8)
        if (raw.size <= Proto.MAX_NAME_BYTES) return raw
        // Truncate on a UTF-8 character boundary.
        var n = Proto.MAX_NAME_BYTES
        while (n > 0 && (raw[n].toInt() and 0xC0) == 0x80) n--
        return raw.copyOf(n)
    }

    private fun readName(b: ByteArray, off: Int, len: Int): String? {
        if (off >= len) return null
        val n = getU8(b, off)
        if (off + 1 + n > len) return null
        return String(b, off + 1, n, Charsets.UTF_8)
    }

    // ---- AUDIO (0x01)  host -> client -----------------------------------------------------------
    //  0  'H' 'B' ver type
    //  4  u32 sequence number        (+1 per 20 ms frame)
    //  8  u64 timestamp              (host monotonic clock, microseconds: capture time of the first
    //                                 PCM sample this packet decodes to)
    // 16  Opus payload               (one 20 ms frame, 48 kHz stereo; length = datagram - 16)

    fun writeAudioHeader(b: ByteArray, seq: Long, timestampUs: Long) {
        writeHeader(b, Proto.TYPE_AUDIO)
        putU32(b, 4, seq and 0xFFFFFFFFL)
        putU64(b, 8, timestampUs)
    }

    fun audioSeq(b: ByteArray): Long = getU32(b, 4)

    fun audioTimestampUs(b: ByteArray): Long = getU64(b, 8)

    // ---- JOIN (0x02)  client -> host ------------------------------------------------------------
    //  4  u32 clientId   8  u8 platform   9  u8 nameLen   10  name (UTF-8)

    fun encodeJoin(clientId: Long, platform: Int, name: String): ByteArray {
        val nb = nameBytes(name)
        val b = header(10 + nb.size, Proto.TYPE_JOIN)
        putU32(b, 4, clientId)
        b[8] = platform.toByte()
        b[9] = nb.size.toByte()
        nb.copyInto(b, 10)
        return b
    }

    fun decodeJoin(b: ByteArray, len: Int): JoinMsg? {
        if (len < 10) return null
        val name = readName(b, 9, len) ?: return null
        return JoinMsg(getU32(b, 4), getU8(b, 8), name)
    }

    // ---- JOIN_ACK (0x03)  host -> client ----------------------------------------------------------
    //  4  u32 streamId   8  u32 sampleRate   12  u8 channels   13  u8 frameMs
    // 14  u16 playoutDelayMs   16  u8 nameLen   17  hostName (UTF-8)

    fun encodeJoinAck(streamId: Long, playoutDelayMs: Int, hostName: String): ByteArray {
        val nb = nameBytes(hostName)
        val b = header(17 + nb.size, Proto.TYPE_JOIN_ACK)
        putU32(b, 4, streamId)
        putU32(b, 8, Proto.SAMPLE_RATE.toLong())
        b[12] = Proto.CHANNELS.toByte()
        b[13] = Proto.FRAME_MS.toByte()
        putU16(b, 14, playoutDelayMs)
        b[16] = nb.size.toByte()
        nb.copyInto(b, 17)
        return b
    }

    fun decodeJoinAck(b: ByteArray, len: Int): JoinAckMsg? {
        if (len < 17) return null
        val name = readName(b, 16, len) ?: return null
        return JoinAckMsg(getU32(b, 4), getU32(b, 8).toInt(), getU8(b, 12), getU8(b, 13), getU16(b, 14), name)
    }

    // ---- SYNC_REQ (0x04)  client -> host   (also the keep-alive) --------------------------------
    //  4  u32 clientId   8  u64 t0 (client clock, us)   16  u32 rttUs
    // 20  u16 bufferMs   22  u8 status   23  u8 flags (bit 0 Bluetooth, bits 1-7 loss %)   24  i32 adjustUs
    // (the 24-byte form without adjustUs is still accepted)

    /** SYNC_REQ byte 23: what the host's auto-tuning needs to know about this listener. */
    fun syncFlags(bluetooth: Boolean, lossPercent: Float): Int =
        (if (bluetooth) 1 else 0) or (Math.round(lossPercent).coerceIn(0, 100) shl 1)

    fun encodeSyncReq(clientId: Long, t0Us: Long, rttUs: Long, bufferMs: Int, status: Int, adjustUs: Long = 0, flags: Int = 0): ByteArray {
        val b = header(SYNC_REQ_SIZE, Proto.TYPE_SYNC_REQ)
        putU32(b, 4, clientId)
        putU64(b, 8, t0Us)
        putU32(b, 16, rttUs.coerceIn(0, 0xFFFFFFFFL))
        putU16(b, 20, bufferMs.coerceIn(0, 0xFFFF))
        b[22] = status.toByte()
        b[23] = flags.toByte()
        putU32(b, 24, adjustUs.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()) and 0xFFFFFFFFL)
        return b
    }

    fun decodeSyncReq(b: ByteArray, len: Int): SyncReqMsg? {
        if (len < SYNC_REQ_MIN_SIZE) return null
        val adjust = if (len >= SYNC_REQ_SIZE) getU32(b, 24).toInt().toLong() else 0L
        return SyncReqMsg(getU32(b, 4), getU64(b, 8), getU32(b, 16), getU16(b, 20), getU8(b, 22), adjust, getU8(b, 23))
    }

    // ---- SYNC_RESP (0x05)  host -> client ---------------------------------------------------------
    //  4  u32 streamId   8  u64 t0 (echo)   16  u64 t1 (host rx, us)   24  u64 t2 (host tx, us)
    // 32  u16 playoutDelayMs   34  u16 reserved

    fun encodeSyncResp(streamId: Long, t0Us: Long, t1Us: Long, t2Us: Long, playoutDelayMs: Int): ByteArray {
        val b = header(SYNC_RESP_SIZE, Proto.TYPE_SYNC_RESP)
        putU32(b, 4, streamId)
        putU64(b, 8, t0Us)
        putU64(b, 16, t1Us)
        putU64(b, SYNC_RESP_T2_OFFSET, t2Us)
        putU16(b, 32, playoutDelayMs)
        return b
    }

    /** Stamp the transmit time as late as possible, right before the datagram is sent. */
    fun patchSyncRespT2(b: ByteArray, t2Us: Long) = putU64(b, SYNC_RESP_T2_OFFSET, t2Us)

    fun decodeSyncResp(b: ByteArray, len: Int): SyncRespMsg? {
        if (len < SYNC_RESP_SIZE) return null
        return SyncRespMsg(getU32(b, 4), getU64(b, 8), getU64(b, 16), getU64(b, 24), getU16(b, 32))
    }

    // ---- LEAVE (0x06) client -> host:  4 u32 clientId ---------------------------------------------
    // ---- BYE   (0x07) host -> client:  4 u32 streamId ---------------------------------------------
    // ---- DISCOVER (0x08) client -> host (unicast or broadcast):  4 u32 nonce ------------------------

    fun encodeLeave(clientId: Long): ByteArray = header(8, Proto.TYPE_LEAVE).also { putU32(it, 4, clientId) }

    fun encodeBye(streamId: Long): ByteArray = header(8, Proto.TYPE_BYE).also { putU32(it, 4, streamId) }

    fun encodeDiscover(nonce: Long): ByteArray = header(8, Proto.TYPE_DISCOVER).also { putU32(it, 4, nonce) }

    /** Body of LEAVE / BYE / DISCOVER: a single u32, or -1 if truncated. */
    fun decodeU32Body(b: ByteArray, len: Int): Long = if (len < 8) -1 else getU32(b, 4)

    // ---- DISCOVER_RESP (0x09)  host -> client -----------------------------------------------------
    //  4  u32 nonce (echo)   8  u32 streamId   12  u8 listeners   13  u8 nameLen   14  hostName

    fun encodeDiscoverResp(nonce: Long, streamId: Long, listeners: Int, hostName: String): ByteArray {
        val nb = nameBytes(hostName)
        val b = header(14 + nb.size, Proto.TYPE_DISCOVER_RESP)
        putU32(b, 4, nonce)
        putU32(b, 8, streamId)
        b[12] = listeners.coerceIn(0, 255).toByte()
        b[13] = nb.size.toByte()
        nb.copyInto(b, 14)
        return b
    }

    fun decodeDiscoverResp(b: ByteArray, len: Int): DiscoverRespMsg? {
        if (len < 14) return null
        val name = readName(b, 13, len) ?: return null
        return DiscoverRespMsg(getU32(b, 4), getU32(b, 8), getU8(b, 12), name)
    }
}

/** Messages added for the DJ's own music and the sound check. */
object WireParty {
    // ---- NOW_PLAYING (0x0A)  host -> client ---------------------------------------------------------
    //  4  u32 streamId   8  u8 flags (bit 0: playing)   9  u8 titleLen   10  title   then u8 artistLen, artist

    fun encodeNowPlaying(streamId: Long, playing: Boolean, title: String, artist: String): ByteArray {
        val t = Wire.nameField(title)
        val a = Wire.nameField(artist)
        val b = ByteArray(9 + t.size + a.size)
        Wire.writeHeader(b, Proto.TYPE_NOW_PLAYING)
        Wire.putU32(b, 4, streamId)
        b[8] = if (playing) 1 else 0
        t.copyInto(b, 9)
        a.copyInto(b, 9 + t.size)
        return b
    }

    fun decodeNowPlaying(b: ByteArray, len: Int): NowPlayingMsg? {
        if (len < 11) return null
        val title = Wire.readNameField(b, 9, len) ?: return null
        val artistAt = 10 + Wire.getU8(b, 9)
        val artist = Wire.readNameField(b, artistAt, len) ?: return null
        return NowPlayingMsg(Wire.getU32(b, 4), Wire.getU8(b, 8) and 1 != 0, title, artist)
    }

    // ---- CHIRP (0x0B)  host -> client ---------------------------------------------------------------
    //  4  u32 streamId   8  u32 clientId (who should chirp)   12  u64 mediaTimeUs
    // 20  u16 checkId   22  u16 reserved

    fun encodeChirp(streamId: Long, clientId: Long, mediaTimeUs: Long, checkId: Int): ByteArray {
        val b = ByteArray(Wire.CHIRP_SIZE)
        Wire.writeHeader(b, Proto.TYPE_CHIRP)
        Wire.putU32(b, 4, streamId)
        Wire.putU32(b, 8, clientId)
        Wire.putU64(b, 12, mediaTimeUs)
        Wire.putU16(b, 20, checkId)
        return b
    }

    fun decodeChirp(b: ByteArray, len: Int): ChirpMsg? {
        if (len < Wire.CHIRP_SIZE) return null
        return ChirpMsg(Wire.getU32(b, 4), Wire.getU32(b, 8), Wire.getU64(b, 12), Wire.getU16(b, 20))
    }

    // ---- OFFSET (0x0C)  host -> client --------------------------------------------------------------
    //  4  u32 streamId   8  u32 clientId   12  i32 adjustUs (add to the current correction)
    // 16  u16 checkId (apply each id once)   18  u16 errorUs (|error| measured, capped at 65535)

    fun encodeOffset(streamId: Long, clientId: Long, adjustUs: Long, checkId: Int, errorUs: Int): ByteArray {
        val b = ByteArray(Wire.OFFSET_SIZE)
        Wire.writeHeader(b, Proto.TYPE_OFFSET)
        Wire.putU32(b, 4, streamId)
        Wire.putU32(b, 8, clientId)
        Wire.putU32(b, 12, adjustUs.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()) and 0xFFFFFFFFL)
        Wire.putU16(b, 16, checkId)
        Wire.putU16(b, 18, errorUs.coerceIn(0, 0xFFFF))
        return b
    }

    fun decodeOffset(b: ByteArray, len: Int): OffsetMsg? {
        if (len < Wire.OFFSET_SIZE) return null
        return OffsetMsg(Wire.getU32(b, 4), Wire.getU32(b, 8), Wire.getU32(b, 12).toInt().toLong(), Wire.getU16(b, 16), Wire.getU16(b, 18))
    }
}

/** YouTube mode: the shared video timeline. */
object WireVideo {
    // ---- VIDEO (0x0D)  host -> client ---------------------------------------------------------------
    //  4  u32 streamId   8  u32 version   12  u8 flags (bit 0: playing, bit 1: a video is on, bit 2: duck,
    //  the karaoke mic is on)   13  u64 anchorHostUs   21  i64 positionUs   29  u8 idLen, videoId
    //  then u8 titleLen, title

    fun encode(streamId: Long, t: VideoTimeline?, duck: Boolean = false): ByteArray {
        val id = Wire.nameField(t?.videoId.orEmpty())
        val title = Wire.nameField(t?.title.orEmpty())
        val b = ByteArray(29 + id.size + title.size)
        Wire.writeHeader(b, Proto.TYPE_VIDEO)
        Wire.putU32(b, 4, streamId)
        Wire.putU32(b, 8, (t?.version ?: 0) and 0xFFFFFFFFL)
        b[12] = ((if (t?.playing == true) 1 else 0) or (if (t != null) 2 else 0) or (if (duck) 4 else 0)).toByte()
        Wire.putU64(b, 13, t?.anchorHostUs ?: 0)
        Wire.putU64(b, 21, t?.positionUs ?: 0)
        id.copyInto(b, 29)
        title.copyInto(b, 29 + id.size)
        return b
    }

    fun decode(b: ByteArray, len: Int): VideoMsg? {
        if (len < 31) return null
        val id = Wire.readNameField(b, 29, len) ?: return null
        val title = Wire.readNameField(b, 30 + Wire.getU8(b, 29), len) ?: return null
        val flags = Wire.getU8(b, 12)
        val timeline = if (flags and 2 == 0 || id.isEmpty()) null else VideoTimeline(
            videoId = id, title = title, playing = flags and 1 != 0,
            anchorHostUs = Wire.getU64(b, 13), positionUs = Wire.getU64(b, 21), version = Wire.getU32(b, 8),
        )
        return VideoMsg(Wire.getU32(b, 4), timeline, duck = flags and 4 != 0)
    }
}

/** Opus TOC helpers (RFC 6716 §3.1) used to sanity-check what the platform encoder emits. */
object OpusToc {
    /** Duration of one packet in microseconds, or -1 if it cannot be determined. */
    fun packetDurationUs(b: ByteArray, off: Int, len: Int): Int {
        if (len < 1) return -1
        val toc = b[off].toInt() and 0xFF
        val config = toc ushr 3
        val frameUs = when {
            config < 12 -> intArrayOf(10_000, 20_000, 40_000, 60_000)[config and 3] // SILK
            config < 16 -> if (config and 1 == 0) 10_000 else 20_000                 // hybrid
            else -> intArrayOf(2_500, 5_000, 10_000, 20_000)[config and 3]           // CELT
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (len < 2) return -1 else b[off + 1].toInt() and 0x3F
        }
        return frameUs * frames
    }
}
