package dev.ocusage

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout

/** Set up each source: OpenCode API key, and web sign-ins for the console, Claude, ChatGPT and Grok. */
class AccountsActivity : Activity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (col, _) = Ui.screen(this)
        col.addView(Ui.title(this, "Accounts"))
        body = Ui.column(this)
        col.addView(body)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        body.removeAllViews()
        body.addView(openCodeKeyCard())
        body.addView(webCard(Ids.CONSOLE, "OpenCode console (models page)",
            "Sign in with GitHub. Once you see the console, open the Activity tab and tap Done."))
        body.addView(webCard(Ids.CLAUDE, "Claude",
            "Sign in with your email. Google sign-in doesn't work in an in-app browser."))
        body.addView(webCard(Ids.CODEX, "Codex (ChatGPT account)",
            "Sign in with your email. Google sign-in doesn't work in an in-app browser."))
        body.addView(webCard(Ids.GROK, "Grok (experimental)",
            "Sign in with your email, then go to the page that shows your weekly usage and tap Done. " +
                "The app reads the % from that page."))
        body.addView(Ui.button(this, "Sign out of all websites", borderless = true) {
            AlertDialog.Builder(this)
                .setMessage("Sign out of Claude, ChatGPT, Grok and the OpenCode console, and delete their saved numbers?")
                .setPositiveButton("Sign out") { _, _ -> Store.signOutEverywhere(this); render() }
                .setNegativeButton("Cancel", null)
                .show()
        })
        body.addView(Ui.muted(this,
            "Sign-ins and your key stay on this phone. The app only contacts a site when you tap Refresh."))
    }

    private fun openCodeKeyCard() = Ui.card(this).apply {
        val ctx = this@AccountsActivity
        addView(Ui.text(ctx, "OpenCode Go API key", 17f, bold = true))
        if (Store.apiKey(ctx) != null) {
            addView(Ui.muted(ctx, "Key saved.", 14f).apply { setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 8)) })
            addView(LinearLayout(ctx).apply {
                addView(Ui.button(ctx, "Remove key", borderless = true) { Store.setApiKey(ctx, null); render() })
                addView(Ui.button(ctx, "Show data", borderless = true) { showDebug(Ids.OPENCODE) })
            })
        } else {
            addView(Ui.muted(ctx, "Copy it from opencode.ai/console.", 14f))
            val input = EditText(ctx).apply {
                hint = "API key"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSingleLine = true
                setTextColor(ctx.getColor(R.color.text))
            }
            addView(input)
            addView(Ui.button(ctx, "Save key") {
                val key = input.text.toString().trim()
                if (key.isEmpty()) input.error = "Enter your API key" else {
                    Store.setApiKey(ctx, key)
                    render()
                }
            })
        }
    }

    private fun webCard(id: String, title: String, hint: String) = Ui.card(this).apply {
        val ctx = this@AccountsActivity
        val signedIn = Store.signedIn(ctx, id)
        addView(Ui.row(ctx, Ui.text(ctx, title, 17f, bold = true),
            Ui.text(ctx, if (signedIn) "Signed in" else "Not signed in", 12f,
                if (signedIn) R.color.accent else R.color.text_muted)))
        addView(Ui.muted(ctx, hint, 14f).apply { setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 8)) })
        if (signedIn && (id == Ids.GROK || id == Ids.CONSOLE)) {
            addView(Ui.button(ctx, if (id == Ids.GROK) "Change page" else "Open console") { open(id) })
        }
        addView(LinearLayout(ctx).apply {
            if (signedIn) {
                addView(Ui.button(ctx, "Sign out", borderless = true) {
                    AlertDialog.Builder(ctx)
                        .setMessage("Sign out of ${Ids.title(id)} in this app?")
                        .setPositiveButton("Sign out") { _, _ -> Store.signOut(ctx, id); render() }
                        .setNegativeButton("Cancel", null)
                        .show()
                })
                addView(Ui.button(ctx, "Show data", borderless = true) { showDebug(id) })
            } else {
                addView(Ui.button(ctx, "Sign in") { open(id) })
            }
        })
        if (id == Ids.GROK && signedIn) {
            addView(Ui.muted(ctx, "Reading: " + (Store.pageUrl(ctx, id) ?: "no page chosen yet"), 12f))
        }
    }

    private fun open(id: String) {
        val url = Store.pageUrl(this, id) ?: when (id) {
            Ids.CONSOLE -> Jobs.CONSOLE_DEFAULT_URL
            Ids.CLAUDE -> "https://claude.ai/login"
            Ids.CODEX -> "https://chatgpt.com/auth/login"
            Ids.GROK -> "https://grok.com/"
            else -> return
        }
        startActivity(Intent(this, BrowserActivity::class.java)
            .putExtra(BrowserActivity.EXTRA_ID, id)
            .putExtra(BrowserActivity.EXTRA_URL, url))
    }

    private fun showDebug(id: String) =
        Ui.showText(this, "${Ids.title(id)}: last response", Store.debug(this, id) ?: "Nothing fetched yet.")
}
