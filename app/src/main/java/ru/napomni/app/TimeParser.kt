package ru.napomni.app

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Что понято из текста. [minutes] — «через N»; иначе [date] и [time] (время может остаться
 * неизвестным: «завтра позвонить» — час выберет человек). [spans] — найденные куски текста
 * (их подсвечиваем), [title] — текст без них.
 */
data class Parsed(
    val minutes: Int? = null,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val spans: List<IntRange> = emptyList(),
    val title: String = "",
) {
    val found get() = minutes != null || date != null || time != null
}

/** Время из обычной фразы: «через 2 часа», «завтра в 10», «в пт 18:00», «18 октября в 19:00», «18.10». */
object TimeParser {
    // Граница слова для кириллицы: \b в Java зависит от версии и флагов, поэтому явно.
    private const val L = "(?<![\\p{L}\\d])"
    private const val R = "(?![\\p{L}\\d])"

    private val NUMS = mapOf(
        "один" to 1, "одну" to 1, "одна" to 1, "пару" to 2, "два" to 2, "две" to 2, "три" to 3,
        "четыре" to 4, "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9,
        "десять" to 10, "пятнадцать" to 15, "двадцать" to 20, "тридцать" to 30, "сорок" to 40,
    )
    private val NUM = NUMS.keys.sortedByDescending { it.length }.joinToString("|")

    private val REL = Regex(
        "${L}через\\s+(?:(\\d+)\\s*|($NUM)\\s+)?" +
            "(полчаса|пол\\s+часа|мин(?:ут[уы]?)?\\.?|ч(?:ас(?:а|ов)?)?\\.?|день|дн(?:я|ей)?\\.?|сут(?:ки|ок)|недел[юиь]|нед\\.?)$R"
    )
    private val DATE_WORD = Regex(
        "${L}(?:(?:на|к)\\s+)?(\\d{1,2})\\s+(янв|фев|мар|апр|ма[яй]|июн|июл|авг|сен|окт|ноя|дек)\\p{L}*\\.?(?:\\s+(\\d{4}))?$R"
    )
    private val DATE_NUM = Regex("${L}(?:(в|во|к|на)\\s+)?(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{4}|\\d{2}))?$R")
    private val DAY_WORD = Regex("${L}(?:на\\s+)?(сегодня|послезавтра|завтра)$R")
    private val WEEKDAY = Regex(
        "${L}(?:(?:в|во|на)\\s+)?(понедельник|вторник|сред[ау]|четверг|пятниц[ау]|суббот[ау]|воскресенье|пн|вт|ср|чт|пт|сб|вс)$R"
    )
    private val TIME_COLON = Regex("${L}(?:(?:в|к|на)\\s+)?(\\d{1,2}):(\\d{2})(?:\\s+(утра|дня|вечера|ночи))?$R")
    private val TIME_PREP = Regex(
        "${L}(?:в|к)\\s+(\\d{1,2})(?:\\s*(?:ч|час(?:а|ов)?)\\.?)?(?:\\s+(утра|дня|вечера|ночи))?$R"
    )
    private val PART = Regex("${L}(утром|днем|вечером)$R")

