package com.cleartether.app.share

import com.cleartether.app.core.share.NdefCodec
import com.cleartether.app.core.share.NdefRecordData
import com.cleartether.app.core.share.Type4TagEmulator
import com.cleartether.app.core.share.WifiCredentials
import com.cleartether.app.core.share.WifiQr
import com.cleartether.app.core.share.WifiSimpleConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class ShareCodecTest {
    private val creds = WifiCredentials("DIRECT-CT-Café;:,\"\\", "p@ss;word:1")

    @Test
    fun wifiQrEscapesAndRoundTrips() {
        val text = WifiQr.encode(creds)
        assertEquals("""WIFI:T:WPA;S:DIRECT-CT-Café\;\:\,\"\\;P:p@ss\;word\:1;;""", text)
        assertEquals(creds, WifiQr.decode(text))
        assertEquals(WifiCredentials("Home", "pw"), WifiQr.decode("WIFI:S:Home;T:WPA;P:pw;H:false;;"))
    }

    @Test
    fun wscTokenRoundTrips() {
        val payload = WifiSimpleConfig.encode(creds)
        assertEquals(creds, WifiSimpleConfig.decode(payload))
    }

    @Test
    fun type4EmulationServesTheNdefMessage() {
        val message = Type4TagEmulator.hostMessage(creds, "http://192.168.49.1:8228/", "com.cleartether.app")
        val tag = Type4TagEmulator { message }
        val ok = Type4TagEmulator.SW_OK

        // Same APDU sequence Android/iOS readers use for NDEF detection.
        assertArrayEquals(ok, tag.process(hex("00A4040007D276000085010100")))
        assertArrayEquals(ok, tag.process(hex("00A4000C02E103")))
        val cc = tag.process(hex("00B000000F"))
        assertEquals(17, cc.size)
        assertEquals(0xE1.toByte(), cc[9]); assertEquals(0x04.toByte(), cc[10])
        assertArrayEquals(ok, tag.process(hex("00A4000C02E104")))
        val nlen = tag.process(hex("00B0000002"))
        val length = ((nlen[0].toInt() and 0xFF) shl 8) or (nlen[1].toInt() and 0xFF)
        assertEquals(message.size, length)

        val read = ByteArrayOutputStream()
        var offset = 2
        while (offset < length + 2) {
            val chunk = minOf(0xF0, length + 2 - offset)
            val r = tag.process(byteArrayOf(0, 0xB0.toByte(), (offset shr 8).toByte(), offset.toByte(), chunk.toByte()))
            read.write(r, 0, r.size - 2)
            offset += r.size - 2
        }
        assertArrayEquals(message, read.toByteArray())

        val records = NdefCodec.decode(read.toByteArray())
        assertEquals(3, records.size)
        assertEquals(WifiSimpleConfig.MIME_TYPE, String(records[0].type))
        assertEquals(creds, WifiSimpleConfig.decode(records[0].payload))
        assertEquals(NdefRecordData.TNF_WELL_KNOWN, records[1].tnf)
        assertEquals("com.cleartether.app", String(records[2].payload))
    }

    @Test
    fun tagIsSilentWhenNotSharing() {
        val tag = Type4TagEmulator { null }
        assertArrayEquals(Type4TagEmulator.SW_FILE_NOT_FOUND, tag.process(hex("00A4040007D276000085010100")))
        assertArrayEquals(Type4TagEmulator.SW_FILE_NOT_FOUND, tag.process(hex("00A4000C02E104")))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
