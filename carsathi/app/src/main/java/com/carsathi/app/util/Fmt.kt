package com.carsathi.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object Fmt {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val timeF = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val dayF = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val dayLongF = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
    private val dayYearF = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val monthYearF = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

    fun local(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)

    fun time(ms: Long): String = local(ms).format(timeF)

    fun day(ms: Long): String = relativeDay(local(ms).toLocalDate()) ?: local(ms).format(dayF)

    fun dayLong(ms: Long): String = local(ms).format(dayLongF)

    fun dayTime(ms: Long): String = "${day(ms)}, ${time(ms)}"

    fun date(d: LocalDate): String = relativeDay(d) ?: d.format(dayF)

    fun dateLong(d: LocalDate): String = d.format(dayLongF)

    fun dateYear(ms: Long): String = local(ms).format(dayYearF)

    fun monthYear(ms: Long): String = local(ms).format(monthYearF)

    private fun relativeDay(d: LocalDate): String? {
        val today = LocalDate.now()
        return when (d) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            today.minusDays(1) -> "Yesterday"
            else -> null
        }
    }

    fun duration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}min"
            m == 0 -> "${h}h"
            else -> "${h}h${m.toString().padStart(2, '0')}"
        }
    }

    fun rupees(amount: Int): String = "₹" + String.format(Locale.ENGLISH, "%,d", amount)

    fun ago(ms: Long): String {
        val now = System.currentTimeMillis()
        val diff = (now - ms) / 1000
        return when {
            diff < 60 -> "just now"
            diff < 3600 -> "${diff / 60} min ago"
            diff < 86_400 -> "${diff / 3600} h ago"
            diff < 7 * 86_400 -> "${diff / 86_400} d ago"
            else -> local(ms).format(dayYearF)
        }
    }

    fun chatTime(ms: Long): String {
        val d = local(ms).toLocalDate()
        return if (d == LocalDate.now()) time(ms) else "${d.format(dayF)} ${time(ms)}"
    }

    fun daysUntil(ms: Long): Long = ChronoUnit.DAYS.between(LocalDate.now(), local(ms).toLocalDate())

    fun rating(avg: Double): String = String.format(Locale.ENGLISH, "%.1f", avg)
}
