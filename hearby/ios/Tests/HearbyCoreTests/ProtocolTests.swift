import XCTest
@testable import HearbyCore

/// Golden vectors for the wire format. android/.../core/ProtocolTest.kt checks the very same hex
/// strings, which is what guarantees Android and iOS agree byte for byte.
final class ProtocolTests: XCTestCase {

    private func hex(_ b: [UInt8]) -> String { b.map { String(format: "%02x", $0) }.joined() }

    private func bytes(_ hex: String) -> [UInt8] {
        var out: [UInt8] = []
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            out.append(UInt8(hex[index..<next], radix: 16)!)
            index = next
        }
        return out
    }

    func testAudioHeader() {
        let b = Wire.encodeAudio(seq: 0x0102_0304, timestampUs: 0x0000_0011_2233_4455, payload: [0xFC, 0xFF, 0xFE])
        XCTAssertEqual(hex(b), "48420101" + "01020304" + "0000001122334455" + "fcfffe")
        XCTAssertEqual(Wire.typeOf(b), Proto.typeAudio)
        XCTAssertEqual(Wire.audioSeq(b), 0x0102_0304)
        XCTAssertEqual(Wire.audioTimestampUs(b), 0x0000_0011_2233_4455)
        XCTAssertEqual(b.count - Proto.audioHeaderSize, 3)
    }

    func testAudioSequenceUsesFullUnsignedRange() {
        let b = Wire.encodeAudio(seq: 0xFFFF_FFFE, timestampUs: 1, payload: [])
        XCTAssertEqual(hex(b), "48420101" + "fffffffe" + "0000000000000001")
        XCTAssertEqual(Wire.audioSeq(b), 0xFFFF_FFFE)
    }

    func testJoin() {
        let b = Wire.encodeJoin(clientId: 0xDEAD_BEEF, platform: Proto.platformIOS, name: "Pixel 8")
        XCTAssertEqual(hex(b), "48420102" + "deadbeef" + "02" + "07" + "506978656c2038")
        XCTAssertEqual(Wire.decodeJoin(b), JoinMsg(clientId: 0xDEAD_BEEF, platform: Proto.platformIOS, name: "Pixel 8"))
    }

    func testJoinAck() {
        let b = Wire.encodeJoinAck(streamId: 0x0A0B_0C0D, playoutDelayMs: 350, hostName: "Host")
        XCTAssertEqual(hex(b), "48420103" + "0a0b0c0d" + "0000bb80" + "02" + "14" + "015e" + "04" + "486f7374")
        XCTAssertEqual(
            Wire.decodeJoinAck(b),
            JoinAckMsg(streamId: 0x0A0B_0C0D, sampleRate: 48_000, channels: 2, frameMs: 20, playoutDelayMs: 350, hostName: "Host"))
    }

    func testSyncRequest() {
        let b = Wire.encodeSyncReq(
            clientId: 0xDEAD_BEEF, t0Us: 0x0000_0001_0203_0405, rttUs: 2500, bufferMs: 180, status: Proto.statusSynced, adjustUs: -120_000)
        XCTAssertEqual(hex(b), "48420104" + "deadbeef" + "0000000102030405" + "000009c4" + "00b4" + "02" + "00" + "fffe2b40")
        XCTAssertEqual(
            Wire.decodeSyncReq(b),
            SyncReqMsg(
                clientId: 0xDEAD_BEEF, t0Us: 0x0000_0001_0203_0405, rttUs: 2500, bufferMs: 180, status: Proto.statusSynced,
                adjustUs: -120_000))
        // The original 24-byte form (no adjust field) is still understood.
        XCTAssertEqual(Wire.decodeSyncReq(Array(b.prefix(24)))?.adjustUs, 0)
    }

    func testNowPlaying() {
        let b = Wire.encodeNowPlaying(streamId: 0x0A0B_0C0D, playing: true, title: "Song", artist: "Band")
        XCTAssertEqual(hex(b), "4842010a" + "0a0b0c0d" + "01" + "04" + "536f6e67" + "04" + "42616e64")
        XCTAssertEqual(Wire.decodeNowPlaying(b), NowPlayingMsg(streamId: 0x0A0B_0C0D, playing: true, title: "Song", artist: "Band"))
        XCTAssertNil(Wire.decodeNowPlaying(Array(b.dropLast())))
        XCTAssertEqual(hex(Wire.encodeNowPlaying(streamId: 1, playing: false, title: "", artist: "")), "4842010a" + "00000001" + "00" + "00" + "00")
    }

    func testChirp() {
        let b = Wire.encodeChirp(streamId: 0x0A0B_0C0D, clientId: 0xDEAD_BEEF, mediaTimeUs: 0x0000_0001_0203_0405, checkId: 7)
        XCTAssertEqual(hex(b), "4842010b" + "0a0b0c0d" + "deadbeef" + "0000000102030405" + "0007" + "0000")
        XCTAssertEqual(Wire.decodeChirp(b), ChirpMsg(streamId: 0x0A0B_0C0D, clientId: 0xDEAD_BEEF, mediaTimeUs: 0x0000_0001_0203_0405, checkId: 7))
    }

    func testVideo() {
        let t = VideoTimeline(videoId: "dQw4w9WgXcQ", title: "Hit", playing: true, anchorHostUs: 0x0000_0001_0203_0405, positionUs: -2_000_000, version: 3)
        let b = Wire.encodeVideo(streamId: 0x0A0B_0C0D, timeline: t)
        XCTAssertEqual(
            hex(b),
            "4842010d" + "0a0b0c0d" + "00000003" + "03" + "0000000102030405" + "ffffffffffe17b80"
                + "0b" + "6451773477395767586351" + "03" + "486974")
        let m = Wire.decodeVideo(b)
        XCTAssertEqual(m?.streamId, 0x0A0B_0C0D)
        XCTAssertEqual(m?.timeline, t)
        // No video on (back to music).
        let off = Wire.encodeVideo(streamId: 0x0A0B_0C0D, timeline: nil)
        XCTAssertEqual(hex(off), "4842010d" + "0a0b0c0d" + "00000000" + "00" + "0000000000000000" + "0000000000000000" + "00" + "00")
        XCTAssertNotNil(Wire.decodeVideo(off))
        XCTAssertNil(Wire.decodeVideo(off)?.timeline)
        XCTAssertNil(Wire.decodeVideo(Array(b.prefix(30))))
    }

    func testOffset() {
        let b = Wire.encodeOffset(streamId: 0x0A0B_0C0D, clientId: 0xDEAD_BEEF, adjustUs: -6_500, checkId: 7, errorUs: 6_500)
        XCTAssertEqual(hex(b), "4842010c" + "0a0b0c0d" + "deadbeef" + "ffffe69c" + "0007" + "1964")
        XCTAssertEqual(Wire.decodeOffset(b), OffsetMsg(streamId: 0x0A0B_0C0D, clientId: 0xDEAD_BEEF, adjustUs: -6_500, checkId: 7, errorUs: 6_500))
    }

    func testSyncResponse() {
        let b = Wire.encodeSyncResp(streamId: 0x0A0B_0C0D, t0Us: 0x0000_0001_0203_0405, t1Us: 1_000_000, t2Us: 1_001_000, playoutDelayMs: 350)
        XCTAssertEqual(
            hex(b),
            "48420105" + "0a0b0c0d" + "0000000102030405" + "00000000000f4240" + "00000000000f4628" + "015e" + "0000")
        XCTAssertEqual(
            Wire.decodeSyncResp(b),
            SyncRespMsg(streamId: 0x0A0B_0C0D, t0Us: 0x0000_0001_0203_0405, t1Us: 1_000_000, t2Us: 1_001_000, playoutDelayMs: 350))
    }

    func testSmallControlMessages() {
        XCTAssertEqual(hex(Wire.encodeLeave(clientId: 0xDEAD_BEEF)), "48420106" + "deadbeef")
        XCTAssertEqual(hex(Wire.encodeBye(streamId: 0x0A0B_0C0D)), "48420107" + "0a0b0c0d")
        XCTAssertEqual(hex(Wire.encodeDiscover(nonce: 7)), "48420108" + "00000007")
        XCTAssertEqual(Wire.decodeU32Body(Wire.encodeBye(streamId: 0x0A0B_0C0D)), 0x0A0B_0C0D)
        XCTAssertNil(Wire.decodeU32Body(Array(Wire.encodeBye(streamId: 1).prefix(6))))
    }

    func testDiscoverResponse() {
        let b = Wire.encodeDiscoverResp(nonce: 7, streamId: 0x0A0B_0C0D, listeners: 3, hostName: "Mia's phone")
        XCTAssertEqual(hex(b), "48420109" + "00000007" + "0a0b0c0d" + "03" + "0b" + "4d696127732070686f6e65")
        XCTAssertEqual(
            Wire.decodeDiscoverResp(b),
            DiscoverRespMsg(nonce: 7, streamId: 0x0A0B_0C0D, listeners: 3, hostName: "Mia's phone"))
    }

    func testRejectsForeignAndTruncatedDatagrams() {
        XCTAssertNil(Wire.typeOf(bytes("48")))
        XCTAssertNil(Wire.typeOf(bytes("58420101"))) // wrong magic
        XCTAssertNil(Wire.typeOf(bytes("48420201"))) // wrong version
        let join = Wire.encodeJoin(clientId: 1, platform: 1, name: "abc")
        XCTAssertNotNil(Wire.decodeJoin(join))
        XCTAssertNil(Wire.decodeJoin(Array(join.dropLast()))) // name runs past the end
        XCTAssertNil(Wire.decodeJoinAck(Array(Wire.encodeJoinAck(streamId: 1, playoutDelayMs: 350, hostName: "abc").prefix(12))))
        XCTAssertNil(Wire.decodeSyncResp([UInt8](repeating: 0, count: 35)))
    }

    func testLongNamesAreTruncatedOnACharacterBoundary() {
        let name = String(repeating: "é", count: 40) // 80 UTF-8 bytes
        let b = Wire.encodeJoin(clientId: 1, platform: 1, name: name)
        XCTAssertEqual(Wire.decodeJoin(b)?.name, String(repeating: "é", count: 24)) // 48 bytes
    }
}
