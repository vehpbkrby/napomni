package ru.napomni.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Один будильник на ближайшее срабатывание из всех напоминаний. Сработал — показываем всё
 * наступившее и ставим следующий. Так же после перезагрузки, смены времени и обновления виджета.
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
                val t = r.dueAt(now)
                val h = if (t == null) r else {
                    shown += r to t
                    r.handled(now, t)
                }
                // Повторяющееся: прошедший раз сменяется следующим.
                h.rolled(now)
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

/** Реакции на напоминание — из уведомления и с экрана будильника. */
object Actions {
    const val DONE = "ru.napomni.app.DONE"
    const val SNOOZE_10 = "ru.napomni.app.SNOOZE_10"
    const val SNOOZE_60 = "ru.napomni.app.SNOOZE_60"
    const val DISMISS = "ru.napomni.app.DISMISS"
    const val EXTRA_ID = "id"

    fun handle(ctx: Context, id: Int, action: String?) {
        val now = System.currentTimeMillis()
        when (action) {
            DONE -> Store.edit(ctx, id) { if (it.rule != Rule.NONE) it.skipped(now) else it.copy(done = true).reacted() }
            SNOOZE_10 -> Store.edit(ctx, id) { it.snoozed(now, 10) }
            SNOOZE_60 -> Store.edit(ctx, id) { it.snoozed(now, 60) }
            // Смахнули уведомление — увидели: повторы больше не нужны.
            DISMISS -> Store.edit(ctx, id) { it.reacted() }
        }
        AlarmService.stop(ctx, id)
        if (action != DISMISS) Notifier.cancel(ctx, id)
        Scheduler.run(ctx)
    }

    fun pending(ctx: Context, id: Int, action: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, id,
        Intent(ctx, ActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) =
        Actions.handle(ctx, intent.getIntExtra(Actions.EXTRA_ID, -1), intent.action)
}
