package com.cleartether.app.core.share

import java.io.ByteArrayOutputStream

/** One NDEF record (NFC Forum NDEF 1.0). */
class NdefRecordData(val tnf: Int, val type: ByteArray, val payload: ByteArray) {
    companion object {
        const val TNF_WELL_KNOWN = 0x01
        const val TNF_MIME = 0x02
        const val TNF_EXTERNAL = 0x04

        fun mime(type: String, payload: ByteArray) = NdefRecordData(TNF_MIME, type.toByteArray(Charsets.US_ASCII), payload)

        /** URI record without prefix abbreviation. */
        fun uri(uri: String): NdefRecordData {
            val (code, rest) = when {
                uri.startsWith("https://") -> 0x04 to uri.removePrefix("https://")
                uri.startsWith("http://") -> 0x03 to uri.removePrefix("http://")
                else -> 0x00 to uri
            }
            return NdefRecordData(TNF_WELL_KNOWN, byteArrayOf('U'.code.toByte()), byteArrayOf(code.toByte()) + rest.toByteArray(Charsets.UTF_8))
        }

        /** Android Application Record: opens (or installs) the app on Android readers. */
        fun androidApplication(packageName: String) =
            NdefRecordData(TNF_EXTERNAL, "android.com:pkg".toByteArray(Charsets.US_ASCII), packageName.toByteArray(Charsets.US_ASCII))
    }
}

object NdefCodec {
    fun encode(records: List<NdefRecordData>): ByteArray {
        val out = ByteArrayOutputStream()
        records.forEachIndexed { index, r ->
            val short = r.payload.size < 256
            var header = r.tnf and 0x07
            if (index == 0) header = header or 0x80 // MB
            if (index == records.lastIndex) header = header or 0x40 // ME
            if (short) header = header or 0x10 // SR
            out.write(header)
            out.write(r.type.size)
            if (short) {
                out.write(r.payload.size)
            } else {
                val n = r.payload.size
                out.write(n ushr 24); out.write(n ushr 16); out.write(n ushr 8); out.write(n)
            }
            out.write(r.type)
            out.write(r.payload)
        }
        return out.toByteArray()
    }

    fun decode(message: ByteArray): List<NdefRecordData> {
        val records = ArrayList<NdefRecordData>()
        var i = 0
        while (i < message.size) {
            val header = message[i++].toInt() and 0xFF
            val short = header and 0x10 != 0
            val hasId = header and 0x08 != 0
            if (i >= message.size) break
            val typeLen = message[i++].toInt() and 0xFF
            val payloadLen = if (short) {
                if (i >= message.size) break
                message[i++].toInt() and 0xFF
            } else {
                if (i + 4 > message.size) break
                val n = ((message[i].toInt() and 0xFF) shl 24) or ((message[i + 1].toInt() and 0xFF) shl 16) or
                    ((message[i + 2].toInt() and 0xFF) shl 8) or (message[i + 3].toInt() and 0xFF)
                i += 4
                n
            }
            val idLen = if (hasId) message[i++].toInt() and 0xFF else 0
            if (payloadLen < 0 || i + typeLen + idLen + payloadLen > message.size) break
            val type = message.copyOfRange(i, i + typeLen)
            i += typeLen + idLen
            records += NdefRecordData(header and 0x07, type, message.copyOfRange(i, i + payloadLen))
            i += payloadLen
            if (header and 0x40 != 0) break
        }
        return records
    }
}

/**
 * Wi-Fi Simple Configuration credential token (`application/vnd.wfa.wsc`), the format Android's
 * NFC stack turns into a "Connect to this network?" prompt when a phone taps the tag.
 */
object WifiSimpleConfig {
    const val MIME_TYPE = "application/vnd.wfa.wsc"

    private const val CREDENTIAL = 0x100E
    private const val NETWORK_INDEX = 0x1026
    private const val SSID = 0x1045
    private const val AUTH_TYPE = 0x1003
    private const val ENCRYPTION_TYPE = 0x100F
    private const val NETWORK_KEY = 0x1027
    private const val MAC_ADDRESS = 0x1020
    private const val VERSION = 0x104A
    private const val AUTH_WPA2_PSK = 0x0020
    private const val ENCRYPTION_AES = 0x0008

    fun encode(credentials: WifiCredentials): ByteArray {
        val inner = ByteArrayOutputStream()
        tlv(inner, NETWORK_INDEX, byteArrayOf(1))
        tlv(inner, SSID, credentials.ssid.toByteArray(Charsets.UTF_8))
        tlv(inner, AUTH_TYPE, byteArrayOf(0, AUTH_WPA2_PSK.toByte()))
        tlv(inner, ENCRYPTION_TYPE, byteArrayOf(0, ENCRYPTION_AES.toByte()))
        tlv(inner, NETWORK_KEY, credentials.passphrase.toByteArray(Charsets.UTF_8))
        tlv(inner, MAC_ADDRESS, ByteArray(6) { 0xFF.toByte() })
        val out = ByteArrayOutputStream()
        tlv(out, VERSION, byteArrayOf(0x10))
        tlv(out, CREDENTIAL, inner.toByteArray())
        return out.toByteArray()
    }

