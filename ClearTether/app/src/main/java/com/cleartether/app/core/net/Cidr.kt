package com.cleartether.app.core.net

import java.net.InetAddress

/** An IPv4 or IPv6 prefix such as `192.168.49.0/24`. */
class Cidr(val network: InetAddress, val prefixLength: Int) {
    private val bytes = network.address

    init {
        require(prefixLength in 0..bytes.size * 8) { "Invalid prefix length $prefixLength" }
    }

    fun contains(address: InetAddress): Boolean {
        val candidate = address.address
        if (candidate.size != bytes.size) return false
        var bits = prefixLength
        var i = 0
        while (bits > 0) {
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((candidate[i].toInt() and mask) != (bytes[i].toInt() and mask)) return false
            bits -= 8
            i++
        }
        return true
    }

    override fun toString(): String = "${network.hostAddress}/$prefixLength"

    companion object {
        /** Parses `a.b.c.d/n` or `x::y/n`. Only literal addresses are accepted, so no DNS lookups. */
        fun parse(text: String): Cidr {
            val slash = text.indexOf('/')
            val addressPart = if (slash < 0) text else text.substring(0, slash)
            val address = InetAddresses.parseLiteral(addressPart)
                ?: throw IllegalArgumentException("Not an IP literal: $addressPart")
            val prefix = if (slash < 0) address.address.size * 8 else text.substring(slash + 1).toInt()
            return Cidr(address, prefix)
        }
    }
}

object InetAddresses {
    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /** Parses an IP literal without triggering a DNS lookup. Returns null for hostnames. */
    fun parseLiteral(text: String): InetAddress? {
        val host = text.removePrefix("[").removeSuffix("]")
        ipv4.matchEntire(host)?.let { m ->
            val parts = m.groupValues.drop(1).map { it.toInt() }
            if (parts.any { it > 255 }) return null
            return InetAddress.getByAddress(ByteArray(4) { parts[it].toByte() })
        }
        if (host.contains(':') && host.all { it.isHexDigit() || it == ':' || it == '.' }) {
            // Contains ':' so it's an IPv6 literal: InetAddress parses it without a lookup.
            return runCatching { InetAddress.getByName(host) }.getOrNull()
        }
        return null
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
