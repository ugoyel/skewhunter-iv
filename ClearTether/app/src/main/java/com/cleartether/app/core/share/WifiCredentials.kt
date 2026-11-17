package com.cleartether.app.core.share

/** Wi-Fi network credentials as shared over QR codes and NFC. */
data class WifiCredentials(val ssid: String, val passphrase: String)

/**
 * `WIFI:` QR payloads (ZXing / Wi-Fi Alliance format, understood by the iOS and Android cameras).
 * Special characters `\ ; , : "` are backslash-escaped.
 */
object WifiQr {
    fun encode(credentials: WifiCredentials, hidden: Boolean = false): String = buildString {
        append("WIFI:T:WPA;S:").append(escape(credentials.ssid))
        append(";P:").append(escape(credentials.passphrase))
        if (hidden) append(";H:true")
        append(";;")
    }

    fun decode(text: String): WifiCredentials? {
        if (!text.startsWith("WIFI:", ignoreCase = true)) return null
        val fields = HashMap<String, String>()
        var i = 5
        while (i < text.length) {
            val colon = text.indexOf(':', i)
            if (colon < 0) break
            val key = text.substring(i, colon).uppercase()
            val value = StringBuilder()
            var j = colon + 1
            while (j < text.length && text[j] != ';') {
                if (text[j] == '\\' && j + 1 < text.length) j++
                value.append(text[j])
                j++
            }
            if (key.isNotEmpty()) fields.putIfAbsent(key, value.toString())
            i = j + 1
            if (i < text.length && text[i] == ';') break
        }
        val ssid = fields["S"]?.takeIf { it.isNotEmpty() } ?: return null
        return WifiCredentials(ssid, fields["P"].orEmpty())
    }

    private fun escape(s: String) = buildString(s.length) {
        for (c in s) {
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') append('\\')
            append(c)
        }
    }
}
