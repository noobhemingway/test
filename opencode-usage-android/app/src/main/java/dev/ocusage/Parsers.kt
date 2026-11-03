package dev.ocusage

import org.json.JSONObject
import java.time.Instant

/** Everything on the OpenCode console Activity page that we show natively. */
data class ConsoleData(
    val goPercent: Double?,
    val renews: String?,
    val limits: List<Meter>,
    val models: List<Pair<String, Double>>,
    val requests: String?,
    val tokens: String?,
    val extraStatus: String?,
    val credit: String?,
)

/** Turns raw responses (JSON or page text) into meters. All parsing is tolerant of missing fields. */
object Parsers {

    fun snapshot(id: String, raw: String, at: Instant): Snapshot = when (id) {
        Ids.OPENCODE -> openCode(raw, at)
        Ids.CLAUDE -> claude(raw, at)
        Ids.CODEX -> codex(raw, at)
        Ids.GROK -> grok(raw, at)
        else -> throw UsageException("Unknown provider $id")
    }

    private fun json(raw: String): JSONObject = try {
        JSONObject(raw)
    } catch (e: Exception) {
        throw UsageException("Unexpected response (not JSON).")
    }

    private fun num(o: JSONObject, vararg keys: String): Double? {
        for (k in keys) {
            if (o.has(k) && !o.isNull(k)) {
                val d = o.optDouble(k)
                if (!d.isNaN()) return d
            }
        }
        return null
    }

    // ---- OpenCode Go (API key) -------------------------------------------------------------

    fun openCode(raw: String, at: Instant): Snapshot {
        val root = json(raw)
        val usage = root.optJSONObject("usage") ?: root
        fun meter(key: String, label: String): Meter {
            val o = usage.optJSONObject(key)
                ?: throw UsageException("Response is missing the \"$key\" window.")
            val percent = num(o, "percent") ?: 0.0
            val resetsAt = Times.parse(o.optString("resetsAt", ""))
            return Meter(
                label = label,
                percent = percent,
                status = o.optString("status", "ok"),
                resetsAt = resetsAt,
                // Same wording as the console when a window hasn't started yet.
                resetNote = if (resetsAt == null && percent == 0.0) "Starts on first use" else null,
            )
        }
        return Snapshot(
            listOf(meter("rolling", "5-hour"), meter("weekly", "Weekly"), meter("monthly", "Monthly")), at,
        )
    }

    // ---- Claude (claude.ai/api/organizations/{org}/usage) --------------------------------

    fun claude(raw: String, at: Instant): Snapshot {
        val root = json(raw)
        val usage = root.optJSONObject("usage") ?: root
        if (!usage.has("five_hour") && !usage.has("seven_day")) {
            throw UsageException("Claude's response had no usage windows.")
        }
        fun meter(key: String, label: String): Meter {
            val o = usage.optJSONObject(key)
                ?: return Meter(label, 0.0, resetNote = "Starts on first use")
            return Meter(
                label = label,
                percent = num(o, "utilization", "percent", "used_percent") ?: 0.0,
                resetsAt = Times.parse(o.optString("resets_at", "")),
            )
        }
        return Snapshot(listOf(meter("five_hour", "5-hour"), meter("seven_day", "Weekly")), at)
    }

    // ---- Codex (chatgpt.com/backend-api/wham/usage) --------------------------------------

    fun codex(raw: String, at: Instant): Snapshot {
        val root = json(raw)
        val rl = root.optJSONObject("rate_limit") ?: root.optJSONObject("rate_limits")
            ?: throw UsageException("Codex response had no rate limits.")
        val meters = mutableListOf<Meter>()
        for (key in listOf("primary_window", "secondary_window")) {
            val w = rl.optJSONObject(key) ?: continue
            val seconds = num(w, "limit_window_seconds")?.toLong()
            val resetsAt = Times.parse(w.optString("reset_at", ""))
                ?: num(w, "reset_after_seconds")?.let { at.plusSeconds(it.toLong()) }
            meters += Meter(
                label = windowLabel(seconds, if (key == "primary_window") "5-hour" else "Weekly"),
                percent = num(w, "used_percent", "percent") ?: 0.0,
                resetsAt = resetsAt,
            )
        }
        if (meters.isEmpty()) throw UsageException("Codex response had no usage windows.")
        return Snapshot(meters, at)
    }

    private fun windowLabel(seconds: Long?, fallback: String): String = when {
        seconds == null || seconds <= 0 -> fallback
        seconds == 18_000L -> "5-hour"
        seconds == 604_800L -> "Weekly"
        seconds in 2_419_200L..2_678_400L -> "Monthly"
        seconds < 86_400L -> "${seconds / 3600}-hour"
        else -> "${seconds / 86_400}-day"
    }

    // ---- Grok (text of the usage page you picked) -----------------------------------------

    private val pct = Regex("""(\d+(?:\.\d+)?)\s*%""")

