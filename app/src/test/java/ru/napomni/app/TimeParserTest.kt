package ru.napomni.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class TimeParserTest {
    // Четверг, 15 октября 2026, 15:42.
    private val now = LocalDateTime.of(2026, 10, 15, 15, 42)
    private fun p(s: String) = TimeParser.parse(s, now)
    private fun d(day: Int, month: Int = 10, year: Int = 2026) = LocalDate.of(year, month, day)
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    @Test fun relativeHours() {
        val r = p("Забрать посылку через 2 часа")
        assertEquals(120, r.minutes)
        assertEquals("Забрать посылку", r.title)
    }

    @Test fun relativeVariants() {
        assertEquals(15, p("через 15 мин чайник").minutes)
        assertEquals(30, p("через полчаса").minutes)
        assertEquals(60, p("через час позвонить").minutes)
        assertEquals(1, p("через минуту").minutes)
        assertEquals(1440, p("через день").minutes)
        assertEquals(3 * 1440, p("через 3 дня").minutes)
        assertEquals(7 * 1440, p("через неделю").minutes)
        assertEquals(120, p("через два часа").minutes)
        assertEquals(5, p("через пять минут").minutes)
        assertEquals(180, p("через 3ч").minutes)
    }

    @Test fun tomorrowAtHour() {
        val r = p("Позвонить маме завтра в 10")
        assertEquals(d(16), r.date)
        assertEquals(t(10), r.time)
        assertEquals("Позвонить маме", r.title)
    }

    @Test fun dateWordAndTime() {
        val r = p("ДР Саши 18 октября в 19:00")
        assertEquals(d(18), r.date)
        assertEquals(t(19), r.time)
        assertEquals("ДР Саши", r.title)
    }

    @Test fun weekdayAndTime() {
        val r = p("в пт 18:00 отчёт")
        assertEquals(d(16), r.date)
        assertEquals(t(18), r.time)
        assertEquals("отчёт", r.title)
        // Сегодня четверг: «в четверг в 10» — 10:00 уже прошло, значит через неделю.
        assertEquals(d(22), p("в четверг в 10 планёрка").date)
        // «в чт в 17» — сегодня ещё впереди.
        assertEquals(d(15), p("в чт в 17").date)
        assertEquals(d(19), p("в понедельник").date)
    }

    @Test fun numericDateVsTime() {
        assertEquals(d(18), p("18.10 врач").date)
        val r = p("врач в 18.30")
        assertEquals(t(18, 30), r.time)
        assertEquals(d(15), r.date)
        assertEquals(d(1, 1, 2027), p("1.01 праздник").date)
        assertEquals(d(5, 11, 2027), p("05.11.2027").date)
    }

    @Test fun timeOnlyRollsToTomorrow() {
        assertEquals(d(15), p("в 17 забрать").date)
        assertEquals(d(16), p("в 9 забрать").date)
    }

    @Test fun partsOfDay() {
        assertEquals(t(19), p("в 7 вечера кино").time)
        assertEquals(t(15), p("в 3 дня встреча").time)
        assertEquals(t(0), p("в 12 ночи").time)
        assertEquals(t(9), p("завтра утром").time)
        assertEquals(t(19, 30), p("в 7:30 вечера").time)
    }

    @Test fun dateWithoutTimeLeavesHourOpen() {
        val r = p("послезавтра забрать документы")
        assertEquals(d(17), r.date)
        assertNull(r.time)
        assertEquals("забрать документы", r.title)
    }

    @Test fun nothingFound() {
        val r = p("купить 2 кг яблок")
        assertFalse(r.found)
        assertEquals("купить 2 кг яблок", r.title)
        assertFalse(p("встреча в среду-четверг?").minutes != null)
    }

    @Test fun pastDateGoesToNextYear() {
        assertEquals(d(1, 3, 2027), p("1 марта").date)
        assertEquals(d(20, 10, 2026), p("на 20 окт.").date)
    }

    @Test fun spansCoverMatchedText() {
        val s = "Забрать посылку через 2 часа"
        val r = p(s)
        assertEquals("через 2 часа", s.substring(r.spans[0].first, r.spans[0].last + 1))
    }
}
