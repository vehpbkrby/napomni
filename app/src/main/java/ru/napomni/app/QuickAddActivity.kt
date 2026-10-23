package ru.napomni.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * Мастер «новое напоминание» поверх рабочего стола (из виджета) или поверх списка (правка).
 * Шаг 1 — когда событие, шаг 2 — когда напомнить. «Через N» сохраняется сразу с первого шага.
 */
class QuickAddActivity : ComponentActivity() {
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        val edit = intent.getIntExtra(EXTRA_ID, -1).takeIf { it > 0 }?.let { Store.get(this, it) }
        val preset = intent.getStringExtra(EXTRA_PRESET)
        val day = intent.getLongExtra(EXTRA_DAY, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let { LocalDate.ofEpochDay(it) }
        setContent { AppTheme { Wizard(edit, preset, day) { finish() } } }
    }

    companion object {
        const val EXTRA_PRESET = "preset"
        const val EXTRA_ID = "id"
        const val PRESET_1H = "1h"
        const val PRESET_3H = "3h"
        const val PRESET_DAY = "day"
        const val PRESET_DATE = "date"
        const val EXTRA_DAY = "day"

        fun open(ctx: Context, id: Int? = null) = ctx.startActivity(
            Intent(ctx, QuickAddActivity::class.java).apply { id?.let { putExtra(EXTRA_ID, it) } }
        )
    }
}

/** Блоки конструктора по порядку: заполненный сворачивается в строку, ниже раскрывается следующий. */
private enum class B { TEXT, WHEN, DETAIL, HOW }

/** Когда напомнить — для разового события. Повторяющемуся остаётся только «перед событием». */
private enum class Mode(val title: String, val sub: String) {
    IN("В ближайшие сутки", "через 15 мин, 1 ч… или к часу"),
    BEFORE("Перед событием", "в день, за дни, за часы"),
    DATES("В конкретные дни", "отметить несколько дней"),
}

/** «Через» — до суток. */
private val QUICK_OPTIONS = IN_OPTIONS + 1440

/** «За сколько дней»: 0 — в день события. */
private val DAY_CHOICES = listOf(1, 2, 3, 7)

/** Время по умолчанию: у события при выборе даты и у напоминаний «заранее», пока его не задали. */
private const val NOON = 12 * 60
private val NOON_TIME: java.time.LocalTime = java.time.LocalTime.of(12, 0)

private fun hm(h: Int, m: Int) = "$h:%02d".format(m)
private fun hm(t: java.time.LocalTime) = hm(t.hour, t.minute)
private fun hm(minutes: Int) = hm(minutes / 60, minutes % 60)

/** Прокрутка к раскрывшемуся блоку — кривая и длительность от дизайнера. */
private val SCROLL_SPEC = tween<Float>(420, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))

