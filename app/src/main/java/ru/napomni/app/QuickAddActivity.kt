package ru.napomni.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/** Лист «новое напоминание» поверх рабочего стола (из виджета) или поверх списка (правка). */
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
        setContent { AppTheme { Sheet(edit, preset) { finish() } } }
    }

    companion object {
        const val EXTRA_PRESET = "preset"
        const val EXTRA_ID = "id"
        const val PRESET_1H = "1h"
        const val PRESET_3H = "3h"
        const val PRESET_TOMORROW = "tomorrow"
        const val PRESET_DATE = "date"

        fun open(ctx: Context, id: Int? = null) = ctx.startActivity(
            Intent(ctx, QuickAddActivity::class.java).apply { id?.let { putExtra(EXTRA_ID, it) } }
        )
    }
}

private enum class Mode { IN, AT }

/** Утро по умолчанию для «Завтра» с виджета. */
private const val MORNING = 9

@Composable
private fun Sheet(edit: Reminder?, preset: String?, onClose: () -> Unit) {
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
    val start = remember { Fmt.local(System.currentTimeMillis(), zone).toLocalDate() }
    val editAt = edit?.let { Fmt.local(it.at, zone) }

    var text by remember { mutableStateOf(TextFieldValue(edit?.title ?: "", TextRange(edit?.title?.length ?: 0))) }
    var mode by remember {
        mutableStateOf(if (edit != null || preset == QuickAddActivity.PRESET_TOMORROW || preset == QuickAddActivity.PRESET_DATE) Mode.AT else Mode.IN)
    }
    var inMin by remember {
        mutableStateOf(
            when (preset) {
                QuickAddActivity.PRESET_1H -> 60
                QuickAddActivity.PRESET_3H -> 180
                else -> null
            }
        )
    }
    var custom by remember { mutableStateOf(false) }
    var customN by remember { mutableIntStateOf(1) }
    var customUnit by remember { mutableIntStateOf(60) }
    var date by remember {
        mutableStateOf<LocalDate?>(
            when {
                editAt != null -> editAt.toLocalDate()
                preset == QuickAddActivity.PRESET_TOMORROW -> start.plusDays(1)
                preset == QuickAddActivity.PRESET_DATE -> start
                else -> null
            }
        )
    }
    var hour by remember { mutableStateOf(editAt?.hour ?: if (preset == QuickAddActivity.PRESET_TOMORROW) MORNING else null) }
    var minute by remember { mutableIntStateOf(editAt?.minute ?: 0) }
    var month by remember { mutableStateOf(YearMonth.from(date ?: start)) }
    var before by remember { mutableStateOf(edit?.before?.toSet() ?: emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }
    var parsed by remember { mutableStateOf(TimeParser.parse(text.text, LocalDateTime.now(zone))) }
    var lastKey by remember { mutableStateOf(parsed.copy(spans = emptyList(), title = "")) }

    val items by Store.flow.collectAsState()
    val marked = remember(items, now) {
        items.filter { it.isActive(now) && it.id != edit?.id }.map { Fmt.local(it.at, zone).toLocalDate() }.toSet()
    }

    fun target(t: Long): Long? = when (mode) {
        Mode.IN -> inMin?.let { t + it * MINUTE }
        Mode.AT -> {
            val d = date
            val h = hour
            if (d != null && h != null) d.atTime(h, minute).atZone(zone).toInstant().toEpochMilli() else null
        }
    }

    fun setCustom(min: Int) {
        customUnit = when {
            min % 1440 == 0 -> 1440
            min % 60 == 0 -> 60
            else -> 1
        }
        customN = min / customUnit
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
            mode = Mode.IN
            inMin = m
            custom = m !in IN_OPTIONS
            if (custom) setCustom(m)
        } else if (d != null) {
            mode = Mode.AT
            date = d
            month = YearMonth.from(d)
            p.time?.let {
                hour = it.hour
                minute = it.minute
            }
        }
    }

    fun save(): Boolean {
        val t = System.currentTimeMillis()
        val at = target(t)
        if (at == null) {
            error = if (mode == Mode.AT && date != null) "Выберите час" else "Выберите, когда напомнить"
            return false
        }
        if (at <= t) {
            error = "Это время уже прошло"
            return false
        }
        val title = TimeParser.parse(text.text, LocalDateTime.now(zone)).title.ifBlank { "Напоминание" }
        val bf = before.filter { at - it * MINUTE > t }.sorted()
        if (edit != null) {
            Store.edit(ctx, edit.id) { it.copy(title = title, at = at, before = bf, snoozeAt = null, handledUntil = t, done = false) }
            Notifier.cancel(ctx, edit.id)
        } else {
            Store.add(ctx) { id -> Reminder(id = id, title = title, at = at, before = bf, handledUntil = t) }
        }
        Scheduler.run(ctx)
        Toast.makeText(ctx, "Напомню ${Fmt.long(at, t)}", Toast.LENGTH_SHORT).show()
        onClose()
        return true
    }

    LaunchedEffect(Unit) {
        if (edit == null) {
            delay(120)
            fr.requestFocus()
            keyboard?.show()
        }
    }

    val tgt = target(now)
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
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = ::onText,
                    textStyle = TextStyle(fontSize = 18.sp, color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    visualTransformation = Highlight(parsed.spans, cs.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!save()) focus.clearFocus() }),
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
                Spacer(Modifier.height(12.dp))
                Segmented(listOf("Через", "Дата и время"), mode.ordinal) {
                    error = null
                    mode = Mode.entries[it]
                    if (mode == Mode.AT) {
                        if (date == null) {
                            date = today
                            month = YearMonth.from(today)
                        }
                        // Календарю нужно место — убираем клавиатуру.
                        focus.clearFocus()
                    }
                }

                if (mode == Mode.IN) {
                    Spacer(Modifier.height(12.dp))
                    Chips {
                        IN_OPTIONS.forEach { m ->
                            Chip(Fmt.dur(m), selected = !custom && inMin == m) {
                                custom = false
                                inMin = m
                                error = null
                            }
                        }
                        Chip("Другое…", selected = custom) {
                            custom = true
                            inMin = customN * customUnit
                            error = null
                        }
                    }
                    if (custom) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            val step = if (customUnit == 1) 5 else 1
                            TextButton(onClick = {
                                customN = maxOf(step, customN - step)
                                inMin = customN * customUnit
                            }) { Text("−", fontSize = 22.sp) }
                            Text("$customN", fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.width(44.dp))
                            TextButton(onClick = {
                                customN += step
                                inMin = customN * customUnit
                            }) { Text("+", fontSize = 22.sp) }
                            Spacer(Modifier.width(8.dp))
                            Chips {
                                listOf(1 to "мин", 60 to "ч", 1440 to "дн").forEach { (u, l) ->
                                    Chip(l, selected = customUnit == u) {
                                        customUnit = u
                                        inMin = customN * u
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.height(4.dp))
                    MonthCalendar(month, { month = it }, date, today, marked) {
                        date = it
                        error = null
                    }
                    Label("Час")
                    val nowLocal = Fmt.local(now, zone)
                    NumberGrid((0..23).toList(), 8, hour, { "$it" }, { h -> date != today || h >= nowLocal.hour }) {
                        hour = it
                        error = null
                    }
                    Label("Минуты")
                    NumberGrid((0..55 step 5).toList(), 6, minute.takeIf { it % 5 == 0 }, { "%02d".format(it) }, { true }) {
                        minute = it
                        error = null
                    }
                }

                val bad = error != null || (tgt != null && tgt <= now)
                Surface(
                    color = cs.surfaceVariant, shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Text(
                        when {
                            error != null -> error!!
                            tgt == null -> if (mode == Mode.AT) "Выберите час" else "Выберите, когда напомнить"
                            tgt <= now -> "Это время уже прошло"
                            else -> "Сработает: ${Fmt.long(tgt, now)}"
                        },
                        fontSize = 14.sp,
                        color = if (bad) cs.error else cs.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }

                Label("Напомнить заранее")
                Chips {
                    BEFORE_OPTIONS.forEach { off ->
                        val ok = tgt == null || tgt - off * MINUTE > now
                        Chip(Fmt.before(off), selected = ok && off in before, enabled = ok) {
                            before = if (off in before) before - off else before + off
                        }
                    }
                }
                val early = if (tgt == null) emptyList() else before.filter { tgt - it * MINUTE > now }.sortedDescending()
                if (early.isNotEmpty()) Text(
                    "заранее: " + early.joinToString(" · ") { Fmt.long(tgt!! - it * MINUTE, now) },
                    fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                )

                Button(
                    onClick = { save() },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(50.dp),
                ) { Text(if (edit != null) "Сохранить" else "Напомнить", fontSize = 16.sp) }
                if (edit != null) TextButton(
                    onClick = {
                        Store.remove(ctx, listOf(edit.id))
                        Notifier.cancel(ctx, edit.id)
                        Scheduler.run(ctx)
                        onClose()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Удалить напоминание", color = cs.error) }
            }
        }
    }
}
