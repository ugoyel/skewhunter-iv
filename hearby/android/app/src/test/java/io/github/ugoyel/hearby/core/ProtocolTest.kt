package io.github.ugoyel.hearby.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Golden vectors for the wire format. ios/Tests/HearbyCoreTests/ProtocolTests.swift checks the very
 * same hex strings, which is what guarantees Android and iOS agree byte for byte.
 */
class ProtocolTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun audioHeader() {
        val b = ByteArray(Proto.AUDIO_HEADER_SIZE + 3)
        Wire.writeAudioHeader(b, 0x01020304L, 0x0000001122334455L)
        b[16] = 0xFC.toByte(); b[17] = 0xFF.toByte(); b[18] = 0xFE.toByte()
        assertEquals("48420101" + "01020304" + "0000001122334455" + "fcfffe", hex(b))

        assertEquals(Proto.TYPE_AUDIO, Wire.typeOf(b, b.size))
        assertEquals(0x01020304L, Wire.audioSeq(b))
        assertEquals(0x0000001122334455L, Wire.audioTimestampUs(b))
    }

    @Test
    fun audioSequenceUsesFullUnsignedRange() {
        val b = ByteArray(Proto.AUDIO_HEADER_SIZE)
        Wire.writeAudioHeader(b, 0xFFFFFFFEL, 1L)
        assertEquals("48420101" + "fffffffe" + "0000000000000001", hex(b))
        assertEquals(0xFFFFFFFEL, Wire.audioSeq(b))
    }

    @Test
    fun join() {
        val b = Wire.encodeJoin(0xDEADBEEFL, Proto.PLATFORM_IOS, "Pixel 8")
        assertEquals("48420102" + "deadbeef" + "02" + "07" + "506978656c2038", hex(b))
        val m = Wire.decodeJoin(b, b.size)!!
        assertEquals(0xDEADBEEFL, m.clientId)
        assertEquals(Proto.PLATFORM_IOS, m.platform)
        assertEquals("Pixel 8", m.name)
    }

    @Test
    fun joinAck() {
        val b = Wire.encodeJoinAck(0x0A0B0C0DL, 350, "Host")
        assertEquals("48420103" + "0a0b0c0d" + "0000bb80" + "02" + "14" + "015e" + "04" + "486f7374", hex(b))
        val m = Wire.decodeJoinAck(b, b.size)!!
        assertEquals(0x0A0B0C0DL, m.streamId)
        assertEquals(48_000, m.sampleRate)
        assertEquals(2, m.channels)
        assertEquals(20, m.frameMs)
        assertEquals(350, m.playoutDelayMs)
        assertEquals("Host", m.hostName)
    }

    @Test
    fun syncRequest() {
        val b = Wire.encodeSyncReq(0xDEADBEEFL, 0x0000000102030405L, 2500, 180, Proto.STATUS_SYNCED)
        assertEquals("48420104" + "deadbeef" + "0000000102030405" + "000009c4" + "00b4" + "02" + "00", hex(b))
        val m = Wire.decodeSyncReq(b, b.size)!!
        assertEquals(0xDEADBEEFL, m.clientId)
        assertEquals(0x0000000102030405L, m.t0Us)
        assertEquals(2500L, m.rttUs)
        assertEquals(180, m.bufferMs)
        assertEquals(Proto.STATUS_SYNCED, m.status)
    }

    @Test
    fun syncResponse() {
        val b = Wire.encodeSyncResp(0x0A0B0C0DL, 0x0000000102030405L, 0x00000000000F4240L, 0, 350)
        Wire.patchSyncRespT2(b, 0x00000000000F4628L)
        assertEquals(
            "48420105" + "0a0b0c0d" + "0000000102030405" + "00000000000f4240" + "00000000000f4628" + "015e" + "0000",
            hex(b),
        )
        val m = Wire.decodeSyncResp(b, b.size)!!
        assertEquals(0x0A0B0C0DL, m.streamId)
        assertEquals(0x0000000102030405L, m.t0Us)
        assertEquals(1_000_000L, m.t1Us)
        assertEquals(1_001_000L, m.t2Us)
        assertEquals(350, m.playoutDelayMs)
    }

    @Test
    fun smallControlMessages() {
        assertEquals("48420106" + "deadbeef", hex(Wire.encodeLeave(0xDEADBEEFL)))
        assertEquals("48420107" + "0a0b0c0d", hex(Wire.encodeBye(0x0A0B0C0DL)))
        assertEquals("48420108" + "00000007", hex(Wire.encodeDiscover(7)))
        val bye = Wire.encodeBye(0x0A0B0C0DL)
        assertEquals(0x0A0B0C0DL, Wire.decodeU32Body(bye, bye.size))
        assertEquals(-1L, Wire.decodeU32Body(bye, 6))
    }

    @Test
    fun discoverResponse() {
        val b = Wire.encodeDiscoverResp(7, 0x0A0B0C0DL, 3, "Mia's phone")
        assertEquals("48420109" + "00000007" + "0a0b0c0d" + "03" + "0b" + "4d696127732070686f6e65", hex(b))
        val m = Wire.decodeDiscoverResp(b, b.size)!!
        assertEquals(7L, m.nonce)
        assertEquals(0x0A0B0C0DL, m.streamId)
        assertEquals(3, m.listeners)
        assertEquals("Mia's phone", m.hostName)
    }

    @Test
    fun rejectsForeignAndTruncatedDatagrams() {
        assertEquals(-1, Wire.typeOf(bytes("48"), 1))
        assertEquals(-1, Wire.typeOf(bytes("58420101"), 4))  // wrong magic
        assertEquals(-1, Wire.typeOf(bytes("48420201"), 4))  // wrong version
        val join = Wire.encodeJoin(1, 1, "abc")
        assertNotNull(Wire.decodeJoin(join, join.size))
        assertNull(Wire.decodeJoin(join, join.size - 1))     // name runs past the end
        val ack = Wire.encodeJoinAck(1, 350, "abc")
        assertNull(Wire.decodeJoinAck(ack, 12))
        assertNull(Wire.decodeSyncResp(ByteArray(35), 35))
    }

    @Test
    fun longNamesAreTruncatedOnACharacterBoundary() {
        val name = "é".repeat(40) // 80 UTF-8 bytes
        val b = Wire.encodeJoin(1, 1, name)
        val m = Wire.decodeJoin(b, b.size)!!
        assertEquals("é".repeat(24), m.name) // 48 bytes
    }

    @Test
    fun opusTocDurations() {
        // config 31 (CELT fullband 20 ms), stereo, one frame
        assertEquals(20_000, OpusToc.packetDurationUs(bytes("fc00"), 0, 2))
        // config 30 (CELT fullband 10 ms), code 1 => two frames
        assertEquals(20_000, OpusToc.packetDurationUs(bytes("f100"), 0, 2))
        // config 1 (SILK NB 20 ms), code 3 with 3 frames
        assertEquals(60_000, OpusToc.packetDurationUs(bytes("0b03"), 0, 2))
        // config 15 (hybrid FB 20 ms)
        assertEquals(20_000, OpusToc.packetDurationUs(bytes("78"), 0, 1))
        assertEquals(-1, OpusToc.packetDurationUs(ByteArray(0), 0, 0))
        assertArrayEquals(bytes("0a"), byteArrayOf(10))
    }
}
