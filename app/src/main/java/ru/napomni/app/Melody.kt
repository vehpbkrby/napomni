package ru.napomni.app

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns

/** Мелодия будильника: последняя выбранная становится выбором по умолчанию для новых напоминаний. */
object Melody {
    private const val PREFS = "settings"
    private const val KEY_LAST = "lastMelody"

    fun last(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null)

    fun remember(ctx: Context, uri: String?) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST, uri).apply()

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
