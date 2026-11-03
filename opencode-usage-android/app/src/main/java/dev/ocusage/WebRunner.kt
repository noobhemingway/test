package dev.ocusage

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.UUID

/**
 * A hidden WebView that shares cookies with the sign-in browser. For each job it opens a page on
 * the provider's own site, runs a small script there (so requests go out exactly like the site's
 * own, with your session), and hands back the result. One job at a time.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebRunner(ctx: Context, container: ViewGroup) {
    private val main = Handler(Looper.getMainLooper())
    private val web = WebView(ctx)
    private var token: String? = null
    private var script: String? = null
    private var host: String? = null
    private var callback: ((Result<String>) -> Unit)? = null
    private var timeout: Runnable? = null

    init {
        container.addView(web, ViewGroup.LayoutParams(1, 1))
        web.alpha = 0f
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Bridge(), "OCBridge")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val s = script ?: return
                if (Uri.parse(url).host != host) return // e.g. bounced to a login page
                script = null
                view.evaluateJavascript(s, null)
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun post(tok: String, payload: String) {
            main.post { if (tok == token) finish(decode(payload)) }
        }
    }

    fun run(url: String, body: String, timeoutMs: Long, cb: (Result<String>) -> Unit) {
        val tok = UUID.randomUUID().toString()
        token = tok
        host = Uri.parse(url).host
        callback = cb
        script = """
            (async () => {
              let out;
              try { out = { ok: true, data: await (async () => { $body })() }; }
              catch (e) { out = { ok: false, error: String((e && e.message) || e) }; }
              OCBridge.post('$tok', JSON.stringify(out));
            })();
        """.trimIndent()
        timeout = Runnable {
            val onSite = Uri.parse(web.url ?: "").host == host
            finish(Result.failure(UsageException(
                if (onSite) "Timed out waiting for the site." else "Not signed in. Sign in again in Accounts."
            )))
        }.also { main.postDelayed(it, timeoutMs) }
        web.loadUrl(url)
    }

    private fun decode(payload: String): Result<String> = runCatching {
        val o = JSONObject(payload)
        if (o.optBoolean("ok")) {
            when (val d = o.opt("data")) {
                is String -> d
                null, JSONObject.NULL -> throw UsageException("The site returned nothing.")
                else -> d.toString()
            }
        } else {
            val err = o.optString("error")
            throw UsageException(
                when {
                    err.contains("NOT_SIGNED_IN") || err.contains("HTTP 401") || err.contains("HTTP 403") ->
                        "Signed out. Sign in again in Accounts."
                    else -> err.ifEmpty { "Unknown error from the site." }
                }
            )
        }
    }

    private fun finish(r: Result<String>) {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
        token = null
        script = null
        val cb = callback
        callback = null
        web.loadUrl("about:blank")
        cb?.invoke(r)
    }

    fun destroy() {
        timeout?.let { main.removeCallbacks(it) }
        callback = null
        web.destroy()
    }
}

/** The scripts each site runs, and where. Nothing here sends anything except to that site. */
object Jobs {
    private const val GET_JSON = """
        const getJson = async (u, h) => {
          const r = await fetch(u, { credentials: 'include', headers: Object.assign({ accept: 'application/json' }, h || {}) });
          const t = await r.text();
          if (!r.ok) throw new Error('HTTP ' + r.status);
          try { return JSON.parse(t); } catch (e) { throw new Error('Not JSON (the site may be showing a check page)'); }
        };
    """

    // Claude: your organization, then its usage.
    const val CLAUDE_URL = "https://claude.ai/robots.txt"
    const val CLAUDE_JS = GET_JSON + """
        const orgs = await getJson('/api/organizations');
        const list = Array.isArray(orgs) ? orgs : [];
        if (!list.length) throw new Error('NOT_SIGNED_IN');
        const org = list.find(o => (o.capabilities || []).includes('chat')) || list[0];
        const usage = await getJson('/api/organizations/' + org.uuid + '/usage');
        return JSON.stringify({ usage: usage });
    """

    // Codex: the site's session token, then the same usage endpoint the Codex CLI uses.
    // The token stays inside the page and is never returned to the app.
    const val CODEX_URL = "https://chatgpt.com/robots.txt"
    const val CODEX_JS = GET_JSON + """
        const s = await getJson('/api/auth/session');
        if (!s || !s.accessToken) throw new Error('NOT_SIGNED_IN');
        const h = { Authorization: 'Bearer ' + s.accessToken };
        if (s.account && s.account.id) h['ChatGPT-Account-Id'] = s.account.id;
        const u = await getJson('/backend-api/wham/usage', h);
        return JSON.stringify({ rate_limit: u.rate_limit || u.rate_limits || null, plan_type: u.plan_type || null });
    """

    /** Waits for the page to render something matching [pattern], then returns its visible text. */
    fun pageText(pattern: String, waitMs: Int): String = """
        const re = new RegExp(${JSONObject.quote(pattern)});
        const end = Date.now() + $waitMs;
        const text = () => (document.body && document.body.innerText) || '';
        while (Date.now() < end) {
          if (re.test(text())) { await new Promise(r => setTimeout(r, 1200)); return text(); }
          await new Promise(r => setTimeout(r, 500));
        }
        return text();
    """

    const val CONSOLE_DEFAULT_URL = "https://opencode.ai/console"
    val CONSOLE_JS = pageText("Most used this period", 20_000)
    val GROK_JS = pageText("\\d\\s*%", 20_000)
}
