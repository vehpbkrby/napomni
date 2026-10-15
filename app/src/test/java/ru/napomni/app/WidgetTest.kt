package ru.napomni.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class WidgetTest {
    @Test fun weekButtons() {
        val today = LocalDate.of(2026, 10, 15) // четверг
        val labels = (1..7).map { Widget.dayLabel(it, today.plusDays(it.toLong())) }
        assertEquals(listOf("Завтра", "Послезавтра", "Вс 18", "Пн 19", "Вт 20", "Ср 21", "Чт 22"), labels)
    }
}
