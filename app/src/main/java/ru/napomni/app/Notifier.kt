package ru.napomni.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.temporal.ChronoUnit

/**
 * Звук и вибрацию уведомления Android задаёт каналом, и после создания канала приложение их
 * не меняет. Поэтому способ напоминания = свой канал; будильник звучит не каналом, а службой
 * [AlarmService] — там мелодия любая.
 */
object Notifier {
    private const val CH_SOUND = "reminders"
    private const val CH_VIBRATE = "vibrate"
    private const val CH_SILENT = "silent"
    private const val CH_ALARM = "alarm"

    fun allowed(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    /** Окно будильника поверх блокировки: на Android 14+ это отдельное разрешение. */
    fun fullScreenAllowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    private fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        fun make(id: String, name: String, importance: Int, desc: String, setup: NotificationChannel.() -> Unit = {}) {
            if (nm.getNotificationChannel(id) == null) {
                nm.createNotificationChannel(NotificationChannel(id, name, importance).apply { description = desc; setup() })
            }
        }
        make(CH_SOUND, "Напоминания", NotificationManager.IMPORTANCE_HIGH, "Со звуком и вибрацией") { enableVibration(true) }
        make(CH_VIBRATE, "Только вибро", NotificationManager.IMPORTANCE_HIGH, "Без звука, с вибрацией") {
            setSound(null, null)
            enableVibration(true)
        }
        make(CH_SILENT, "Тихо", NotificationManager.IMPORTANCE_LOW, "Только значок в шторке")
        make(CH_ALARM, "Будильник", NotificationManager.IMPORTANCE_HIGH, "Мелодию играет приложение, пока не нажмёте кнопку") {
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
        }
    }

    private fun channel(a: Alert) = when (a) {
        Alert.SOUND -> CH_SOUND
        Alert.VIBRATE -> CH_VIBRATE
        Alert.SILENT -> CH_SILENT
        Alert.ALARM -> CH_ALARM
    }

    /** Строка под заголовком: когда событие и сколько до него. */
    fun text(r: Reminder, t: Trigger, now: Long): String {
        if (r.allDay) {
            val d = Fmt.local(r.at).toLocalDate()
            val today = Fmt.local(now).toLocalDate()
            val n = ChronoUnit.DAYS.between(today, d).toInt()
            val date = Fmt.dayShort(d, today)
            return when {
                n == 0 -> "Сегодня · весь день"
                n == 1 -> "Завтра · $date"
                n == 2 -> "Послезавтра · $date"
                n > 2 -> "Через ${Fmt.dur(n * 1440)} · $date"
                else -> "Было $date"
            }
        }
        val left = ((r.at - now) / MINUTE).toInt()
        val whenText = Fmt.long(r.at, now)
        return when {
            r.at > now + 2 * MINUTE -> {
                // Пришло вовремя — пишем ровно выбранное «за 1 день»; опоздало (телефон был выключен) — сколько осталось.
                val onTime = t.kind == Kind.BEFORE && now - t.time < 2 * MINUTE
                val head = if (onTime) Fmt.dur(t.beforeMin) else Fmt.approx(left)
                "Через $head · $whenText"
            }
            now - r.at < 2 * MINUTE -> "Сейчас · ${Fmt.hm(Fmt.local(r.at))}"
            else -> "Было $whenText"
        }
    }

    fun build(ctx: Context, r: Reminder, t: Trigger, now: Long, ring: Boolean, quiet: Boolean = false): Notification {
        channels(ctx)
        val alert = if (ring) Alert.ALARM else r.alertFor(t).let { if (it == Alert.ALARM) Alert.SOUND else it }
        val open = if (ring) AlarmActivity.pending(ctx, r.id, t.time) else PendingIntent.getActivity(
            ctx, r.id,
            Intent(ctx, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(ctx, channel(alert))
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(r.title)
            .setContentText(text(r, t, now))
            .setCategory(if (ring) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (alert == Alert.SILENT) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setWhen(r.at)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(quiet)
            .setContentIntent(open)
            .setDeleteIntent(Actions.pending(ctx, r.id, Actions.DISMISS))
            .addAction(0, "Готово", Actions.pending(ctx, r.id, Actions.DONE))
        if (ring) {
            b.setFullScreenIntent(open, true)
            b.addAction(0, "Отложить 10 мин", Actions.pending(ctx, r.id, Actions.SNOOZE_10))
        }
        b.addAction(0, "Отложить 1 ч", Actions.pending(ctx, r.id, Actions.SNOOZE_60))
        return b.build()
    }

    fun show(ctx: Context, r: Reminder, t: Trigger, now: Long) {
        if (!allowed(ctx)) return
        val ring = r.rings(t, now)
        try {
            NotificationManagerCompat.from(ctx).notify(r.id, build(ctx, r, t, now, ring))
        } catch (_: SecurityException) {
            // Разрешение отозвали между проверкой и показом.
            return
        }
        when {
            ring -> AlarmService.start(ctx, r.id, t.time, ring = true)
            r.effects(t) -> AlarmService.start(ctx, r.id, t.time, ring = false)
        }
    }

    fun cancel(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)
}
