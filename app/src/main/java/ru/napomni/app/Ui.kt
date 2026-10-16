package ru.napomni.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

// ---------- Текст ----------

/** Надстрочник: в «латуни» капсом латунным текстом, в «изумруде» обычным вторичным. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier) {
    val p = LocalPal.current
    if (isBrass) Text(
        text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.12.em,
        color = p.accentText, modifier = modifier,
    ) else Text(text, fontSize = 13.sp, color = p.textSub, modifier = modifier)
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) = Text(
    text, fontFamily = displayFont(), fontSize = 30.sp, lineHeight = 36.sp,
    fontWeight = if (isBrass) FontWeight.Bold else FontWeight.Medium,
    letterSpacing = if (isBrass) 0.em else (-0.02).em, color = LocalPal.current.text, modifier = modifier,
)

@Composable
fun StepTitle(text: String, modifier: Modifier = Modifier) = Text(
    text, fontFamily = displayFont(), fontSize = if (isBrass) 24.sp else 22.sp, lineHeight = 30.sp,
    fontWeight = if (isBrass) FontWeight.Bold else FontWeight.Medium, color = LocalPal.current.text, modifier = modifier,
)

/** Метка блока: «ВРЕМЯ СОБЫТИЯ» капсом (латунь) или «Время события» (изумруд). */
@Composable
fun BlockLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    if (isBrass) Text(
        text.uppercase(), fontSize = 11.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.09.em,
        color = color, modifier = modifier,
    ) else Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = color, modifier = modifier)
}

// ---------- Блоки мастера ----------

enum class BlockState { IDLE, ACTIVE, DONE }

/**
 * Блок шага. Латунь — бумажная карточка: активная с латунной обводкой, планкой слева, ромбом и
 * свечением; заполненная — точка и значение с ✓. Изумруд — секция во всю ширину с волосяной линией;
 * активная — мятная подложка и изумрудная полоса слева.
 */
@Composable
fun StepBlock(
    title: String?,
    state: BlockState,
    value: String? = null,
    hint: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val p = LocalPal.current
    val active = state == BlockState.ACTIVE
    val spec = tween<Color>(220)
    val bgC by animateColorAsState(if (active) p.activeBg else p.surface, spec, label = "bg")
    val edge by animateColorAsState(if (active) p.accent else p.outlineVar, spec, label = "edge")
    val labelC by animateColorAsState(
        if (active) p.accentText else if (isBrass) p.textSub else p.text, spec, label = "label",
    )
    val density = LocalDensity.current
    if (isBrass) {
        val shape = RoundedCornerShape(12.dp)
        val glow by animateDpAsState(if (active) 3.dp else 0.dp, tween(220), label = "glow")
        Box(
            modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
                .border(glow, p.accent.copy(alpha = .16f), RoundedCornerShape(15.dp))
                .padding(glow)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(bgC)
                    .border(1.dp, edge, shape)
                    .drawBehind {
                        if (active) {
                            val inset = with(density) { 14.dp.toPx() }
                            drawRect(p.accent, Offset(0f, inset), Size(with(density) { 3.dp.toPx() }, size.height - 2 * inset))
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                if (title != null) BlockHeader(title, state, value, hint, labelC)
                content()
            }
        }
    } else {
        Column(
            modifier
                .fillMaxWidth()
                .background(bgC)
                .drawBehind {
                    drawRect(p.outlineVar, Offset.Zero, Size(size.width, with(density) { 1.dp.toPx() }))
                    if (active) drawRect(p.accent, Offset.Zero, Size(with(density) { 3.dp.toPx() }, size.height))
                }
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            if (title != null) BlockHeader(title, state, value, hint, labelC)
            content()
        }
    }
}

@Composable
private fun BlockHeader(title: String, state: BlockState, value: String?, hint: String?, labelC: Color) {
    val p = LocalPal.current
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (isBrass && state == BlockState.ACTIVE) Box(
            Modifier.padding(end = 8.dp).size(7.dp).rotate(45f).background(p.accent)
        )
        if (isBrass && state == BlockState.DONE) Box(
            Modifier.padding(end = 8.dp).size(6.dp).clip(CircleShape).background(p.primary)
        )
        BlockLabel(title, labelC, Modifier.weight(1f))
        when {
            state == BlockState.ACTIVE && hint != null ->
                Text(hint, fontSize = 12.sp, color = p.accentText)
            state == BlockState.DONE && value != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    value, fontFamily = displayFont(), fontSize = if (isBrass) 14.sp else 13.sp,
                    fontWeight = if (isBrass) FontWeight.Normal else FontWeight.Medium,
                    color = if (isBrass) p.text else p.textSub, maxLines = 1,
                )
                Text(" ✓", fontSize = 13.sp, color = p.accentText)
            }
        }
    }
}

