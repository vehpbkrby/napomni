package ru.napomni.app

/** «Напомнить заранее» — минуты до события; ровно те варианты, что просил пользователь. */
val BEFORE_OPTIONS = listOf(60, 180, 360, 1440, 2880)

/** Быстрые варианты «через»: минуты от текущего момента. */
val IN_OPTIONS = listOf(15, 30, 60, 120, 180, 360, 1440)

const val MINUTE = 60_000L

/** Будильник может прийти чуть раньше срока — такое срабатывание считаем наступившим. */
const val EARLY_MS = 1_000L

data class Reminder(
    val id: Int,
    val title: String,
    /** Момент события, мс от эпохи. */
    val at: Long,
    /** За сколько минут до события напомнить заранее. */
    val before: List<Int> = emptyList(),
    /** «Отложить» — ещё одно срабатывание сверх расписания. */
    val snoozeAt: Long? = null,
    /** Все срабатывания с моментом не позже этого уже показаны (или прошли до создания напоминания). */
    val handledUntil: Long,
    val done: Boolean = false,
)

enum class Kind { BEFORE, EVENT, SNOOZE }

data class Trigger(val time: Long, val kind: Kind, val beforeMin: Int = 0)

fun Reminder.triggers(): List<Trigger> {
    if (done) return emptyList()
    val list = before.map { Trigger(at - it * MINUTE, Kind.BEFORE, it) } + Trigger(at, Kind.EVENT)
    val all = if (snoozeAt != null) list + Trigger(snoozeAt, Kind.SNOOZE) else list
    return all.sortedBy { it.time }
}

/**
 * Что показать сейчас. Если телефон был выключен и накопилось несколько срабатываний,
 * показываем одно — самое позднее: если пропущены «за 2 дня» и «за 1 день», нужно только второе.
 */
fun Reminder.dueAt(now: Long): Trigger? =
    triggers().filter { it.time > handledUntil && it.time <= now + EARLY_MS }.maxByOrNull { it.time }

fun Reminder.nextTrigger(): Trigger? = triggers().firstOrNull { it.time > handledUntil }

/** Отметить показанное: всё до этого момента больше не срабатывает. */
fun Reminder.handled(now: Long, shown: Trigger): Reminder = copy(handledUntil = maxOf(now, shown.time))

/** Напоминание ещё впереди (само событие или отложенный повтор). */
fun Reminder.isActive(now: Long): Boolean = !done && (at > now || (snoozeAt != null && snoozeAt > now))

/** Под каким временем показывать в списке: отложенное — под временем повтора. */
fun Reminder.shownAt(now: Long): Long = if (at > now || snoozeAt == null) at else snoozeAt

/** Предупреждение «заранее» уже было. */
fun Reminder.beforeShown(min: Int): Boolean = at - min * MINUTE <= handledUntil
