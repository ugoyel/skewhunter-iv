import Foundation

/// Hearby wire protocol, version 1.
///
/// Mirrors android/.../core/Protocol.kt byte for byte; both sides are checked against the same hex
/// test vectors. The full spec lives in docs/PROTOCOL.md.
///
/// Every datagram starts with a 4-byte prelude: 'H' 'B' <version> <type>.
/// All multi-byte integers are unsigned big-endian (network byte order).
enum Proto {
    static let magic0: UInt8 = 0x48 // 'H'
    static let magic1: UInt8 = 0x42 // 'B'
    static let version: UInt8 = 1

    /// Well-known UDP port of a host. Also advertised over Bonjour, so a fallback port still works.
    static let port: UInt16 = 47800
    static let serviceType = "_hearby._udp"

    static let sampleRate = 48_000
    static let channels = 2
    static let frameMs = 20
    static let frameSamples = 960
    static let frameUs: Int64 = 20_000

    static let headerSize = 4
    static let audioHeaderSize = 16
    /// Largest possible single-frame Opus packet (RFC 6716 §3.2.1).
    static let maxPayload = 1275
    static let maxNameBytes = 48

    static let typeAudio: UInt8 = 0x01
    static let typeJoin: UInt8 = 0x02
    static let typeJoinAck: UInt8 = 0x03
    static let typeSyncReq: UInt8 = 0x04
    static let typeSyncResp: UInt8 = 0x05
    static let typeLeave: UInt8 = 0x06
    static let typeBye: UInt8 = 0x07
    static let typeDiscover: UInt8 = 0x08
    static let typeDiscoverResp: UInt8 = 0x09
    static let typeNowPlaying: UInt8 = 0x0A
    static let typeChirp: UInt8 = 0x0B
    static let typeOffset: UInt8 = 0x0C

    static let platformAndroid: UInt8 = 1
    static let platformIOS: UInt8 = 2

    static let statusConnecting: UInt8 = 0
    static let statusBuffering: UInt8 = 1
    static let statusSynced: UInt8 = 2
    static let statusLate: UInt8 = 3
}

struct JoinMsg: Equatable {
    var clientId: UInt32
    var platform: UInt8
    var name: String
}

struct JoinAckMsg: Equatable {
    var streamId: UInt32
    var sampleRate: UInt32
    var channels: UInt8
    var frameMs: UInt8
    var playoutDelayMs: UInt16
    var hostName: String
}

struct SyncReqMsg: Equatable {
    var clientId: UInt32
    var t0Us: UInt64
    var rttUs: UInt32
    var bufferMs: UInt16
    var status: UInt8
    /// Everything the listener adds to the playout delay (slider + sound-check correction + fallback), µs.
    var adjustUs: Int32 = 0
}

struct SyncRespMsg: Equatable {
    var streamId: UInt32
    var t0Us: UInt64
    var t1Us: UInt64
    var t2Us: UInt64
    var playoutDelayMs: UInt16
}

struct NowPlayingMsg: Equatable {
    var streamId: UInt32
    var playing: Bool
    var title: String
    var artist: String
}

struct ChirpMsg: Equatable {
    var streamId: UInt32
    var clientId: UInt32
    var mediaTimeUs: UInt64
    var checkId: UInt16
}

struct OffsetMsg: Equatable {
    var streamId: UInt32
    var clientId: UInt32
    var adjustUs: Int32
    var checkId: UInt16
    var errorUs: UInt16
}

struct DiscoverRespMsg: Equatable {
    var nonce: UInt32
    var streamId: UInt32
    var listeners: UInt8
    var hostName: String
}

/// Stateless encode/decode helpers. Decoders return nil on anything malformed.
enum Wire {
    static let syncReqSize = 28
    /// SYNC_REQ before the adjust field was added; still accepted.
    static let syncReqMinSize = 24
    static let chirpSize = 24
    static let offsetSize = 20
    static let syncRespSize = 36

    // MARK: primitives

    static func putU16(_ b: inout [UInt8], _ off: Int, _ v: UInt16) {
        b[off] = UInt8(truncatingIfNeeded: v >> 8)
        b[off + 1] = UInt8(truncatingIfNeeded: v)
    }

