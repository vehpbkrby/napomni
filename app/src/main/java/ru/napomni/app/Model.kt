package ru.napomni.app

/** «Напомнить заранее» — минуты до события; ровно те варианты, что просил пользователь. */
val BEFORE_OPTIONS = listOf(60, 180, 360, 1440, 2880)

/** Быстрые варианты «через»: минуты от текущего момента. */
val IN_OPTIONS = listOf(15, 30, 60, 120, 180, 360, 1440)

const val MINUTE = 60_000L

/** Будильник может прийти чуть раньше срока — такое срабатывание считаем наступившим. */
const val EARLY_MS = 1_000L

/** «Повторять, пока не отреагирую»: каждые 5 минут, не дольше часа. */
const val NAG_MIN = 5
const val NAG_MAX = 12

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
    /** Момент события, мс от эпохи. */
    val at: Long,
    /** За сколько минут до события напомнить заранее. */
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

enum class Kind { BEFORE, EVENT, SNOOZE, NAG }

data class Trigger(val time: Long, val kind: Kind, val beforeMin: Int = 0)

fun Reminder.triggers(): List<Trigger> {
    if (done) return emptyList()
    val list = before.map { Trigger(at - it * MINUTE, Kind.BEFORE, it) }.toMutableList()
    list += Trigger(at, Kind.EVENT)
    snoozeAt?.let { list += Trigger(it, Kind.SNOOZE) }
    nagAt?.let { list += Trigger(it, Kind.NAG) }
    return list.sortedBy { it.time }
}

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
    val again = repeat && alert != Alert.SILENT && shown.kind != Kind.BEFORE && nags < NAG_MAX
    return when {
        again -> base.copy(nagAt = now + NAG_MIN * MINUTE, nags = nags + 1)
        shown.kind == Kind.BEFORE -> base
        else -> base.copy(nagAt = null)
    }
}

/** Человек отреагировал (смахнул, открыл) — повторы прекращаются. */
fun Reminder.reacted(): Reminder = copy(nagAt = null, nags = 0)

fun Reminder.snoozed(now: Long, min: Int): Reminder =
    copy(snoozeAt = now + min * MINUTE, nagAt = null, nags = 0, done = false)

/** Способ для конкретного срабатывания: «заранее» будильником не звенит — приходит обычным звуком. */
fun Reminder.alertFor(t: Trigger): Alert = if (t.kind == Kind.BEFORE && alert == Alert.ALARM) Alert.SOUND else alert

/** Звенеть будильником сейчас. */
fun Reminder.rings(t: Trigger, now: Long): Boolean = alertFor(t) == Alert.ALARM && now - t.time < RING_FRESH_MS

/** Голос и вспышка — только у самого события и его повторов, не у «заранее». */
fun Reminder.effects(t: Trigger): Boolean = t.kind != Kind.BEFORE && (speak || flash) && alert != Alert.SILENT

private fun Long?.after(now: Long) = this != null && this > now

/** Напоминание ещё впереди (само событие, отложенный повтор или повтор «пока не отреагирую»). */
fun Reminder.isActive(now: Long): Boolean = !done && (at > now || snoozeAt.after(now) || nagAt.after(now))

/** Под каким временем показывать в списке: отложенное — под временем повтора. */
fun Reminder.shownAt(now: Long): Long =
    if (at > now) at else listOfNotNull(snoozeAt, nagAt).filter { it > now }.minOrNull() ?: at

/** Предупреждение «заранее» уже было. */
fun Reminder.beforeShown(min: Int): Boolean = at - min * MINUTE <= handledUntil
