package ru.napomni.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class FmtTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private fun ms(d: Int, h: Int, m: Int = 0, month: Int = 10, y: Int = 2026) =
        LocalDateTime.of(y, month, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = ms(15, 15, 42)

    @Test fun durations() {
        assertEquals("15 мин", Fmt.dur(15))
        assertEquals("1 ч", Fmt.dur(60))
        assertEquals("1 ч 30 мин", Fmt.dur(90))
        assertEquals("1 день", Fmt.dur(1440))
        assertEquals("2 дня", Fmt.dur(2880))
        assertEquals("5 дней", Fmt.dur(5 * 1440))
        assertEquals("21 день", Fmt.dur(21 * 1440))
        assertEquals("11 дней", Fmt.dur(11 * 1440))
        assertEquals("за 6 ч", Fmt.before(360))
    }

    @Test fun approx() {
        assertEquals("2 ч", Fmt.approx(119))
        assertEquals("1 день", Fmt.approx(1439))
        assertEquals("3 дня", Fmt.approx(3 * 1440 + 100))
        assertEquals("1 мин", Fmt.approx(0))
    }

    @Test fun shortForms() {
        assertEquals("17:42", Fmt.short(ms(15, 17, 42), now, zone))
        assertEquals("Завтра 10:00", Fmt.short(ms(16, 10), now, zone))
        assertEquals("Вс 19:00", Fmt.short(ms(18, 19), now, zone))
        assertEquals("25 окт 19:00", Fmt.short(ms(25, 19), now, zone))
        assertEquals("3 янв 2027 09:05", Fmt.short(ms(3, 9, 5, 1, 2027), now, zone))
    }

    @Test fun wizardLabels() {
        assertEquals("В день события", Fmt.dayLabel(0))
        assertEquals("За 1 день", Fmt.dayLabel(1))
        assertEquals("За 5 дней", Fmt.dayLabel(5))
        assertEquals("За неделю", Fmt.dayLabel(7))
        assertEquals("сегодня, 17:42", Fmt.compact(ms(15, 17, 42), now, zone))
        assertEquals("завтра, 10:00", Fmt.compact(ms(16, 10), now, zone))
        assertEquals("вс 18, 10:00", Fmt.compact(ms(18, 10), now, zone))
        val allDay = Reminder(1, "ДР", ms(18, 0), allDay = true, handledUntil = now)
        assertEquals("вс 18 окт, весь день", Fmt.event(allDay, now, zone))
        assertEquals("вс 18 окт, 19:00", Fmt.event(allDay.copy(allDay = false, at = ms(18, 19)), now, zone))
    }

    @Test fun longForms() {
        assertEquals("сегодня, 17:42", Fmt.long(ms(15, 17, 42), now, zone))
        assertEquals("завтра, 10:00", Fmt.long(ms(16, 10), now, zone))
        assertEquals("вс 18 окт, 19:00", Fmt.long(ms(18, 19), now, zone))
    }
}