    static func putU32(_ b: inout [UInt8], _ off: Int, _ v: UInt32) {
        for i in 0..<4 { b[off + i] = UInt8(truncatingIfNeeded: v >> UInt32(24 - 8 * i)) }
    }

    static func putU64(_ b: inout [UInt8], _ off: Int, _ v: UInt64) {
        for i in 0..<8 { b[off + i] = UInt8(truncatingIfNeeded: v >> UInt64(56 - 8 * i)) }
    }

    static func getU16(_ b: [UInt8], _ off: Int) -> UInt16 {
        (UInt16(b[off]) << 8) | UInt16(b[off + 1])
    }

    static func getU32(_ b: [UInt8], _ off: Int) -> UInt32 {
        var v: UInt32 = 0
        for i in 0..<4 { v = (v << 8) | UInt32(b[off + i]) }
        return v
    }

    static func getU64(_ b: [UInt8], _ off: Int) -> UInt64 {
        var v: UInt64 = 0
        for i in 0..<8 { v = (v << 8) | UInt64(b[off + i]) }
        return v
    }

    private static func header(_ size: Int, _ type: UInt8) -> [UInt8] {
        var b = [UInt8](repeating: 0, count: size)
        b[0] = Proto.magic0
        b[1] = Proto.magic1
        b[2] = Proto.version
        b[3] = type
        return b
    }

    /// Packet type, or nil if this is not a Hearby v1 datagram.
    static func typeOf(_ b: [UInt8]) -> UInt8? {
        guard b.count >= Proto.headerSize, b[0] == Proto.magic0, b[1] == Proto.magic1, b[2] == Proto.version else { return nil }
        return b[3]
    }

    private static func nameBytes(_ name: String) -> [UInt8] {
        let raw = Array(name.utf8)
        if raw.count <= Proto.maxNameBytes { return raw }
        // Truncate on a UTF-8 character boundary.
        var n = Proto.maxNameBytes
        while n > 0 && (raw[n] & 0xC0) == 0x80 { n -= 1 }
        return Array(raw[0..<n])
    }

    private static func readName(_ b: [UInt8], _ off: Int) -> String? {
        guard off < b.count else { return nil }
        let n = Int(b[off])
        guard off + 1 + n <= b.count else { return nil }
        return String(decoding: b[(off + 1)..<(off + 1 + n)], as: UTF8.self)
    }

    // MARK: AUDIO (0x01) host -> client
    //  0  'H' 'B' ver type
    //  4  u32 sequence number   (+1 per 20 ms frame)
    //  8  u64 timestamp         (host monotonic clock, microseconds: capture time of the first PCM
    //                            sample this packet decodes to)
    // 16  Opus payload          (one 20 ms frame, 48 kHz stereo; length = datagram - 16)

    static func encodeAudio(seq: UInt32, timestampUs: UInt64, payload: [UInt8]) -> [UInt8] {
        var b = header(Proto.audioHeaderSize, Proto.typeAudio)
        putU32(&b, 4, seq)
        putU64(&b, 8, timestampUs)
        b.append(contentsOf: payload)
        return b
    }

    static func audioSeq(_ b: [UInt8]) -> UInt32 { getU32(b, 4) }

    static func audioTimestampUs(_ b: [UInt8]) -> UInt64 { getU64(b, 8) }

    // MARK: JOIN (0x02) client -> host
    //  4  u32 clientId   8  u8 platform   9  u8 nameLen   10  name (UTF-8)

    static func encodeJoin(clientId: UInt32, platform: UInt8, name: String) -> [UInt8] {
        let nb = nameBytes(name)
        var b = header(10, Proto.typeJoin)
        putU32(&b, 4, clientId)
        b[8] = platform
        b[9] = UInt8(nb.count)
        b.append(contentsOf: nb)
        return b
    }

    static func decodeJoin(_ b: [UInt8]) -> JoinMsg? {
        guard b.count >= 10, let name = readName(b, 9) else { return nil }
        return JoinMsg(clientId: getU32(b, 4), platform: b[8], name: name)
    }

    // MARK: JOIN_ACK (0x03) host -> client
    //  4  u32 streamId   8  u32 sampleRate   12  u8 channels   13  u8 frameMs
    // 14  u16 playoutDelayMs   16  u8 nameLen   17  hostName (UTF-8)

