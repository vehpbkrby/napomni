package ru.napomni.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/**
 * Виджет на рабочем столе: «+» и быстрые кнопки открывают лист добавления поверх рабочего стола,
 * ниже — три ближайших напоминания. Время пишем как «17:42», «Пт 10:00», а не «через 2 ч»:
 * такая надпись не устаревает между обновлениями.
 */
class Widget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = render(ctx, mgr, ids)

    companion object {
        private val ROWS = listOf(
            Triple(R.id.row1, R.id.time1, R.id.title1) to R.id.sub1,
            Triple(R.id.row2, R.id.time2, R.id.title2) to R.id.sub2,
            Triple(R.id.row3, R.id.time3, R.id.title3) to R.id.sub3,
        )

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, Widget::class.java))
            if (ids.isNotEmpty()) render(ctx, mgr, ids)
        }

        private fun quick(ctx: Context, code: Int, preset: String?, id: Int? = null): PendingIntent {
            val i = Intent(ctx, QuickAddActivity::class.java)
                .setAction("ru.napomni.app.QUICK.$code")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            preset?.let { i.putExtra(QuickAddActivity.EXTRA_PRESET, it) }
            id?.let { i.putExtra(QuickAddActivity.EXTRA_ID, it) }
            return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val now = System.currentTimeMillis()
            val list = Store.all(ctx).filter { it.isActive(now) }.sortedBy { it.shownAt(now) }.take(ROWS.size)
            val v = RemoteViews(ctx.packageName, R.layout.widget)

            v.setOnClickPendingIntent(
                R.id.header,
                PendingIntent.getActivity(
                    ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            v.setOnClickPendingIntent(R.id.add, quick(ctx, 10, null))
            v.setOnClickPendingIntent(R.id.q1, quick(ctx, 11, QuickAddActivity.PRESET_1H))
            v.setOnClickPendingIntent(R.id.q2, quick(ctx, 12, QuickAddActivity.PRESET_3H))
            v.setOnClickPendingIntent(R.id.q3, quick(ctx, 13, QuickAddActivity.PRESET_TOMORROW))
            v.setOnClickPendingIntent(R.id.q4, quick(ctx, 14, QuickAddActivity.PRESET_DATE))

            ROWS.forEachIndexed { i, (ids3, sub) ->
                val (row, time, title) = ids3
                val r = list.getOrNull(i)
                if (r == null) {
                    v.setViewVisibility(row, View.GONE)
                    return@forEachIndexed
                }
                v.setViewVisibility(row, View.VISIBLE)
                v.setTextViewText(time, Fmt.short(r.shownAt(now), now))
                v.setTextViewText(title, r.title)
                val subText = subtitle(r, now)
                v.setTextViewText(sub, subText)
                v.setViewVisibility(sub, if (subText.isEmpty()) View.GONE else View.VISIBLE)
                v.setOnClickPendingIntent(row, quick(ctx, 100 + r.id, null, r.id))
            }
            v.setViewVisibility(R.id.empty, if (list.isEmpty()) View.VISIBLE else View.GONE)
            mgr.updateAppWidget(ids, v)
        }

        /** «за 2 дня, за 1 день» — только то, что ещё впереди; у отложенного — «отложено». */
        fun subtitle(r: Reminder, now: Long): String = when {
            r.at <= now -> "отложено"
            else -> r.before.filter { !r.beforeShown(it) }.sortedDescending().joinToString(", ") { Fmt.before(it) }
        }
    }
}
