package ru.napomni.app

import java.time.LocalDateTime
import java.time.ZoneId

/** «Перед самим событием» — минуты до события со временем. */
val BEFORE_OPTIONS = listOf(60, 180, 360)

/** «В какие дни»: 0 — в день события, N — за N дней (7 — за неделю). */
val DAY_OPTIONS = (0..7).toList()

/** Быстрые варианты «через»: минуты от текущего момента. */
val IN_OPTIONS = listOf(15, 30, 60, 120, 180, 360)

const val MINUTE = 60_000L

/** Будильник может прийти чуть раньше срока — такое срабатывание считаем наступившим. */
const val EARLY_MS = 1_000L

/** «Повторять, пока не отреагирую»: каждые 5 минут, не дольше часа. */
const val NAG_MIN = 5
const val NAG_MAX = 12

/** Время по умолчанию для напоминаний «по дням». */
const val DEFAULT_DAY_TIME = 9 * 60

/**
 * Звенеть будильником только по свежему срабатыванию. Давно пропущенное (телефон был выключен)
 * приходит обычным уведомлением — звон про вчерашнее событие никому не нужен.
 */
const val RING_FRESH_MS = 10 * MINUTE

/** Как напомнить. Порядок — порядок кнопок в листе. */
enum class Alert(val label: String) {
    SOUND("Звук"),
    VIBRATE("Вибро"),
    SILENT("Тихо"),
    ALARM("Будильник"),
}

data class Reminder(
    val id: Int,
    val title: String,
    /** Момент события, мс от эпохи; у события «весь день» — начало его дня. */
    val at: Long,
    val allDay: Boolean = false,
    /** Напоминания по дням: 0 — в день события, N — за N дней; все в [dayTime]. */
    val days: List<Int> = emptyList(),
    /** Время суток для напоминаний по дням, минуты от полуночи. */
    val dayTime: Int = DEFAULT_DAY_TIME,
    /** За сколько минут до события со временем напомнить ещё. */
    val before: List<Int> = emptyList(),
    /** «Отложить» — ещё одно срабатывание сверх расписания. */
    val snoozeAt: Long? = null,
    /** Все срабатывания с моментом не позже этого уже показаны (или прошли до создания напоминания). */
    val handledUntil: Long,
    val done: Boolean = false,
    val alert: Alert = Alert.SOUND,
    /** Мелодия будильника (content://…); null — будильник телефона по умолчанию. */
    val melody: String? = null,
    val repeat: Boolean = false,
    val speak: Boolean = false,
    val flash: Boolean = false,
    /** Следующий повтор, пока человек не отреагировал. */
    val nagAt: Long? = null,
    val nags: Int = 0,
)

enum class Kind { BEFORE, DAY, EVENT, SNOOZE, NAG }

data class Trigger(val time: Long, val kind: Kind, val beforeMin: Int = 0, val day: Int = 0)

