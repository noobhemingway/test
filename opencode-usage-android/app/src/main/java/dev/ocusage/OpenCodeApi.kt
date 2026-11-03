package dev.ocusage

import java.net.HttpURLConnection
import java.net.URL

/** OpenCode Go usage via API key: GET https://opencode.ai/zen/go/v1/usage */
object OpenCodeApi {
    private const val USAGE_URL = "https://opencode.ai/zen/go/v1/usage"

    /** Blocking; run off the main thread. Returns the raw JSON body (validated). */
    fun fetchRaw(apiKey: String): String {
        val conn = (URL(USAGE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "opencode-usage-android/2.0")
        }
        try {
            when (val code = conn.responseCode) {
                401 -> throw UsageException("API key not recognised (401). Check it in Accounts.")
                403 -> throw UsageException("This key has no OpenCode Go subscription (403).")
                in 200..299 -> {}
                else -> throw UsageException("Server returned HTTP $code.")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            Parsers.openCode(body, java.time.Instant.now()) // validate before caching
            return body
        } finally {
            conn.disconnect()
        }
    }
}