    static func encodeJoinAck(streamId: UInt32, playoutDelayMs: UInt16, hostName: String) -> [UInt8] {
        let nb = nameBytes(hostName)
        var b = header(17, Proto.typeJoinAck)
        putU32(&b, 4, streamId)
        putU32(&b, 8, UInt32(Proto.sampleRate))
        b[12] = UInt8(Proto.channels)
        b[13] = UInt8(Proto.frameMs)
        putU16(&b, 14, playoutDelayMs)
        b[16] = UInt8(nb.count)
        b.append(contentsOf: nb)
        return b
    }

    static func decodeJoinAck(_ b: [UInt8]) -> JoinAckMsg? {
        guard b.count >= 17, let name = readName(b, 16) else { return nil }
        return JoinAckMsg(
            streamId: getU32(b, 4), sampleRate: getU32(b, 8), channels: b[12], frameMs: b[13],
            playoutDelayMs: getU16(b, 14), hostName: name)
    }

    // MARK: SYNC_REQ (0x04) client -> host (also the keep-alive)
    //  4  u32 clientId   8  u64 t0 (client clock, us)   16  u32 rttUs
    // 20  u16 bufferMs   22  u8 status   23  u8 reserved   24  i32 adjustUs
    // (the 24-byte form without adjustUs is still accepted)

    static func encodeSyncReq(clientId: UInt32, t0Us: UInt64, rttUs: UInt32, bufferMs: UInt16, status: UInt8, adjustUs: Int32 = 0) -> [UInt8] {
        var b = header(syncReqSize, Proto.typeSyncReq)
        putU32(&b, 4, clientId)
        putU64(&b, 8, t0Us)
        putU32(&b, 16, rttUs)
        putU16(&b, 20, bufferMs)
        b[22] = status
        putU32(&b, 24, UInt32(bitPattern: adjustUs))
        return b
    }

    static func decodeSyncReq(_ b: [UInt8]) -> SyncReqMsg? {
        guard b.count >= syncReqMinSize else { return nil }
        let adjust = b.count >= syncReqSize ? Int32(bitPattern: getU32(b, 24)) : 0
        return SyncReqMsg(
            clientId: getU32(b, 4), t0Us: getU64(b, 8), rttUs: getU32(b, 16), bufferMs: getU16(b, 20), status: b[22], adjustUs: adjust)
    }

    // MARK: SYNC_RESP (0x05) host -> client
    //  4  u32 streamId   8  u64 t0 (echo)   16  u64 t1 (host rx, us)   24  u64 t2 (host tx, us)
    // 32  u16 playoutDelayMs   34  u16 reserved

    static func encodeSyncResp(streamId: UInt32, t0Us: UInt64, t1Us: UInt64, t2Us: UInt64, playoutDelayMs: UInt16) -> [UInt8] {
        var b = header(syncRespSize, Proto.typeSyncResp)
        putU32(&b, 4, streamId)
        putU64(&b, 8, t0Us)
        putU64(&b, 16, t1Us)
        putU64(&b, 24, t2Us)
        putU16(&b, 32, playoutDelayMs)
        return b
    }

    static func decodeSyncResp(_ b: [UInt8]) -> SyncRespMsg? {
        guard b.count >= syncRespSize else { return nil }
        return SyncRespMsg(
            streamId: getU32(b, 4), t0Us: getU64(b, 8), t1Us: getU64(b, 16), t2Us: getU64(b, 24),
            playoutDelayMs: getU16(b, 32))
    }

    // MARK: LEAVE (0x06) client -> host: 4 u32 clientId
    // MARK: BYE (0x07) host -> client: 4 u32 streamId
    // MARK: DISCOVER (0x08) client -> host (unicast or broadcast): 4 u32 nonce

    private static func u32Message(_ type: UInt8, _ value: UInt32) -> [UInt8] {
        var b = header(8, type)
        putU32(&b, 4, value)
        return b
    }

    static func encodeLeave(clientId: UInt32) -> [UInt8] { u32Message(Proto.typeLeave, clientId) }

    static func encodeBye(streamId: UInt32) -> [UInt8] { u32Message(Proto.typeBye, streamId) }

    static func encodeDiscover(nonce: UInt32) -> [UInt8] { u32Message(Proto.typeDiscover, nonce) }

