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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
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
        setContent { AppTheme { Home(resumes.intValue) } }
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }
}

@Composable
private fun Home(resumes: Int) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
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
    val batteryOk = remember(resumes) {
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }

    Scaffold(
        containerColor = cs.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { QuickAddActivity.open(ctx) },
                containerColor = cs.primary, contentColor = cs.onPrimary,
            ) { Icon(Icons.Default.Add, contentDescription = "Новое напоминание") }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item {
                Text(
                    "Напоминания", fontSize = 24.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            if (!notifOk) item {
                Banner(
                    "Уведомления выключены",
                    "Без них напоминания не появятся на экране.",
                    "Включить уведомления",
                ) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    )
                }
            }
            if (!exactOk && Build.VERSION.SDK_INT >= 31) item {
                Banner(
                    "Нет разрешения на точное время",
                    "Напоминания будут приходить с опозданием.",
                    "Разрешить точное время",
                ) {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                }
            }
            if (!batteryOk) item {
                Banner(
                    "Телефон может усыплять приложение",
                    "Особенно Realme и Xiaomi: напоминание придёт позже или не придёт. Разрешите работу без ограничений — заряд это почти не тратит.",
                    "Разрешить",
                ) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    )
                }
            }
            if (active.isEmpty()) item {
                Text(
                    "Будущих напоминаний нет. Нажмите «+» здесь или на виджете.",
                    fontSize = 15.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            groups.forEach { (title, list) ->
                item(key = "g$title") { Label(title) }
                items(list, key = { it.id }) { r ->
                    ReminderRow(r, now, past = false, timeText = if (title == "Позже") Fmt.short(r.shownAt(now), now) else Fmt.hm(Fmt.local(r.shownAt(now), zone))) {
                        QuickAddActivity.open(ctx, r.id)
                    }
                }
            }
            if (past.isNotEmpty()) {
                item(key = "past") {
                    Column(Modifier.padding(top = 8.dp)) {
                        HorizontalDivider(color = cs.outline.copy(alpha = .3f))
                        Row(
                            Modifier.fillMaxWidth().clickable { showPast = !showPast }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Прошедшие (${past.size})", fontSize = 15.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Icon(
                                if (showPast) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null, tint = cs.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (showPast) {
                    items(past, key = { "p${it.id}" }) { r ->
                        ReminderRow(r, now, past = true, timeText = Fmt.short(r.at, now)) { QuickAddActivity.open(ctx, r.id) }
                    }
                    item(key = "clear") {
                        TextButton(onClick = { confirmClear = true }) { Text("Очистить прошедшие") }
                    }
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Удалить прошедшие?") },
        text = { Text("Из списка исчезнут ${past.size} шт. Будущие напоминания останутся.") },
        confirmButton = {
            TextButton(onClick = {
                past.forEach { Notifier.cancel(ctx, it.id) }
                Store.remove(ctx, past.map { it.id })
                Widget.refresh(ctx)
                confirmClear = false
            }) { Text("Удалить") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
    )
}

@Composable
private fun Banner(title: String, text: String, action: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surface, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(text, fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
            TextButton(onClick = onClick, modifier = Modifier.align(Alignment.End)) { Text(action) }
        }
    }
}

/** Строка списка: время слева, ниже названия — сколько осталось и «заранее» (✓ — уже было). */
@Composable
private fun ReminderRow(r: Reminder, now: Long, past: Boolean, timeText: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        HorizontalDivider(color = cs.outline.copy(alpha = .2f))
        Row(Modifier.padding(vertical = 10.dp)) {
            Text(timeText, fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.width(92.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    r.title, fontSize = 16.sp,
                    color = if (past) cs.onSurfaceVariant else cs.onSurface,
                    textDecoration = if (r.done) TextDecoration.LineThrough else null,
                )
                val sub = subtitle(r, now, past)
                if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, color = cs.onSurfaceVariant)
            }
        }
    }
}

private fun subtitle(r: Reminder, now: Long, past: Boolean): String {
    if (past) return if (r.done) "готово" else ""
    val parts = mutableListOf<String>()
    if (r.at <= now && r.snoozeAt != null) parts += "отложено, событие было ${Fmt.short(r.at, now)}"
    else parts += "через ${Fmt.approx(((r.at - now) / MINUTE).toInt())}"
    if (r.before.isNotEmpty() && r.at > now) {
        parts += "заранее: " + r.before.sortedDescending().joinToString(", ") {
            if (r.beforeShown(it)) "${Fmt.before(it)} ✓" else Fmt.before(it)
        }
    }
    return parts.joinToString(" · ")
}