// ---------- Чипы и кнопки ----------

@Composable
fun Chip(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    weekend: Boolean = false,
    check: Boolean = false,
    onClick: () -> Unit,
) {
    val p = LocalPal.current
    val brass = isBrass
    val shape = RoundedCornerShape(if (brass) 10.dp else 8.dp)
    val density = LocalDensity.current
    val bgC = when {
        selected -> p.primary
        !enabled -> Color.Transparent
        brass -> p.surface
        weekend -> p.weekendEdge
        else -> p.surfaceVar
    }
    val fg = when {
        selected -> if (weekend) p.weekendOnPrimary else p.onPrimary
        weekend -> p.weekend
        else -> p.text
    }
    Box(
        Modifier
            .heightIn(min = 38.dp)
            .graphicsLayer { alpha = if (enabled) 1f else .42f }
            .clip(shape)
            .background(bgC)
            .then(
                when {
                    selected -> Modifier
                    !enabled && !brass -> Modifier.drawBehind {
                        val r = with(density) { 8.dp.toPx() }
                        drawRoundRect(
                            p.outlineVar, cornerRadius = CornerRadius(r),
                            style = Stroke(with(density) { 1.dp.toPx() }, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))),
                        )
                    }
                    brass -> Modifier.border(1.dp, if (weekend) p.weekendEdge else p.outline, shape)
                    else -> Modifier
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text((if (check && selected) "✓ " else "") + text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(content: @Composable () -> Unit) = FlowRow(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
) { content() }

/**
 * Основная кнопка. [ring] — «сюда дальше»: кольцо акцентом с зазором (место под кольцо держим всегда,
 * чтобы кнопка не прыгала).
 */
@Composable
fun MainButton(text: String, modifier: Modifier = Modifier, ring: Boolean = false, height: Dp = 52.dp, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPal.current
    val r = if (isBrass) 12.dp else 10.dp
    val ringC by animateColorAsState(if (ring) p.accent else Color.Transparent, tween(220), label = "ring")
    val density = LocalDensity.current
    val brass = isBrass
    Box(
        modifier
            .border(1.5.dp, ringC, RoundedCornerShape(r + 4.5.dp))
            .padding(4.5.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .graphicsLayer { alpha = if (enabled) 1f else .38f }
                .clip(RoundedCornerShape(r))
                .background(p.primary)
                .drawBehind {
                    // Внутренний блик сверху — «латунь».
                    if (brass) drawRect(Color.White.copy(alpha = .08f), Offset.Zero, Size(size.width, with(density) { 1.dp.toPx() }))
                }
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.01.em, color = p.onPrimary) }
    }
}

@Composable
fun SecondButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPal.current
    val shape = RoundedCornerShape(if (isBrass) 12.dp else 10.dp)
    Box(Modifier.padding(4.5.dp).then(modifier)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(shape)
                .background(if (isBrass) Color.Transparent else p.surfaceVar)
                .then(if (isBrass) Modifier.border(1.dp, p.outline, shape) else Modifier)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = p.text) }
    }
}

@Composable
fun LinkButton(text: String, modifier: Modifier = Modifier, color: Color = LocalPal.current.accentText, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = color) }
}

/** Круглая кнопка ✕ в шапке листа. */
@Composable
fun CloseButton(onClick: () -> Unit) {
    val p = LocalPal.current
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (isBrass) Color.Transparent else p.surfaceVar)
            .then(if (isBrass) Modifier.border(1.dp, p.outline, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text("✕", fontSize = 15.sp, color = p.textSub) }
}

