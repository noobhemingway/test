package dev.ocusage

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast

/**
 * In-app browser for signing in. Cookies are shared with the hidden reader, so after you sign in
 * here, Refresh can read your usage. "Paste link" opens a sign-in link copied from your email.
 */
class BrowserActivity : Activity() {
    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_URL = "url"
    }

    private lateinit var web: WebView
    private lateinit var providerId: String

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        providerId = intent.getStringExtra(EXTRA_ID) ?: return finish()
        val start = intent.getStringExtra(EXTRA_URL) ?: return finish()

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    val scheme = req.url.scheme ?: return true
                    if (scheme == "http" || scheme == "https") return false
                    // mailto:, intent: etc. go to the system.
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, req.url)) }
                    return true
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        val bar = LinearLayout(this).apply {
            val p = Ui.dp(this@BrowserActivity, 8)
            setPadding(p * 2, p, p, p)
            setBackgroundColor(getColor(R.color.card))
            addView(Ui.text(this@BrowserActivity, Ids.title(providerId), 16f, bold = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                })
            addView(Ui.button(this@BrowserActivity, "Paste link", borderless = true) { pasteLink() })
            addView(Ui.button(this@BrowserActivity, "Done") { done() })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
        }
        setContentView(root)
        web.loadUrl(start)
    }

    private fun pasteLink() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text != null && text.startsWith("https://")) web.loadUrl(text)
        else Toast.makeText(this, "Copy a https:// link first (e.g. the sign-in link from your email).", Toast.LENGTH_LONG).show()
    }

    private fun done() {
        CookieManager.getInstance().flush()
        Store.setSignedIn(this, providerId, true)
        val url = web.url
        val host = url?.let { Uri.parse(it).host } ?: ""
        when (providerId) {
            Ids.CONSOLE -> if (host == "opencode.ai" && !url!!.contains("/auth")) Store.setPageUrl(this, providerId, url)
            Ids.GROK -> if (host == "grok.com" || host.endsWith(".grok.com")) Store.setPageUrl(this, providerId, url)
        }
        finish()
    }

    @Deprecated("Back navigates inside the page first.")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
