package io.github.joanrra2.pocketwake.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    /** 7 -> "7 s", 65 -> "1 min 5 s", 120 -> "2 min". */
    fun seconds(total: Long): String {
        val s = total.coerceAtLeast(0)
        return when {
            s < 60 -> "$s s"
            s % 60 == 0L -> "${s / 60} min"
            else -> "${s / 60} min ${s % 60} s"
        }
    }

    /** Board uptime: 45 -> "45 s", 754 -> "12 min", 11_520 -> "3 h 12 min", 367_200 -> "4 d 6 h". */
    fun uptime(total: Long): String {
        val s = total.coerceAtLeast(0)
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            s < 86_400 -> if (s / 60 % 60 == 0L) "${s / 3600} h" else "${s / 3600} h ${s / 60 % 60} min"
            else -> if (s / 3600 % 24 == 0L) "${s / 86_400} d" else "${s / 86_400} d ${s / 3600 % 24} h"
        }
    }

    /** "power dip (brownout)" -> "Power dip (brownout)". */
    fun sentence(text: String): String = text.replaceFirstChar { it.uppercaseChar() }

    /** 139651 -> "139,651" (grouped for [locale]). */
    fun count(n: Long, locale: Locale = Locale.getDefault()): String = java.text.NumberFormat.getIntegerInstance(locale).format(n)

    /** 30.84 -> "30.8 %". */
    fun percent(p: Double, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.1f %%", p)

    /** "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago". */
    fun ago(now: Long, then: Long): String {
        val d = ((now - then) / 1000).coerceAtLeast(0)
        return when {
            d < 45 -> "just now"
            d < 3600 -> "${maxOf(1, (d + 30) / 60)} min ago"
            d < 86_400 -> "${d / 3600} h ago"
            d < 2 * 86_400 -> "yesterday"
            else -> "${d / 86_400} days ago"
        }
    }

    /** "14:32" today, "yesterday 14:32", otherwise "3 Nov 14:32". */
    fun clock(
        now: Long,
        then: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val t = Instant.ofEpochMilli(then).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = t.format(DateTimeFormatter.ofPattern("HH:mm", locale))
        return when (t.toLocalDate()) {
            today -> time
            today.minusDays(1) -> "yesterday $time"
            else -> t.format(DateTimeFormatter.ofPattern("d MMM", locale)) + " $time"
        }
    }
}