/** Шапка листа-мастера: ручка, «Шаг 1 из 2», заголовок, ✕ и индикатор из двух отрезков. */
@Composable
fun SheetHeader(step: Int, title: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPal.current
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 10.dp)
                .size(36.dp, 4.dp).clip(CircleShape).background(p.outline)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Overline("Шаг $step из 2")
                StepTitle(title, Modifier.padding(top = 2.dp))
            }
            CloseButton(onClose)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..2).forEach { i ->
                Box(
                    Modifier.weight(1f).height(2.dp).clip(CircleShape)
                        .background(if (i <= step) p.accent else p.outlineVar)
                )
            }
        }
    }
}

// ---------- Неделя и календарь ----------

/**
 * Неделя крупными кнопками (4×2) от дня открытия листа: «Сегодня / чт 15», «Завтра», «Послезавтра»,
 * дальше «Вс / 18 окт». Выходные — цветом выходных.
 */
@Composable
fun WeekGrid(week: List<LocalDate>, selected: LocalDate?, today: LocalDate, onPick: (LocalDate) -> Unit) =
    WeekGrid(week, { it == selected }, today, check = false, onPick = onPick)

/** То же с выбором нескольких дней: у выбранных — ✓. */
@Composable
fun WeekGrid(week: List<LocalDate>, isSel: (LocalDate) -> Boolean, today: LocalDate, check: Boolean, onPick: (LocalDate) -> Unit) {
    val p = LocalPal.current
    val brass = isBrass
    val shape = RoundedCornerShape(if (brass) 10.dp else 8.dp)
    Column {
        week.withIndex().chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (i, d) ->
                    val sel = isSel(d)
                    val en = d >= today
                    val we = isWeekend(d)
                    val wd = Fmt.WEEKDAYS[d.dayOfWeek.value - 1]
                    // Сверху всегда день недели и число; снизу — подсказка: сегодня / завтра / послезавтра или месяц.
                    val top = "$wd ${d.dayOfMonth}"
                    val sub = when (i) {
                        0 -> "сегодня"
                        1 -> "завтра"
                        2 -> "послезавтра"
                        else -> Fmt.dm(d).substringAfter(' ')
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .height(56.dp)
                            .graphicsLayer { alpha = if (en) 1f else .42f }
                            .clip(shape)
                            .background(
                                when {
                                    sel -> p.primary
                                    brass -> p.surface
                                    we -> p.weekendEdge
                                    else -> p.surfaceVar
                                }
                            )
                            .then(
                                if (brass && !sel) Modifier.border(1.dp, if (we) p.weekendEdge else p.outline, shape) else Modifier
                            )
                            .clickable(enabled = en) { onPick(d) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            (if (check && sel) "✓ " else "") + top, fontFamily = displayFont(), fontSize = 15.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false,
                            color = when {
                                sel -> if (we) p.weekendOnPrimary else p.onPrimary
                                we -> p.weekend
                                else -> p.text
                            },
                        )
                        Text(
                            sub, fontSize = 11.sp, maxLines = 1,
                            color = when {
                                sel -> if (we) p.weekendOnPrimary else p.onPrimarySub
                                we -> p.weekend
                                else -> p.textSub
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Календарь месяца: выходные цветом, прошедшие бледные, сегодня в кольце, точка — уже есть напоминание. */
@Composable
fun MonthCalendar(
    month: YearMonth,
    onMonth: (YearMonth) -> Unit,
    selected: LocalDate?,
    today: LocalDate,
    marked: Set<LocalDate>,
    onPick: (LocalDate) -> Unit,
) = MonthCalendar(month, onMonth, { it == selected }, today, marked, onPick)

/** Календарь с выбором нескольких дней. */
@Composable
fun MonthCalendar(
    month: YearMonth,
    onMonth: (YearMonth) -> Unit,
    isSel: (LocalDate) -> Boolean,
    today: LocalDate,
    marked: Set<LocalDate>,
    onPick: (LocalDate) -> Unit,
) {
    val p = LocalPal.current
    val brass = isBrass
    Column(Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinkButton("‹", color = if (month > YearMonth.from(today)) p.accentText else p.outline) {
                if (month > YearMonth.from(today)) onMonth(month.minusMonths(1))
            }
            Text(
                "${Fmt.MONTHS[month.monthValue - 1]} ${month.year}", Modifier.weight(1f), textAlign = TextAlign.Center,
                fontFamily = displayFont(), fontSize = if (brass) 17.sp else 16.sp,
                fontWeight = if (brass) FontWeight.Normal else FontWeight.Medium, color = p.text,
            )
            LinkButton("›") { onMonth(month.plusMonths(1)) }
        }
        Row(Modifier.padding(vertical = 4.dp)) {
            Fmt.WEEKDAYS.forEachIndexed { i, w ->
                Text(
                    w, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp,
                    color = if (i >= 5) p.weekend else p.textSub,
                )
            }
        }
        val offset = month.atDay(1).dayOfWeek.value - 1
        val days = month.lengthOfMonth()
        for (r in 0 until (offset + days + 6) / 7) {
            Row {
                for (c in 0 until 7) {
                    val n = r * 7 + c - offset + 1
                    Box(Modifier.weight(1f).height(42.dp), contentAlignment = Alignment.Center) {
                        if (n in 1..days) {
                            val d = month.atDay(n)
                            DayCell(n, isSel(d), d == today, d < today, d in marked, isWeekend(d)) { onPick(d) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(n: Int, sel: Boolean, today: Boolean, past: Boolean, mark: Boolean, weekend: Boolean, onClick: () -> Unit) {
    val p = LocalPal.current
    Box(
        Modifier
            .size(38.dp)
            .graphicsLayer { alpha = if (past) .4f else 1f }
            .clip(CircleShape)
            .background(if (sel) p.primary else Color.Transparent)
            .then(if (today && !sel) Modifier.border(1.5.dp, if (isBrass) p.primary else p.text, CircleShape) else Modifier)
            .clickable(enabled = !past, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$n", fontFamily = displayFont(), fontSize = if (isBrass) 15.sp else 14.sp,
            fontWeight = if (isBrass) FontWeight.Normal else FontWeight.Medium,
            color = when {
                sel -> if (weekend) p.weekendOnPrimary else p.onPrimary
                weekend -> p.weekend
                else -> p.text
            },
        )
        if (mark) Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(4.dp).clip(CircleShape)
                .background(if (sel) p.onPrimary else p.accent)
        )
    }
}

// ---------- Барабан ----------

val WHEEL_ROW = 40.dp
private const val WHEEL_CYCLES = 400

/**
 * Барабан: прокрутка с защёлкиванием и по кругу (после 23 — 0). Выбрано то, что в полосе посередине;
 * соседние значения меньше и бледнее, края колонки затухают. [onSelect] — только когда человек сам сменил.
 */
@Composable
fun Wheel(
    values: List<Int>,
    selected: Int,
    label: (Int) -> String,
    rows: Int,
    align: Alignment,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val p = LocalPal.current
    val brass = isBrass
    val n = values.size
    val nearest = { v: Int -> values.indices.minByOrNull { abs(values[it] - v) } ?: 0 }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = n * (WHEEL_CYCLES / 2) + nearest(selected) - rows / 2)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val rowPx = with(LocalDensity.current) { WHEEL_ROW.toPx() }
    val center by remember {
        derivedStateOf { state.firstVisibleItemIndex + (if (state.firstVisibleItemScrollOffset > rowPx / 2) 1 else 0) + rows / 2 }
    }
    val current by rememberUpdatedState(selected)
    val pick by rememberUpdatedState(onSelect)

    // Остановился — сообщаем выбор. Сравниваем позиции: «42 минуты» из текста не перетираем ближайшими «40».
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { moving ->
            if (!moving && center % n != nearest(current)) pick(values[center % n])
        }
    }
    // Щелчок под пальцем на каждом значении.
    LaunchedEffect(state) {
        snapshotFlow { center }.drop(1).collect { if (state.isScrollInProgress) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
    // Значение сменили снаружи (из текста «в 19:30») — докрутить кратчайшим путём.
    LaunchedEffect(selected) {
        val want = nearest(selected)
        val have = center % n
        if (want != have && !state.isScrollInProgress) {
            var delta = want - have
            if (delta > n / 2) delta -= n
            if (delta < -n / 2) delta += n
            state.animateScrollToItem(center + delta - rows / 2)
        }
    }

    LazyColumn(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        modifier = modifier
            .height(WHEEL_ROW * rows)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen, alpha = if (dimmed) .45f else 1f)
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, .28f to Color.Black, .72f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        items(n * WHEEL_CYCLES) { i ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(WHEEL_ROW)
                    .clickable(remember { MutableInteractionSource() }, null) {
                        scope.launch { state.animateScrollToItem(i - rows / 2) }
                    }
                    .graphicsLayer {
                        // Расстояние от центра в строках — дробное, чтобы масштаб менялся плавно на ходу.
                        val info = state.layoutInfo
                        val item = info.visibleItemsInfo.firstOrNull { it.index == i }
                        val mid = (info.viewportEndOffset + info.viewportStartOffset) / 2f
                        val d = if (item == null) 2f else abs(item.offset + item.size / 2f - mid) / item.size
                        val s = if (d <= 1f) 1f - .26f * d else (.74f - .14f * (d - 1f)).coerceAtLeast(.6f)
                        scaleX = s
                        scaleY = s
                        alpha = if (d <= 1f) 1f - .45f * d else (.55f - .27f * (d - 1f)).coerceAtLeast(.28f)
                    }
                    .padding(horizontal = 6.dp),
                contentAlignment = align,
            ) {
                Text(
                    label(values[i % n]), fontFamily = displayFont(),
                    fontSize = if (brass) 28.sp else 30.sp,
                    fontWeight = if (brass) FontWeight.Normal else FontWeight.Light,
                    color = p.text, maxLines = 1,
                )
            }
        }
    }
}

/**
 * Часы : минуты. Полоса выбора во всю ширину: в «латуни» — окошко даты с двумя латунными линиями,
 * в «изумруде» — скруглённая подложка. Часы без ведущего нуля, минуты шагом 5.
 */
@Composable
fun TimeWheels(hour: Int, minute: Int, rows: Int = 5, dimmed: Boolean = false, onHour: (Int) -> Unit, onMinute: (Int) -> Unit) {
    val p = LocalPal.current
    val brass = isBrass
    val density = LocalDensity.current
    Box(Modifier.fillMaxWidth().height(WHEEL_ROW * rows), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(WHEEL_ROW)
                .then(if (brass) Modifier else Modifier.clip(RoundedCornerShape(10.dp)))
                .background(p.band)
                .drawBehind {
                    if (brass) {
                        val w = with(density) { 1.dp.toPx() }
                        drawRect(p.accent, Offset.Zero, Size(size.width, w))
                        drawRect(p.accent, Offset(0f, size.height - w), Size(size.width, w))
                    }
                }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Wheel((0..23).toList(), hour, { "$it" }, rows, Alignment.CenterEnd, dimmed, Modifier.width(76.dp), onHour)
            Text(
                ":", fontFamily = displayFont(), fontSize = if (brass) 28.sp else 30.sp,
                color = p.accent, modifier = Modifier.padding(horizontal = 6.dp).offset(y = (-2).dp),
            )
            Wheel((0..55 step 5).toList(), minute, { "%02d".format(it) }, rows, Alignment.CenterStart, dimmed, Modifier.width(76.dp), onMinute)
        }
    }
}

// ---------- Сегмент, переключатель, строки ----------

/** «Звук / Вибро / Тихо / Будильник». */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalPal.current
    if (isBrass) {
        val shape = RoundedCornerShape(10.dp)
        Row(Modifier.fillMaxWidth().height(44.dp).clip(shape).border(1.dp, p.outline, shape)) {
            options.forEachIndexed { i, s ->
                if (i > 0) Box(Modifier.width(1.dp).fillMaxSize().background(p.outline))
                Box(
                    Modifier.weight(1f).fillMaxSize().background(if (i == selected) p.primary else Color.Transparent)
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) { Text(s, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (i == selected) p.onPrimary else p.text, maxLines = 1) }
            }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(p.surfaceVar).padding(3.dp)
        ) {
            options.forEachIndexed { i, s ->
                Box(
                    Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(8.dp))
                        .background(if (i == selected) p.primary else Color.Transparent).clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) { Text(s, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (i == selected) p.onPrimary else p.text, maxLines = 1) }
            }
        }
    }
}

/** Переключатель 44×26; нажатие по всей строке. */
@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPal.current
    val on = if (isBrass) p.primary else p.accent
    val knobOn = if (isBrass) p.onPrimary else p.onAccent
    val x by animateDpAsState(if (checked) 20.dp else 2.dp, tween(180), label = "knob")
    val track by animateColorAsState(if (checked) on else Color.Transparent, tween(180), label = "track")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = p.text, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Box(
            Modifier.size(44.dp, 26.dp).clip(CircleShape).background(track)
                .then(if (checked) Modifier else Modifier.border(1.dp, p.outline, CircleShape))
        ) {
            Box(
                Modifier.offset(x = x, y = 3.dp).size(20.dp).clip(CircleShape)
                    .background(if (checked) knobOn else p.outline)
            )
        }
    }
}

/** «Мелодия — Восход ›». */
@Composable
fun OptionRow(label: String, value: String, onClick: () -> Unit) {
    val p = LocalPal.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = p.text, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp, color = p.textSub, maxLines = 1, modifier = Modifier.widthIn(max = 200.dp))
        Text("  ›", fontSize = 16.sp, color = p.accent)
    }
}

/** Итоговая строка шага: что получится, или что мешает. */
@Composable
fun ResultLine(text: AnnotatedString, bad: Boolean, modifier: Modifier = Modifier) {
    val p = LocalPal.current
    Box(
        modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(p.surfaceVar)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) { Text(text, fontSize = 14.sp, color = if (bad) p.error else p.text) }
}

/**
 * Просьба о разрешении — не авария: без цвета ошибки. Планка слева акцентом, малая кнопка «Разрешить».
 */
@Composable
fun WarnCard(title: String, text: String, action: String, onClick: () -> Unit) {
    val p = LocalPal.current
    val shape = RoundedCornerShape(12.dp)
    val density = LocalDensity.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(shape)
            .background(p.warnBg)
            .border(1.dp, p.outlineVar, shape)
            .drawBehind { drawRect(p.accent, Offset.Zero, Size(with(density) { 3.dp.toPx() }, size.height)) }
            .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 6.dp)
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = p.text)
        Text(text, fontSize = 13.5.sp, color = p.textSub, modifier = Modifier.padding(top = 3.dp, end = 6.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            MainButton(action, Modifier.width(130.dp), height = 38.dp, onClick = onClick)
        }
    }
}

