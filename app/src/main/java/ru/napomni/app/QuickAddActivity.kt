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
import androidx.compose.foundation.background
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

/** Блоки мастера по порядку заполнения: после выбора в одном подсвечивается следующий. */
private enum class B { TEXT, WHEN, GO1, QUICK, DAY, NOTIFY, DATES, DAYTIME, HOW, GO2 }

/** Как напомнить по времени — выбор шага 1. */
private enum class Mode(val title: String, val sub: String) {
    IN("Через", "15 мин, 1 ч, 3 ч… от сейчас"),
    DAY("В день события", "выбрать день — и, если нужно, заранее"),
    DATES("В конкретные дни", "отметить несколько любых дней"),
}

/** «Через» на шаге 2 — до суток. */
private val QUICK_OPTIONS = IN_OPTIONS + 1440

private fun hm(h: Int, m: Int) = "$h:%02d".format(m)

/** Прокрутка к следующему блоку — кривая и длительность от дизайнера. */
private val SCROLL_SPEC = tween<Float>(420, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))

@Composable
private fun Wizard(edit: Reminder?, preset: String?, day: LocalDate?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPal.current
    val brass = isBrass
    val zone = remember { ZoneId.systemDefault() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val fr = remember { FocusRequester() }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    val today = Fmt.local(now, zone).toLocalDate()
    // Неделя считается от дня, когда открыт лист: «Сегодня», «Завтра», «Послезавтра», дальше дни недели.
    val start = remember { Fmt.local(System.currentTimeMillis(), zone).toLocalDate() }
    val week = remember(start) { (0..7).map { start.plusDays(it.toLong()) } }
    val editAt = edit?.let { Fmt.local(it.at, zone) }

    var step by remember { mutableIntStateOf(1) }
    var mode by remember {
        mutableStateOf(
            when {
                edit != null && edit.dates.isNotEmpty() -> Mode.DATES
                edit != null -> Mode.DAY
                preset == QuickAddActivity.PRESET_1H || preset == QuickAddActivity.PRESET_3H -> Mode.IN
                preset == QuickAddActivity.PRESET_DAY || preset == QuickAddActivity.PRESET_DATE -> Mode.DAY
                else -> null
            }
        )
    }
    // Новое — начинаем с текста; правка — ничего не подсвечиваем, всё уже заполнено.
    var active by remember { mutableStateOf(if (edit == null) B.TEXT else null) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    /** Верх и высота каждого блока внутри прокручиваемой колонки. */
    val pos = remember { mutableStateMapOf<B, Pair<Int, Int>>() }

    /**
     * Подсветить блок и довести до него лист: наименьший сдвиг, чтобы блок был виден целиком с полем 16 dp;
     * если не помещается — его верх на 16 dp от края. Пока палец на экране — не дёргаем.
     */
    fun go(b: B?) {
        active = b
        if (b == null) return
        scope.launch {
            delay(140)
            if (scroll.isScrollInProgress) return@launch
            val (top, h) = pos[b] ?: return@launch
            val vp = scroll.viewportSize
            val margin = with(density) { 16.dp.roundToPx() }
            val cur = scroll.value
            val target = when {
                h + 2 * margin > vp || top < cur + margin -> top - margin
                top + h > cur + vp - margin -> top + h - vp + margin
                else -> return@launch
            }.coerceIn(0, scroll.maxValue)
            scroll.animateScrollTo(target, SCROLL_SPEC)
        }
    }

    fun Modifier.at(b: B) = onGloballyPositioned { pos[b] = it.positionInParent().y.toInt() to it.size.height }

    var text by remember { mutableStateOf(TextFieldValue(edit?.title ?: "", TextRange(edit?.title?.length ?: 0))) }
    var focused by remember { mutableStateOf(false) }
    var inMin by remember {
        mutableStateOf(
            when (preset) {
                QuickAddActivity.PRESET_1H -> 60
                QuickAddActivity.PRESET_3H -> 180
                else -> null
            }
        )
    }
    var date by remember {
        mutableStateOf(
            when {
                edit != null && edit.dates.isEmpty() -> editAt?.toLocalDate()
                // Дата зашита в кнопку виджета; если виджет не успел обновиться после полуночи — не раньше сегодня.
                preset == QuickAddActivity.PRESET_DAY -> maxOf(day ?: start.plusDays(1), start)
                else -> null
            }
        )
    }
    var dates by remember { mutableStateOf(edit?.dates?.map { LocalDate.ofEpochDay(it) }?.toSet() ?: emptySet()) }
    var showCal by remember { mutableStateOf(preset == QuickAddActivity.PRESET_DATE || (date != null && date !in week)) }
    // Время события — только из текста («врач в 15:30»), по желанию. Без него событие на весь день.
    var eventTime by remember {
        mutableStateOf(if (edit != null && !edit.allDay && editAt != null) java.time.LocalTime.of(editAt.hour, editAt.minute) else null)
    }
    var month by remember { mutableStateOf(YearMonth.from(date ?: start)) }

    var days by remember { mutableStateOf(edit?.days?.toSet() ?: emptySet()) }
    var dayHour by remember { mutableIntStateOf((edit?.dayTime ?: DEFAULT_DAY_TIME) / 60) }
    var dayMinute by remember { mutableIntStateOf((edit?.dayTime ?: DEFAULT_DAY_TIME) % 60) }
    var before by remember { mutableStateOf(edit?.before?.toSet() ?: emptySet()) }

    var alert by remember { mutableStateOf(edit?.alert ?: Prefs.lastAlert(ctx)) }
    var melody by remember { mutableStateOf(if (edit != null) edit.melody else Prefs.lastMelody(ctx)) }
    var repeat by remember { mutableStateOf(edit?.repeat ?: false) }
    var speak by remember { mutableStateOf(edit?.speak ?: false) }
    var flash by remember { mutableStateOf(edit?.flash ?: false) }
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

    var error by remember { mutableStateOf<String?>(null) }
    var parsed by remember { mutableStateOf(TimeParser.parse(text.text, LocalDateTime.now(zone))) }
    var lastKey by remember { mutableStateOf(parsed.copy(spans = emptyList(), title = "")) }

    val items by Store.flow.collectAsState()
    val marked = remember(items, now) {
        items.filter { it.isActive(now) && it.id != edit?.id }.map { Fmt.local(it.at, zone).toLocalDate() }.toSet()
    }

    /** Черновик — та же модель, что сохранится; null — не хватает выбора. */
    fun draft(t: Long): Reminder? {
        val dt = dayHour * 60 + dayMinute
        return when (mode) {
            Mode.IN -> inMin?.let { Reminder(id = 0, title = "", at = t + it * MINUTE, handledUntil = t) }
            Mode.DAY -> {
                val d = date ?: return null
                val et = eventTime
                if (et == null) Reminder(
                    id = 0, title = "", at = d.atStartOfDay(zone).toInstant().toEpochMilli(), allDay = true,
                    days = days.toList(), dayTime = dt, handledUntil = t,
                ) else Reminder(
                    id = 0, title = "", at = d.atTime(et).atZone(zone).toInstant().toEpochMilli(),
                    days = days.toList(), dayTime = dt, before = before.toList(), handledUntil = t,
                )
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

    /** Что будет сохранено: только то, что ещё впереди и уместно. */
    fun cleaned(r: Reminder, t: Long): Reminder = r.copy(
        days = r.days.filter { r.dayFits(it) && r.dayAt(it) > t }.sorted(),
        before = r.before.filter { r.at - it * MINUTE > t }.sorted(),
        dates = r.dates.filter { r.dateAt(it) > t },
    )

    /** Что мешает сохранить, или null. */
    fun problem(t: Long): String? {
        val r = draft(t) ?: return when (mode) {
            Mode.IN -> "Выберите, через сколько"
            Mode.DAY -> "Выберите день"
            Mode.DATES -> "Отметьте хотя бы один день"
            null -> "Выберите, когда напомнить"
        }
        val c = cleaned(r, t)
        return when {
            mode == Mode.DAY && !r.allDay && r.at <= t -> "Время события уже прошло"
            mode == Mode.DAY && r.allDay && date!! < Fmt.local(t, zone).toLocalDate() -> "Этот день уже прошёл"
            c.triggers().none { it.time > t } -> if (mode == Mode.DATES) "Это время в выбранные дни уже прошло" else "Ни одного напоминания — отметьте, когда напомнить"
            else -> null
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
        val m = pr.minutes
        if (m != null) {
            mode = Mode.IN
            inMin = m
            return
        }
        eventTime = pr.time
        pr.date?.let { d ->
            if (mode != Mode.DATES) mode = Mode.DAY
            if (mode == Mode.DAY) {
                date = d
                month = YearMonth.from(d)
                showCal = d !in week
            }
        }
    }

    fun save(): Boolean {
        val t = System.currentTimeMillis()
        problem(t)?.let {
            error = it
            return false
        }
        val r0 = cleaned(draft(t) ?: return false, t)
        val title = TimeParser.parse(text.text, LocalDateTime.now(zone)).title.ifBlank { "Напоминание" }
        // Лишние для способа галочки не сохраняем: у «Тихо» нет повтора, у «Вибро» — голоса.
        val rep = repeat && alert != Alert.SILENT
        val voice = speak && (alert == Alert.SOUND || alert == Alert.ALARM)
        val fl = flash && alert != Alert.SILENT
        val mel = if (alert == Alert.ALARM) melody else null
        if (alert == Alert.ALARM) Prefs.setLastMelody(ctx, melody)
        Prefs.setLastAlert(ctx, alert)
        val saved = r0.copy(title = title, alert = alert, melody = mel, repeat = rep, speak = voice, flash = fl)
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

    /** Первый блок шага 2, который надо заполнить. */
    fun firstOfStep2(): B = when (mode) {
        Mode.IN -> if (inMin == null) B.QUICK else B.HOW
        Mode.DAY -> if (date == null) B.DAY else B.NOTIFY
        Mode.DATES -> B.DATES
        null -> B.WHEN
    }

    fun next() {
        if (mode == null) {
            error = "Выберите, когда напомнить"
            go(B.WHEN)
            return
        }
        error = null
        // У события без времени без напоминания ничего не сработает — сразу предлагаем «в день события».
        if (mode == Mode.DAY && eventTime == null && days.isEmpty()) days = setOf(0)
        focus.clearFocus()
        step = 2
        scope.launch { scroll.scrollTo(0) }
        go(firstOfStep2())
    }

    fun back() {
        step = 1
        error = null
        scope.launch { scroll.scrollTo(0) }
        go(B.GO1)
    }

    BackHandler(enabled = step == 2) { back() }

    LaunchedEffect(Unit) {
        if (edit == null) {
            delay(120)
            fr.requestFocus()
            keyboard?.show()
        }
    }

    fun state(b: B, filled: Boolean) = when {
        active == b -> BlockState.ACTIVE
        filled -> BlockState.DONE
        else -> BlockState.IDLE
    }

    // В «латуни» блоки — карточки на бумаге с полями листа; в «изумруде» секции во всю ширину.
    val side = if (brass) 4.dp else 20.dp
    val title2 = when (mode) {
        Mode.IN -> "Через сколько"
        Mode.DAY -> "В день события"
        Mode.DATES -> "В конкретные дни"
        null -> "Когда напомнить"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .45f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClose)
            .statusBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
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
            ) {
                SheetHeader(step, if (step == 1) "Что напомнить" else title2, onClose, Modifier.padding(horizontal = side))
                if (step == 1) {
                    // --- О чём
                    StepBlock("О чём", state(B.TEXT, text.text.isNotBlank()), hint = "время можно написать", modifier = Modifier.at(B.TEXT)) {
                        val field = @Composable { inner: @Composable () -> Unit ->
                            Box {
                                if (text.text.isEmpty()) Text(
                                    "Врач, кабинет 12 в 15:30", fontFamily = displayFont(),
                                    fontSize = if (brass) 19.sp else 17.sp, color = p.textSub.copy(alpha = .6f),
                                )
                                inner()
                            }
                        }
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
                            keyboardActions = KeyboardActions(onDone = {
                                focus.clearFocus()
                                if (mode == null) go(B.WHEN) else next()
                            }),
                            modifier = Modifier.fillMaxWidth().focusRequester(fr).onFocusChanged { focused = it.isFocused },
                            decorationBox = { inner ->
                                if (brass) Column {
                                    field(inner)
                                    Spacer(Modifier.height(8.dp))
                                    Box(
                                        Modifier.fillMaxWidth().height(if (focused) 1.5.dp else 1.dp)
                                            .background(if (focused) p.primary else p.outline)
                                    )
                                } else Column(
                                    Modifier.clip(RoundedCornerShape(10.dp)).background(p.surfaceVar)
                                        .padding(horizontal = 12.dp, vertical = 9.dp)
                                ) {
                                    Text("О чём", fontSize = 12.sp, color = p.textSub)
                                    field(inner)
                                }
                            },
                        )
                        Text(
                            when {
                                parsed.minutes != null -> "понято из текста: через ${Fmt.dur(parsed.minutes!!)}"
                                eventTime != null -> "время события понято из текста: ${hm(eventTime!!.hour, eventTime!!.minute)}"
                                else -> "время события можно написать в тексте — «в 15:30», «завтра в 10»; можно не писать"
                            },
                            fontSize = 12.sp, color = if (parsed.found) p.accentText else p.textSub,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }

                    // --- Когда вам напомнить
                    StepBlock(
                        "Когда вам напомнить", state(B.WHEN, mode != null), value = mode?.title, hint = "выберите",
                        modifier = Modifier.at(B.WHEN),
                    ) {
                        Mode.entries.forEach { m ->
                            ModeOption(m.title, m.sub, mode == m) {
                                mode = m
                                error = null
                                go(B.GO1)
                            }
                        }
                    }

                    Column(Modifier.padding(horizontal = side).at(B.GO1)) {
                        error?.let { Text(it, fontSize = 14.sp, color = p.error, modifier = Modifier.padding(top = 8.dp)) }
                        Spacer(Modifier.height(8.dp))
                        MainButton("Далее ›", Modifier.fillMaxWidth(), ring = active == B.GO1 && mode != null) { next() }
                    }
                } else {
                    // --- Сводка шага 1
                    Box(Modifier.padding(horizontal = side, vertical = 6.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (brass) p.primary else p.surfaceVar)
                                .clickable { back() }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    parsed.title.ifBlank { "Напоминание" }, fontFamily = displayFont(), fontSize = 16.sp,
                                    fontWeight = if (brass) FontWeight.Normal else FontWeight.Medium,
                                    color = if (brass) p.onPrimary else p.text, maxLines = 2,
                                )
                                Text(
                                    when (mode) {
                                        Mode.IN -> "через — от сейчас"
                                        Mode.DATES -> "в выбранные дни"
                                        else -> eventTime?.let { "событие в ${hm(it.hour, it.minute)}" } ?: "событие без времени"
                                    },
                                    fontSize = 13.sp, color = if (brass) p.onPrimarySub else p.textSub,
                                )
                            }
                            Text("‹ изменить", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (brass) p.linkOnPrimary else p.accentText)
                        }
                    }

                    when (mode) {
                        Mode.IN -> StepBlock(
                            "Через", state(B.QUICK, inMin != null), value = inMin?.let { Fmt.dur(it) }, hint = "выберите",
                            modifier = Modifier.at(B.QUICK),
                        ) {
                            Chips {
                                QUICK_OPTIONS.forEach { m ->
                                    Chip(Fmt.dur(m), selected = inMin == m) {
                                        inMin = m
                                        error = null
                                        go(B.HOW)
                                    }
                                }
                                // «через 45 минут» из текста — своей кнопкой.
                                inMin?.takeIf { it !in QUICK_OPTIONS }?.let { m -> Chip(Fmt.dur(m), selected = true) {} }
                            }
                        }

                        Mode.DAY -> {
                            StepBlock(
                                "День события", state(B.DAY, date != null),
                                value = date?.let { Fmt.dayShort(it, today) }, hint = "выберите день", modifier = Modifier.at(B.DAY),
                            ) {
                                WeekGrid(week, date, today) { d ->
                                    date = d
                                    month = YearMonth.from(d)
                                    showCal = false
                                    error = null
                                    go(B.NOTIFY)
                                }
                                val other = date?.takeIf { it !in week }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        other?.let { "Выбрано: ${Fmt.dayShort(it, today)}" } ?: "",
                                        fontSize = 14.sp, color = p.text, modifier = Modifier.weight(1f),
                                    )
                                    LinkButton(if (showCal) "Скрыть календарь" else "Другая дата ›") {
                                        showCal = !showCal
                                        if (showCal) go(B.DAY)
                                    }
                                }
                                if (showCal) MonthCalendar(month, { month = it }, date, today, marked) { d ->
                                    date = d
                                    error = null
                                    go(B.NOTIFY)
                                }
                            }
                            val r0 = draft(now)
                            if (r0 != null) {
                                StepBlock(
                                    "Напомнить", state(B.NOTIFY, days.isNotEmpty() || before.isNotEmpty()),
                                    value = "выбрано ${days.size + if (eventTime != null) before.size else 0}", hint = "можно несколько",
                                    modifier = Modifier.at(B.NOTIFY),
                                ) {
                                    Chips {
                                        (0..3).forEach { d ->
                                            val ok = r0.dayFits(d) && r0.dayAt(d) > now
                                            Chip(Fmt.dayLabel(d), selected = ok && d in days, enabled = ok, check = true) {
                                                days = if (d in days) days - d else days + d
                                                error = null
                                                go(B.DAYTIME)
                                            }
                                        }
                                        if (eventTime != null) BEFORE_OPTIONS.forEach { b ->
                                            val ok = r0.at - b * MINUTE > now
                                            Chip(Fmt.before(b), selected = ok && b in before, enabled = ok, check = true) {
                                                before = if (b in before) before - b else before + b
                                                error = null
                                            }
                                        }
                                    }
                                    Text(
                                        if (eventTime != null) "в само время события напомню всегда; «в день» и «за дни» — во время ниже"
                                        else "событие без времени: напомню в выбранные дни во время ниже",
                                        fontSize = 12.sp, color = p.textSub, modifier = Modifier.padding(top = 6.dp),
                                    )
                                }
                                if (days.isNotEmpty()) StepBlock(
                                    "Во сколько", state(B.DAYTIME, true), value = hm(dayHour, dayMinute), hint = "для дней",
                                    modifier = Modifier.at(B.DAYTIME),
                                ) {
                                    TimeWheels(
                                        hour = dayHour, minute = dayMinute, rows = 3,
                                        onHour = { dayHour = it },
                                        onMinute = {
                                            dayMinute = it
                                            go(B.HOW)
                                        },
                                    )
                                }
                            }
                        }

                        Mode.DATES -> {
                            StepBlock(
                                "Дни", state(B.DATES, dates.isNotEmpty()),
                                value = if (dates.isEmpty()) null else "выбрано ${dates.size}", hint = "отметьте несколько",
                                modifier = Modifier.at(B.DATES),
                            ) {
                                val toggle = { d: LocalDate ->
                                    dates = if (d in dates) dates - d else dates + d
                                    error = null
                                }
                                WeekGrid(week, { it in dates }, today, check = true) { toggle(it) }
                                val others = dates.filter { it !in week }.sorted()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (others.isEmpty()) "" else "Ещё: " + others.joinToString(", ") { Fmt.dm(it) },
                                        fontSize = 13.sp, color = p.text, modifier = Modifier.weight(1f),
                                    )
                                    LinkButton(if (showCal) "Скрыть календарь" else "Другие даты ›") {
                                        showCal = !showCal
                                        if (showCal) go(B.DATES)
                                    }
                                }
                                if (showCal) MonthCalendar(month, { month = it }, { it in dates }, today, marked) { toggle(it) }
                            }
                            StepBlock(
                                "Во сколько", state(B.DAYTIME, true), value = hm(dayHour, dayMinute), hint = "для всех дней",
                                modifier = Modifier.at(B.DAYTIME),
                            ) {
                                TimeWheels(
                                    hour = dayHour, minute = dayMinute, rows = 3,
                                    onHour = { dayHour = it },
                                    onMinute = {
                                        dayMinute = it
                                        go(B.HOW)
                                    },
                                )
                            }
                        }

                        null -> {}
                    }

                    StepBlock(
                        "Как напомнить", state(B.HOW, true), value = alert.label, hint = "способ",
                        modifier = Modifier.at(B.HOW),
                    ) {
                        Segmented(Alert.entries.map { it.label }, alert.ordinal) {
                            alert = Alert.entries[it]
                            go(B.GO2)
                        }
                        Spacer(Modifier.height(4.dp))
                        if (alert == Alert.ALARM) {
                            OptionRow("Мелодия", melodyName) { melodyMenu = true }
                            if (!Notifier.fullScreenAllowed(ctx)) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Без разрешения окно будильника не появится поверх блокировки — будет только мелодия и уведомление.",
                                    fontSize = 12.sp, color = p.textSub, modifier = Modifier.weight(1f),
                                )
                                LinkButton("Разрешить") {
                                    ctx.startActivity(
                                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))
                                    )
                                }
                            }
                        }
                        if (alert != Alert.SILENT) ToggleRow("Повторять каждые $NAG_MIN мин, пока не отвечу", repeat) { repeat = it }
                        if (alert == Alert.SOUND || alert == Alert.ALARM) ToggleRow("Читать вслух", speak) { speak = it }
                        if (alert != Alert.SILENT) ToggleRow("Вспышка", flash) { flash = it }
                        if (alert == Alert.ALARM && mode == Mode.DAY && eventTime != null) Text(
                            "заранее придёт обычным уведомлением со звуком",
                            fontSize = 12.sp, color = p.textSub, modifier = Modifier.padding(top = 2.dp),
                        )
                    }

                    Column(Modifier.padding(horizontal = side).at(B.GO2)) {
                        val r = draft(now)?.let { cleaned(it, now) }
                        val plan = r?.triggers()?.filter { it.time > now }.orEmpty()
                        val bad = error ?: problem(now)
                        ResultLine(
                            if (bad != null) AnnotatedString(bad) else buildAnnotatedString {
                                append("Напомню: ")
                                plan.forEachIndexed { i, t ->
                                    if (i > 0) append(" · ")
                                    val s = Fmt.compact(t.time, now) + if (t.kind == Kind.EVENT) " (событие)" else ""
                                    // Выходные — цветом и здесь.
                                    if (isWeekend(Fmt.local(t.time, zone).toLocalDate())) withStyle(SpanStyle(color = p.weekend)) { append(s) }
                                    else append(s)
                                }
                            },
                            bad = bad != null,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row {
                            SecondButton("‹ Назад", Modifier.weight(1f)) { back() }
                            MainButton(
                                if (edit != null) "Сохранить" else "Напомнить", Modifier.weight(2f), ring = active == B.GO2 && bad == null,
                            ) { save() }
                        }
                        if (edit != null) LinkButton("Удалить напоминание", Modifier.fillMaxWidth(), color = p.error) {
                            Store.remove(ctx, listOf(edit.id))
                            Notifier.cancel(ctx, edit.id)
                            AlarmService.stop(ctx, edit.id)
                            Scheduler.run(ctx)
                            onClose()
                        }
                    }
                }
            }
        }
    }

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

/** Вариант «Когда вам напомнить»: радио, название и строка-пояснение; выбранный — на основном цвете. */
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
        tones = listOf(Melody.Tone(null, "Будильник телефона")) + list +
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
