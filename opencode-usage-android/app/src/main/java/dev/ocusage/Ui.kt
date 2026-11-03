package dev.ocusage

import android.content.Context
import java.time.Instant
import java.util.Locale
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Shared formatting for the app screen and the widget. */
object Ui {
    fun color(ctx: Context, m: Meter): Int = ctx.getColor(
        when {
            m.exceeded || m.percent >= 90 -> R.color.danger
            m.percent >= 70 -> R.color.warn
            else -> R.color.accent
        }
    )

    fun pct(m: Meter): String =
        if (m.percent < 10 && m.percent % 1.0 != 0.0) String.format(Locale.US, "%.1f%%", m.percent)
        else "${m.percent.toInt()}%"

    /** Progress value for a bar with max = 1000. */
    fun progress(m: Meter): Int = (m.percent.coerceIn(0.0, 100.0) * 10).toInt()

    fun time(at: Instant): String =
        at.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
}