/** Плавающая «+»: латунь — круг с латунным кольцом внутри, изумруд — скруглённый квадрат. */
@Composable
fun Fab(onClick: () -> Unit) {
    val p = LocalPal.current
    val brass = isBrass
    val shape = if (brass) CircleShape else RoundedCornerShape(18.dp)
    val bgC = if (brass) p.primary else p.accent
    val fg = if (brass) p.onPrimary else p.onAccent
    Box(
        Modifier.size(58.dp).graphicsLayer { shadowElevation = if (brass) 18f else 12f; this.shape = shape; clip = true }
            .background(bgC).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (brass) Box(Modifier.fillMaxSize().padding(2.dp).border(1.dp, p.accent, CircleShape))
        Canvas(Modifier.size(22.dp)) {
            val w = 1.8.dp.toPx()
            drawLine(fg, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), w)
            drawLine(fg, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), w)
        }
    }
}

/** Заголовок группы списка: «СЕГОДНЯ ———» (латунь) или «Сегодня … 3» (изумруд). */
@Composable
fun GroupHeader(title: String, count: Int, modifier: Modifier = Modifier) {
    val p = LocalPal.current
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (isBrass) {
            BlockLabel(title, p.textSub)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).height(1.dp).background(p.outlineVar))
        } else {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = p.textSub, modifier = Modifier.weight(1f))
            Text("$count", fontSize = 13.sp, color = p.textSub)
        }
    }
}

/** Подсветка понятого из текста времени прямо в поле ввода: фон подсветки и свой цвет текста. */
class Highlight(private val spans: List<IntRange>, private val bg: Color, private val fg: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val out = buildAnnotatedString {
            append(text)
            spans.filter { it.last < text.length }.forEach {
                addStyle(SpanStyle(background = bg, color = fg), it.first, it.last + 1)
            }
        }
        return TransformedText(out, OffsetMapping.Identity)
    }

    override fun equals(other: Any?) = other is Highlight && other.spans == spans && other.bg == bg && other.fg == fg
    override fun hashCode() = (spans.hashCode() * 31 + bg.hashCode()) * 31 + fg.hashCode()
}
