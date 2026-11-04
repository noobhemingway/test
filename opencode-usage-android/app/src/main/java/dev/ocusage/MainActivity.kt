package dev.ocusage

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Home: one card each for OpenCode Go, Claude, Codex and Grok. Shows saved numbers only;
 * nothing is fetched until you tap Refresh.
 */
class MainActivity : Activity() {
    private lateinit var cards: LinearLayout
    private lateinit var status: TextView
    private lateinit var refresh: Button
    private lateinit var runner: WebRunner
    private lateinit var refresher: Refresher
    private val errors = mutableMapOf<String, String>()
    private val updating = mutableSetOf<String>()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (col, hidden) = Ui.screen(this)
        runner = WebRunner(this, hidden)
        refresher = Refresher(applicationContext, runner)

        col.addView(Ui.title(this, "Usage"))
        cards = Ui.column(this)
        col.addView(cards)
        status = Ui.muted(this, "")
        col.addView(status)
        refresh = Ui.button(this, "Refresh") { refreshAll() }
        val accounts = Ui.button(this, "Accounts", borderless = true) {
            startActivity(Intent(this, AccountsActivity::class.java))
        }
        col.addView(LinearLayout(this).apply {
            setPadding(0, Ui.dp(this@MainActivity, 12), 0, 0)
            addView(refresh)
            addView(accounts)
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

    private fun refreshAll() {
        if (busy) return
        val ids = Ids.HOME.filter { Store.configured(this, it) }
        if (ids.isEmpty()) {
            status.text = "Nothing set up yet. Open Accounts to add your OpenCode key or sign in."
            return
        }
        busy = true
        refresh.isEnabled = false
        updating += ids
        ids.forEach { errors.remove(it) }
        render()
        refresher.fetchAll(ids,
            each = { id, r ->
                updating -= id
                r.exceptionOrNull()?.let { errors[id] = it.message ?: it.javaClass.simpleName }
                if (!isDestroyed) render()
            },
            all = {
                busy = false
                if (!isDestroyed) {
                    refresh.isEnabled = true
                    render()
                }
                UsageWidget.updateAll(applicationContext)
            })
    }

    private fun render() {
        cards.removeAllViews()
        for (id in Ids.HOME) cards.addView(card(id))
        if (!busy) {
            status.text = if (Ids.HOME.none { Store.configured(this, it) })
                "Open Accounts to add your OpenCode key and sign in to Claude, ChatGPT and Grok."
            else "Numbers update only when you tap Refresh."
        } else status.text = "Updating…"
    }

    private fun card(id: String) = Ui.card(this).apply {
        val ctx = this@MainActivity
        val configured = Store.configured(ctx, id)
        val snap = Store.snapshot(ctx, id)
        val right = when {
            id in updating -> "Updating…"
            snap != null -> "As of ${Ui.time(snap.fetchedAt)}"
            else -> ""
        }
        val name = Ui.text(ctx, Ids.title(id), 17f, bold = true)
        addView(Ui.row(ctx, name, Ui.muted(ctx, right, 12f)))

        when {
            !configured -> {
                addView(Ui.muted(ctx, when (id) {
                    Ids.OPENCODE -> "Not set up · tap to add your API key"
                    Ids.GROK -> "Not set up · tap to sign in (experimental)"
                    else -> "Not signed in · tap to sign in"
                }, 14f).apply { setPadding(0, Ui.dp(ctx, 8), 0, 0) })
                setOnClickListener { startActivity(Intent(ctx, AccountsActivity::class.java)) }
            }
            snap == null && errors[id] == null -> addView(Ui.muted(ctx,
                if (id in updating) "Loading…" else "No numbers yet · tap Refresh", 14f
            ).apply { setPadding(0, Ui.dp(ctx, 8), 0, 0) })
        }
        snap?.meters?.forEach { addView(Ui.meterView(ctx, it)) }
        errors[id]?.let { msg ->
            val shown = if (snap != null) "$msg Still showing numbers from ${Ui.time(snap.fetchedAt)}." else msg
            addView(Ui.text(ctx, shown, 13f, R.color.danger).apply { setPadding(0, Ui.dp(ctx, 10), 0, 0) })
        }
        if (id == Ids.OPENCODE && configured) {
            addView(Ui.text(ctx, "Models and details ›", 14f, R.color.accent).apply {
                setPadding(0, Ui.dp(ctx, 12), 0, 0)
            })
            setOnClickListener { startActivity(Intent(ctx, OpenCodeActivity::class.java)) }
        }
    }

}
