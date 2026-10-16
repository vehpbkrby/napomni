package ru.napomni.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    /** Растёт при каждом возврате на экран — перепроверить разрешения. */
    private val resumes = mutableIntStateOf(0)
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumes.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Догнать пропущенное (телефон был выключен) и обновить виджет.
        Scheduler.run(this)
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            AppTheme {
                var settings by remember { mutableStateOf(false) }
                BackHandler(enabled = settings) { settings = false }
                if (settings) SettingsScreen { settings = false } else Home(resumes.intValue) { settings = true }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }
}

private val WEEKDAYS_FULL = arrayOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")
private val MONTHS_GEN = arrayOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

private fun todayLine(d: LocalDate) = "${WEEKDAYS_FULL[d.dayOfWeek.value - 1]}, ${d.dayOfMonth} ${MONTHS_GEN[d.monthValue - 1]}"

@Composable
private fun Home(resumes: Int, openSettings: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPal.current
    val brass = isBrass
    val zone = remember { ZoneId.systemDefault() }
    val all by Store.flow.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(resumes) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val today = Fmt.local(now, zone).toLocalDate()
    val active = all.filter { it.isActive(now) }.sortedBy { it.shownAt(now) }
    val past = all.filter { !it.isActive(now) }.sortedByDescending { it.at }
    val groups = active.groupBy { Fmt.dayTitle(Fmt.local(it.shownAt(now), zone).toLocalDate(), today) }
    var showPast by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val notifOk = remember(resumes) { Notifier.allowed(ctx) }
    val exactOk = remember(resumes) { Scheduler.canExact(ctx) }
    val fullScreenOk = remember(resumes) { Notifier.fullScreenAllowed(ctx) }
    val batteryOk = remember(resumes) {
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }
    // В «латуни» группы — карточки с полями экрана; в «изумруде» список во всю ширину.
    val side = if (brass) 16.dp else 20.dp

    Box(Modifier.fillMaxSize().background(p.bg)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            item {
                Row(Modifier.padding(start = side, end = 8.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Overline(todayLine(today))
                        ScreenTitle("Напоминания", Modifier.padding(top = 4.dp))
                    }
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = openSettings),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Settings, contentDescription = "Настройки", tint = p.textSub) }
                }
            }
            val warns = buildList {
                if (!notifOk) add(Triple("Уведомления выключены", "Без них напоминания не появятся на экране.") {
                    ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                })
                if (!exactOk && Build.VERSION.SDK_INT >= 31) add(Triple("Нет разрешения на точное время", "Напоминания будут приходить с опозданием.") {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                })
                if (!fullScreenOk && active.any { it.alert == Alert.ALARM }) add(
                    Triple("Будильник не откроется поверх блокировки", "Мелодия зазвучит, но окно с кнопками «Готово» и «Отложить» будет только в шторке.") {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
                    }
                )
                if (!batteryOk) add(
                    Triple("Телефон может усыплять приложение", "Особенно Realme и Xiaomi: напоминание придёт позже или не придёт. Разрешите работу без ограничений — заряд это почти не тратит.") {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                    }
                )
            }
            warns.forEach { (t, s, go) -> item { Box(Modifier.padding(horizontal = side)) { WarnCard(t, s, "Разрешить", go) } } }
            if (active.isEmpty()) item {
                Text(
                    "Будущих напоминаний нет. Нажмите «+» здесь или на виджете.",
                    fontSize = 15.sp, color = p.textSub, modifier = Modifier.padding(horizontal = side, vertical = 20.dp),
                )
            }
            groups.forEach { (title, list) ->
                item(key = "g$title") { GroupHeader(title, list.size, Modifier.padding(horizontal = side)) }
                item(key = "list$title") {
                    RowGroup(brass, side) {
                        list.forEachIndexed { i, r ->
                            ReminderRow(r, now, past = false, later = title == "Позже", first = i == 0) { QuickAddActivity.open(ctx, r.id) }
                        }
                    }
                }
            }
            if (past.isNotEmpty()) {
                item(key = "past") {
                    Row(
                        Modifier.padding(horizontal = side, vertical = 14.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (brass) p.surfaceVar else Color.Transparent)
                            .clickable { showPast = !showPast }.padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Прошедшие (${past.size})", fontSize = 15.sp, color = p.textSub, modifier = Modifier.weight(1f))
                        Text(if (showPast) "⌃" else "›", fontSize = 18.sp, color = p.accent)
                    }
                }
                if (showPast) {
                    item(key = "pastlist") {
                        RowGroup(brass, side) {
                            past.forEachIndexed { i, r ->
                                ReminderRow(r, now, past = true, later = true, first = i == 0) { QuickAddActivity.open(ctx, r.id) }
                            }
                        }
                    }
                    item(key = "clear") {
                        LinkButton("Очистить прошедшие", Modifier.padding(horizontal = side - 6.dp, vertical = 6.dp)) { confirmClear = true }
                    }
                }
            }
            item { Spacer(Modifier.height(110.dp)) }
        }
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp)) {
            Fab { QuickAddActivity.open(ctx) }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        containerColor = p.surface,
        title = { Text("Удалить прошедшие?", fontFamily = displayFont(), color = p.text) },
        text = { Text("Из списка исчезнут ${past.size} шт. Будущие напоминания останутся.", color = p.textSub) },
        confirmButton = {
            LinkButton("Удалить", color = p.error) {
                past.forEach { Notifier.cancel(ctx, it.id) }
                Store.remove(ctx, past.map { it.id })
                Widget.refresh(ctx)
                confirmClear = false
            }
        },
        dismissButton = { LinkButton("Отмена") { confirmClear = false } },
    )
}

