package dev.ocusage

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * OpenCode details, laid out like the console's Activity page: Go usage headline, Limits,
 * Models (most used this period), Requests/Tokens, Extra usage. Read from the console page
 * (signed in with GitHub) only when you tap Refresh.
 */
class OpenCodeActivity : Activity() {
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var refresh: Button
    private lateinit var runner: WebRunner
    private lateinit var refresher: Refresher
    private var error: String? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (col, hidden) = Ui.screen(this)
        runner = WebRunner(this, hidden)
        refresher = Refresher(applicationContext, runner)

        col.addView(Ui.title(this, "OpenCode Go"))
        body = Ui.column(this)
        col.addView(body)
        status = Ui.muted(this, "")
        col.addView(status)
        refresh = Ui.button(this, "Refresh") { load() }
        col.addView(LinearLayout(this).apply {
            setPadding(0, Ui.dp(this@OpenCodeActivity, 12), 0, 0)
            addView(refresh)
            addView(Ui.button(this@OpenCodeActivity, "Show data", borderless = true) {
                Ui.showText(this@OpenCodeActivity, "Console page text",
                    Store.debug(this@OpenCodeActivity, Ids.CONSOLE) ?: "Nothing fetched yet.")
            })
        })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    @Deprecated("Back closes a site that's waiting for you before leaving the screen.")
    override fun onBackPressed() {
        if (runner.isRevealed) runner.cancel() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        runner.destroy()
        refresher.close()
        super.onDestroy()
    }

    private fun load() {
        if (busy) return
        if (!Store.signedIn(this, Ids.CONSOLE)) {
            signIn()
            return
        }
        busy = true
        error = null
        refresh.isEnabled = false
        render()
        refresher.fetch(Ids.CONSOLE) { r ->
            busy = false
            error = r.exceptionOrNull()?.message
            if (!isDestroyed) {
                refresh.isEnabled = true
                render()
            }
        }
    }

    private fun signIn() = startActivity(
        Intent(this, BrowserActivity::class.java)
            .putExtra(BrowserActivity.EXTRA_ID, Ids.CONSOLE)
            .putExtra(BrowserActivity.EXTRA_URL, Store.pageUrl(this, Ids.CONSOLE) ?: Jobs.CONSOLE_DEFAULT_URL)
    )

    private fun render() {
        body.removeAllViews()
        val saved = Store.raw(this, Ids.CONSOLE)
        val data = saved?.let { (raw, at) -> runCatching { Parsers.console(raw, at) }.getOrNull() }

        if (!Store.signedIn(this, Ids.CONSOLE)) {
            body.addView(Ui.card(this).apply {
                addView(Ui.text(context, "See usage per model", 17f, bold = true))
                addView(Ui.muted(context,
                    "Your API key only gives the three limits. For the model breakdown, sign in to the " +
                        "OpenCode console once (GitHub works). Open the Activity tab, then tap Done.", 14f
                ).apply { setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 10)) })
                addView(Ui.button(this@OpenCodeActivity, "Sign in to console") { signIn() })
            })
        }

        if (data != null) {
            // Headline: "18%  Go usage · Renews Dec 3"
            data.goPercent?.let { p ->
                body.addView(Ui.text(this, Ui.pct(p), 34f, bold = true))
                body.addView(Ui.muted(this, listOfNotNull("Go usage", data.renews?.let { "Renews $it" }).joinToString(" · "), 15f)
                    .apply { setPadding(0, 0, 0, Ui.dp(context, 14)) })
            }
            limitsCard(data.limits.ifEmpty { Store.snapshot(this, Ids.OPENCODE)?.meters.orEmpty() })
            modelsCard(data)
            extraCard(data)
        } else {
            // No console data yet: still show the limits from the API key.
            Store.snapshot(this, Ids.OPENCODE)?.let { limitsCard(it.meters) }
        }

        status.text = when {
            busy -> "Reading the console…"
            error != null -> error + (saved?.let { " Still showing numbers from ${Ui.time(it.second)}." } ?: "")
            saved != null -> "As of ${Ui.time(saved.second)} · tap Refresh for new numbers"
            Store.signedIn(this, Ids.CONSOLE) -> "Tap Refresh to read your console."
            else -> ""
        }
        status.setTextColor(getColor(if (error != null && !busy) R.color.danger else R.color.text_muted))
        refresh.text = if (Store.signedIn(this, Ids.CONSOLE)) "Refresh" else "Sign in"
    }

    private fun header(card: LinearLayout, title: String, sub: String) {
        card.addView(Ui.text(this, title, 17f, bold = true))
        card.addView(Ui.muted(this, sub, 14f))
    }

    private fun limitsCard(meters: List<Meter>) {
        if (meters.isEmpty()) return
        body.addView(Ui.card(this).apply {
            header(this, "Limits", "When your Go usage resets")
            meters.forEach { addView(Ui.meterView(context, it)) }
        })
    }

    private fun modelsCard(d: ConsoleData) {
        if (d.models.isEmpty() && d.requests == null && d.tokens == null) return
        body.addView(Ui.card(this).apply {
            header(this, "Models", "Most used this period")
            for ((name, p) in d.models) {
                addView(Ui.column(context).apply {
                    setPadding(0, Ui.dp(context, 12), 0, 0)
                    addView(Ui.row(context, Ui.text(context, name, 15f), Ui.text(context, Ui.pct(p), 15f, bold = true)))
                    addView(Ui.bar(context, p, context.getColor(R.color.accent), heightDp = 6))
                })
            }
            addView(Ui.divider(context))
            d.requests?.let { addView(Ui.row(context, Ui.muted(context, "Requests", 15f), Ui.text(context, it, 15f))) }
            d.tokens?.let {
                addView(Ui.row(context, Ui.muted(context, "Tokens", 15f), Ui.text(context, it, 15f)).apply {
                    setPadding(0, Ui.dp(context, 8), 0, 0)
                })
            }
        })
    }

    private fun extraCard(d: ConsoleData) {
        if (d.extraStatus == null && d.credit == null) return
        body.addView(Ui.card(this).apply {
            header(this, "Extra usage", d.extraStatus ?: "")
            d.credit?.let {
                addView(Ui.row(context, Ui.muted(context, "Available credit", 15f), Ui.text(context, it, 20f, bold = true)).apply {
                    setPadding(0, Ui.dp(context, 12), 0, 0)
                })
            }
        })
    }
}
