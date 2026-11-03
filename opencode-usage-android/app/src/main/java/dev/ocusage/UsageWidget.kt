package dev.ocusage

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.widget.RemoteViews

/** Home-screen widget showing the last numbers fetched in the app. It never contacts the server itself. */
class UsageWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        push(ctx, mgr, ids, Prefs.cached(ctx))
    }

    companion object {
        /** Redraw every widget from the cache (no network). */
        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, UsageWidget::class.java))
            if (ids.isNotEmpty()) push(ctx, mgr, ids, Prefs.cached(ctx))
        }

        private fun push(ctx: Context, mgr: AppWidgetManager, ids: IntArray, u: Usage?) {
            val views = RemoteViews(ctx.packageName, R.layout.widget_usage)
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.w_root, open)

            if (u == null) {
                for ((pct, bar, reset) in rows()) {
                    views.setTextViewText(pct, "–")
                    views.setProgressBar(bar, 1000, 0, false)
                    views.setTextViewText(reset, "")
                }
                views.setTextViewText(
                    R.id.w_status,
                    if (Prefs.apiKey(ctx) == null) "Tap to add your API key" else "Tap to open and refresh",
                )
            } else {
                val meters = listOf(u.rolling, u.weekly, u.monthly)
                for ((i, ids3) in rows().withIndex()) {
                    val (pct, bar, reset) = ids3
                    val m = meters[i]
                    val color = Ui.color(ctx, m)
                    views.setTextViewText(pct, Ui.pct(m))
                    views.setTextColor(pct, if (m.percent >= 70 || m.exceeded) color else ctx.getColor(R.color.text))
                    views.setProgressBar(bar, 1000, Ui.progress(m), false)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        views.setColorStateList(bar, "setProgressTintList", ColorStateList.valueOf(color))
                    }
                    views.setTextViewText(reset, m.resetText())
                }
                views.setTextViewText(R.id.w_status, "As of ${Ui.time(u.fetchedAt)}")
            }
            mgr.updateAppWidget(ids, views)
        }

        private fun rows() = listOf(
            Triple(R.id.w_5h_pct, R.id.w_5h_bar, R.id.w_5h_reset),
            Triple(R.id.w_week_pct, R.id.w_week_bar, R.id.w_week_reset),
            Triple(R.id.w_month_pct, R.id.w_month_bar, R.id.w_month_reset),
        )
    }
}