@Composable
private fun Wizard(edit: Reminder?, preset: String?, day: LocalDate?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPal.current
    val brass = isBrass
    val zone = remember { ZoneId.systemDefault() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val fr = remember { FocusRequester() }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    val today = Fmt.local(now, zone).toLocalDate()
    val start = remember { Fmt.local(System.currentTimeMillis(), zone).toLocalDate() }
    val week = remember(start) { (0..7).map { start.plusDays(it.toLong()) } }
    val editAt = edit?.let { Fmt.local(it.at, zone) }

    // --- О чём
    var text by remember { mutableStateOf(TextFieldValue(edit?.title ?: "", TextRange(edit?.title?.length ?: 0))) }
    var focused by remember { mutableStateOf(false) }
    // Картинка: выбранная вручную или подобранная по тексту.
    var icon by remember { mutableStateOf(edit?.icon) }
    var picDialog by remember { mutableStateOf(false) }
    var date by remember {
        mutableStateOf(
            when {
                edit != null && edit.dates.isEmpty() && edit.before.isEmpty() && edit.days.isEmpty() && !edit.allDay && edit.rule == Rule.NONE -> null
                edit != null && edit.dates.isEmpty() -> editAt?.toLocalDate()
                preset == QuickAddActivity.PRESET_DAY -> maxOf(day ?: start.plusDays(1), start)
                else -> null
            }
        )
    }
    var eventTime by remember {
        mutableStateOf(if (edit != null && !edit.allDay && date != null && editAt != null) java.time.LocalTime.of(editAt.hour, editAt.minute) else null)
    }
    var rule by remember { mutableStateOf(edit?.rule ?: Rule.NONE) }
    var weekdays by remember { mutableStateOf(edit?.weekdays?.toSet() ?: emptySet()) }

    // --- Когда напомнить
    var mode by remember {
        mutableStateOf(
            when {
                edit == null -> when (preset) {
                    QuickAddActivity.PRESET_1H, QuickAddActivity.PRESET_3H -> Mode.IN
                    QuickAddActivity.PRESET_DAY -> Mode.BEFORE
                    else -> null
                }
                edit.dates.isNotEmpty() -> Mode.DATES
                date == null -> Mode.IN
                else -> Mode.BEFORE
            }
        )
    }
    // «В ближайшие сутки → к часу»: время суток в минутах; сработает ближайшее такое — сегодня или завтра.
    var byClock by remember { mutableStateOf(false) }
    var soonAt by remember { mutableStateOf<Int?>(null) }
    var inMin by remember {
        mutableStateOf(
            when {
                preset == QuickAddActivity.PRESET_1H -> 60
                preset == QuickAddActivity.PRESET_3H -> 180
                edit != null && date == null && edit.dates.isEmpty() ->
                    ((edit.at - System.currentTimeMillis()) / MINUTE).toInt().takeIf { it > 0 }
                else -> null
            }
        )
    }
    var days by remember { mutableStateOf(edit?.days?.filter { it > 0 }?.toSet() ?: emptySet()) }
    var customDialog by remember { mutableStateOf(false) }
    var before by remember { mutableStateOf(edit?.before?.toSet() ?: emptySet()) }
    var dates by remember { mutableStateOf(edit?.dates?.map { LocalDate.ofEpochDay(it) }?.toSet() ?: emptySet()) }
    // Время напоминаний по дням: по умолчанию не задано — прочерки, нажать, чтобы поставить.
    var dayTime by remember { mutableStateOf(edit?.takeIf { it.days.isNotEmpty() || it.dates.isNotEmpty() }?.dayTime) }

    // --- Как напомнить
    // Звук, вибрация, вспышка, голос, «как будильник», повтор — в любом сочетании; по умолчанию последний выбор.
    // Новое — всегда с «Обычно» (звук и вибрация); правка — как было сохранено.
    var way by remember { mutableStateOf(edit?.way ?: Preset.NORMAL.way) }
    var melody by remember { mutableStateOf(if (edit != null) edit.melody else Prefs.lastMelody(ctx)) }
    var melodyMenu by remember { mutableStateOf(false) }
    val melodyName = remember(melody) { Melody.name(ctx, melody) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Право читать файл и после перезагрузки — иначе к сроку мелодия не откроется.
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
            melody = uri.toString()
        }
    }

    var dateDialog by remember { mutableStateOf(preset == QuickAddActivity.PRESET_DATE) }
    var timeDialog by remember { mutableStateOf<String?>(null) } // "day" — время напоминаний
    var calOpen by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf(YearMonth.from(date ?: start)) }
    var parsed by remember { mutableStateOf(TimeParser.parse(text.text, LocalDateTime.now(zone))) }
    var lastKey by remember { mutableStateOf(parsed.copy(spans = emptyList(), title = "")) }
    var error by remember { mutableStateOf<String?>(null) }

    val items by Store.flow.collectAsState()
    val marked = remember(items, now) {
        items.filter { it.isActive(now) && it.id != edit?.id }.map { Fmt.local(it.at, zone).toLocalDate() }.toSet()
    }

    val recurring = rule != Rule.NONE
    // Есть дата события (или повтор) — выбирать «когда» не из чего: только перед событием.
    val effMode = if (recurring || date != null) Mode.BEFORE else mode

    /** Первый раз повторяющегося: для «каждую неделю» по дням — ближайший отмеченный день не раньше даты. */
    fun firstDate(d: LocalDate): LocalDate {
        if (rule != Rule.WEEKLY || weekdays.isEmpty()) return d
        var x = d
        while (x.dayOfWeek.value !in weekdays) x = x.plusDays(1)
        return x
    }

    /** Ближайший момент с таким временем суток после [t]: сегодня, если ещё впереди, иначе завтра (не дальше суток). */
    fun nextAt(minutes: Int, t: Long): Long {
        val n = Fmt.local(t, zone)
        val today = n.toLocalDate().atTime(minutes / 60, minutes % 60)
        val at = if (today.isAfter(n)) today else today.plusDays(1)
        return at.atZone(zone).toInstant().toEpochMilli()
    }

    /** Свёрнутая строка «В ближайшие сутки»: «через 2 ч» или «к 18:30». */
    fun soonLine(): String = if (byClock) soonAt?.let { "к ${hm(it)}" } ?: "…" else inMin?.let { "через ${Fmt.dur(it)}" } ?: "…"

    /** Черновик — та же модель, что сохранится; null — не хватает выбора. */
    fun draft(t: Long): Reminder? {
        val dt = dayTime ?: NOON
        return when (effMode) {
            Mode.IN -> if (byClock) soonAt?.let { m ->
                Reminder(id = 0, title = "", at = nextAt(m, t), handledUntil = t)
            } else inMin?.let { Reminder(id = 0, title = "", at = t + it * MINUTE, handledUntil = t) }
            Mode.BEFORE -> {
                val d = firstDate(date ?: return null)
                // У события всегда есть время: при выборе даты ставится 12:00, его можно сменить.
                val et = eventTime ?: NOON_TIME
                val base = Reminder(
                    id = 0, title = "", at = d.atTime(et).atZone(zone).toInstant().toEpochMilli(),
                    days = days.toList(), dayTime = dt, before = before.toList(), handledUntil = t,
                )
                base.copy(rule = rule, weekdays = if (rule == Rule.WEEKLY) weekdays.sorted() else emptyList()).rolled(t)
            }
            Mode.DATES -> {
                if (dates.isEmpty()) return null
                Reminder(
                    id = 0, title = "", at = dates.max().atStartOfDay(zone).toInstant().toEpochMilli(), allDay = true,
                    dates = dates.map { it.toEpochDay() }.sorted(), dayTime = dt, handledUntil = t,
                )
            }
            null -> null
        }
    }

    /** Сохранится только то, что ещё впереди и уместно. */
    fun cleaned(r: Reminder, t: Long): Reminder = r.copy(
        days = r.days.filter { r.dayFits(it) && r.dayAt(it) > t }.sorted(),
        before = r.before.filter { r.at - it * MINUTE > t }.sorted(),
        dates = r.dates.filter { r.dateAt(it) > t },
    )

    // Готовность блоков — от неё зависит, что раскрыто.
    val textOk = text.text.isNotBlank()
    val recurOk = !recurring || (date != null && (rule != Rule.WEEKLY || weekdays.isNotEmpty()))
    val whenOk = recurring || date != null || mode != null
    val needDayTime = false
    val detailOk = when (effMode) {
        Mode.IN -> if (byClock) soonAt != null else inMin != null
        // В день события напоминает само событие — «заранее» по желанию; время «заранее» не задано — 12:00.
        Mode.BEFORE -> date != null
        Mode.DATES -> dates.isNotEmpty()
        null -> false
    }
    val plan = draft(now)?.let { cleaned(it, now) }?.triggers()?.filter { it.time > now }.orEmpty()
    val ready = recurOk && whenOk && detailOk && plan.isNotEmpty()

    // Какой блок раскрыт: по умолчанию — первый незаполненный; нажатие на свёрнутую строку раскрывает её.
    // Новое — поле текста открыто, пока человек пишет: набор букв ничего не сворачивает и не прокручивает.
    var opened by remember { mutableStateOf<B?>(if (edit == null) B.TEXT else null) }
    val firstOpen = when {
        !recurOk -> B.TEXT
        !whenOk -> B.WHEN
        !detailOk -> B.DETAIL
        edit == null -> B.HOW
        else -> null
    }
    val open = opened ?: firstOpen

    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Раскрылся следующий блок — довести лист до него.
    LaunchedEffect(open) {
        if (open != null && open != B.TEXT) {
            delay(160)
            if (!scroll.isScrollInProgress) scroll.animateScrollTo(scroll.maxValue, SCROLL_SPEC)
        }
    }

    fun onText(v: TextFieldValue) {
        text = v
        error = null
        val pr = TimeParser.parse(v.text, LocalDateTime.now(zone))
        parsed = pr
        val key = pr.copy(spans = emptyList(), title = "")
        if (key == lastKey) return
        lastKey = key
        // Понятое из текста выставляет выбор; ручной выбор держится, пока текст не скажет другое.
        pr.minutes?.let {
            mode = Mode.IN
            inMin = it
            return
        }
        pr.date?.let {
            date = it
            month = YearMonth.from(it)
            if (mode == null || mode == Mode.IN) mode = Mode.BEFORE
        }
        pr.time?.let { eventTime = it }
        if (pr.date != null && eventTime == null) eventTime = NOON_TIME
    }

    fun save(): Boolean {
        val t = System.currentTimeMillis()
        val r0 = draft(t)?.let { cleaned(it, t) }
        if (!ready || r0 == null || r0.triggers().none { it.time > t }) {
            error = "Не хватает выбора — посмотрите раскрытый блок"
            return false
        }
        val title = TimeParser.parse(text.text, LocalDateTime.now(zone)).title.ifBlank { "Напоминание" }
        // Лишние для способа галочки не сохраняем: у «Тихо» нет повтора, у «Вибро» — голоса.
        val mel = if (way.sound || way.loud) melody else null
        if (way.sound || way.loud) Prefs.setLastMelody(ctx, melody)
        Prefs.setLastWay(ctx, way)
        val saved = r0.copy(title = title, icon = icon ?: Pics.guess(text.text), melody = mel).withWay(way)
        if (edit != null) {
            Store.edit(ctx, edit.id) { saved.copy(id = edit.id) }
            Notifier.cancel(ctx, edit.id)
            AlarmService.stop(ctx, edit.id)
        } else {
            Store.add(ctx) { id -> saved.copy(id = id) }
        }
        Scheduler.run(ctx)
        saved.triggers().firstOrNull { it.time > t }?.let {
            Toast.makeText(ctx, "Напомню ${Fmt.compact(it.time, t)}", Toast.LENGTH_SHORT).show()
        }
        onClose()
        return true
    }

    val templates by Templates.flow.collectAsState()
    LaunchedEffect(Unit) { Templates.all(ctx) }

    /** Заготовка: полная — сразу готовое напоминание; иначе подставить заполненное и спросить дату. */
    fun useTemplate(t: Template) {
        val now0 = System.currentTimeMillis()
        if (t.complete) {
            var made: Reminder? = null
            Store.add(ctx) { id -> t.toReminder(id, now0).also { made = it } }
            Scheduler.run(ctx)
            made?.triggers()?.firstOrNull { it.time > now0 }?.let {
                Toast.makeText(ctx, "${t.title}: напомню ${Fmt.compact(it.time, now0)}", Toast.LENGTH_SHORT).show()
            }
            onClose()
            return
        }
        onText(TextFieldValue(t.title, TextRange(t.title.length)))
        icon = t.icon
        days = t.days.toSet()
        before = t.before.toSet()
        dayTime = t.dayTime
        way = t.way
        if (t.inMin != null) {
            mode = Mode.IN
            inMin = t.inMin
        } else {
            mode = Mode.BEFORE
            if (date == null) dateDialog = true
        }
        focus.clearFocus()
        opened = B.DETAIL
    }

    LaunchedEffect(Unit) {
        if (edit == null && !dateDialog) {
            delay(120)
            fr.requestFocus()
            keyboard?.show()
        }
    }

    // Строки свёрнутых блоков.
    val eventLine = buildString {
        date?.let { append(Fmt.dayShort(firstDate(it), today)) } ?: append("без даты")
        append(" · ")
        append(eventTime?.let { hm(it) } ?: "--:--")
        if (recurring) append(" · ${rule.label}")
    }
    val whenLine = when (effMode) {
        Mode.IN -> soonLine()
        Mode.BEFORE -> buildList {
            days.sorted().forEach { add("за ${Fmt.dur(it * 1440)}") }
            before.sorted().forEach { add("за ${Fmt.dur(it)}") }
        }.joinToString(", ").ifEmpty { "в само событие" } + (if (days.isNotEmpty()) ", ${hm(dayTime ?: NOON)}" else "")
        Mode.DATES -> dates.sorted().joinToString(", ") { Fmt.dm(it) } + ", ${hm(dayTime ?: NOON)}"
        null -> ""
    }

    val side = if (brass) 4.dp else 20.dp
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .45f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClose)
            .statusBarsPadding(),
        // Лист на всю высоту, содержимое от верха: клавиатура прячется — текст остаётся на месте.
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 12.dp)
                .imePadding()
                .clickable(remember { MutableInteractionSource() }, null) {},
            shape = RoundedCornerShape(topStart = if (brass) 22.dp else 18.dp, topEnd = if (brass) 22.dp else 18.dp),
            color = if (brass) p.bg else p.surface,
        ) {
            Column(
                Modifier
                    .verticalScroll(scroll)
                    .navigationBarsPadding()
                    .padding(horizontal = if (brass) 12.dp else 0.dp)
                    .padding(bottom = 14.dp)
                    .animateContentSize()
            ) {
                // Шапка: ручка, заголовок, ✕.
                Column(Modifier.padding(horizontal = side)) {
                    Box(
                        Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 10.dp)
                            .size(36.dp, 4.dp).clip(CircleShape).background(p.outline)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StepTitle(if (edit != null) "Напоминание" else "Новое напоминание", Modifier.weight(1f))
                        CloseButton(onClose)
                    }
                    Spacer(Modifier.height(6.dp))
                }

                // ---------- 1. О чём ----------
                if (open == B.TEXT) StepBlock("О чём", BlockState.ACTIVE) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        PicBadge(icon ?: Pics.guess(text.text)) { picDialog = true }
                        Spacer(Modifier.size(10.dp))
                        Box(Modifier.weight(1f)) {
                            BasicTextField(
                                value = text,
                                onValueChange = ::onText,
                                textStyle = TextStyle(
                                    fontFamily = displayFont(), fontSize = if (brass) 19.sp else 17.sp,
                                    lineHeight = if (brass) 28.sp else 24.sp, color = p.text,
                                ),
                                cursorBrush = SolidColor(p.accent),
                                visualTransformation = Highlight(parsed.spans, p.hlBg, p.hlText),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                                // Клавиша ввода только прячет клавиатуру; поле свернётся, когда начнёте работать в следующем блоке.
                                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                                modifier = Modifier.fillMaxWidth().focusRequester(fr).onFocusChanged { focused = it.isFocused },
                                decorationBox = { inner ->
                                    Column {
                                        Box {
                                            if (text.text.isEmpty()) Text(
                                                "Врач, кабинет 12", fontFamily = displayFont(),
                                                fontSize = if (brass) 19.sp else 17.sp, color = p.textSub.copy(alpha = .6f),
                                            )
                                            inner()
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Box(
                                            Modifier.fillMaxWidth().height(if (focused) 1.5.dp else 1.dp)
                                                .background(if (focused) p.primary else p.outline)
                                        )
                                    }
                                },
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        CalendarButton(date != null) {
                            focus.clearFocus()
                            dateDialog = true
                        }
                    }
                    if (date != null) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp)).background(p.hlBg).clickable { dateDialog = true }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) { Text(eventLine, fontSize = 13.sp, color = p.hlText) }
                        LinkButton("✕") {
                            date = null
                            eventTime = null
                            rule = Rule.NONE
                            if (mode == Mode.BEFORE) mode = null
                        }
                    }
                    // Повторяющееся событие — своя логика: дата первого раза и периодичность.
                    CheckRow("Повторяющееся событие", recurring) {
                        rule = if (it) Rule.WEEKLY else Rule.NONE
                        if (it && date == null) dateDialog = true
                    }
                    if (recurring) {
                        Chips {
                            Rule.entries.drop(1).forEach { r ->
                                Chip(r.label.replaceFirstChar { c -> c.uppercase() }, selected = rule == r) { rule = r }
                            }
                        }
                        if (rule == Rule.WEEKLY) {
                            Text("По каким дням недели", fontSize = 13.sp, color = p.textSub, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Fmt.WEEKDAYS.forEachIndexed { i, w ->
                                    val n = i + 1
                                    val sel = n in weekdays
                                    val we = n >= 6
                                    val shape = RoundedCornerShape(8.dp)
                                    Box(
                                        Modifier.weight(1f).height(38.dp).clip(shape)
                                            .background(if (sel) p.primary else if (isBrass) p.surface else p.surfaceVar)
                                            .then(if (!sel && isBrass) Modifier.border(1.dp, if (we) p.weekendEdge else p.outline, shape) else Modifier)
                                            .clickable { weekdays = if (sel) weekdays - n else weekdays + n },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            w, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false,
                                            color = when {
                                                sel -> if (we) p.weekendOnPrimary else p.onPrimary
                                                we -> p.weekend
                                                else -> p.text
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (date == null) LinkButton("Выберите дату первого раза ▦") { dateDialog = true }
                    }
                } else DoneRow(text.text.ifBlank { "О чём" }, eventLine.takeIf { date != null } ?: "без даты") { opened = B.TEXT }

                // ---------- 2. Когда напомнить (только разовое) ----------
                // «Когда напомнить» виден сразу — следующий пункт по умолчанию, пока пишется текст.
                if (recurOk && !recurring && date == null) {
                    if (open == B.WHEN || (open == B.TEXT && mode == null)) StepBlock("Когда напомнить", BlockState.ACTIVE) {
                        Mode.entries.forEach { m ->
                            ModeOption(m.title, m.sub, mode == m) {
                                mode = m
                                opened = null
                                // Перед событием без даты нельзя — сразу календарь.
                                if (m == Mode.BEFORE && date == null) dateDialog = true
                            }
                        }
                    } else if (mode != null) DoneRow(mode!!.title, if (open == B.DETAIL) "изменить" else whenLine) { opened = B.WHEN }
                }

                // ---------- 3. Подробности выбранного ----------
                // Есть дата (кнопкой ▦ или из текста) — «за сколько» видно сразу под полем; первый блок
                // свернётся, когда начнёте работать здесь.
                val detailBelow = open == B.TEXT && date != null
                if (recurOk && whenOk && (open != B.TEXT || detailBelow)) {
                    if (open == B.DETAIL || detailBelow) when (effMode) {
                        Mode.IN -> StepBlock("В ближайшие сутки", BlockState.ACTIVE) {
                            Segmented(listOf("Через", "К часу"), if (byClock) 1 else 0) {
                                byClock = it == 1
                                // Выбираем здесь — блок держится открытым до «Готово» (или выбора срока).
                                opened = B.DETAIL
                                if (byClock && soonAt == null) {
                                    // Предлагаем ближайший целый час; дальше — барабаном.
                                    val n = Fmt.local(System.currentTimeMillis(), zone)
                                    soonAt = ((n.hour + 1) % 24) * 60
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            if (!byClock) Chips {
                                QUICK_OPTIONS.forEach { m ->
                                    Chip(Fmt.dur(m), selected = inMin == m) {
                                        inMin = m
                                        opened = null
                                    }
                                }
                                inMin?.takeIf { it !in QUICK_OPTIONS }?.let { m -> Chip(Fmt.dur(m), selected = true) {} }
                            } else {
                                val m = soonAt ?: 0
                                TimeWheels(
                                    hour = m / 60, minute = m % 60, rows = 3,
                                    onHour = {
                                        soonAt = it * 60 + (soonAt ?: 0) % 60
                                        opened = B.DETAIL
                                    },
                                    onMinute = {
                                        soonAt = (soonAt ?: 0) / 60 * 60 + it
                                        opened = B.DETAIL
                                    },
                                )
                                Text(
                                    "сейчас ${Fmt.hm(Fmt.local(now, zone))} — можно до этого же времени завтра · сработает: " +
                                        Fmt.compact(nextAt(m, now), now),
                                    fontSize = 12.sp, color = p.textSub, modifier = Modifier.padding(top = 6.dp),
                                )
                                MainButton("Готово", Modifier.fillMaxWidth().padding(top = 8.dp), height = 44.dp) { opened = null }
                            }
                        }
                        Mode.BEFORE -> StepBlock(
                            "За сколько напомнить", BlockState.ACTIVE,
                            // Начали работать здесь — первый блок сворачивается. Только от нажатия:
                            // палец сдвинулся (прокрутка) — ничего не трогаем.
                            modifier = Modifier.pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val down = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.pressed } ?: continue
                                        val start = down.position
                                        var moved = false
                                        while (true) {
                                            val e = awaitPointerEvent(PointerEventPass.Initial)
                                            val c = e.changes.firstOrNull() ?: break
                                            if ((c.position - start).getDistance() > viewConfiguration.touchSlop) moved = true
                                            if (!c.pressed) break
                                        }
                                        if (!moved && opened == B.TEXT) opened = B.DETAIL
                                    }
                                }
                            },
                        ) {
                            if (date == null) LinkButton("Выберите дату события ▦") { dateDialog = true } else {
                                val r0 = draft(now)
                                Chips {
                                    DAY_CHOICES.forEach { d ->
                                        val ok = r0 != null && (recurring || r0.dayAt(d) > now)
                                        Chip(Fmt.dayLabel(d), selected = d in days, enabled = ok || d in days, check = true) {
                                            days = if (d in days) days - d else days + d
                                            // Работаем здесь — блок держится открытым до «Готово».
                                            opened = B.DETAIL
                                        }
                                    }
                                    // Свои «за N дней» — тоже кнопками; нажатие снимает.
                                    days.filter { it !in DAY_CHOICES }.sorted().forEach { d ->
                                        Chip(Fmt.dayLabel(d), selected = true, check = true) { days = days - d }
                                    }
                                    BEFORE_OPTIONS.forEach { b ->
                                        Chip(Fmt.before(b), selected = b in before, check = true) {
                                            before = if (b in before) before - b else before + b
                                            // Работаем здесь — блок держится открытым до «Готово».
                                            opened = B.DETAIL
                                        }
                                    }
                                    before.filter { it !in BEFORE_OPTIONS }.sorted().forEach { b ->
                                        Chip(Fmt.before(b), selected = true, check = true) { before = before - b }
                                    }
                                    Chip("+ Своё", selected = false) { customDialog = true }
                                }
                                Text(
                                    "в день события напомню в его время — ${hm(eventTime ?: NOON_TIME)}",
                                    fontSize = 12.sp, color = p.textSub, modifier = Modifier.padding(top = 4.dp),
                                )
                                if (days.isNotEmpty()) TimeField("Во сколько напоминать в эти дни", dayTime ?: NOON) { timeDialog = "day" }
                                if (detailOk) MainButton("Готово", Modifier.fillMaxWidth().padding(top = 8.dp), height = 44.dp) { opened = null }
                            }
                        }
                        Mode.DATES -> StepBlock("В какие дни", BlockState.ACTIVE) {
                            // Отмечаем дни — блок держится открытым до «Готово», а не сворачивается после первого.
                            val toggle = { d: LocalDate ->
                                dates = if (d in dates) dates - d else dates + d
                                opened = B.DETAIL
                            }
                            WeekGrid(week, { it in dates }, today, check = true) { toggle(it) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val others = dates.filter { it !in week }.sorted()
                                Text(
                                    if (others.isEmpty()) "" else "Ещё: " + others.joinToString(", ") { Fmt.dm(it) },
                                    fontSize = 13.sp, color = p.text, modifier = Modifier.weight(1f),
                                )
                                LinkButton(if (calOpen) "Скрыть" else "Календарь ›") { calOpen = !calOpen }
                            }
                            if (calOpen) MonthCalendar(month, { month = it }, { it in dates }, today, marked) { toggle(it) }
                            TimeField("Во сколько", dayTime ?: NOON) { timeDialog = "day" }
                            if (detailOk) MainButton("Готово", Modifier.fillMaxWidth().padding(top = 8.dp), height = 44.dp) { opened = null }
                        }
                        null -> {}
                    } else if (detailOk) DoneRow(
                        when (effMode) {
                            Mode.IN -> "В ближайшие сутки"
                            Mode.BEFORE -> "Напомнить"
                            else -> "В дни"
                        },
                        when (effMode) {
                            Mode.IN -> soonLine()
                            else -> whenLine
                        },
                    ) { opened = B.DETAIL }
                }

                // ---------- 4. Как напомнить ----------
                if (recurOk && whenOk && detailOk && (open != B.TEXT || detailBelow)) {
                    if (open == B.HOW) StepBlock("Как напомнить", BlockState.ACTIVE) {
                        WaySection(way, melodyName, onMelody = { melodyMenu = true }) { way = it }
                        if (way.loud && !Notifier.fullScreenAllowed(ctx)) LinkButton("Разрешить окно поверх блокировки") {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))
                            )
                        }
                    } else DoneRow("Как напомнить", way.label()) { opened = B.HOW }

                    // ---------- Итог: готовое напоминание ----------
                    Column(Modifier.padding(horizontal = side)) {
                        FinalCard(
                            icon = icon ?: Pics.guess(text.text),
                            title = parsed.title.ifBlank { text.text.ifBlank { "Напоминание" } },
                            event = if (effMode == Mode.IN || date == null) null else eventLine,
                            plan = plan.map { Fmt.compact(it.time, now) + if (it.kind == Kind.EVENT) " (событие)" else "" },
                            weekend = plan.map { isWeekend(Fmt.local(it.time, zone).toLocalDate()) },
                            how = way.label(),
                        )
                        error?.let { Text(it, fontSize = 13.sp, color = p.error, modifier = Modifier.padding(top = 6.dp)) }
                        MainButton(
                            if (edit != null) "Сохранить" else "Напомнить", Modifier.fillMaxWidth().padding(top = 8.dp),
                            ring = ready, enabled = ready,
                        ) { save() }
                        if (edit != null) LinkButton("Удалить напоминание", Modifier.fillMaxWidth(), color = p.error) {
                            Store.remove(ctx, listOf(edit.id))
                            Notifier.cancel(ctx, edit.id)
                            AlarmService.stop(ctx, edit.id)
                            Scheduler.run(ctx)
                            onClose()
                        }
                    }
                }

                // ---------- Заготовки: быстрый выбор вместо ручного заполнения ----------
                // Заготовки — пока экран пуст; начали своё (текст, дата, выбор «когда») — они уже не нужны.
                val fresh = text.text.isBlank() && date == null && mode == null && preset == null
                if (edit == null && fresh && templates.isNotEmpty()) Column(Modifier.padding(horizontal = side).padding(top = 18.dp)) {
                    BlockLabel("Заготовки", p.accentText, Modifier.padding(bottom = 8.dp))
                    templates.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { t -> TemplatePlate(t, Modifier.weight(1f)) { useTemplate(t) } }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    if (dateDialog) EventDateDialog(
        date = date ?: start, time = eventTime, today = today, marked = marked,
        onDone = { d, t ->
            date = d
            eventTime = t
            month = YearMonth.from(d)
            dateDialog = false
            mode = Mode.BEFORE
            // Дата выбрана — работаем уже в следующем блоке: сразу раскрываем «за сколько».
            // Дата выбрана — работаем в следующем блоке; у повторяющегося ещё выбирают периодичность здесь же.
            opened = if (rule != Rule.NONE) B.TEXT else B.DETAIL
        },
        onClose = { dateDialog = false },
    )
    if (customDialog) CustomBeforeDialog(onAdd = { n, unit ->
        when (unit) {
            0 -> before = before + n
            1 -> before = before + n * 60
            else -> days = days + n
        }
        customDialog = false
    }, onClose = { customDialog = false })
    if (timeDialog != null) TimeDialog("Во сколько напомнить", dayTime ?: NOON, onDone = {
        dayTime = it
        timeDialog = null
    }, onClose = { timeDialog = null })
    if (picDialog) PicPicker(icon ?: Pics.guess(text.text), onPick = {
        icon = it
        picDialog = false
    }, onClose = { picDialog = false })
    if (melodyMenu) MelodyPicker(
        current = melody,
        onPick = { melody = it },
        onFile = {
            melodyMenu = false
            pickFile.launch(arrayOf("audio/*"))
        },
        onClose = { melodyMenu = false },
    )
}

