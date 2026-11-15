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