private fun ms(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** Когда сработает напоминание «за [d] дней» (в часовом поясе телефона). */
fun Reminder.dayAt(d: Int): Long =
    ms(Fmt.local(at).toLocalDate().minusDays(d.toLong()).atTime(dayTime / 60, dayTime % 60))

/** Конец события: у «весь день» — полночь после него. */
fun Reminder.end(): Long = if (allDay) ms(Fmt.local(at).toLocalDate().plusDays(1).atStartOfDay()) else at

/** Напоминание по дню имеет смысл: у события со временем — строго до него. */
fun Reminder.dayFits(d: Int): Boolean = allDay || dayAt(d) < at

/** Напоминания заранее (по дням и за часы) — без самого события и повторов. */
fun Reminder.early(): List<Trigger> {
    val list = days.filter { dayFits(it) }.map { Trigger(dayAt(it), Kind.DAY, day = it) }.toMutableList()
    if (!allDay) list += before.map { Trigger(at - it * MINUTE, Kind.BEFORE, beforeMin = it) }
    return list.sortedBy { it.time }
}

fun Reminder.triggers(): List<Trigger> {
    if (done) return emptyList()
    val list = early().toMutableList()
    if (!allDay) list += Trigger(at, Kind.EVENT)
    snoozeAt?.let { list += Trigger(it, Kind.SNOOZE) }
    nagAt?.let { list += Trigger(it, Kind.NAG) }
    return list.sortedBy { it.time }
}

/**
 * Главное срабатывание — само событие (у «весь день» — напоминание в его день) и его повторы.
 * Будильник, голос, вспышка и «повторять» — только у главного; заранее приходит обычным уведомлением.
 */
fun Reminder.isMain(t: Trigger): Boolean =
    t.kind == Kind.EVENT || t.kind == Kind.SNOOZE || t.kind == Kind.NAG || (allDay && t.kind == Kind.DAY && t.day == 0)

/**
 * Что показать сейчас. Если телефон был выключен и накопилось несколько срабатываний,
 * показываем одно — самое позднее: если пропущены «за 2 дня» и «за 1 день», нужно только второе.
 */
fun Reminder.dueAt(now: Long): Trigger? =
    triggers().filter { it.time > handledUntil && it.time <= now + EARLY_MS }.maxByOrNull { it.time }

fun Reminder.nextTrigger(): Trigger? = triggers().firstOrNull { it.time > handledUntil }

/** Отметить показанное: всё до этого момента больше не срабатывает; при «повторять» — следующий повтор. */
fun Reminder.handled(now: Long, shown: Trigger): Reminder {
    val base = copy(handledUntil = maxOf(now, shown.time))
    val main = isMain(shown)
    val again = repeat && alert != Alert.SILENT && main && nags < NAG_MAX
    return when {
        again -> base.copy(nagAt = now + NAG_MIN * MINUTE, nags = nags + 1)
        !main -> base
        else -> base.copy(nagAt = null)
    }
}

/** Человек отреагировал (смахнул, открыл) — повторы прекращаются. */
fun Reminder.reacted(): Reminder = copy(nagAt = null, nags = 0)

fun Reminder.snoozed(now: Long, min: Int): Reminder =
    copy(snoozeAt = now + min * MINUTE, nagAt = null, nags = 0, done = false)

/** Способ для конкретного срабатывания: заранее будильником не звенит — приходит обычным звуком. */
fun Reminder.alertFor(t: Trigger): Alert = if (!isMain(t) && alert == Alert.ALARM) Alert.SOUND else alert

/** Звенеть будильником сейчас. */
fun Reminder.rings(t: Trigger, now: Long): Boolean = alertFor(t) == Alert.ALARM && now - t.time < RING_FRESH_MS

/** Голос и вспышка — только у главного срабатывания. */
fun Reminder.effects(t: Trigger): Boolean = isMain(t) && (speak || flash) && alert != Alert.SILENT

private fun Long?.after(now: Long) = this != null && this > now

/** Напоминание ещё впереди (событие не прошло, отложено или повторяется). */
fun Reminder.isActive(now: Long): Boolean = !done && (end() > now || snoozeAt.after(now) || nagAt.after(now))

/** Под каким временем показывать в списке: отложенное — под временем повтора. */
fun Reminder.shownAt(now: Long): Long =
    if (end() > now) at else listOfNotNull(snoozeAt, nagAt).filter { it > now }.minOrNull() ?: at

/** Срабатывание уже было. */
fun Reminder.shown(t: Trigger): Boolean = t.time <= handledUntil

/**
 * 1.0–1.1 хранили «за 1 день / за 2 дня» минутами (1440, 2880) вместе с часами. Переносим в дни
 * в то же время суток, что у события, — срабатывать будет ровно тогда же, что и раньше.
 */
fun migrateBefore(before: List<Int>, at: Long): Triple<List<Int>, List<Int>, Int> {
    val (daysMin, hours) = before.partition { it >= 1440 && it % 1440 == 0 }
    val t = Fmt.local(at)
    return Triple(hours, daysMin.map { it / 1440 }.sorted(), if (daysMin.isEmpty()) DEFAULT_DAY_TIME else t.hour * 60 + t.minute)
}
