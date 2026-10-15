package ru.napomni.app

import android.Manifest
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

object Notifier {
    private const val CHANNEL = "reminders"

    fun allowed(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    private fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Напоминания", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Сами напоминания и предупреждения заранее"
                enableVibration(true)
            }
        )
    }

    /** Строка под заголовком: когда событие и сколько до него. */
    fun text(r: Reminder, t: Trigger, now: Long): String {
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

    fun show(ctx: Context, r: Reminder, t: Trigger, now: Long) {
        if (!allowed(ctx)) return
        channel(ctx)
        val open = PendingIntent.getActivity(
            ctx, r.id, Intent(ctx, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(r.title)
            .setContentText(text(r, t, now))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(r.at)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Готово", action(ctx, r.id, ActionReceiver.ACTION_DONE))
            .addAction(0, "Отложить ${Fmt.dur(ActionReceiver.SNOOZE_MIN)}", action(ctx, r.id, ActionReceiver.ACTION_SNOOZE))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(r.id, n)
        } catch (_: SecurityException) {
            // Разрешение отозвали между проверкой и показом.
        }
    }

    fun cancel(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)

    private fun action(ctx: Context, id: Int, action: String) = PendingIntent.getBroadcast(
        ctx, id,
        Intent(ctx, ActionReceiver::class.java).setAction(action).putExtra(ActionReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
