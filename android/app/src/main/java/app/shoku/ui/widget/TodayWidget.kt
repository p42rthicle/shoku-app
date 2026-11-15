package app.shoku.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import app.shoku.MainActivity
import app.shoku.R
import app.shoku.ShokuApplication
import app.shoku.data.db.EntryStatus
import app.shoku.domain.goalFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

/**
 * Home-screen widget: calories left today (eaten only), protein, a calorie bar, and + Add / Describe /
 * Scan through shoku:// links. Redrawn whenever the log changes (see ShokuApplication) and every 30 min.
 */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext as ShokuApplication
        app.appScope.launch {
            try { update(context) } finally { pending.finish() }
        }
    }

    companion object {
        /** Redraws every Shoku widget with today's numbers. Cheap; safe to call often. */
        suspend fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))
            if (ids.isEmpty()) return
            val app = context.applicationContext as ShokuApplication
            val today = LocalDate.now()
            val entries = app.repository.day(today).first().filter { it.status == EntryStatus.EATEN }
            val goal = goalFor(app.repository.allGoals.first(), today.toString())
            val kcal = entries.sumOf { it.kcal ?: 0.0 }
            val protein = entries.sumOf { it.protein ?: 0.0 }
            val fmt = NumberFormat.getIntegerInstance(Locale.UK)
            val views = RemoteViews(context.packageName, R.layout.widget_today)
            if (goal != null) {
                val left = goal.kcal - kcal
                views.setTextViewText(R.id.widget_kcal, if (left >= 0) "${fmt.format(Math.round(left))} kcal left" else "${fmt.format(Math.round(-left))} kcal over")
                views.setTextColor(R.id.widget_kcal, context.getColor(if (left >= 0) R.color.widget_ink else R.color.widget_over))
                views.setTextViewText(R.id.widget_protein, "${Math.round(protein)} / ${Math.round(goal.protein)} g protein")
                views.setProgressBar(R.id.widget_bar, 100, (kcal / goal.kcal * 100).toInt().coerceIn(0, 100), false)
            } else {
                views.setTextViewText(R.id.widget_kcal, "${fmt.format(Math.round(kcal))} kcal")
                views.setTextViewText(R.id.widget_protein, "${Math.round(protein)} g protein")
            }
            views.setOnClickPendingIntent(R.id.widget_root, link(context, "shoku://today", 1))
            views.setOnClickPendingIntent(R.id.widget_add, link(context, "shoku://add", 2))
            views.setOnClickPendingIntent(R.id.widget_describe, link(context, "shoku://describe", 3))
            views.setOnClickPendingIntent(R.id.widget_scan, link(context, "shoku://scan", 4))
            manager.updateAppWidget(ids, views)
        }

        private fun link(context: Context, uri: String, code: Int): PendingIntent = PendingIntent.getActivity(
            context, 6000 + code,
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setClass(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
