package ru.napomni.app

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Последние выборы — по умолчанию для новых напоминаний: мелодия будильника и способ («через» сохраняется сразу). */
object Prefs {
    private const val PREFS = "settings"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastMelody(ctx: Context): String? = p(ctx).getString("lastMelody", null)
    fun setLastMelody(ctx: Context, uri: String?) = p(ctx).edit().putString("lastMelody", uri).apply()

    fun lastAlert(ctx: Context): Alert = Alert.entries.firstOrNull { it.name == p(ctx).getString("lastAlert", null) } ?: Alert.SOUND
    fun setLastAlert(ctx: Context, a: Alert) = p(ctx).edit().putString("lastAlert", a.name).apply()

    /** Оформление: поток, чтобы смена в настройках сразу перерисовала открытые экраны. По умолчанию — «латунь». */
    private var look: MutableStateFlow<Look>? = null

    @Synchronized
    fun lookFlow(ctx: Context): StateFlow<Look> = look ?: MutableStateFlow(
        Look.entries.firstOrNull { it.name == p(ctx).getString("look", null) } ?: Look.BRASS
    ).also { look = it }

    fun setLook(ctx: Context, l: Look) {
        p(ctx).edit().putString("look", l.name).apply()
        (lookFlow(ctx) as MutableStateFlow).value = l
    }
}

object Melody {
    data class Tone(val uri: String?, val name: String)

    /** Мелодии будильника, что есть в телефоне, — для своего меню выбора. */
    fun alarmTones(ctx: Context): List<Tone> = try {
        val rm = RingtoneManager(ctx).apply { setType(RingtoneManager.TYPE_ALARM) }
        val c = rm.cursor
        buildList {
            while (c.moveToNext()) add(Tone(rm.getRingtoneUri(c.position).toString(), c.getString(RingtoneManager.TITLE_COLUMN_INDEX)))
        }
    } catch (e: Exception) {
        emptyList()
    }

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
