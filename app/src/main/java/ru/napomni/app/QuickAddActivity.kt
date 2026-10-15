package ru.napomni.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
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

@Composable
private fun Wizard(edit: Reminder?, preset: String?, day: LocalDate?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
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
    // Неделя считается от дня, когда открыт лист: «Сегодня», «Завтра», «Послезавтра», дальше дни недели.
    val start = remember { Fmt.local(System.currentTimeMillis(), zone).toLocalDate() }
    val week = remember(start) { (0..7).map { start.plusDays(it.toLong()) } }
    val editAt = edit?.let { Fmt.local(it.at, zone) }
    val timed = edit != null && !edit.allDay

    var step by remember { mutableIntStateOf(1) }
    var text by remember { mutableStateOf(TextFieldValue(edit?.title ?: "", TextRange(edit?.title?.length ?: 0))) }
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
    var hour by remember { mutableStateOf(if (timed) editAt?.hour else null) }
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
    val pickTone = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            res.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            res.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        if (res.resultCode == Activity.RESULT_OK && uri != null) {
            melody = if (uri == Settings.System.DEFAULT_ALARM_ALERT_URI) null else uri.toString()
        }
    }
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
        val p = TimeParser.parse(v.text, LocalDateTime.now(zone))
        parsed = p
        val key = p.copy(spans = emptyList(), title = "")
        if (key == lastKey) return
        lastKey = key
        // Новое понятое время из текста выставляет выбор; ручной выбор держится, пока текст не скажет другое.
        val m = p.minutes
        val d = p.date
        if (m != null) {
            inMin = m
        } else if (d != null) {
            inMin = null
            date = d
            month = YearMonth.from(d)
            showCal = d !in week
            p.time?.let {
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
    }

    BackHandler(enabled = step == 2) {
        step = 1
        error = null
    }

    LaunchedEffect(Unit) {
        if (edit == null) {
            delay(120)
            fr.requestFocus()
            keyboard?.show()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .4f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClose)
            .statusBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .clickable(remember { MutableInteractionSource() }, null) {},
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = cs.surface,
        ) {
            key(step) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text("Шаг $step из 2", fontSize = 12.sp, color = cs.onSurfaceVariant)
                    Text(
                        if (step == 1) "Когда событие" else "Когда напомнить",
                        fontSize = 19.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 10.dp),
                    )
                    if (step == 1) {
                        BasicTextField(
                            value = text,
                            onValueChange = ::onText,
                            textStyle = TextStyle(fontSize = 18.sp, color = cs.onSurface),
                            cursorBrush = SolidColor(cs.primary),
                            visualTransformation = Highlight(parsed.spans, cs.primary),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                val t = System.currentTimeMillis()
                                when {
                                    inMin != null -> if (!save()) focus.clearFocus()
                                    stepOneError(t) == null -> next()
                                    else -> focus.clearFocus()
                                }
                            }),
                            modifier = Modifier.fillMaxWidth().focusRequester(fr),
                            decorationBox = { inner ->
                                Column {
                                    Box {
                                        if (text.text.isEmpty()) Text("Что напомнить", fontSize = 18.sp, color = cs.onSurfaceVariant)
                                        inner()
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    HorizontalDivider(thickness = 1.5.dp, color = cs.primary)
                                }
                            },
                        )
                        if (parsed.found) Text(
                            "время понято из текста", fontSize = 12.sp, color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )

                        Label("Через")
                        Chips {
                            IN_OPTIONS.forEach { m ->
                                Chip(Fmt.dur(m), selected = inMin == m) {
                                    inMin = if (inMin == m) null else m
                                    error = null
                                }
                            }
                            // «через 45 минут» из текста — своей кнопкой.
                            inMin?.takeIf { it !in IN_OPTIONS }?.let { m -> Chip(Fmt.dur(m), selected = true) { inMin = null } }
                        }

                        Label("День")
                        WeekGrid(week, if (inMin == null) date else null, today) { d ->
                            date = d
                            inMin = null
                            showCal = false
                            month = YearMonth.from(d)
                            error = null
                        }
                        val other = date?.takeIf { inMin == null && it !in week }
                        OptionRow("Другая дата", other?.let { Fmt.dayShort(it, today) } ?: if (showCal) "скрыть" else "календарь") {
                            showCal = !showCal
                            focus.clearFocus()
                        }
                        if (showCal) MonthCalendar(month, { month = it }, if (inMin == null) date else null, today, marked) { d ->
                            date = d
                            inMin = null
                            error = null
                        }

                        if (inMin == null) {
                            Label("Время события")
                            val nowLocal = Fmt.local(now, zone)
                            NumberGrid(
                                (0..23).toList(), 8, if (allDay) null else hour, { "$it" },
                                { h -> date != today || h >= nowLocal.hour },
                            ) {
                                hour = it
                                allDay = false
                                error = null
                            }
                            Spacer(Modifier.height(4.dp))
                            NumberGrid(
                                (0..55 step 5).toList(), 6,
                                if (allDay || hour == null) null else minute.takeIf { it % 5 == 0 }, { ":%02d".format(it) }, { true },
                            ) {
                                minute = it
                                allDay = false
                                error = null
                            }
                            Spacer(Modifier.height(6.dp))
                            Chips {
                                Chip("Без времени (весь день)", selected = allDay) {
                                    allDay = !allDay
                                    error = null
                                }
                            }
                        }

                        val problem = error ?: stepOneError(now)
                        val m = inMin
                        Result(
                            when {
                                m != null -> "Сработает: ${Fmt.long(now + m * MINUTE, now)}"
                                problem != null -> problem
                                else -> "Событие: ${draft(now)?.let { Fmt.event(it, now) }}"
                            },
                            bad = m == null && (error != null || problem == "Это время уже прошло" || problem == "Этот день уже прошёл"),
                        )
                        if (inMin != null) {
                            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { next() }, shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f).height(50.dp),
                                ) { Text("Настроить ›") }
                                Button(
                                    onClick = { save() }, shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(2f).height(50.dp),
                                ) { Text(if (edit != null) "Сохранить" else "Напомнить", fontSize = 16.sp) }
                            }
                        } else {
                            Button(
                                onClick = { next() }, shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(50.dp),
                            ) { Text("Далее ›", fontSize = 16.sp) }
                        }
                    } else {
                        val r0 = draft(now)
                        Surface(
                            color = cs.surfaceVariant, shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().clickable { step = 1 },
                        ) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${parsed.title.ifBlank { "Напоминание" }} · ${r0?.let { Fmt.event(it, now) } ?: ""}",
                                    fontSize = 14.sp, modifier = Modifier.weight(1f),
                                )
                                Text("‹ изменить", fontSize = 13.sp, color = cs.primary)
                            }
                        }

                        if (inMin == null && r0 != null) {
                            Label("В какие дни (можно несколько)")
                            Chips {
                                DAY_OPTIONS.forEach { d ->
                                    val ok = r0.dayFits(d) && r0.dayAt(d) > now
                                    Chip(Fmt.dayLabel(d), selected = ok && d in days, enabled = ok) {
                                        days = if (d in days) days - d else days + d
                                        error = null
                                    }
                                }
                            }
                            if (DAY_OPTIONS.any { !(r0.dayFits(it) && r0.dayAt(it) > now) }) Text(
                                "бледные — уже прошли или позже самого события",
                                fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                            )
                            Label("Во сколько")
                            NumberGrid((0..23).toList(), 8, dayHour, { "$it" }, { true }) { dayHour = it }
                            Spacer(Modifier.height(4.dp))
                            NumberGrid((0..55 step 5).toList(), 6, dayMinute.takeIf { it % 5 == 0 }, { ":%02d".format(it) }, { true }) {
                                dayMinute = it
                            }
                            if (!allDay) {
                                Label("И перед самим событием")
                                Chips {
                                    BEFORE_OPTIONS.forEach { b ->
                                        val ok = r0.at - b * MINUTE > now
                                        Chip(Fmt.before(b), selected = ok && b in before, enabled = ok) {
                                            before = if (b in before) before - b else before + b
                                        }
                                    }
                                }
                            }
                            val plan = r0.triggers().filter { it.time > now }
                            Result(
                                if (plan.isEmpty()) "Ни одного напоминания — выберите день" else "Напомню: " +
                                    plan.joinToString(" · ") { Fmt.compact(it.time, now) + if (it.kind == Kind.EVENT) " (событие)" else "" },
                                bad = plan.isEmpty(),
                            )
                        }

                        Label("Как напомнить")
                        Chips {
                            Alert.entries.forEach { a -> Chip(a.label, selected = alert == a) { alert = a } }
                        }
                        Spacer(Modifier.height(4.dp))
                        if (alert == Alert.ALARM) {
                            Box {
                                OptionRow("Мелодия", melodyName) { melodyMenu = true }
                                DropdownMenu(expanded = melodyMenu, onDismissRequest = { melodyMenu = false }) {
                                    DropdownMenuItem(text = { Text("Звуки телефона") }, onClick = {
                                        melodyMenu = false
                                        pickTone.launch(
                                            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Мелодия будильника")
                                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                                .putExtra(
                                                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                                    melody?.let { Uri.parse(it) } ?: Settings.System.DEFAULT_ALARM_ALERT_URI,
                                                )
                                        )
                                    })
                                    DropdownMenuItem(text = { Text("Свой файл…") }, onClick = {
                                        melodyMenu = false
                                        pickFile.launch(arrayOf("audio/*"))
                                    })
                                }
                            }
                            if (!Notifier.fullScreenAllowed(ctx)) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Без разрешения окно будильника не появится поверх блокировки — будет только мелодия и уведомление.",
                                    fontSize = 12.sp, color = cs.error, modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    ctx.startActivity(
                                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))
                                    )
                                }) { Text("Разрешить") }
                            }
                        }
                        if (alert != Alert.SILENT) Toggle("Повторять каждые $NAG_MIN мин, пока не отвечу", repeat) { repeat = it }
                        if (alert == Alert.SOUND || alert == Alert.ALARM) Toggle("Читать вслух", speak) { speak = it }
                        if (alert != Alert.SILENT) Toggle("Вспышка", flash) { flash = it }
                        if (alert == Alert.ALARM && inMin == null) Text(
                            "заранее придёт обычным уведомлением со звуком",
                            fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                        )
                        error?.let { Text(it, fontSize = 14.sp, color = cs.error, modifier = Modifier.padding(top = 8.dp)) }

                        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    step = 1
                                    error = null
                                },
                                shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(50.dp),
                            ) { Text("‹ Назад") }
                            Button(
                                onClick = { save() }, shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(2f).height(50.dp),
                            ) { Text(if (edit != null) "Сохранить" else "Напомнить", fontSize = 16.sp) }
                        }
                        if (edit != null) TextButton(
                            onClick = {
                                Store.remove(ctx, listOf(edit.id))
                                Notifier.cancel(ctx, edit.id)
                                AlarmService.stop(ctx, edit.id)
                                Scheduler.run(ctx)
                                onClose()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Удалить напоминание", color = cs.error) }
                    }
                }
            }
        }
    }
}

/** Итоговая строка шага: что получится, или что мешает. */
@Composable
private fun Result(text: String, bad: Boolean) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceVariant, shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Text(
            text, fontSize = 14.sp, color = if (bad) cs.error else cs.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}