    fun decode(payload: ByteArray): WifiCredentials? {
        val credential = findTlv(payload, 0, payload.size, CREDENTIAL) ?: return null
        val ssid = findTlv(payload, credential.first, credential.second, SSID) ?: return null
        val key = findTlv(payload, credential.first, credential.second, NETWORK_KEY)
        return WifiCredentials(
            String(payload, ssid.first, ssid.second - ssid.first, Charsets.UTF_8),
            key?.let { String(payload, it.first, it.second - it.first, Charsets.UTF_8) }.orEmpty(),
        )
    }

    /** Returns [start, end) of the value of the first TLV with [id] in the range. */
    private fun findTlv(data: ByteArray, from: Int, to: Int, id: Int): Pair<Int, Int>? {
        var i = from
        while (i + 4 <= to) {
            val type = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            val len = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            val start = i + 4
            if (start + len > to) return null
            if (type == id) return start to start + len
            i = start + len
        }
        return null
    }

    private fun tlv(out: ByteArrayOutputStream, type: Int, value: ByteArray) {
        out.write(type ushr 8); out.write(type and 0xFF)
        out.write(value.size ushr 8); out.write(value.size and 0xFF)
        out.write(value)
    }
}

/**
 * NFC Forum Type 4 Tag emulation (T4T v2.0) for Host Card Emulation. Answers the SELECT/READ
 * BINARY APDUs a reader sends to fetch an NDEF message.
 */
class Type4TagEmulator(private val ndefProvider: () -> ByteArray?) {
    private enum class File { NONE, CC, NDEF }

    private var appSelected = false
    private var file = File.NONE
    private var ndefFile: ByteArray = ByteArray(0)

    fun reset() {
        appSelected = false
        file = File.NONE
    }

    fun process(apdu: ByteArray): ByteArray {
        if (apdu.size < 4) return SW_WRONG_LENGTH
        val cla = apdu[0].toInt() and 0xFF
        val ins = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF
        if (cla != 0x00) return SW_CLA_NOT_SUPPORTED
        return when (ins) {
            0xA4 -> select(apdu, p1)
            0xB0 -> readBinary(apdu, (p1 shl 8) or p2)
            else -> SW_INS_NOT_SUPPORTED
        }
    }

    private fun select(apdu: ByteArray, p1: Int): ByteArray {
        if (apdu.size < 5) return SW_WRONG_LENGTH
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return SW_WRONG_LENGTH
        val data = apdu.copyOfRange(5, 5 + lc)
        return when (p1) {
            0x04 -> {
                if (!data.contentEquals(NDEF_AID)) return SW_FILE_NOT_FOUND
                val message = ndefProvider() ?: return SW_FILE_NOT_FOUND // not sharing right now
                ndefFile = ByteArray(message.size + 2).also {
                    it[0] = (message.size ushr 8).toByte(); it[1] = message.size.toByte()
                    System.arraycopy(message, 0, it, 2, message.size)
                }
                appSelected = true
                file = File.NONE
                SW_OK
            }
            0x00 -> {
                if (!appSelected || data.size != 2) return SW_FILE_NOT_FOUND
                file = when {
                    data[0] == 0xE1.toByte() && data[1] == 0x03.toByte() -> File.CC
                    data[0] == 0xE1.toByte() && data[1] == 0x04.toByte() -> File.NDEF
                    else -> return SW_FILE_NOT_FOUND
                }
                SW_OK
            }
            else -> SW_FILE_NOT_FOUND
        }
    }

    private fun readBinary(apdu: ByteArray, offset: Int): ByteArray {
        val content = when (file) {
            File.CC -> capabilityContainer()
            File.NDEF -> ndefFile
            File.NONE -> return SW_FILE_NOT_FOUND
        }
        if (offset > content.size) return SW_WRONG_OFFSET
        val le = if (apdu.size >= 5) (apdu[apdu.size - 1].toInt() and 0xFF).let { if (it == 0) 256 else it } else 256
        val end = minOf(content.size, offset + le, offset + MAX_READ)
        return content.copyOfRange(offset, end) + SW_OK
    }

    private fun capabilityContainer(): ByteArray {
        val maxNdef = ndefFile.size.coerceAtLeast(2)
        return byteArrayOf(
            0x00, 0x0F, // CC length
            0x20, // mapping version 2.0
            0x00, MAX_READ.toByte(), // MLe
            0x00, 0x7F, // MLc
            0x04, 0x06, // NDEF file control TLV
            0xE1.toByte(), 0x04, // file id
            (maxNdef ushr 8).toByte(), maxNdef.toByte(), // max NDEF file size
            0x00, // read access: granted
            0xFF.toByte(), // write access: denied
        )
    }

    companion object {
        val NDEF_AID = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
        private const val MAX_READ = 0xF0
        val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        val SW_FILE_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        val SW_WRONG_OFFSET = byteArrayOf(0x6B, 0x00)
        val SW_WRONG_LENGTH = byteArrayOf(0x67, 0x00)
        val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
        val SW_CLA_NOT_SUPPORTED = byteArrayOf(0x6E, 0x00)

        /** NDEF message a ClearTether host advertises: Wi-Fi credentials, setup page, and the app. */
        fun hostMessage(credentials: WifiCredentials, setupUrl: String, packageName: String): ByteArray =
            NdefCodec.encode(
                listOf(
                    NdefRecordData.mime(WifiSimpleConfig.MIME_TYPE, WifiSimpleConfig.encode(credentials)),
                    NdefRecordData.uri(setupUrl),
                    NdefRecordData.androidApplication(packageName),
                ),
            )
    }
}
