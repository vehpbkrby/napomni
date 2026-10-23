package ru.napomni.app

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Напоминания — один JSON-файл во внутренней папке приложения. Наружу ничего не уходит:
 * у приложения нет даже разрешения на интернет. Экран, виджет и будильник живут в одном
 * процессе и читают отсюда же.
 */
object Store {
    private const val FILE = "reminders.json"
    private var items: List<Reminder>? = null
    private var nextId = 1
    private val _flow = MutableStateFlow<List<Reminder>>(emptyList())
    val flow: StateFlow<List<Reminder>> get() = _flow

    /** Для функциональных тестов: забыть прочитанное, чтобы следующий тест начал с чистого листа. */
    @Synchronized
    fun resetForTests() {
        items = null
        nextId = 1
        _flow.value = emptyList()
    }

    @Synchronized
    fun all(ctx: Context): List<Reminder> = items ?: load(ctx).also { items = it; _flow.value = it }

    @Synchronized
    fun get(ctx: Context, id: Int): Reminder? = all(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun update(ctx: Context, f: (List<Reminder>) -> List<Reminder>) {
        val new = f(all(ctx))
        save(ctx, new)
        items = new
        _flow.value = new
    }

    @Synchronized
    fun add(ctx: Context, make: (id: Int) -> Reminder) {
        all(ctx)
        val r = make(nextId++)
        update(ctx) { it + r }
    }

    fun edit(ctx: Context, id: Int, f: (Reminder) -> Reminder) =
        update(ctx) { list -> list.map { if (it.id == id) f(it) else it } }

    fun remove(ctx: Context, ids: Collection<Int>) = update(ctx) { list -> list.filter { it.id !in ids } }

    private fun file(ctx: Context) = AtomicFile(File(ctx.filesDir, FILE))

    private fun load(ctx: Context): List<Reminder> {
        val f = file(ctx)
        if (!f.baseFile.exists()) return emptyList()
        return try {
            parse(String(f.readFully(), Charsets.UTF_8))
        } catch (e: Exception) {
            // Испорченный файл не должен ронять будильник при каждом запуске: откладываем в сторону.
            f.baseFile.renameTo(File(ctx.filesDir, "$FILE.bad-${System.currentTimeMillis()}"))
            emptyList()
        }
    }

    private fun parse(json: String): List<Reminder> {
        val root = JSONObject(json)
        nextId = root.optInt("nextId", 1)
        val arr = root.getJSONArray("items")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val at = o.getLong("at")
            val b = o.optJSONArray("before") ?: JSONArray()
            val beforeAll = (0 until b.length()).map { b.getInt(it) }
            // Записи 1.0–1.1 без «days»: дни лежали в before минутами — переносим.
            val (hours, days, dayTime) = if (o.has("days")) {
                val d = o.getJSONArray("days")
                Triple(beforeAll, (0 until d.length()).map { d.getInt(it) }, o.optInt("dayTime", DEFAULT_DAY_TIME))
            } else {
                migrateBefore(beforeAll, at)
            }
            Reminder(
                id = o.getInt("id"),
                title = o.getString("title"),
                icon = if (o.isNull("icon")) null else o.optString("icon").ifEmpty { null },
                at = at,
                allDay = o.optBoolean("allDay"),
                rule = Rule.entries.firstOrNull { it.name == o.optString("rule") } ?: Rule.NONE,
                weekdays = o.optJSONArray("weekdays")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList(),
                dates = o.optJSONArray("dates")?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList(),
                days = days,
                dayTime = dayTime,
                before = hours,
                snoozeAt = if (o.isNull("snoozeAt")) null else o.getLong("snoozeAt"),
                handledUntil = o.getLong("handled"),
                done = o.optBoolean("done"),
                // До 2.0 способ был один из четырёх (alert) — переносим в признаки.
                sound = if (o.has("sound")) o.optBoolean("sound") else Way.fromOld(o.optString("alert")).sound,
                vibrate = if (o.has("vibrate")) o.optBoolean("vibrate") else Way.fromOld(o.optString("alert")).vibrate,
                // До 2.1 голос читал сразу — так и оставляем у старых напоминаний.
                speakAuto = if (o.has("speakAuto")) o.optBoolean("speakAuto") else o.optBoolean("speak"),
                loud = if (o.has("loud")) o.optBoolean("loud") else Way.fromOld(o.optString("alert")).loud,
                melody = if (o.isNull("melody")) null else o.optString("melody").ifEmpty { null },
                repeat = o.optBoolean("repeat"),
                speak = o.optBoolean("speak"),
                flash = o.optBoolean("flash"),
                nagAt = if (o.isNull("nagAt")) null else o.getLong("nagAt"),
                nags = o.optInt("nags"),
            )
        }.also { list -> nextId = maxOf(nextId, (list.maxOfOrNull { it.id } ?: 0) + 1) }
    }

    private fun save(ctx: Context, list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("title", r.title)
                    .put("icon", r.icon ?: JSONObject.NULL)
                    .put("at", r.at)
                    .put("allDay", r.allDay)
                    .put("days", JSONArray(r.days))
                    .put("dates", JSONArray(r.dates))
                    .put("rule", r.rule.name)
                    .put("weekdays", JSONArray(r.weekdays))
                    .put("dayTime", r.dayTime)
                    .put("before", JSONArray(r.before))
                    .put("snoozeAt", r.snoozeAt ?: JSONObject.NULL)
                    .put("handled", r.handledUntil)
                    .put("done", r.done)
                    .put("sound", r.sound)
                    .put("vibrate", r.vibrate)
                    .put("loud", r.loud)
                    .put("speakAuto", r.speakAuto)
                    .put("melody", r.melody ?: JSONObject.NULL)
                    .put("repeat", r.repeat)
                    .put("speak", r.speak)
                    .put("flash", r.flash)
                    .put("nagAt", r.nagAt ?: JSONObject.NULL)
                    .put("nags", r.nags)
            )
        }
        val data = JSONObject().put("version", 2).put("nextId", nextId).put("items", arr).toString(1)
        val f = file(ctx)
        val out = f.startWrite()
        try {
            out.write(data.toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
    }
}
