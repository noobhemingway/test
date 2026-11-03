package dev.ocusage

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import java.time.Instant

/**
 * Everything the app remembers, in its private storage: the OpenCode API key, which web
 * accounts you've signed in to, the last good response per provider, and the last raw
 * response (good or bad) so you can see what a site returned.
 */
object Store {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("ocusage", Context.MODE_PRIVATE)

    // OpenCode API key
    fun apiKey(ctx: Context): String? = sp(ctx).getString("api_key", null)?.takeIf { it.isNotBlank() }
    fun setApiKey(ctx: Context, key: String?) {
        sp(ctx).edit().putString("api_key", key?.trim()).apply()
        if (key == null) clearData(ctx, Ids.OPENCODE)
    }

    // Web sign-ins (Claude, Codex, Grok, OpenCode console)
    fun signedIn(ctx: Context, id: String) = sp(ctx).getBoolean("signed_in_$id", false)
    fun setSignedIn(ctx: Context, id: String, value: Boolean) =
        sp(ctx).edit().putBoolean("signed_in_$id", value).apply()

    /** Page to read for Grok usage / the OpenCode console, chosen when you tapped Done. */
    fun pageUrl(ctx: Context, id: String): String? = sp(ctx).getString("page_$id", null)
    fun setPageUrl(ctx: Context, id: String, url: String?) = sp(ctx).edit().putString("page_$id", url).apply()

    fun configured(ctx: Context, id: String) =
        if (id == Ids.OPENCODE) apiKey(ctx) != null else signedIn(ctx, id)

    // Last good response
    fun saveRaw(ctx: Context, id: String, raw: String, at: Instant) =
        sp(ctx).edit().putString("raw_$id", raw).putLong("at_$id", at.toEpochMilli()).apply()

    fun raw(ctx: Context, id: String): Pair<String, Instant>? {
        val p = sp(ctx)
        val raw = p.getString("raw_$id", null) ?: return null
        return raw to Instant.ofEpochMilli(p.getLong("at_$id", 0L))
    }

    fun snapshot(ctx: Context, id: String): Snapshot? {
        val (raw, at) = raw(ctx, id) ?: return null
        return runCatching { Parsers.snapshot(id, raw, at) }.getOrNull()
    }

    // Last response or error, for "Show last response"
    fun saveDebug(ctx: Context, id: String, text: String) =
        sp(ctx).edit().putString("debug_$id", "${Instant.now()}\n\n${text.take(60_000)}").apply()
    fun debug(ctx: Context, id: String): String? = sp(ctx).getString("debug_$id", null)

    fun clearData(ctx: Context, id: String) =
        sp(ctx).edit().remove("raw_$id").remove("at_$id").remove("debug_$id").apply()

    /** Sign out of one web account: forget it and delete that site's cookies. */
    fun signOut(ctx: Context, id: String) {
        setSignedIn(ctx, id, false)
        clearData(ctx, id)
        if (id == Ids.GROK || id == Ids.CONSOLE) setPageUrl(ctx, id, null)
        clearCookies(domains(id))
    }

    fun signOutEverywhere(ctx: Context) {
        for (id in listOf(Ids.CLAUDE, Ids.CODEX, Ids.GROK, Ids.CONSOLE)) {
            setSignedIn(ctx, id, false)
            clearData(ctx, id)
            setPageUrl(ctx, id, null)
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }

    private fun domains(id: String) = when (id) {
        Ids.CLAUDE -> listOf("https://claude.ai")
        Ids.CODEX -> listOf("https://chatgpt.com", "https://auth.openai.com", "https://openai.com")
        Ids.GROK -> listOf("https://grok.com", "https://accounts.x.ai", "https://x.ai")
        Ids.CONSOLE -> listOf("https://opencode.ai")
        else -> emptyList()
    }

    private fun clearCookies(urls: List<String>) {
        val cm = CookieManager.getInstance()
        for (u in urls) {
            val host = Uri.parse(u).host ?: continue
            val cookies = cm.getCookie(u) ?: continue
            for (part in cookies.split(";")) {
                val name = part.substringBefore("=").trim()
                if (name.isEmpty()) continue
                cm.setCookie(u, "$name=; Max-Age=0; Path=/; Secure")
                cm.setCookie(u, "$name=; Max-Age=0; Path=/; Secure; Domain=.$host")
            }
        }
        cm.flush()
    }
}
