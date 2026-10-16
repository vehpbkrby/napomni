package ru.napomni.app

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Заготовка: о чём, картинка, когда/за сколько напомнить и как — без даты и времени события.
 * Полностью заполненная («через 3 мин») создаёт напоминание одним нажатием; в остальной при
 * использовании выбирают только дату и время.
 */
data class Template(
    val id: Int,
    val title: String,
    val icon: String? = null,
    /** «Через N минут»; null — напоминание к дате события. */
    val inMin: Int? = null,
    /** За сколько дней до события (в [dayTime], по умолчанию 12:00). */
    val days: List<Int> = emptyList(),
    /** За сколько минут до события. */
    val before: List<Int> = emptyList(),
    val dayTime: Int? = null,
    val alert: Alert = Alert.SOUND,
    val repeat: Boolean = false,
    val speak: Boolean = false,
    val flash: Boolean = false,
    /** Из набора приложения (можно править и удалять, как свои). */
    val builtIn: Boolean = false,
) {
    /** Всё заполнено — создаётся одним нажатием, без вопросов. */
    val complete get() = inMin != null && title.isNotBlank()

    /** Подпись на плашке: «через 3 мин · сразу», «дата · за 1 и 3 дня», «дата и время». */
    fun summary(): String {
        val how = if (alert != Alert.SOUND) " · ${alert.label.lowercase()}" else ""
        inMin?.let { return "через ${Fmt.dur(it)} · сразу$how" }
        val early = days.sorted().map { Fmt.dur(it * 1440) } + before.sorted().map { Fmt.dur(it) }
        return (if (early.isEmpty()) "дата и время" else "дата · за " + early.joinToString(" и ")) + how
    }

    /** Готовое напоминание по полной заготовке. */
    fun toReminder(id: Int, now: Long): Reminder = Reminder(
        id = id, title = title.ifBlank { "Напоминание" }, icon = icon, at = now + (inMin ?: 0) * MINUTE,
        handledUntil = now, alert = alert, repeat = repeat && alert != Alert.SILENT,
        speak = speak && (alert == Alert.SOUND || alert == Alert.ALARM), flash = flash && alert != Alert.SILENT,
    )
}

/** Заготовки — свой файл рядом с напоминаниями. При первом запуске — набор готовых. */
object Templates {
    private const val FILE = "templates.json"
    private var items: List<Template>? = null
    private var nextId = 1
    private val _flow = MutableStateFlow<List<Template>>(emptyList())
    val flow: StateFlow<List<Template>> get() = _flow

    val DEFAULTS = listOf(
        Template(1, "Через 3 мин", "⏱", inMin = 3, builtIn = true),
        Template(2, "Чайник", "🫖", inMin = 5, builtIn = true),
        Template(3, "Лекарство", "💊", alert = Alert.ALARM, repeat = true, builtIn = true),
        Template(4, "День рождения", "🎂", days = listOf(1, 3), builtIn = true),
        Template(5, "Позвонить", "📞", builtIn = true),
        Template(6, "Оплатить", "💳", days = listOf(1), builtIn = true),
    )

    /** Для функциональных тестов: забыть прочитанное, чтобы следующий тест начал с чистого листа. */
    @Synchronized
    fun resetForTests() {
        items = null
        nextId = 1
        _flow.value = emptyList()
    }

    @Synchronized
    fun all(ctx: Context): List<Template> = items ?: load(ctx).also { items = it; _flow.value = it }

    @Synchronized
    fun put(ctx: Context, t: Template) {
        val list = all(ctx)
        val item = if (t.id == 0) t.copy(id = nextId++) else t
        val new = if (list.any { it.id == item.id }) list.map { if (it.id == item.id) item else it } else list + item
        save(ctx, new)
    }

    @Synchronized
    fun remove(ctx: Context, id: Int) = save(ctx, all(ctx).filter { it.id != id })

    private fun file(ctx: Context) = AtomicFile(File(ctx.filesDir, FILE))

    private fun load(ctx: Context): List<Template> {
        val f = file(ctx)
        if (!f.baseFile.exists()) {
            nextId = DEFAULTS.size + 1
            return DEFAULTS
        }
        return try {
            val root = JSONObject(String(f.readFully(), Charsets.UTF_8))
            nextId = root.optInt("nextId", 1)
            val arr = root.getJSONArray("items")
            fun ints(a: JSONArray?) = a?.let { x -> (0 until x.length()).map { x.getInt(it) } } ?: emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Template(
                    id = o.getInt("id"),
                    title = o.getString("title"),
                    icon = if (o.isNull("icon")) null else o.optString("icon"),
                    inMin = if (o.isNull("inMin")) null else o.getInt("inMin"),
                    days = ints(o.optJSONArray("days")),
                    before = ints(o.optJSONArray("before")),
                    dayTime = if (o.isNull("dayTime")) null else o.getInt("dayTime"),
                    alert = Alert.entries.firstOrNull { it.name == o.optString("alert") } ?: Alert.SOUND,
                    repeat = o.optBoolean("repeat"),
                    speak = o.optBoolean("speak"),
                    flash = o.optBoolean("flash"),
                    builtIn = o.optBoolean("builtIn"),
                )
            }.also { list -> nextId = maxOf(nextId, (list.maxOfOrNull { it.id } ?: 0) + 1) }
        } catch (e: Exception) {
            f.baseFile.renameTo(File(ctx.filesDir, "$FILE.bad-${System.currentTimeMillis()}"))
            DEFAULTS
        }
    }

    private fun save(ctx: Context, list: List<Template>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id).put("title", t.title).put("icon", t.icon ?: JSONObject.NULL)
                    .put("inMin", t.inMin ?: JSONObject.NULL).put("days", JSONArray(t.days)).put("before", JSONArray(t.before))
                    .put("dayTime", t.dayTime ?: JSONObject.NULL).put("alert", t.alert.name)
                    .put("repeat", t.repeat).put("speak", t.speak).put("flash", t.flash).put("builtIn", t.builtIn)
            )
        }
        val data = JSONObject().put("version", 1).put("nextId", nextId).put("items", arr).toString(1)
        val f = file(ctx)
        val out = f.startWrite()
        try {
            out.write(data.toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
        items = list
        _flow.value = list
    }
}