    fun grok(raw: String, at: Instant): Snapshot {
        val lines = lines(raw)
        var percent: Double? = null
        var at0 = -1
        for ((i, l) in lines.withIndex()) {
            val m = pct.find(l) ?: continue
            val near = (listOfNotNull(lines.getOrNull(i - 1), l, lines.getOrNull(i + 1))).joinToString(" ").lowercase()
            if ("used" in near || "usage" in near) {
                percent = m.groupValues[1].toDouble()
                at0 = i
                break
            }
        }
        if (percent == null) {
            throw UsageException("Couldn't find a usage % on the Grok page. In Accounts, choose the page that shows your usage.")
        }
        // Prefer a reset line close to the percentage.
        val reset = lines.withIndex()
            .filter { it.value.contains("reset", ignoreCase = true) && it.value.length <= 80 }
            .minByOrNull { kotlin.math.abs(it.index - at0) }?.value
        return Snapshot(
            listOf(Meter("Weekly", percent, resetsAt = reset?.let { Times.relative(it, at) }, resetNote = reset)), at,
        )
    }

    // ---- OpenCode console Activity page (page text) ----------------------------------------

    fun lines(text: String): List<String> =
        text.split('\n').map { it.replace(' ', ' ').trim() }.filter { it.isNotEmpty() }

    private val pctUsed = Regex("""(\d+(?:\.\d+)?)%\s*used""", RegexOption.IGNORE_CASE)
    private val pctOnly = Regex("""^(\d+(?:\.\d+)?)%$""")
    private val namePct = Regex("""^(.*\S)\s+(\d+(?:\.\d+)?)%$""")
    private val renewsRe = Regex("""Renews\s+([A-Za-z]{3,9}\.?\s+\d{1,2})""")

    fun console(text: String, at: Instant): ConsoleData {
        val l = lines(text)
        fun idx(pred: (String) -> Boolean, from: Int = 0): Int {
            for (i in from until l.size) if (pred(l[i])) return i
            return -1
        }
        fun after(label: String, from: Int = 0): String? {
            val i = idx({ it == label || it.startsWith("$label ") }, from)
            if (i < 0) return null
            val rest = l[i].removePrefix(label).trim()
            return rest.ifEmpty { l.getOrNull(i + 1) }
        }

        // "18%" / "Go usage · Renews Dec 3"
        var goPercent: Double? = null
        var renews: String? = null
        for (i in l.indices) {
            val m = Regex("""^(\d+(?:\.\d+)?)%""").find(l[i]) ?: continue
            val window = l.subList(i, minOf(l.size, i + 4)).joinToString(" ")
            if ("Go usage" in window) {
                goPercent = m.groupValues[1].toDouble()
                renews = renewsRe.find(window)?.groupValues?.get(1)
                break
            }
        }

        // Limits card: Rolling / Weekly / Monthly, each followed by a note and "N% used".
        val limitsStart = idx({ it.startsWith("When your Go usage resets") }).let {
            if (it >= 0) it else idx({ it == "Limits" })
        }
        val limits = mutableListOf<Meter>()
        if (limitsStart >= 0) {
            for ((name, label) in listOf("Rolling" to "5-hour", "Weekly" to "Weekly", "Monthly" to "Monthly")) {
                val i = idx({ it == name || it.startsWith("$name ") }, limitsStart)
                if (i < 0) continue
                val window = l.subList(i, minOf(l.size, i + 4)).joinToString(" ")
                val m = pctUsed.find(window) ?: continue
                val note = window.substring(name.length, m.range.first).trim().trim('·').trim()
                limits += Meter(
                    label = label,
                    percent = m.groupValues[1].toDouble(),
                    resetsAt = Times.relative(note, at),
                    resetNote = note.ifEmpty { null },
                )
            }
        }

        // Models card: "Most used this period", then name / percent pairs until "Requests".
        val modelsStart = idx({ it.startsWith("Most used this period") })
        val models = mutableListOf<Pair<String, Double>>()
        var requestsFrom = 0
        if (modelsStart >= 0) {
            var pendingName: String? = null
            var i = modelsStart + 1
            while (i < l.size && !l[i].startsWith("Requests")) {
                val line = l[i]
                val only = pctOnly.find(line)
                val both = namePct.find(line)
                when {
                    only != null -> {
                        val n = pendingName
                        if (n != null) models += n to only.groupValues[1].toDouble()
                        pendingName = null
                    }
                    both != null && both.groupValues[1] != "GO" ->
                        models += both.groupValues[1].removePrefix("GO ").trim() to both.groupValues[2].toDouble()
                    line != "GO" -> pendingName = line
                }
                i++
            }
            requestsFrom = modelsStart
        }

        val extraIdx = idx({ it == "Extra usage" })
        val extraStatus = if (extraIdx >= 0) l.getOrNull(extraIdx + 1)?.takeIf { !it.startsWith("Available credit") } else null

        val data = ConsoleData(
            goPercent = goPercent,
            renews = renews,
            limits = limits,
            models = models,
            requests = after("Requests", requestsFrom),
            tokens = after("Tokens", requestsFrom),
            extraStatus = extraStatus,
            credit = after("Available credit"),
        )
        if (data.goPercent == null && data.models.isEmpty() && data.limits.isEmpty()) {
            throw UsageException("Couldn't read the console page. You may need to sign in again.")
        }
        return data
    }
}
