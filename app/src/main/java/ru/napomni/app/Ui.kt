package ru.napomni.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFF8AB4F8), onPrimary = Color(0xFF0B1F44),
        primaryContainer = Color(0xFF1E3A6B), onPrimaryContainer = Color(0xFFD6E3FF),
        background = Color(0xFF141417), surface = Color(0xFF1F1F23), surfaceVariant = Color(0xFF2A2A30),
    ) else lightColorScheme(
        primary = Color(0xFF2563EB), onPrimary = Color.White,
        primaryContainer = Color(0xFFE6F0FE), onPrimaryContainer = Color(0xFF0C447C),
        background = Color(0xFFF1F5F9), surface = Color.White, surfaceVariant = Color(0xFFF1F5F9),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) = Text(
    text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier.padding(top = 14.dp, bottom = 6.dp),
)

@Composable
fun Chip(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) cs.primaryContainer else Color.Transparent)
            .border(1.dp, if (selected) cs.primary.copy(alpha = .6f) else cs.outline.copy(alpha = if (enabled) .45f else .2f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
    ) {
        Text(
            text, fontSize = 14.sp, maxLines = 1,
            color = when {
                selected -> cs.onPrimaryContainer
                enabled -> cs.onSurface
                else -> cs.onSurface.copy(alpha = .35f)
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(content: @Composable () -> Unit) = FlowRow(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
) { content() }

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().clip(shape).border(1.dp, cs.outline.copy(alpha = .45f), shape)) {
        options.forEachIndexed { i, s ->
            Box(
                Modifier
                    .weight(1f)
                    .background(if (i == selected) cs.primaryContainer else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(s, fontSize = 14.sp, color = if (i == selected) cs.onPrimaryContainer else cs.onSurfaceVariant)
            }
        }
    }
}

/** Календарь месяца: прошедшие дни бледные, сегодня обведено, точка — на день уже есть напоминание. */
@Composable
fun MonthCalendar(
    month: YearMonth,
    onMonth: (YearMonth) -> Unit,
    selected: LocalDate?,
    today: LocalDate,
    marked: Set<LocalDate>,
    onPick: (LocalDate) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onMonth(month.minusMonths(1)) }, enabled = month > YearMonth.from(today)) {
                Text("‹", fontSize = 24.sp)
            }
            Text(
                "${Fmt.MONTHS[month.monthValue - 1]} ${month.year}",
                Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 16.sp, fontWeight = FontWeight.Medium,
            )
            TextButton(onClick = { onMonth(month.plusMonths(1)) }) { Text("›", fontSize = 24.sp) }
        }
        Row {
            Fmt.WEEKDAYS.forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp, color = cs.onSurfaceVariant)
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
                            DayCell(n, d == selected, d == today, d < today, d in marked) { onPick(d) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(n: Int, sel: Boolean, today: Boolean, past: Boolean, mark: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (sel) cs.primary else Color.Transparent)
            .then(if (today && !sel) Modifier.border(1.dp, cs.primary, CircleShape) else Modifier)
            .clickable(enabled = !past, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$n", fontSize = 15.sp,
            color = when {
                sel -> cs.onPrimary
                past -> cs.onSurface.copy(alpha = .3f)
                else -> cs.onSurface
            },
        )
        if (mark) Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(if (sel) cs.onPrimary else cs.primary)
        )
    }
}

/** Сетка чисел: часы 8×3, минуты 6×2 — время ставится двумя нажатиями. */
@Composable
fun NumberGrid(
    values: List<Int>,
    columns: Int,
    selected: Int?,
    label: (Int) -> String,
    enabled: (Int) -> Boolean,
    onPick: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Column {
        values.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { v ->
                    val sel = v == selected
                    val en = enabled(v)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(shape)
                            .background(if (sel) cs.primaryContainer else Color.Transparent)
                            .border(1.dp, if (sel) cs.primary.copy(alpha = .6f) else cs.outline.copy(alpha = .25f), shape)
                            .clickable(enabled = en) { onPick(v) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label(v), fontSize = 15.sp,
                            color = when {
                                sel -> cs.onPrimaryContainer
                                en -> cs.onSurface
                                else -> cs.onSurface.copy(alpha = .3f)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Подсветка понятого из текста времени прямо в поле ввода. */
class Highlight(private val spans: List<IntRange>, private val color: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val out = buildAnnotatedString {
            append(text)
            spans.filter { it.last < text.length }.forEach {
                addStyle(SpanStyle(color = color, textDecoration = TextDecoration.Underline), it.first, it.last + 1)
            }
        }
        return TransformedText(out, OffsetMapping.Identity)
    }

    override fun equals(other: Any?) = other is Highlight && other.spans == spans && other.color == color
    override fun hashCode() = spans.hashCode() * 31 + color.hashCode()
}