/** Маленькая кнопка ▦ рядом с полем: дата события. */
@Composable
private fun CalendarButton(set: Boolean, onClick: () -> Unit) {
    val p = LocalPal.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(40.dp).clip(shape).background(if (set) p.primary else Color.Transparent)
            .border(1.dp, if (set) p.primary else p.accent, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text("▦", fontSize = 18.sp, color = if (set) p.linkOnPrimary.takeIf { isBrass } ?: p.onPrimary else p.accentText) }
}

/** Свёрнутый блок: строка «что выбрано · ›», нажатие раскрывает. */
@Composable
private fun DoneRow(title: String, value: String, onClick: () -> Unit) {
    val p = LocalPal.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .padding(horizontal = if (isBrass) 0.dp else 20.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(p.surface)
            .border(1.dp, p.outlineVar, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = p.text, maxLines = 1, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = p.accentText, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        Text(" ›", fontSize = 15.sp, color = p.accent)
    }
}

/** Галочка во всю строку. */
@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPal.current
    Row(
        Modifier.padding(top = 10.dp).fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(5.dp)
        Box(
            Modifier.size(20.dp).clip(shape).background(if (checked) p.primary else Color.Transparent)
                .border(1.5.dp, if (checked) p.primary else p.outline, shape),
            contentAlignment = Alignment.Center,
        ) { if (checked) Text("✓", fontSize = 13.sp, color = p.onPrimary) }
        Text(label, fontSize = 15.sp, color = p.text, modifier = Modifier.padding(start = 10.dp))
    }
}

