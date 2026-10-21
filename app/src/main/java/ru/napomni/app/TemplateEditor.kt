package ru.napomni.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** «Настройки → Заготовки»: список готовых и своих, «+ Новая заготовка». */
@Composable
fun TemplatesSection() {
    val ctx = LocalContext.current
    val p = LocalPal.current
    Templates.all(ctx)
    val list by Templates.flow.collectAsState()
    var editing by remember { mutableStateOf<Template?>(null) }
    GroupHeader("Заготовки", list.size)
    Text(
        "Полностью заполненная создаёт напоминание одним нажатием; в остальных при использовании выбирают дату и время.",
        fontSize = 12.5.sp, color = p.textSub, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(p.surface).border(1.dp, p.outlineVar, shape)) {
        list.forEachIndexed { i, t ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.outlineVar))
            Row(
                Modifier.fillMaxWidth().clickable { editing = t }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PicBadge(t.icon, 36.dp)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(t.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = p.text)
                    Text((if (t.builtIn) "готовая · " else "моя · ") + t.summary(), fontSize = 12.sp, color = p.textSub, maxLines = 1)
                }
                Text("›", fontSize = 18.sp, color = p.accent)
            }
        }
    }
    Box(
        Modifier.padding(top = 8.dp).fillMaxWidth().clip(shape).border(1.dp, p.accent, shape)
            .clickable { editing = Template(0, "") }.padding(12.dp),
        contentAlignment = Alignment.Center,
    ) { Text("+ Новая заготовка", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = p.accentText) }

    editing?.let { t ->
        TemplateEditor(t, onSave = {
            Templates.put(ctx, it)
            editing = null
        }, onDelete = {
            Templates.remove(ctx, t.id)
            editing = null
        }, onClose = { editing = null })
    }
}

/** Правка заготовки: о чём, картинка, «через» или «к дате» с «за сколько», способ. Дату и время не храним. */
@Composable
private fun TemplateEditor(t: Template, onSave: (Template) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    val p = LocalPal.current
    var title by remember { mutableStateOf(t.title) }
    var icon by remember { mutableStateOf(t.icon) }
    var quick by remember { mutableStateOf(t.inMin != null) }
    var inMin by remember { mutableStateOf(t.inMin ?: 3) }
    var days by remember { mutableStateOf(t.days.toSet()) }
    var before by remember { mutableStateOf(t.before.toSet()) }
    var way by remember { mutableStateOf(t.way) }
    var picDialog by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(20.dp), color = p.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                BlockLabel(if (t.id == 0) "Новая заготовка" else "Заготовка", p.accentText)
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PicBadge(icon ?: Pics.guess(title)) { picDialog = true }
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        BasicTextField(
                            title, { title = it },
                            textStyle = TextStyle(fontFamily = displayFont(), fontSize = 18.sp, color = p.text),
                            cursorBrush = SolidColor(p.accent), singleLine = true,
                            decorationBox = { inner ->
                                Box {
                                    if (title.isEmpty()) Text("О чём", fontSize = 18.sp, color = p.textSub.copy(alpha = .6f))
                                    inner()
                                }
                            },
                        )
                        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(1.dp).background(p.outline))
                    }
                }
                Text("Когда напомнить", fontSize = 13.sp, color = p.textSub, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                Segmented(listOf("Через — сразу", "К дате события"), if (quick) 0 else 1) { quick = it == 0 }
                Spacer(Modifier.height(8.dp))
                if (quick) Chips {
                    (IN_OPTIONS + 1440).forEach { m -> Chip(Fmt.dur(m), selected = inMin == m) { inMin = m } }
                } else {
                    Text("За сколько (по желанию; дату и время выберут при использовании)", fontSize = 12.sp, color = p.textSub)
                    Spacer(Modifier.height(6.dp))
                    Chips {
                        listOf(1, 2, 3, 7).forEach { d ->
                            Chip(Fmt.dayLabel(d), selected = d in days, check = true) { days = if (d in days) days - d else days + d }
                        }
                        BEFORE_OPTIONS.forEach { b ->
                            Chip(Fmt.before(b), selected = b in before, check = true) { before = if (b in before) before - b else before + b }
                        }
                    }
                }
                Text("Как напомнить", fontSize = 13.sp, color = p.textSub, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                WaySection(way, "как в телефоне", onMelody = {}) { way = it }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (t.id != 0) LinkButton("Удалить", color = p.error) { onDelete() }
                    MainButton("Сохранить", Modifier.weight(1f), enabled = title.isNotBlank()) {
                        if (title.isNotBlank()) onSave(
                            t.copy(
                                title = title.trim(), icon = icon ?: Pics.guess(title),
                                inMin = if (quick) inMin else null,
                                days = if (quick) emptyList() else days.sorted(),
                                before = if (quick) emptyList() else before.sorted(),
                                way = way,
                            )
                        )
                    }
                }
            }
        }
    }
    if (picDialog) PicPicker(icon ?: Pics.guess(title), onPick = {
        icon = it
        picDialog = false
    }, onClose = { picDialog = false })
}