    private val MONTH_KEYS = listOf("янв", "фев", "мар", "апр", "ма", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

    private fun weekday(w: String): Int = when (w.take(2)) {
        "пн", "по" -> 1
        "вт" -> 2
        "ср" -> 3
        "чт", "че" -> 4
        "пт", "пя" -> 5
        "сб", "су" -> 6
        else -> 7
    }

    /** «7 вечера» → 19, «3 дня» → 15, «12 ночи» → 0. */
    private fun hour24(h: Int, part: String?): Int = when (part) {
        "вечера", "дня" -> if (h in 1..11) h + 12 else h
        "ночи" -> if (h == 12) 0 else h
        "утра" -> if (h == 12) 0 else h
        else -> h
    }

    private fun timeOf(h: Int, m: Int): LocalTime? = if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null

    /** Ближайшая будущая дата с таким днём и месяцем (без года — в этом году или следующем). */
    private fun dateOf(day: Int, month: Int, year: Int?, today: LocalDate): LocalDate? = try {
        if (year != null) LocalDate.of(if (year < 100) 2000 + year else year, month, day)
        else LocalDate.of(today.year, month, day).let { if (it < today) it.plusYears(1) else it }
    } catch (_: DateTimeException) {
        null
    }

    fun parse(text: String, now: LocalDateTime): Parsed {
        // Строчные и «ё»→«е» не меняют длину строки — позиции совпадают с исходным текстом.
        val low = text.lowercase().replace('ё', 'е')
        val s = if (low.length == text.length) low else text
        val today = now.toLocalDate()
        val spans = mutableListOf<IntRange>()
        fun free(r: IntRange) = spans.none { it.first <= r.last && r.first <= it.last }

        REL.findAll(s).firstOrNull { free(it.range) }?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: NUMS[m.groupValues[2]] ?: 1
            val unit = m.groupValues[3]
            val minutes = when {
                unit.startsWith("пол") -> 30
                unit.startsWith("м") -> n
                unit.startsWith("ч") -> n * 60
                unit.startsWith("нед") -> n * 7 * 1440
                else -> n * 1440
            }
            spans += m.range
            if (minutes > 0) return Parsed(minutes = minutes, spans = spans, title = title(text, spans))
        }

        var date: LocalDate? = null
        var time: LocalTime? = null
        var weekday: Int? = null

        DATE_WORD.findAll(s).firstOrNull { free(it.range) }?.let { m ->
            val month = MONTH_KEYS.indexOfFirst { m.groupValues[2].startsWith(it) } + 1
            dateOf(m.groupValues[1].toInt(), month, m.groupValues[3].toIntOrNull(), today)?.let {
                date = it
                spans += m.range
            }
        }
        DATE_NUM.findAll(s).filter { free(it.range) }.forEach { m ->
            val a = m.groupValues[2].toInt()
            val b = m.groupValues[3].toInt()
            val prep = m.groupValues[1]
            val year = m.groupValues[4].toIntOrNull()
            // «в 18.30» — время; «18.10», «на 18.10» — дата; «к 18.30» (30-го месяца нет) — время.
            val asTime = if ((prep == "в" || prep == "во") && year == null) timeOf(a, b) else null
            val asDate = if (asTime == null) dateOf(a, b, year, today) else null
            val fallback = if (asTime == null && asDate == null && year == null) timeOf(a, b) else null
            when {
                asTime != null && time == null -> { time = asTime; spans += m.range }
                asDate != null && date == null -> { date = asDate; spans += m.range }
                fallback != null && time == null -> { time = fallback; spans += m.range }
            }
        }
        if (date == null) DAY_WORD.findAll(s).firstOrNull { free(it.range) }?.let { m ->
            date = today.plusDays(
                when (m.groupValues[1]) {
                    "завтра" -> 1L
                    "послезавтра" -> 2L
                    else -> 0L
                }
            )
            spans += m.range
        }
        if (date == null) WEEKDAY.findAll(s).firstOrNull { free(it.range) }?.let { m ->
            weekday = weekday(m.groupValues[1])
            spans += m.range
        }
        if (time == null) TIME_COLON.findAll(s).firstOrNull { free(it.range) }?.let { m ->
            timeOf(hour24(m.groupValues[1].toInt(), m.groupValues[3].ifEmpty { null }), m.groupValues[2].toInt())?.let {
                time = it
                spans += m.range
            }
        }
        if (time == null) TIME_PREP.findAll(s).firstOrNull { free(it.range) }?.let { m ->
            timeOf(hour24(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { null }), 0)?.let {
                time = it
                spans += m.range
            }
        }
        if (time == null) PART.findAll(s).firstOrNull { free(it.range) }?.let { m ->
            time = when (m.groupValues[1]) {
                "утром" -> LocalTime.of(9, 0)
                "днем" -> LocalTime.of(13, 0)
                else -> LocalTime.of(19, 0)
            }
            spans += m.range
        }

        weekday?.let { w ->
            val diff = ((w - today.dayOfWeek.value) + 7) % 7
            val sameDayLater = diff == 0 && time != null && time!! > now.toLocalTime()
            date = today.plusDays(if (diff == 0 && !sameDayLater) 7L else diff.toLong())
        }
        // Только время: сегодня, если ещё не прошло, иначе завтра.
        if (date == null && time != null) date = if (time!! > now.toLocalTime()) today else today.plusDays(1)

        return Parsed(date = date, time = time, spans = spans.sortedBy { it.first }, title = title(text, spans))
    }

    private fun title(text: String, spans: List<IntRange>): String {
        val sb = StringBuilder()
        text.forEachIndexed { i, c -> if (spans.none { i in it }) sb.append(c) else if (sb.lastOrNull() != ' ') sb.append(' ') }
        return sb.toString().replace(Regex("\\s+"), " ").trim(' ', ',', '.', '-', '—', '–', ':', ';')
    }
}