    /// Body of LEAVE / BYE / DISCOVER: a single u32, or nil if truncated.
    static func decodeU32Body(_ b: [UInt8]) -> UInt32? { b.count < 8 ? nil : getU32(b, 4) }

    // MARK: NOW_PLAYING (0x0A) host -> client
    //  4  u32 streamId   8  u8 flags (bit 0: playing)   9  u8 titleLen   10  title   then u8 artistLen, artist

    static func encodeNowPlaying(streamId: UInt32, playing: Bool, title: String, artist: String) -> [UInt8] {
        let t = nameBytes(title)
        let a = nameBytes(artist)
        var b = header(9, Proto.typeNowPlaying)
        putU32(&b, 4, streamId)
        b[8] = playing ? 1 : 0
        b.append(UInt8(t.count))
        b.append(contentsOf: t)
        b.append(UInt8(a.count))
        b.append(contentsOf: a)
        return b
    }

    static func decodeNowPlaying(_ b: [UInt8]) -> NowPlayingMsg? {
        guard b.count >= 11, let title = readName(b, 9) else { return nil }
        guard let artist = readName(b, 10 + Int(b[9])) else { return nil }
        return NowPlayingMsg(streamId: getU32(b, 4), playing: b[8] & 1 != 0, title: title, artist: artist)
    }

    // MARK: CHIRP (0x0B) host -> client
    //  4  u32 streamId   8  u32 clientId (who should chirp)   12  u64 mediaTimeUs   20  u16 checkId   22  u16 reserved

    static func encodeChirp(streamId: UInt32, clientId: UInt32, mediaTimeUs: UInt64, checkId: UInt16) -> [UInt8] {
        var b = header(chirpSize, Proto.typeChirp)
        putU32(&b, 4, streamId)
        putU32(&b, 8, clientId)
        putU64(&b, 12, mediaTimeUs)
        putU16(&b, 20, checkId)
        return b
    }

    static func decodeChirp(_ b: [UInt8]) -> ChirpMsg? {
        guard b.count >= chirpSize else { return nil }
        return ChirpMsg(streamId: getU32(b, 4), clientId: getU32(b, 8), mediaTimeUs: getU64(b, 12), checkId: getU16(b, 20))
    }

    // MARK: OFFSET (0x0C) host -> client
    //  4  u32 streamId   8  u32 clientId   12  i32 adjustUs (add to the current correction)
    // 16  u16 checkId (apply each id once)   18  u16 errorUs (|error| measured, capped at 65535)

    static func encodeOffset(streamId: UInt32, clientId: UInt32, adjustUs: Int32, checkId: UInt16, errorUs: UInt16) -> [UInt8] {
        var b = header(offsetSize, Proto.typeOffset)
        putU32(&b, 4, streamId)
        putU32(&b, 8, clientId)
        putU32(&b, 12, UInt32(bitPattern: adjustUs))
        putU16(&b, 16, checkId)
        putU16(&b, 18, errorUs)
        return b
    }

    static func decodeOffset(_ b: [UInt8]) -> OffsetMsg? {
        guard b.count >= offsetSize else { return nil }
        return OffsetMsg(
            streamId: getU32(b, 4), clientId: getU32(b, 8), adjustUs: Int32(bitPattern: getU32(b, 12)),
            checkId: getU16(b, 16), errorUs: getU16(b, 18))
    }

    // MARK: DISCOVER_RESP (0x09) host -> client
    //  4  u32 nonce (echo)   8  u32 streamId   12  u8 listeners   13  u8 nameLen   14  hostName

    static func encodeDiscoverResp(nonce: UInt32, streamId: UInt32, listeners: UInt8, hostName: String) -> [UInt8] {
        let nb = nameBytes(hostName)
        var b = header(14, Proto.typeDiscoverResp)
        putU32(&b, 4, nonce)
        putU32(&b, 8, streamId)
        b[12] = listeners
        b[13] = UInt8(nb.count)
        b.append(contentsOf: nb)
        return b
    }

    static func decodeDiscoverResp(_ b: [UInt8]) -> DiscoverRespMsg? {
        guard b.count >= 14, let name = readName(b, 13) else { return nil }
        return DiscoverRespMsg(nonce: getU32(b, 4), streamId: getU32(b, 8), listeners: b[12], hostName: name)
    }
}
