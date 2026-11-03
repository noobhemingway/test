package dev.ocusage

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One usage window (5-hour, weekly or monthly). */
data class Meter(val percent: Double, val status: String, val resetsAt: Instant?) {
    val exceeded: Boolean get() = percent >= 100.0 || !status.equals("ok", ignoreCase = true)

    fun resetText(now: Instant = Instant.now()): String {
        val at = resetsAt ?: return "Reset time unknown"
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

data class Usage(val rolling: Meter, val weekly: Meter, val monthly: Meter, val fetchedAt: Instant)

class UsageException(message: String) : Exception(message)

object UsageApi {
    private const val USAGE_URL = "https://opencode.ai/zen/go/v1/usage"

    /** Blocking call; run off the main thread. */
    fun fetchRaw(apiKey: String): String {
        val conn = (URL(USAGE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "opencode-usage-android/1.0")
        }
        try {
            val code = conn.responseCode
            when (code) {
                401 -> throw UsageException("API key not recognised (401). Check the key in the OpenCode console.")
                403 -> throw UsageException("This key has no OpenCode Go subscription (403).")
                in 200..299 -> {}
                else -> throw UsageException("Server returned HTTP $code.")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(body) // validate before caching
            return body
        } finally {
            conn.disconnect()
        }
    }

    fun parse(body: String, fetchedAt: Instant = Instant.now()): Usage {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw UsageException("Unexpected response from server.")
        }
        val usage = root.optJSONObject("usage") ?: root
        fun meter(name: String): Meter {
            val o = usage.optJSONObject(name)
                ?: throw UsageException("Response is missing the \"$name\" window.")
            val resets = o.optString("resetsAt", "").takeIf { it.isNotBlank() && it != "null" }
            return Meter(
                percent = o.optDouble("percent", 0.0).let { if (it.isNaN()) 0.0 else it },
                status = o.optString("status", "ok"),
                resetsAt = resets?.let { runCatching { Instant.parse(it) }.getOrNull() },
            )
        }
        return Usage(meter("rolling"), meter("weekly"), meter("monthly"), fetchedAt)
    }
}
