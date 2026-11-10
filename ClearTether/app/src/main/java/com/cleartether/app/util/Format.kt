package com.cleartether.app.util

import java.util.Locale

object Format {
    fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = value.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (v >= 100) String.format(Locale.US, "%.0f %s", v, units[i]) else String.format(Locale.US, "%.1f %s", v, units[i])
    }

    /** Allowance wording such as "15 min" (rounded down) or "40 s" under a minute. */
    fun allowance(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return if (s >= 60) "${s / 60} min" else "$s s"
    }

    /** `mm:ss`, or `h:mm:ss` past an hour. */
    fun duration(ms: Long): String {
        val total = (ms.coerceAtLeast(0) + 999) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }
}
