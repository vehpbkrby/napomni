package ru.napomni.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Один будильник на ближайшее срабатывание из всех напоминаний. Сработал — показываем всё
 * наступившее и ставим следующий. Так же после перезагрузки и смены времени.
 *
 * setAlarmClock, а не setExactAndAllowWhileIdle: на Realme («Хрюня», 2026-09) точные будильники
 * при выключенном экране приходили с опозданием до 5 минут и больше; «будильник» оболочка не
 * откладывает. Цена — значок будильника в строке состояния, пока есть будущее напоминание.
 */
object Scheduler {
    fun run(ctx: Context) {
        val now = System.currentTimeMillis()
        val shown = mutableListOf<Pair<Reminder, Trigger>>()
        Store.update(ctx) { list ->
            list.map { r ->
                val t = r.dueAt(now) ?: return@map r
                shown += r to t
                r.handled(now, t)
            }
        }
        shown.forEach { (r, t) -> Notifier.show(ctx, r, t, now) }
        schedule(ctx, Store.all(ctx))
        Widget.refresh(ctx)
    }

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun schedule(ctx: Context, list: List<Reminder>) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, AlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val next = list.mapNotNull { it.nextTrigger()?.time }.minOrNull()
        if (next == null) {
            am.cancel(pi)
            return
        }
        val at = maxOf(next, System.currentTimeMillis() + 1_000)
        if (canExact(ctx)) {
            val show = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Scheduler.run(ctx)
}

/** Перезагрузка, обновление приложения, перевод часов, выдача разрешения — всё пересчитать. */
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Scheduler.run(ctx)
}

/** Кнопки в уведомлении. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val now = System.currentTimeMillis()
        when (intent.action) {
            ACTION_DONE -> Store.edit(ctx, id) { it.copy(done = true) }
            ACTION_SNOOZE -> Store.edit(ctx, id) { it.copy(snoozeAt = now + SNOOZE_MIN * MINUTE, done = false) }
        }
        Notifier.cancel(ctx, id)
        Scheduler.run(ctx)
    }

    companion object {
        const val ACTION_DONE = "ru.napomni.app.DONE"
        const val ACTION_SNOOZE = "ru.napomni.app.SNOOZE"
        const val EXTRA_ID = "id"
        const val SNOOZE_MIN = 60
    }
}
