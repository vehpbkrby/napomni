package ru.napomni.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.ZoneId

/**
 * Виджет на рабочем столе: «+» и быстрые кнопки открывают лист добавления поверх рабочего стола,
 * кнопки дней — на неделю вперёд, ниже — три ближайших напоминания. Время пишем как «17:42»,
 * «Пт 10:00», а не «через 2 ч»: такая надпись не устаревает между обновлениями.
 */
class Widget : AppWidgetProvider() {
    // Каждое обновление виджета (раз в 30 минут и после загрузки рабочего стола) заново ставит будильник:
    // подстраховка на случай, если оболочка телефона не сообщила приложению о перезагрузке.
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = Scheduler.run(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_MIDNIGHT) Scheduler.run(ctx) else super.onReceive(ctx, intent)
    }

    companion object {
        private const val ACTION_MIDNIGHT = "ru.napomni.app.MIDNIGHT"

        private val ROWS = listOf(
            Triple(R.id.row1, R.id.time1, R.id.title1) to R.id.sub1,
            Triple(R.id.row2, R.id.time2, R.id.title2) to R.id.sub2,
            Triple(R.id.row3, R.id.time3, R.id.title3) to R.id.sub3,
        )
        private val DAYS = listOf(R.id.d1, R.id.d2, R.id.d3, R.id.d4, R.id.d5, R.id.d6, R.id.d7)

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, Widget::class.java))
            if (ids.isNotEmpty()) render(ctx, mgr, ids)
        }

        private fun quick(ctx: Context, code: Int, preset: String?, id: Int? = null, day: LocalDate? = null): PendingIntent {
            val i = Intent(ctx, QuickAddActivity::class.java)
                .setAction("ru.napomni.app.QUICK.$code")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            preset?.let { i.putExtra(QuickAddActivity.EXTRA_PRESET, it) }
            id?.let { i.putExtra(QuickAddActivity.EXTRA_ID, it) }
            day?.let { i.putExtra(QuickAddActivity.EXTRA_DAY, it.toEpochDay()) }
            return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** Подпись кнопки дня: «Завтра», «Послезавтра», дальше «Вс 18». */
        fun dayLabel(offset: Int, d: LocalDate): String = when (offset) {
            1 -> "Завтра"
            2 -> "Послезавтра"
            else -> "${Fmt.WEEKDAYS[d.dayOfWeek.value - 1]} ${d.dayOfMonth}"
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val today = Fmt.local(now, zone).toLocalDate()
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
            v.setOnClickPendingIntent(R.id.q4, quick(ctx, 14, QuickAddActivity.PRESET_DATE))
            DAYS.forEachIndexed { i, viewId ->
                val d = today.plusDays(i + 1L)
                v.setTextViewText(viewId, dayLabel(i + 1, d))
                v.setOnClickPendingIntent(viewId, quick(ctx, 21 + i, QuickAddActivity.PRESET_DAY, day = d))
            }

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
            scheduleMidnight(ctx, today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        }

        /** После полуночи «Завтра» и дни недели сдвигаются — перерисовать (без пробуждения телефона). */
        private fun scheduleMidnight(ctx: Context, midnight: Long) {
            val pi = PendingIntent.getBroadcast(
                ctx, 7, Intent(ctx, Widget::class.java).setAction(ACTION_MIDNIGHT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            ctx.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, midnight + 5_000, pi)
        }

        /** «вибро · за 2 дня, за 1 день» — способ (кроме обычного звука) и то, что ещё впереди. */
        fun subtitle(r: Reminder, now: Long): String {
            val parts = mutableListOf<String>()
            if (r.alert != Alert.SOUND) parts += r.alert.label.lowercase()
            if (r.at <= now) parts += "отложено"
            else r.before.filter { !r.beforeShown(it) }.sortedDescending().joinToString(", ") { Fmt.before(it) }
                .takeIf { it.isNotEmpty() }?.let { parts += it }
            return parts.joinToString(" · ")
        }
    }
}
