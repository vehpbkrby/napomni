package ru.napomni.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import java.time.LocalDate

/**
 * Оформление выбирается в настройках. Оба направления — от дизайнера (docs/design/concept.md):
 * A — тёплая бумага, полуночно-синий, латунь и антиква PT Serif в цифрах; B — фарфор, графит,
 * изумруд, только системный Roboto, без карточек.
 */
enum class Look(val title: String, val idea: String) {
    BRASS("Полночь и латунь", "Тёплая бумага, глубокий синий, латунные линии, цифры антиквой"),
    EMERALD("Графит и изумруд", "Фарфоровый белый, графит, один изумрудный акцент, строгие секции"),
}

/** Цвета по ролям. Латунь/изумруд (accent) — только линии и значки; текстом — accentText. */
data class Pal(
    val bg: Color,
    val surface: Color,
    val surfaceVar: Color,
    val primary: Color,
    val onPrimary: Color,
    val onPrimarySub: Color,
    val accent: Color,
    val onAccent: Color,
    val accentText: Color,
    val weekend: Color,
    val weekendOnPrimary: Color,
    /** A — контур выходного дня, B — подложка выходного дня. */
    val weekendEdge: Color,
    val outlineVar: Color,
    val outline: Color,
    val error: Color,
    val text: Color,
    val textSub: Color,
    val activeBg: Color,
    val hlBg: Color,
    val hlText: Color,
    val band: Color,
    val warnBg: Color,
    val linkOnPrimary: Color,
    val dark: Boolean,
)

private fun c(hex: Long) = Color(0xFF000000 or hex)

val BRASS_LIGHT = Pal(
    bg = c(0xF4EFE6), surface = c(0xFBF8F2), surfaceVar = c(0xECE5D8),
    primary = c(0x1B2B4B), onPrimary = c(0xF7F2E8), onPrimarySub = c(0xC9C6BF),
    accent = c(0x9C7A3C), onAccent = c(0x1C1607), accentText = c(0x7A5A22),
    weekend = c(0x9E2F2F), weekendOnPrimary = c(0xF2B8AE), weekendEdge = c(0xD9B5AC),
    outlineVar = c(0xD9CFBE), outline = c(0xBFB29C), error = c(0xA8261E),
    text = c(0x1A1F2B), textSub = c(0x5C574E), activeBg = c(0xFCF6E9),
    hlBg = c(0xEEDDB8), hlText = c(0x1A1F2B), band = c(0xEFE7D6), warnBg = c(0xF5EBD6),
    linkOnPrimary = c(0xD9BC7E), dark = false,
)

val BRASS_DARK = Pal(
    bg = c(0x0D1321), surface = c(0x151D2E), surfaceVar = c(0x1E2839),
    primary = c(0xCFB072), onPrimary = c(0x1C1607), onPrimarySub = c(0x4A3C1C),
    accent = c(0xC2A162), onAccent = c(0x1C1607), accentText = c(0xD6BA80),
    weekend = c(0xE8998F), weekendOnPrimary = c(0x7E211A), weekendEdge = c(0x5C3C44),
    outlineVar = c(0x2D3950), outline = c(0x40506B), error = c(0xF2A398),
    text = c(0xEDE7DA), textSub = c(0xA9A396), activeBg = c(0x1A2336),
    hlBg = c(0x3B3120), hlText = c(0xF3E2BC), band = c(0x1F2A3E), warnBg = c(0x1C2436),
    linkOnPrimary = c(0x4A3C1C), dark = true,
)

val EMERALD_LIGHT = Pal(
    bg = c(0xF2F3F2), surface = c(0xFFFFFF), surfaceVar = c(0xE8EBEA),
    primary = c(0x1E2427), onPrimary = c(0xFFFFFF), onPrimarySub = c(0xB9C0C2),
    accent = c(0x0B6B4D), onAccent = c(0xFFFFFF), accentText = c(0x0B6B4D),
    weekend = c(0xA3243B), weekendOnPrimary = c(0xF5A3B1), weekendEdge = c(0xF4E6E9),
    outlineVar = c(0xD2D7D5), outline = c(0xB4BCB9), error = c(0xB3261E),
    text = c(0x14181A), textSub = c(0x566064), activeBg = c(0xEDF5F1),
    hlBg = c(0xD5EEE2), hlText = c(0x0A5A40), band = c(0xE8EBEA), warnBg = c(0xFFFFFF),
    linkOnPrimary = c(0x9BEBCB), dark = false,
)

val EMERALD_DARK = Pal(
    bg = c(0x0E1112), surface = c(0x171B1D), surfaceVar = c(0x212629),
    primary = c(0xE4E8E6), onPrimary = c(0x111416), onPrimarySub = c(0x4A5356),
    accent = c(0x4CC497), onAccent = c(0x062117), accentText = c(0x5ACFA2),
    weekend = c(0xF0909F), weekendOnPrimary = c(0xA3243B), weekendEdge = c(0x2A1E22),
    outlineVar = c(0x2E3538), outline = c(0x3D4548), error = c(0xF2B8B5),
    text = c(0xE7EBE9), textSub = c(0x9BA5A7), activeBg = c(0x13201B),
    hlBg = c(0x173A2D), hlText = c(0x9BEBCB), band = c(0x212629), warnBg = c(0x171B1D),
    linkOnPrimary = c(0x0A5A40), dark = true,
)

fun palette(look: Look, dark: Boolean): Pal = when (look) {
    Look.BRASS -> if (dark) BRASS_DARK else BRASS_LIGHT
    Look.EMERALD -> if (dark) EMERALD_DARK else EMERALD_LIGHT
}

/** PT Serif (ParaType, SIL OFL 1.1, лицензия в assets/licenses) — заголовки, цифры и даты в «латуни». */
val PtSerif = FontFamily(
    Font(R.font.pt_serif_regular, FontWeight.Normal),
    Font(R.font.pt_serif_bold, FontWeight.Bold),
)

val LocalPal = staticCompositionLocalOf { BRASS_LIGHT }
val LocalLook = staticCompositionLocalOf { Look.BRASS }

/** Шрифт цифр, дат и заголовков: в «латуни» антиква, в «изумруде» системный. */
@Composable
fun displayFont(): FontFamily = if (LocalLook.current == Look.BRASS) PtSerif else FontFamily.Default

val isBrass: Boolean @Composable get() = LocalLook.current == Look.BRASS

fun isWeekend(d: LocalDate) = d.dayOfWeek.value >= 6

/** [forceDark] — экран будильника всегда тёмный: ночью белый экран слепит. */
@Composable
fun AppTheme(forceDark: Boolean = false, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val look by Prefs.lookFlow(ctx).collectAsState()
    val p = palette(look, forceDark || isSystemInDarkTheme())
    val base = if (p.dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.primary, onPrimary = p.onPrimary,
        primaryContainer = p.surfaceVar, onPrimaryContainer = p.text,
        secondary = p.accent, tertiary = p.accent, onTertiary = p.onAccent,
        background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text,
        surfaceVariant = p.surfaceVar, onSurfaceVariant = p.textSub,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surfaceVar,
        outline = p.outline, outlineVariant = p.outlineVar, error = p.error,
    )
    CompositionLocalProvider(LocalPal provides p, LocalLook provides look) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