/** Время: «--:--» пунктиром, пока не задано; нажатие открывает барабан. */
@Composable
private fun TimeField(label: String, minutes: Int?, onClick: () -> Unit) {
    val p = LocalPal.current
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = p.text, modifier = Modifier.weight(1f))
        val shape = RoundedCornerShape(8.dp)
        Box(
            Modifier.clip(shape)
                .then(
                    if (minutes == null) Modifier.drawBehind {
                        drawRoundRect(
                            p.accent, cornerRadius = CornerRadius(8.dp.toPx()),
                            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                        )
                    } else Modifier.border(1.dp, p.outline, shape)
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                minutes?.let { hm(it) } ?: "--:--", fontFamily = displayFont(), fontSize = 18.sp,
                color = if (minutes == null) p.accentText else p.text,
            )
        }
    }
}

/** Окно ▦: календарь и время события — по умолчанию не задано («--:--»), нажать, чтобы поставить. */
@Composable
private fun EventDateDialog(
    date: LocalDate,
    time: java.time.LocalTime?,
    today: LocalDate,
    marked: Set<LocalDate>,
    onDone: (LocalDate, java.time.LocalTime?) -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalPal.current
    var d by remember { mutableStateOf(maxOf(date, today)) }
    var t by remember { mutableStateOf(time ?: NOON_TIME) }
    var month by remember { mutableStateOf(YearMonth.from(d)) }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(20.dp), color = p.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                BlockLabel("Дата события", p.accentText)
                MonthCalendar(month, { month = it }, d, today, marked) { d = it }
                Text("Время события", fontSize = 13.sp, color = p.textSub, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                TimeWheels(
                    hour = t.hour, minute = t.minute, rows = 3,
                    onHour = { t = java.time.LocalTime.of(it, t.minute) },
                    onMinute = { t = java.time.LocalTime.of(t.hour, it) },
                )
                MainButton("Готово", Modifier.fillMaxWidth().padding(top = 10.dp)) { onDone(d, t) }
            }
        }
    }
}

