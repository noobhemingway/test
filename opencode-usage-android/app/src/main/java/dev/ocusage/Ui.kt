package dev.ocusage

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Small view-building helpers shared by every screen (no XML needed for dynamic lists). */
object Ui {
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun color(ctx: Context, m: Meter): Int = ctx.getColor(
        when {
            m.exceeded || m.percent >= 90 -> R.color.danger
            m.percent >= 70 -> R.color.warn
            else -> R.color.accent
        }
    )

    fun pct(p: Double): String =
        if (p < 10 && p % 1.0 != 0.0) String.format(Locale.US, "%.1f%%", p) else "${p.toInt()}%"

    fun progress(p: Double): Int = (p.coerceIn(0.0, 100.0) * 10).toInt()

    /** "14:05" for today, "Mon 3 Nov, 14:05" otherwise, so stale numbers are obvious. */
    fun time(at: Instant): String {
        val zone = ZoneId.systemDefault()
        val local = at.atZone(zone)
        val today = Instant.now().atZone(zone).toLocalDate()
        val pattern = if (local.toLocalDate() == today) "HH:mm" else "EEE d MMM, HH:mm"
        return local.format(DateTimeFormatter.ofPattern(pattern))
    }

    fun text(ctx: Context, s: CharSequence, sp: Float = 15f, colorRes: Int = R.color.text, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(ctx.getColor(colorRes))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    fun muted(ctx: Context, s: CharSequence, sp: Float = 13f) = text(ctx, s, sp, R.color.text_muted)

    fun column(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun row(ctx: Context, left: View, right: View?) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (right != null) addView(right)
    }

    fun card(ctx: Context) = column(ctx).apply {
        setBackgroundResource(R.drawable.card_bg)
        val p = dp(ctx, 16)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(ctx, 12) }
    }

    fun gap(ctx: Context, dpH: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, dpH))
    }

    fun divider(ctx: Context) = View(ctx).apply {
        setBackgroundColor(ctx.getColor(R.color.track))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)).apply {
            topMargin = dp(ctx, 10); bottomMargin = dp(ctx, 10)
        }
    }

    fun bar(ctx: Context, percent: Double, color: Int, heightDp: Int = 8) =
        ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressDrawable = ctx.getDrawable(R.drawable.progress_bar)?.mutate()
            progress = progress(percent)
            progressTintList = ColorStateList.valueOf(color)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, heightDp))
                .apply { topMargin = dp(ctx, 6) }
        }

    /** "5-hour ........ 12%", a bar, then "Resets in 2h 3m (14:05)". */
    fun meterView(ctx: Context, m: Meter, big: Boolean = false): View = column(ctx).apply {
        val c = color(ctx, m)
        val pctView = text(ctx, pct(m.percent), if (big) 22f else 17f, bold = true).apply {
            if (m.percent >= 70 || m.exceeded) setTextColor(c)
        }
        addView(row(ctx, muted(ctx, m.label, 14f), pctView))
        addView(bar(ctx, m.percent, c))
        addView(muted(ctx, if (m.exceeded) "Limit reached · ${m.resetText()}" else m.resetText(), 12f).apply {
            setPadding(0, dp(ctx, 6), 0, 0)
        })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(ctx, 12) }
    }

    fun button(ctx: Context, label: String, borderless: Boolean = false, onClick: () -> Unit) =
        (if (borderless) Button(ctx, null, 0, android.R.style.Widget_Material_Button_Borderless_Colored) else Button(ctx)).apply {
            text = label
            setOnClickListener { onClick() }
        }

    /**
     * Standard screen: a scrolling column with system-bar insets, plus a layer behind it
     * for the WebRunner. Returns (column, webLayer).
     */
    fun screen(a: Activity): Pair<LinearLayout, FrameLayout> {
        val col = column(a).apply {
            val h = dp(a, 20)
            setPadding(h, dp(a, 24), h, dp(a, 24))
        }
        val scroll = ScrollView(a).apply { isFillViewport = true; addView(col) }
        val hidden = FrameLayout(a)
        val root = FrameLayout(a).apply {
            // Full-size layer for the WebRunner, kept behind the content until a site needs you.
            addView(hidden, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
        }
        a.setContentView(root)
        return col to hidden
    }

    fun title(ctx: Context, s: String) = text(ctx, s, 24f, bold = true).apply {
        setPadding(0, 0, 0, dp(ctx, 16))
    }

    /** Shows raw text (e.g. a site's last response) with a Copy button. */
    fun showText(a: Activity, title: String, body: String) {
        val tv = TextView(a).apply {
            text = body
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            val p = dp(a, 16)
            setPadding(p, p, p, p)
        }
        AlertDialog.Builder(a)
            .setTitle(title)
            .setView(ScrollView(a).apply { addView(tv) })
            .setPositiveButton("Copy") { _, _ ->
                a.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(title, body))
                Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
