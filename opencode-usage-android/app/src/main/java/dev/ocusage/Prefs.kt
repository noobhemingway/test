package dev.ocusage

import android.content.Context
import java.time.Instant

/** API key and last fetched usage, kept in the app's private storage. */
object Prefs {
    private const val FILE = "ocusage"
    private const val KEY_API = "api_key"
    private const val KEY_LAST = "last_body"
    private const val KEY_LAST_AT = "last_at"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun apiKey(ctx: Context): String? = sp(ctx).getString(KEY_API, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(ctx: Context, key: String?) {
        sp(ctx).edit().putString(KEY_API, key?.trim()).remove(KEY_LAST).remove(KEY_LAST_AT).apply()
    }

    fun cached(ctx: Context): Usage? {
        val p = sp(ctx)
        val body = p.getString(KEY_LAST, null) ?: return null
        val at = Instant.ofEpochMilli(p.getLong(KEY_LAST_AT, 0L))
        return runCatching { UsageApi.parse(body, at) }.getOrNull()
    }

    /** Fetches fresh usage (blocking), caches it and returns it. */
    fun refresh(ctx: Context): Usage {
        val key = apiKey(ctx) ?: throw UsageException("No API key set.")
        val body = UsageApi.fetchRaw(key)
        val now = Instant.now()
        sp(ctx).edit().putString(KEY_LAST, body).putLong(KEY_LAST_AT, now.toEpochMilli()).apply()
        return UsageApi.parse(body, now)
    }
}