/** «+ Своё»: число и единица — минуты, часы или дни до события. */
@Composable
private fun CustomBeforeDialog(onAdd: (Int, Int) -> Unit, onClose: () -> Unit) {
    val p = LocalPal.current
    var n by remember { mutableIntStateOf(1) }
    var unit by remember { mutableIntStateOf(2) }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(20.dp), color = p.surface) {
            Column(Modifier.padding(16.dp)) {
                BlockLabel("Напомнить заранее за", p.accentText)
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    LinkButton("−") { if (n > 1) n-- }
                    Text("$n", fontFamily = displayFont(), fontSize = 34.sp, color = p.text, modifier = Modifier.padding(horizontal = 24.dp))
                    LinkButton("+") { n++ }
                }
                Segmented(listOf("минут", "часов", "дней"), unit) { unit = it }
                MainButton("Добавить", Modifier.fillMaxWidth().padding(top = 12.dp)) { onAdd(n, unit) }
            }
        }
    }
}

/** Окно с барабаном для времени напоминаний. */
@Composable
private fun TimeDialog(title: String, minutes: Int, onDone: (Int) -> Unit, onClose: () -> Unit) {
    val p = LocalPal.current
    var h by remember { mutableIntStateOf(minutes / 60) }
    var m by remember { mutableIntStateOf(minutes % 60) }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(20.dp), color = p.surface) {
            Column(Modifier.padding(16.dp)) {
                BlockLabel(title, p.accentText)
                Spacer(Modifier.height(8.dp))
                TimeWheels(hour = h, minute = m, onHour = { h = it }, onMinute = { m = it })
                MainButton("Готово", Modifier.fillMaxWidth().padding(top = 10.dp)) { onDone(h * 60 + m) }
            }
        }
    }
}

