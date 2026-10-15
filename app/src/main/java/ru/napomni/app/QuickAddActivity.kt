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
private enum class B { TEXT, QUICK, DAY, TIME, GO1, DAYS, DAYTIME, HOURS, HOW, GO2 }

/** Время события по умолчанию, когда выбран день: сегодня — следующий час, иначе утро. */
private fun defaultHour(d: LocalDate, now: LocalDateTime): Int =
    if (d == now.toLocalDate()) minOf(now.hour + 1, 23) else DEFAULT_DAY_TIME / 60

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
    val timed = edit != null && !edit.allDay

    var step by remember { mutableIntStateOf(1) }
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
                editAt != null -> editAt.toLocalDate()
                // Дата зашита в кнопку виджета; если виджет не успел обновиться после полуночи — не раньше сегодня.
                preset == QuickAddActivity.PRESET_DAY -> maxOf(day ?: start.plusDays(1), start)
                else -> null
            }
        )
    }
    var showCal by remember { mutableStateOf(preset == QuickAddActivity.PRESET_DATE || (date != null && date !in week)) }
    var allDay by remember { mutableStateOf(edit?.allDay ?: false) }
    var hour by remember {
        mutableStateOf(
            when {
                timed -> editAt?.hour
                date != null && edit == null -> defaultHour(date!!, LocalDateTime.now(zone))
                else -> null
            }
        )
    }
    var minute by remember { mutableIntStateOf(if (timed) editAt?.minute ?: 0 else 0) }
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

    /** Момент события; у «через» — от переданного «сейчас». */
    fun eventAt(t: Long): Long? {
        inMin?.let { return t + it * MINUTE }
        val d = date ?: return null
        if (allDay) return d.atStartOfDay(zone).toInstant().toEpochMilli()
        val h = hour ?: return null
        return d.atTime(h, minute).atZone(zone).toInstant().toEpochMilli()
    }

    /** Черновик для расчётов шага 2 — та же модель, что сохранится. */
    fun draft(t: Long): Reminder? {
        val at = eventAt(t) ?: return null
        val quick = inMin != null
        return Reminder(
            id = edit?.id ?: 0, title = "", at = at, allDay = !quick && allDay,
            days = if (quick) emptyList() else days.toList(),
            dayTime = dayHour * 60 + dayMinute,
            before = if (quick || allDay) emptyList() else before.toList(),
            handledUntil = t,
        )
    }

    /** Что мешает идти дальше с шага 1, или null. */
    fun stepOneError(t: Long): String? {
        if (inMin != null) return null
        val d = date ?: return "Выберите день"
        if (allDay) return if (d < Fmt.local(t, zone).toLocalDate()) "Этот день уже прошёл" else null
        val at = eventAt(t) ?: return "Выберите время или «Без времени»"
        return if (at <= t) "Это время уже прошло" else null
    }

    fun onText(v: TextFieldValue) {
        text = v
        error = null
        val pr = TimeParser.parse(v.text, LocalDateTime.now(zone))
        parsed = pr
        val key = pr.copy(spans = emptyList(), title = "")
        if (key == lastKey) return
        lastKey = key
        // Новое понятое время из текста выставляет выбор; ручной выбор держится, пока текст не скажет другое.
        val m = pr.minutes
        val d = pr.date
        if (m != null) {
            inMin = m
        } else if (d != null) {
            inMin = null
            date = d
            month = YearMonth.from(d)
            showCal = d !in week
            pr.time?.let {
                hour = it.hour
                minute = it.minute
                allDay = false
            }
        }
    }

    fun save(): Boolean {
        val t = System.currentTimeMillis()
        stepOneError(t)?.let {
            error = it
            step = 1
            return false
        }
        val r0 = draft(t) ?: return false
        // Сохраняем только то, что ещё впереди и уместно для события.
        val d = r0.days.filter { r0.dayFits(it) && r0.dayAt(it) > t }.sorted()
        val b = r0.before.filter { r0.at - it * MINUTE > t }.sorted()
        if (r0.allDay && d.isEmpty()) {
            error = "Выберите хотя бы один день: у события без времени напоминают только дни"
            step = 2
            return false
        }
        val title = TimeParser.parse(text.text, LocalDateTime.now(zone)).title.ifBlank { "Напоминание" }
        // Лишние для способа галочки не сохраняем: у «Тихо» нет повтора, у «Вибро» — голоса.
        val rep = repeat && alert != Alert.SILENT
        val voice = speak && (alert == Alert.SOUND || alert == Alert.ALARM)
        val fl = flash && alert != Alert.SILENT
        val mel = if (alert == Alert.ALARM) melody else null
        if (alert == Alert.ALARM) Prefs.setLastMelody(ctx, melody)
        Prefs.setLastAlert(ctx, alert)
        val saved = r0.copy(
            title = title, days = d, before = b, alert = alert, melody = mel, repeat = rep, speak = voice, flash = fl,
        )
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

    fun pickDay(d: LocalDate) {
        date = d
        inMin = null
        month = YearMonth.from(d)
        error = null
        if (hour == null && !allDay) {
            hour = defaultHour(d, LocalDateTime.now(zone))
            minute = 0
        }
        go(B.TIME)
    }

    fun next() {
        val t = System.currentTimeMillis()
        stepOneError(t)?.let {
            error = it
            return
        }
        error = null
        // У события без времени без дня-напоминания ничего не сработает — сразу предлагаем «в день события».
        if (inMin == null && allDay && days.isEmpty()) days = setOf(0)
        focus.clearFocus()
        step = 2
        scope.launch { scroll.scrollTo(0) }
        go(if (inMin != null) B.HOW else B.DAYS)
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
                SheetHeader(
                    step, if (step == 1) "Когда событие" else "Когда напомнить", onClose,
                    Modifier.padding(horizontal = side),
                )
                if (step == 1) {
                    // --- Что напомнить
                    StepBlock(
                        if (brass) "Что напомнить" else null, state(B.TEXT, text.text.isNotBlank()),
                        hint = "что и когда", modifier = Modifier.at(B.TEXT),
                    ) {
                        val field = @Composable { inner: @Composable () -> Unit ->
                            Box {
                                if (text.text.isEmpty()) Text(
                                    "Забрать посылку завтра в 10", fontFamily = displayFont(),
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
                                val t = System.currentTimeMillis()
                                when {
                                    inMin != null -> if (!save()) focus.clearFocus()
                                    stepOneError(t) == null -> next()
                                    else -> {
                                        focus.clearFocus()
                                        go(if (date == null) B.DAY else B.TIME)
                                    }
                                }
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
                                    Text("Что напомнить", fontSize = 12.sp, color = p.textSub)
                                    field(inner)
                                }
                            },
                        )
                        if (parsed.found) Text(
                            "время понято из текста", fontSize = 12.sp, color = p.accentText,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }

                    // --- Через
                    StepBlock(
                        "Через", state(B.QUICK, inMin != null),
                        value = inMin?.let { "через ${Fmt.dur(it)}" }, modifier = Modifier.at(B.QUICK),
                    ) {
                        Chips {
                            IN_OPTIONS.forEach { m ->
                                Chip(Fmt.dur(m), selected = inMin == m) {
                                    inMin = if (inMin == m) null else m
                                    error = null
                                    go(if (inMin != null) B.GO1 else B.DAY)
                                }
                            }
                            // «через 45 минут» из текста — своей кнопкой.
                            inMin?.takeIf { it !in IN_OPTIONS }?.let { m -> Chip(Fmt.dur(m), selected = true) { inMin = null } }
                        }
                    }

                    // --- День
                    val dayChosen = inMin == null && date != null
                    StepBlock(
                        "День", state(B.DAY, dayChosen),
                        value = date?.takeIf { dayChosen }?.let { Fmt.dayShort(it, today) }, hint = "выберите день",
                        modifier = Modifier.at(B.DAY),
                    ) {
                        WeekGrid(week, if (inMin == null) date else null, today) { d ->
                            showCal = false
                            pickDay(d)
                        }
                        val other = date?.takeIf { inMin == null && it !in week }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                other?.let { "Другая дата: ${Fmt.dayShort(it, today)}" } ?: "",
                                fontSize = 14.sp, color = p.text, modifier = Modifier.weight(1f),
                            )
                            LinkButton(if (showCal) "Скрыть календарь" else "Другая дата ›") {
                                showCal = !showCal
                                focus.clearFocus()
                                if (showCal) go(B.DAY)
                            }
                        }
                        if (showCal) MonthCalendar(month, { month = it }, if (inMin == null) date else null, today, marked) { d ->
                            pickDay(d)
                        }
                    }

                    // --- Время события
                    if (inMin == null) {
                        val h = hour
                        StepBlock(
                            "Время события", state(B.TIME, dayChosen && (allDay || h != null)),
                            value = if (allDay) "весь день" else h?.let { hm(it, minute) }, hint = "выберите время",
                            modifier = Modifier.at(B.TIME),
                        ) {
                            TimeWheels(
                                hour = h ?: DEFAULT_DAY_TIME / 60,
                                minute = minute,
                                dimmed = allDay || h == null,
                                onHour = {
                                    // Час сменили — остаёмся: обычно сразу докручивают минуты.
                                    hour = it
                                    allDay = false
                                    error = null
                                },
                                onMinute = {
                                    if (hour == null) hour = DEFAULT_DAY_TIME / 60
                                    minute = it
                                    allDay = false
                                    error = null
                                    go(B.GO1)
                                },
                            )
                            Spacer(Modifier.height(10.dp))
                            Chips {
                                Chip("Без времени (весь день)", selected = allDay) {
                                    allDay = !allDay
                                    error = null
                                    if (allDay) go(B.GO1)
                                }
                            }
                        }
                    }

                    // --- Итог и кнопки
                    Column(Modifier.padding(horizontal = side).at(B.GO1)) {
                        val problem = error ?: stepOneError(now)
                        val m = inMin
                        ResultLine(
                            AnnotatedString(
                                when {
                                    m != null -> "Сработает: ${Fmt.long(now + m * MINUTE, now)}"
                                    problem != null -> problem
                                    else -> "Событие: ${draft(now)?.let { Fmt.event(it, now) }}"
                                }
                            ),
                            bad = m == null && (error != null || problem == "Это время уже прошло" || problem == "Этот день уже прошёл"),
                        )
                        Spacer(Modifier.height(8.dp))
                        if (inMin != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LinkButton("Настроить ›") { next() }
                                MainButton(
                                    if (edit != null) "Сохранить" else "Напомнить", Modifier.weight(1f),
                                    ring = active == B.GO1,
                                ) { save() }
                            }
                        } else {
                            MainButton("Далее ›", Modifier.fillMaxWidth(), ring = active == B.GO1 && problem == null) { next() }
                        }
                    }
                } else {
                    // --- Сводка шага 1
                    val r0 = draft(now)
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
                                    r0?.let { if (inMin != null) Fmt.long(it.at, now) else Fmt.event(it, now) } ?: "",
                                    fontSize = 13.sp, color = if (brass) p.onPrimarySub else p.textSub,
                                )
                            }
                            Text("‹ изменить", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (brass) p.linkOnPrimary else p.accentText)
                        }
                    }

                    if (inMin == null && r0 != null) {
                        StepBlock(
                            "В какие дни", state(B.DAYS, days.isNotEmpty()),
                            value = if (days.isEmpty()) null else "выбрано ${days.size}", hint = "можно несколько",
                            modifier = Modifier.at(B.DAYS),
                        ) {
                            Chips {
                                DAY_OPTIONS.forEach { d ->
                                    val ok = r0.dayFits(d) && r0.dayAt(d) > now
                                    Chip(Fmt.dayLabel(d), selected = ok && d in days, enabled = ok, check = true) {
                                        days = if (d in days) days - d else days + d
                                        error = null
                                        go(B.DAYTIME)
                                    }
                                }
                            }
                            if (DAY_OPTIONS.any { !(r0.dayFits(it) && r0.dayAt(it) > now) }) Text(
                                "бледные — уже прошли или позже самого события",
                                fontSize = 12.sp, color = p.textSub, modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        StepBlock(
                            "Во сколько", state(B.DAYTIME, true), value = hm(dayHour, dayMinute), hint = "время напоминаний",
                            modifier = Modifier.at(B.DAYTIME),
                        ) {
                            TimeWheels(
                                hour = dayHour, minute = dayMinute, rows = 3,
                                onHour = { dayHour = it },
                                onMinute = {
                                    dayMinute = it
                                    go(if (allDay) B.HOW else B.HOURS)
                                },
                            )
                        }
                        if (!allDay) StepBlock(
                            "Перед самим событием", state(B.HOURS, before.isNotEmpty()),
                            value = before.sorted().joinToString(", ") { Fmt.dur(it) }.ifEmpty { null }, hint = "по желанию",
                            modifier = Modifier.at(B.HOURS),
                        ) {
                            Chips {
                                BEFORE_OPTIONS.forEach { b ->
                                    val ok = r0.at - b * MINUTE > now
                                    Chip(Fmt.before(b), selected = ok && b in before, enabled = ok, check = true) {
                                        before = if (b in before) before - b else before + b
                                        go(B.HOW)
                                    }
                                }
                            }
                        }
                        val plan = r0.triggers().filter { it.time > now }
                        Box(Modifier.padding(horizontal = side)) {
                            ResultLine(
                                if (plan.isEmpty()) AnnotatedString("Ни одного напоминания — выберите день") else buildAnnotatedString {
                                    append("Напомню: ")
                                    plan.forEachIndexed { i, t ->
                                        if (i > 0) append(" · ")
                                        val s = Fmt.compact(t.time, now) + if (t.kind == Kind.EVENT) " (событие)" else ""
                                        // Выходные — цветом и здесь.
                                        if (isWeekend(Fmt.local(t.time, zone).toLocalDate())) withStyle(SpanStyle(color = p.weekend)) { append(s) }
                                        else append(s)
                                    }
                                },
                                bad = plan.isEmpty(),
                            )
                        }
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
                        if (alert == Alert.ALARM && inMin == null) Text(
                            "заранее придёт обычным уведомлением со звуком",
                            fontSize = 12.sp, color = p.textSub, modifier = Modifier.padding(top = 2.dp),
                        )
                    }

                    Column(Modifier.padding(horizontal = side).at(B.GO2)) {
                        error?.let { Text(it, fontSize = 14.sp, color = p.error, modifier = Modifier.padding(top = 8.dp)) }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            SecondButton("‹ Назад", Modifier.weight(1f)) { back() }
                            MainButton(
                                if (edit != null) "Сохранить" else "Напомнить", Modifier.weight(2f), ring = active == B.GO2,
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
