package dev.ocusage

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var loading = false

    private lateinit var setupGroup: View
    private lateinit var usageGroup: View
    private lateinit var keyInput: EditText
    private lateinit var status: TextView
    private lateinit var refresh: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Keep content clear of the status / navigation bars (edge-to-edge on Android 15).
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupGroup = findViewById(R.id.setupGroup)
        usageGroup = findViewById(R.id.usageGroup)
        keyInput = findViewById(R.id.keyInput)
        status = findViewById(R.id.status)
        refresh = findViewById(R.id.refresh)

        label(R.id.card5h, R.string.win_5h)
        label(R.id.cardWeek, R.string.win_week)
        label(R.id.cardMonth, R.string.win_month)

        findViewById<Button>(R.id.saveKey).setOnClickListener {
            val key = keyInput.text.toString().trim()
            if (key.isEmpty()) {
                keyInput.error = "Enter your API key"
                return@setOnClickListener
            }
            Prefs.setApiKey(this, key)
            keyInput.setText("")
            showMode()
            load()
        }
        refresh.setOnClickListener { load() }
        findViewById<Button>(R.id.changeKey).setOnClickListener {
            Prefs.setApiKey(this, null)
            UsageWidget.updateAll(this)
            showMode()
        }
    }

    override fun onResume() {
        super.onResume()
        showMode()
        if (Prefs.apiKey(this) != null) load()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun showMode() {
        val hasKey = Prefs.apiKey(this) != null
        setupGroup.visibility = if (hasKey) View.GONE else View.VISIBLE
        usageGroup.visibility = if (hasKey) View.VISIBLE else View.GONE
        if (hasKey) {
            val cached = Prefs.cached(this)
            if (cached != null) {
                render(cached)
                status.text = "Last updated ${Ui.time(cached.fetchedAt)}"
            } else {
                clearCards()
            }
        }
    }

    private fun load() {
        if (loading) return
        loading = true
        refresh.isEnabled = false
        status.text = "Updating…"
        io.execute {
            val result = runCatching { Prefs.refresh(applicationContext) }
            main.post {
                loading = false
                if (isDestroyed) return@post
                refresh.isEnabled = true
                result.onSuccess { usage ->
                    render(usage)
                    status.text = "Updated ${Ui.time(usage.fetchedAt)}"
                    UsageWidget.updateAll(this)
                }.onFailure { e ->
                    val msg = (e as? UsageException)?.message
                        ?: "Couldn't reach opencode.ai (${e.javaClass.simpleName})."
                    val cached = Prefs.cached(this)
                    status.text = if (cached != null) "$msg Showing data from ${Ui.time(cached.fetchedAt)}." else msg
                }
            }
        }
    }

    private fun render(u: Usage) {
        card(R.id.card5h, u.rolling)
        card(R.id.cardWeek, u.weekly)
        card(R.id.cardMonth, u.monthly)
    }

    private fun label(cardId: Int, textRes: Int) {
        findViewById<View>(cardId).findViewById<TextView>(R.id.label).setText(textRes)
    }

    private fun clearCards() {
        for (id in listOf(R.id.card5h, R.id.cardWeek, R.id.cardMonth)) {
            val c = findViewById<View>(id)
            c.findViewById<TextView>(R.id.percent).text = "–"
            c.findViewById<ProgressBar>(R.id.bar).progress = 0
            c.findViewById<TextView>(R.id.reset).text = ""
        }
    }

    private fun card(cardId: Int, m: Meter) {
        val c = findViewById<View>(cardId)
        val color = Ui.color(this, m)
        c.findViewById<TextView>(R.id.percent).apply {
            text = Ui.pct(m)
            setTextColor(if (m.percent >= 70 || m.exceeded) color else getColor(R.color.text))
        }
        c.findViewById<ProgressBar>(R.id.bar).apply {
            progress = Ui.progress(m)
            progressTintList = ColorStateList.valueOf(color)
        }
        c.findViewById<TextView>(R.id.reset).text =
            if (m.exceeded) "Limit reached · ${m.resetText()}" else m.resetText()
    }
}