/** Группа строк: в «латуни» — карточка с разделителями, в «изумруде» — полоса на поверхности. */
@Composable
private fun RowGroup(brass: Boolean, side: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    val p = LocalPal.current
    val shape = RoundedCornerShape(12.dp)
    if (brass) Column(
        Modifier.padding(horizontal = side).fillMaxWidth().clip(shape).background(p.surface).border(1.dp, p.outlineVar, shape)
    ) { content() }
    else Column(Modifier.fillMaxWidth().background(p.surface)) { content() }
}

/** Строка списка: время слева (две строки у «Позже»: дата — цветом выходных, если выходной), справа название и подпись. */
@Composable
private fun ReminderRow(r: Reminder, now: Long, past: Boolean, later: Boolean, first: Boolean, onClick: () -> Unit) {
    val p = LocalPal.current
    val brass = isBrass
    val shown = r.shownAt(now)
    val t = Fmt.local(shown)
    val d = t.toLocalDate()
    val allDayHere = r.allDay && shown == r.at
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        if (!first) Box(
            Modifier.padding(start = if (brass) 0.dp else 20.dp).fillMaxWidth().height(1.dp).background(p.outlineVar)
        )
        Row(
            Modifier.heightIn(min = 64.dp).padding(horizontal = if (brass) 14.dp else 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.padding(end = 10.dp).graphicsLayer { alpha = if (past) .55f else 1f }) {
                PicBadge(r.icon ?: Pics.guess(r.title), 38.dp)
            }
            Column(Modifier.width(72.dp)) {
                if (later) Text(
                    "${Fmt.WEEKDAYS[d.dayOfWeek.value - 1]} ${Fmt.dm(d)}", fontSize = 12.5.sp,
                    color = if (isWeekend(d)) p.weekend else p.textSub, maxLines = 1,
                )
                if (allDayHere) Text("весь день", fontSize = 13.sp, color = p.textSub)
                else Text(
                    Fmt.hm(t), fontFamily = displayFont(), fontSize = if (brass) 18.sp else 17.sp,
                    fontWeight = if (brass) FontWeight.Normal else FontWeight.Medium,
                    color = if (past) p.textSub else p.text,
                )
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    r.title, fontSize = 15.5.sp, fontWeight = FontWeight.Medium,
                    color = if (past) p.textSub else p.text,
                    textDecoration = if (r.done) TextDecoration.LineThrough else null,
                )
                val sub = subtitle(r, now, past)
                if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, color = p.textSub, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

private fun subtitle(r: Reminder, now: Long, past: Boolean): String {
    if (past) return if (r.done) "готово" else ""
    val parts = mutableListOf<String>()
    if (r.alert != Alert.SOUND) parts += r.alert.label.lowercase()
    when {
        r.end() <= now -> parts += "отложено, событие было ${if (r.allDay) Fmt.dayShort(Fmt.local(r.at).toLocalDate()) else Fmt.short(r.at, now)}"
        r.at > now -> parts += "через ${Fmt.approx(((r.at - now) / MINUTE).toInt())}"
    }
    val early = r.early()
    if (early.isNotEmpty() && r.end() > now) {
        parts += (if (r.dates.isNotEmpty()) "дни: " else "заранее: ") + early.joinToString(", ") { if (r.shown(it)) "${Fmt.early(r, it)} ✓" else Fmt.early(r, it) }
    }
    return parts.joinToString(" · ")
}

/** Настройки: выбор оформления. Смена сразу перерисовывает приложение и виджет. */
@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPal.current
    val look by Prefs.lookFlow(ctx).collectAsState()
    Column(
        Modifier.fillMaxSize().background(p.bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 16.dp)
    ) {
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = p.text)
            }
        }
        ScreenTitle("Настройки", Modifier.padding(top = 4.dp, start = 4.dp))
        GroupHeader("Оформление", Look.entries.size)
        Look.entries.forEach { l -> LookCard(l, l == look) {
            Prefs.setLook(ctx, l)
            Widget.refresh(ctx)
        } }
        Spacer(Modifier.height(12.dp))
        TemplatesSection()
        Spacer(Modifier.height(20.dp))
        Text(
            "Шрифт PT Serif — ParaType, лицензия SIL Open Font License 1.1.",
            fontSize = 12.sp, color = p.textSub, modifier = Modifier.padding(16.dp),
        )
    }
}

/** Карточка варианта оформления: образец цветов и цифр в своём шрифте, название, идея, радио. */
@Composable
private fun LookCard(l: Look, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPal.current
    val own = palette(l, p.dark)
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.padding(vertical = 6.dp).fillMaxWidth().clip(shape).background(p.surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) p.accent else p.outlineVar, shape)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Образец: фон варианта, его основной цвет и акцент, «19:00» его шрифтом.
        Box(
            Modifier.size(76.dp, 64.dp).clip(RoundedCornerShape(10.dp)).background(own.bg).border(1.dp, own.outlineVar, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "19:00", fontFamily = if (l == Look.BRASS) PtSerif else androidx.compose.ui.text.font.FontFamily.Default,
                    fontSize = 18.sp, fontWeight = if (l == Look.BRASS) FontWeight.Normal else FontWeight.Light, color = own.text,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    listOf(own.primary, own.accent, own.weekend).forEach {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(it))
                    }
                }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(l.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = p.text)
            Text(l.idea, fontSize = 13.sp, color = p.textSub, modifier = Modifier.padding(top = 2.dp))
        }
        Box(Modifier.size(20.dp).clip(CircleShape).border(1.5.dp, p.accent, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(p.accent))
        }
    }
}