/** Итог — готовое напоминание: название, событие, все срабатывания (выходные цветом), способ. */
@Composable
private fun FinalCard(icon: String?, title: String, event: String?, plan: List<String>, weekend: List<Boolean>, how: String) {
    val p = LocalPal.current
    val brass = isBrass
    Column(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (brass) p.primary else p.surfaceVar)
            .padding(14.dp)
    ) {
        val main = if (brass) p.onPrimary else p.text
        val sub = if (brass) p.onPrimarySub else p.textSub
        val hi = if (brass) p.linkOnPrimary else p.accentText
        Row(verticalAlignment = Alignment.CenterVertically) {
            PicBadge(icon, 34.dp)
            Text(title, fontFamily = displayFont(), fontSize = 17.sp, color = main, modifier = Modifier.padding(start = 10.dp))
        }
        event?.let { Text("событие: $it", fontSize = 12.5.sp, color = sub, modifier = Modifier.padding(top = 4.dp)) }
        Text(
            if (plan.isEmpty()) AnnotatedString("ни одного напоминания впереди") else buildAnnotatedString {
                plan.forEachIndexed { i, s ->
                    if (i > 0) append(" · ")
                    withStyle(SpanStyle(color = if (weekend[i]) (if (brass) p.weekendOnPrimary else p.weekend) else hi)) { append(s) }
                }
            },
            fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp),
        )
        Text(how, fontSize = 12.5.sp, color = sub, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Вариант «Когда напомнить»: радио, название и строка-пояснение; выбранный — на основном цвете. */
@Composable
private fun ModeOption(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPal.current
    val shape = RoundedCornerShape(if (isBrass) 10.dp else 8.dp)
    Row(
        Modifier
            .padding(bottom = 6.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) p.primary else if (isBrass) p.surface else p.surfaceVar)
            .then(if (isBrass && !selected) Modifier.border(1.dp, p.outline, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp).clip(CircleShape).border(1.5.dp, if (selected) p.onPrimary else p.accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(if (isBrass) p.accent else p.onPrimary)) }
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (selected) p.onPrimary else p.text)
            Text(sub, fontSize = 12.sp, color = if (selected) p.onPrimarySub else p.textSub)
        }
    }
}

