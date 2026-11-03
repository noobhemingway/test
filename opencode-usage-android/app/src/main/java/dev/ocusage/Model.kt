package dev.ocusage

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The four things on the home page. "console" is the OpenCode web console (per-model page). */
object Ids {
    const val OPENCODE = "opencode"
    const val CLAUDE = "claude"
    const val CODEX = "codex"
    const val GROK = "grok"
    const val CONSOLE = "console"

    val HOME = listOf(OPENCODE, CLAUDE, CODEX, GROK)

    fun title(id: String) = when (id) {
        OPENCODE -> "OpenCode Go"
        CLAUDE -> "Claude"
        CODEX -> "Codex"
        GROK -> "Grok"
        CONSOLE -> "OpenCode console"
        else -> id
    }
}

/** One usage window, e.g. "5-hour 12% used, resets in 2h". */
data class Meter(
    val label: String,
    val percent: Double,
    val status: String = "ok",
    val resetsAt: Instant? = null,
    val resetNote: String? = null,
) {
    val exceeded: Boolean get() = percent >= 100.0 || !status.equals("ok", ignoreCase = true)

    fun resetText(now: Instant = Instant.now()): String {
        val at = resetsAt ?: return resetNote ?: "Reset time unknown"
        val d = Duration.between(now, at)
        if (d.isNegative || d.isZero) return "Resetting now"
        val days = d.toDays()
        val hours = d.toHours() % 24
        val mins = d.toMinutes() % 60
        val left = when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${mins}m"
            else -> "${maxOf(mins, 1)}m"
        }
        val local = at.atZone(ZoneId.systemDefault())
        val pattern = if (days > 0) "EEE d MMM, HH:mm" else "HH:mm"
        return "Resets in $left (${local.format(DateTimeFormatter.ofPattern(pattern))})"
    }
}

/** What a provider card shows: its meters and when they were fetched. */
data class Snapshot(val meters: List<Meter>, val fetchedAt: Instant)

class UsageException(message: String) : Exception(message)

object Times {
    /** ISO-8601 with Z or an offset, or epoch seconds / millis. */
    fun parse(s: String?): Instant? {
        if (s.isNullOrBlank() || s == "null") return null
        s.toLongOrNull()?.let { return if (it > 100_000_000_000L) Instant.ofEpochMilli(it) else Instant.ofEpochSecond(it) }
        return runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(s) }.getOrNull()
    }

    private val part = Regex("""(\d+)\s*(d|h|m)\b""")

    /** "Resets in 5d 7h" -> fetchedAt + 5d7h. Null if the text has no duration. */
    fun relative(text: String, from: Instant): Instant? {
        if (!text.contains("in ", ignoreCase = true)) return null
        var total = Duration.ZERO
        var found = false
        for (m in part.findAll(text.substringAfter("in "))) {
            found = true
            val n = m.groupValues[1].toLong()
            total = total.plus(
                when (m.groupValues[2]) {
                    "d" -> Duration.ofDays(n)
                    "h" -> Duration.ofHours(n)
                    else -> Duration.ofMinutes(n)
                }
            )
        }
        return if (found) from.plus(total) else null
    }
}
