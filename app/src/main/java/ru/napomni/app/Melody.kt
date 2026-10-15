package ru.napomni.app

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns

/** Последние выборы — по умолчанию для новых напоминаний: мелодия будильника и способ («через» сохраняется сразу). */
object Prefs {
    private const val PREFS = "settings"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastMelody(ctx: Context): String? = p(ctx).getString("lastMelody", null)
    fun setLastMelody(ctx: Context, uri: String?) = p(ctx).edit().putString("lastMelody", uri).apply()

    fun lastAlert(ctx: Context): Alert = Alert.entries.firstOrNull { it.name == p(ctx).getString("lastAlert", null) } ?: Alert.SOUND
    fun setLastAlert(ctx: Context, a: Alert) = p(ctx).edit().putString("lastAlert", a.name).apply()
}

object Melody {

    /** Название для строки «Мелодия»: звук телефона — по его названию, свой файл — по имени файла. */
    fun name(ctx: Context, uri: String?): String {
        if (uri == null) return "Будильник телефона"
        val u = Uri.parse(uri)
        return try {
            if (u.authority == "media" || u.authority == "settings") {
                RingtoneManager.getRingtone(ctx, u)?.getTitle(ctx) ?: "Своя мелодия"
            } else {
                ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0).substringBeforeLast('.') else null
                } ?: "Своя мелодия"
            }
        } catch (e: Exception) {
            "Своя мелодия"
        }
    }
}