/**
 * Меню мелодии: свой лист со списком мелодий будильника телефона, радио-отметкой и пробой на 3 с.
 * «Свой файл…» — выбор аудиофайла из памяти телефона.
 */
@Composable
private fun MelodyPicker(current: String?, onPick: (String?) -> Unit, onFile: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPal.current
    var tones by remember { mutableStateOf<List<Melody.Tone>>(emptyList()) }
    var playing by remember { mutableStateOf<String?>(null) }
    var ringtone by remember { mutableStateOf<Ringtone?>(null) }
    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { Melody.alarmTones(ctx) }
        tones = listOf(Melody.Tone(null, "Как в телефоне")) + list +
            listOfNotNull(current?.takeIf { c -> list.none { it.uri == c } }?.let { Melody.Tone(it, Melody.name(ctx, it)) })
    }
    fun stop() {
        ringtone?.stop()
        ringtone = null
        playing = null
    }
    LaunchedEffect(playing) {
        if (playing != null) {
            delay(3_000)
            stop()
        }
    }
    DisposableEffect(Unit) { onDispose { ringtone?.stop() } }

    Dialog(onDismissRequest = {
        stop()
        onClose()
    }) {
        Surface(shape = RoundedCornerShape(20.dp), color = p.surface) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(
                    "Мелодия", fontFamily = displayFont(), fontSize = 20.sp,
                    fontWeight = if (isBrass) FontWeight.Normal else FontWeight.Medium, color = p.text,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
                    items(tones, key = { it.uri ?: "default" }) { t ->
                        val sel = t.uri == current
                        val key = t.uri ?: "default"
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                .background(if (sel) (if (isBrass) p.surfaceVar else p.activeBg) else Color.Transparent)
                                .clickable { onPick(t.uri) }
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(18.dp).clip(CircleShape).border(1.5.dp, p.accent, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { if (sel) Box(Modifier.size(8.dp).clip(CircleShape).background(p.accent)) }
                            Text(t.name, fontSize = 15.sp, color = p.text, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 14.dp))
                            LinkButton(if (playing == key) "■" else "▶") {
                                if (playing == key) stop() else {
                                    stop()
                                    val uri = t.uri?.let { Uri.parse(it) } ?: Settings.System.DEFAULT_ALARM_ALERT_URI
                                    ringtone = RingtoneManager.getRingtone(ctx, uri)?.apply {
                                        audioAttributes = AudioAttributes.Builder()
                                            .setUsage(AudioAttributes.USAGE_ALARM)
                                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                                        play()
                                    }
                                    playing = key
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                        stop()
                        onFile()
                    }.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Text("Свой файл…", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = p.accentText) }
                MainButton("Готово", Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    stop()
                    onClose()
                }
            }
        }
    }
}
