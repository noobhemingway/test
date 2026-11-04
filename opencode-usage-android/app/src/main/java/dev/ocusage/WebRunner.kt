package dev.ocusage

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONObject
import java.util.UUID

/**
 * A browser layer that shares cookies with the sign-in browser. Normally invisible: for each job
 * it opens a page on the provider's own site, runs a small script there (so requests go out
 * exactly like the site's own, with your session) and hands back the result.
 *
 * If that fails (a "verify you're human" check, a sign-in page, or a page it can't read), the
 * layer comes to the front showing the site, so you can deal with it and tap Continue. The job
 * then runs once more; if it still fails, the error is reported. One job at a time.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebRunner(ctx: Context, private val overlay: FrameLayout) {
    private class Job(
        val url: String,
        val body: String,
        val timeoutMs: Long,
        val title: String,
        /** Page to show when you're needed; null = keep the page the job is on (page-reading jobs). */
        val revealUrl: String?,
        /** Throws if the result can't be read. */
        val validate: (String) -> Unit,
        val cb: (Result<String>) -> Unit,
    ) {
        var revealed = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val web = WebView(ctx)
    private val panel = LinearLayout(ctx)
    private val message = Ui.text(ctx, "", 14f)
    private var job: Job? = null
    private var token: String? = null
    private var script: String? = null
    private var host: String? = null
    private var timeout: Runnable? = null

    /** True while the site is on screen waiting for you. */
    val isRevealed: Boolean get() = job?.revealed == true && script == null && token == null

    init {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.javaScriptCanOpenWindowsAutomatically = true
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

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ctx.getColor(R.color.card))
            val p = Ui.dp(ctx, 12)
            setPadding(p + Ui.dp(ctx, 4), p, p, Ui.dp(ctx, 4))
            addView(message)
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.END
                addView(Ui.button(ctx, "Cancel", borderless = true) { cancel() })
                addView(Ui.button(ctx, "Continue") { resume() })
            })
        }
        panel.orientation = LinearLayout.VERTICAL
        panel.addView(bar)
        panel.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        overlay.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        hide()
    }

    inner class Bridge {
        @JavascriptInterface
        fun post(tok: String, payload: String) {
            main.post { if (tok == token) handle(decode(payload)) }
        }
    }

    fun run(
        url: String, body: String, timeoutMs: Long, title: String, revealUrl: String?,
        validate: (String) -> Unit, cb: (Result<String>) -> Unit,
    ) {
        job?.let { complete(Result.failure(UsageException("Interrupted."))) }
        job = Job(url, body, timeoutMs, title, revealUrl, validate, cb)
        attempt(reload = true)
    }

    /** Runs the job's script, either after loading its page or right on the page that's open. */
    private fun attempt(reload: Boolean) {
        val j = job ?: return
        val tok = UUID.randomUUID().toString()
        token = tok
        host = Uri.parse(j.url).host
        val wrapped = """
            (async () => {
              let out;
              try { out = { ok: true, data: await (async () => { ${j.body} })() }; }
              catch (e) { out = { ok: false, error: String((e && e.message) || e) }; }
              OCBridge.post('$tok', JSON.stringify(out));
            })();
        """.trimIndent()
        timeout = Runnable {
            val onSite = Uri.parse(web.url ?: "").host == host
            token = null
            script = null
            handle(Result.failure(UsageException(
                if (onSite) "Timed out waiting for the site." else "Not signed in. Sign in again in Accounts."
            )))
        }.also { main.postDelayed(it, j.timeoutMs) }
        if (reload) {
            script = wrapped
            web.loadUrl(j.url)
        } else {
            script = null
            web.evaluateJavascript(wrapped, null)
        }
    }

    private fun handle(r: Result<String>) {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
        token = null
        script = null
        val j = job ?: return
        val readable = r.isSuccess && runCatching { j.validate(r.getOrThrow()) }.isSuccess
        if (readable || j.revealed) complete(r) else reveal(j, r.exceptionOrNull()?.message)
    }

    private fun reveal(j: Job, reason: String?) {
        j.revealed = true
        message.text = buildString {
            append("${j.title} needs you. Sign in or finish any check, then tap Continue.")
            if (!reason.isNullOrBlank()) append("\n($reason)")
        }
        show()
        val current = web.url
        when {
            j.revealUrl != null -> web.loadUrl(j.revealUrl)
            current == null || current == "about:blank" -> web.loadUrl(j.url)
            // else: keep the page that's already open (the one we couldn't read)
        }
    }

    /** Continue: read from the page you're on if it's the right site, otherwise load the job's page again. */
    private fun resume() {
        val j = job ?: return
        if (!isRevealed) return
        hide()
        val onSite = Uri.parse(web.url ?: "").host == Uri.parse(j.url).host
        attempt(reload = !(j.revealUrl == null && onSite))
    }

    fun cancel() {
        if (job != null) complete(Result.failure(UsageException("Cancelled.")))
    }

    private fun complete(r: Result<String>) {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
        token = null
        script = null
        val j = job
        job = null
        hide()
        web.loadUrl("about:blank")
        j?.cb?.invoke(r)
    }

    private fun show() {
        panel.alpha = 1f
        panel.getChildAt(0).visibility = View.VISIBLE
        overlay.setBackgroundColor(overlay.context.getColor(R.color.bg))
        overlay.isClickable = true
        overlay.bringToFront()
    }

    /** Invisible and behind the screen's content, but still full size so pages render normally. */
    private fun hide() {
        panel.alpha = 0f
        panel.getChildAt(0).visibility = View.GONE
        overlay.background = null
        overlay.isClickable = false
        val parent = overlay.parent as? ViewGroup ?: return
        if (parent.indexOfChild(overlay) != 0) {
            val lp = overlay.layoutParams
            parent.removeView(overlay)
            parent.addView(overlay, 0, lp)
        }
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
                        "Signed out, or blocked by a check."
                    else -> err.ifEmpty { "Unknown error from the site." }
                }
            )
        }
    }

    fun destroy() {
        timeout?.let { main.removeCallbacks(it) }
        job = null
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
