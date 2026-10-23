package ru.napomni.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Как напомнить — независимые признаки в любом сочетании: звук, вибрация, вспышка, голос,
 * «как будильник» (на весь экран, по кругу, слышно в беззвучном), повтор каждые 5 мин.
 */
data class Way(
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    val flash: Boolean = false,
    val speak: Boolean = false,
    val loud: Boolean = false,
    val repeat: Boolean = false,
    /** Читать вслух сразу. Без этого — только по кнопке «Прочитать» в уведомлении: название бывает личным. */
    val speakAuto: Boolean = false,
) {
    val any get() = sound || vibrate || flash || speak || loud

    /** «звук · вибрация · вспышка»; «тихо», если ничего. */
    fun label(): String = listOfNotNull(
        "звук".takeIf { sound }, "вибрация".takeIf { vibrate }, "вспышка".takeIf { flash },
        (if (speakAuto) "голос" else "голос по кнопке").takeIf { speak }, "как будильник".takeIf { loud }, "повтор".takeIf { repeat },
    ).joinToString(" · ").ifEmpty { "тихо" }

    /** Для подписей в списке: обычный набор не пишем, остальное — коротко. */
    fun shortLabel(): String? = when (this) {
        Preset.NORMAL.way -> null
        Preset.QUIET.way -> "тихо"
        Preset.LOUD.way -> "громко"
        else -> label()
    }

    /** Признаки строкой — для настроек «последний выбор». */
    fun encode() = listOf(sound, vibrate, flash, speak, loud, repeat, speakAuto).joinToString("") { if (it) "1" else "0" }

    companion object {
        fun decode(s: String?): Way? {
            // 2.0 хранила 6 признаков, с 2.1 — 7 (чтение без подтверждения).
            if (s == null || s.length !in 6..7) return null
            val b = s.map { it == '1' }
            return Way(b[0], b[1], b[2], b[3], b[4], b[5], b.getOrElse(6) { false })
        }

        /** Перенос из 1.1–1.9, где способ был один из четырёх. */
        fun fromOld(alert: String?) = when (alert) {
            "VIBRATE" -> Way(sound = false, vibrate = true)
            "SILENT" -> Way(sound = false, vibrate = false)
            "ALARM" -> Way(sound = true, vibrate = true, loud = true)
            else -> Way()
        }
    }
}

/** Наборы одним нажатием; поменяли любой переключатель — выбор «Свои». */
enum class Preset(val title: String, val way: Way) {
    QUIET("Тихо", Way(sound = false, vibrate = false)),
    NORMAL("Обычно", Way()),
    LOUD("Громко", Way(sound = true, vibrate = true, flash = true, loud = true, repeat = true)),
}

val Reminder.way get() = Way(sound, vibrate, flash, speak, loud, repeat, speakAuto)

fun Reminder.withWay(w: Way) =
    copy(sound = w.sound, vibrate = w.vibrate, flash = w.flash, speak = w.speak, loud = w.loud, repeat = w.repeat, speakAuto = w.speakAuto)

/**
 * Блок «Как напомнить»: наборы сверху и переключатели с картинками. Мелодия — под «Звуком»
 * (для обычного звука и для «как будильник»).
 */
@Composable
fun WaySection(way: Way, melodyName: String, onMelody: () -> Unit, onChange: (Way) -> Unit) {
    val p = LocalPal.current
    Chips {
        Preset.entries.forEach { pr -> Chip(pr.title, selected = way == pr.way) { onChange(pr.way) } }
        Chip("Свои", selected = Preset.entries.none { it.way == way }) {}
    }
    Spacer(Modifier.height(6.dp))
    @Composable
    fun row(icon: String, title: String, sub: String?, on: Boolean, set: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(p.surfaceVar),
                contentAlignment = Alignment.Center,
            ) { Text(icon, fontSize = 15.sp) }
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                ToggleRow(title, on, set)
                if (sub != null) Text(sub, fontSize = 11.5.sp, color = p.textSub, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
    }
    row("🔊", "Звук", null, way.sound) { onChange(way.copy(sound = it)) }
    if (way.sound || way.loud) Box(Modifier.padding(start = 40.dp)) { OptionRow("Мелодия", melodyName, onMelody) }
    row("📳", "Вибрация", null, way.vibrate) { onChange(way.copy(vibrate = it)) }
    row("🔦", "Вспышка", null, way.flash) { onChange(way.copy(flash = it)) }
    row("🗣", "Читать вслух", if (way.speak && !way.speakAuto) "по кнопке «Прочитать» в уведомлении" else null, way.speak) { onChange(way.copy(speak = it)) }
    if (way.speak) Row(
        Modifier.padding(start = 40.dp, bottom = 6.dp).fillMaxWidth().clickable { onChange(way.copy(speakAuto = !way.speakAuto)) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(5.dp)
        Box(
            Modifier.size(20.dp).clip(shape).background(if (way.speakAuto) p.primary else androidx.compose.ui.graphics.Color.Transparent)
                .border(1.5.dp, if (way.speakAuto) p.primary else p.outline, shape),
            contentAlignment = Alignment.Center,
        ) { if (way.speakAuto) Text("✓", fontSize = 13.sp, color = p.onPrimary) }
        Text("сразу, без подтверждения", fontSize = 14.sp, color = p.text, modifier = Modifier.padding(start = 10.dp))
    }
    row("⏰", "Как будильник", "на весь экран, по кругу, слышно в беззвучном", way.loud) { onChange(way.copy(loud = it)) }
    row("🔁", "Повторять каждые $NAG_MIN мин", "пока не нажмёте «Готово»", way.repeat) { onChange(way.copy(repeat = it)) }
    if (way.loud) Text(
        "заранее придёт без будильника — звуком и вибрацией, как выбрано",
        fontSize = 12.sp, color = p.textSub, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}
