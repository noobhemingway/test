package dev.ocusage

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen widget: the last numbers the app fetched. It never contacts any server itself. */
class UsageWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = push(ctx, mgr, ids)

    companion object {
        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, UsageWidget::class.java))
            if (ids.isNotEmpty()) push(ctx, mgr, ids)
        }

        private val rows = listOf(
            Ids.OPENCODE to R.id.w_opencode,
            Ids.CLAUDE to R.id.w_claude,
            Ids.CODEX to R.id.w_codex,
            Ids.GROK to R.id.w_grok,
        )

        private fun short(label: String) = when (label) {
            "5-hour" -> "5h"
            "Weekly" -> "Wk"
            "Monthly" -> "Mo"
            else -> label
        }

        private fun push(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val views = RemoteViews(ctx.packageName, R.layout.widget_usage)
            views.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
            var newest: java.time.Instant? = null
            for ((id, viewId) in rows) {
                val snap = Store.snapshot(ctx, id)
                if (snap == null) {
                    views.setTextViewText(viewId, "–")
                    views.setTextColor(viewId, ctx.getColor(R.color.text_muted))
                    continue
                }
                if (newest == null || snap.fetchedAt.isAfter(newest)) newest = snap.fetchedAt
                views.setTextViewText(viewId, snap.meters.joinToString("  ") { "${short(it.label)} ${Ui.pct(it.percent)}" })
                val worst = snap.meters.maxByOrNull { it.percent }
                views.setTextColor(viewId,
                    if (worst != null && (worst.percent >= 70 || worst.exceeded)) Ui.color(ctx, worst) else ctx.getColor(R.color.text))
            }
            views.setTextViewText(R.id.w_status, newest?.let { "As of ${Ui.time(it)} · tap to open" } ?: "Tap to open")
            mgr.updateAppWidget(ids, views)
        }
    }
}
