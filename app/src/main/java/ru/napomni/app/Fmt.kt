package ru.napomni.app

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Даты и сроки по-русски. Свои таблицы, а не Locale: не зависим от языка телефона. */
object Fmt {
    val WEEKDAYS = arrayOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    private val MON = arrayOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
    val MONTHS = arrayOf(
        "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
        "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
    )

    fun local(t: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(t), zone)

    fun hm(t: LocalDateTime) = "%02d:%02d".format(t.hour, t.minute)

    private fun wd(d: LocalDate) = WEEKDAYS[d.dayOfWeek.value - 1]

    private fun dayMonth(d: LocalDate, today: LocalDate) =
        "${d.dayOfMonth} ${MON[d.monthValue - 1]}" + if (d.year != today.year) " ${d.year}" else ""

    /** Коротко для виджета и списка: «17:42», «Завтра 10:00», «Пт 10:00», «18 окт 19:00». */
    fun short(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = local(at, zone)
        val today = local(now, zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, t.toLocalDate())
        return when {
            days == 0L -> hm(t)
            days == 1L -> "Завтра ${hm(t)}"
            days == -1L -> "Вчера ${hm(t)}"
            days in 2..6 -> "${wd(t.toLocalDate())} ${hm(t)}"
            else -> "${dayMonth(t.toLocalDate(), today)} ${hm(t)}"
        }
    }

    /** Полностью: «сегодня, 17:42», «завтра, 10:00», «вс 18 окт, 19:00». */
    fun long(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = local(at, zone)
        val today = local(now, zone).toLocalDate()
        val day = when (ChronoUnit.DAYS.between(today, t.toLocalDate())) {
            0L -> "сегодня"
            1L -> "завтра"
            -1L -> "вчера"
            else -> "${wd(t.toLocalDate()).lowercase()} ${dayMonth(t.toLocalDate(), today)}"
        }
        return "$day, ${hm(t)}"
    }

    /** Заголовок группы в списке. */
    fun dayTitle(d: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, d)) {
        0L -> "Сегодня"
        1L -> "Завтра"
        else -> "Позже"
    }

    fun plural(n: Int, one: String, few: String, many: String): String {
        val m100 = n % 100
        val m10 = n % 10
        return when {
            m100 in 11..14 -> many
            m10 == 1 -> one
            m10 in 2..4 -> few
            else -> many
        }
    }

    private fun days(n: Int) = "$n ${plural(n, "день", "дня", "дней")}"

    /** Точная длительность: «15 мин», «2 ч», «1 ч 30 мин», «1 день», «5 дней». */
    fun dur(min: Int): String = when {
        min >= 1440 && min % 1440 == 0 -> days(min / 1440)
        min >= 60 && min % 60 == 0 -> "${min / 60} ч"
        min > 60 -> "${min / 60} ч ${min % 60} мин"
        else -> "$min мин"
    }

    /** Примерно — для «через …» в уведомлении, когда срок не круглый. */
    fun approx(min: Int): String = when {
        min < 60 -> "${maxOf(min, 1)} мин"
        min < 1440 - 30 -> "${(min / 60.0).roundToInt()} ч"
        else -> days(maxOf((min / 1440.0).roundToInt(), 1))
    }

    fun before(min: Int) = "за ${dur(min)}"

    /** Кнопка дня в шаге 2: «В день события», «За 1 день», «За 5 дней», «За неделю». */
    fun dayLabel(d: Int): String = when (d) {
        0 -> "В день события"
        7 -> "За неделю"
        else -> "За ${days(d)}"
    }

    /** Подпись срабатывания заранее в списке: «за 2 дня», «в день события», «за 1 ч». */
    fun early(r: Reminder, t: Trigger): String = when {
        r.dates.isNotEmpty() -> dm(local(t.time).toLocalDate())
        t.kind == Kind.DAY -> dayLabel(t.day).lowercase()
        else -> before(t.beforeMin)
    }

    /** «18 окт». */
    fun dm(d: LocalDate) = "${d.dayOfMonth} ${MON[d.monthValue - 1]}"

    /** «вс 18 окт». */
    fun dayShort(d: LocalDate, today: LocalDate = LocalDate.now()) = "${wd(d).lowercase()} ${dayMonth(d, today)}"

    /** Коротко для строки «Напомню: …»: «сегодня, 17:42», «завтра, 10:00», «пт 16, 10:00». */
    fun compact(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = local(at, zone)
        val today = local(now, zone).toLocalDate()
        return when (ChronoUnit.DAYS.between(today, t.toLocalDate())) {
            0L -> "сегодня, ${hm(t)}"
            1L -> "завтра, ${hm(t)}"
            else -> "${wd(t.toLocalDate()).lowercase()} ${t.dayOfMonth}, ${hm(t)}"
        }
    }

    /** Когда событие полностью: «вс 18 окт, 19:00» или «вс 18 окт, весь день». */
    fun event(r: Reminder, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (!r.allDay) return long(r.at, now, zone)
        val d = local(r.at, zone).toLocalDate()
        val today = local(now, zone).toLocalDate()
        val day = when (ChronoUnit.DAYS.between(today, d)) {
            0L -> "сегодня"
            1L -> "завтра"
            else -> dayShort(d, today)
        }
        return "$day, весь день"
    }

    /** Для виджета и списка: у «весь день» вместо времени — день. */
    fun whenShort(r: Reminder, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val shown = r.shownAt(now)
        if (!r.allDay || shown != r.at) return short(shown, now, zone)
        val d = local(r.at, zone).toLocalDate()
        val today = local(now, zone).toLocalDate()
        return when (val n = ChronoUnit.DAYS.between(today, d)) {
            0L -> "Весь день"
            1L -> "Завтра"
            in 2..6 -> wd(d)
            else -> if (n < 0) "Было" else dayMonth(d, today)
        }
    }
}
