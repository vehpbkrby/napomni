package ru.napomni.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

class ModelTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private fun ms(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private val created = ms(15, 15, 42)
    private val event = ms(18, 19)

    // ДР в вс 18 окт 19:00, напомнить за 2 и за 1 день в 19:00.
    private val r = Reminder(1, "ДР", event, days = listOf(1, 2), dayTime = 19 * 60, handledUntil = created)

    // Дни напоминаний считаются в поясе телефона — в тестах это Москва.
    @Before fun tz() = TimeZone.setDefault(TimeZone.getTimeZone(zone))

    @Test fun firstTriggerIsTwoDaysBefore() {
        assertEquals(ms(16, 19), r.nextTrigger()!!.time)
        assertNull(r.dueAt(ms(16, 18, 59)))
    }

    @Test fun firesThenMovesOn() {
        val t = r.dueAt(ms(16, 19))!!
        assertEquals(Kind.DAY, t.kind)
        assertEquals(2, t.day)
        val after = r.handled(ms(16, 19), t)
        val (d2, d1) = after.early().sortedBy { -it.day }
        assertTrue(after.shown(d2))
        assertFalse(after.shown(d1))
        assertEquals(ms(17, 19), after.nextTrigger()!!.time)
    }

    @Test fun earlyAlarmStillCounts() {
        assertEquals(Kind.DAY, r.dueAt(ms(16, 19) - 500)!!.kind)
    }

    @Test fun missedTriggersShowOnlyLatest() {
        // Телефон был выключен с 16-го до вечера 18-го: показать одно — само событие.
        val t = r.dueAt(ms(18, 19, 5))!!
        assertEquals(Kind.EVENT, t.kind)
        assertNull(r.handled(ms(18, 19, 5), t).nextTrigger())
    }

    @Test fun snoozeAddsTrigger() {
        val fired = r.copy(handledUntil = event)
        assertNull(fired.nextTrigger())
        assertFalse(fired.isActive(event + 1))
        val snoozed = fired.copy(snoozeAt = event + 60 * MINUTE)
        assertEquals(Kind.SNOOZE, snoozed.nextTrigger()!!.kind)
        assertTrue(snoozed.isActive(event + 1))
        assertEquals(event + 60 * MINUTE, snoozed.shownAt(event + 1))
    }

    @Test fun doneHasNoTriggers() {
        assertNull(r.copy(done = true).nextTrigger())
    }

    @Test fun repeatNagsEveryFiveMinutesUpToAnHour() {
        var x = r.copy(repeat = true, handledUntil = ms(17, 19))
        var now = event
        val first = x.dueAt(now)!!
        assertEquals(Kind.EVENT, first.kind)
        x = x.handled(now, first)
        assertEquals(event + NAG_MIN * MINUTE, x.nagAt)
        assertTrue(x.isActive(event + 1))
        assertEquals(x.nagAt, x.shownAt(event + 1))
        repeat(NAG_MAX - 1) {
            now = x.nagAt!!
            val t = x.dueAt(now)!!
            assertEquals(Kind.NAG, t.kind)
            x = x.handled(now, t)
        }
        assertEquals(NAG_MAX, x.nags)
        now = x.nagAt!!
        x = x.handled(now, x.dueAt(now)!!)
        assertNull(x.nagAt)
        assertNull(x.nextTrigger())
    }

    @Test fun earlyAndSilentDoNotNag() {
        val rep = r.copy(repeat = true)
        val early = rep.dueAt(ms(16, 19))!!
        assertNull(rep.handled(ms(16, 19), early).nagAt)
        val silent = r.copy(repeat = true, alert = Alert.SILENT, handledUntil = ms(17, 19))
        assertNull(silent.handled(event, silent.dueAt(event)!!).nagAt)
    }

    @Test fun reactionStopsRepeats() {
        val x = r.copy(repeat = true, nagAt = event + 5 * MINUTE, nags = 1, handledUntil = event)
        assertNull(x.reacted().nextTrigger())
        val s = x.snoozed(event, 10)
        assertNull(s.nagAt)
        assertEquals(Kind.SNOOZE, s.nextTrigger()!!.kind)
    }

    @Test fun alarmRingsOnlyForFreshEvent() {
        val a = r.copy(alert = Alert.ALARM)
        val early = Trigger(ms(17, 19), Kind.DAY, day = 1)
        assertEquals(Alert.SOUND, a.alertFor(early))
        assertFalse(a.rings(early, ms(17, 19)))
        val ev = Trigger(event, Kind.EVENT)
        assertTrue(a.rings(ev, event + MINUTE))
        // Телефон был выключен: про событие часовой давности — уведомлением, без звона.
        assertFalse(a.rings(ev, event + 60 * MINUTE))
    }

    @Test fun effectsOnlyForMain() {
        val v = r.copy(speak = true)
        assertFalse(v.effects(Trigger(ms(17, 19), Kind.DAY, day = 1)))
        assertTrue(v.effects(Trigger(event, Kind.EVENT)))
        assertFalse(v.copy(alert = Alert.SILENT).effects(Trigger(event, Kind.EVENT)))
    }

    @Test fun allDayEventRemindsOnlyByDays() {
        val a = Reminder(2, "ДР", ms(18, 0), allDay = true, days = listOf(0, 1), dayTime = 9 * 60, handledUntil = created)
        val t = a.triggers()
        assertEquals(listOf(ms(17, 9), ms(18, 9)), t.map { it.time })
        assertTrue(t.none { it.kind == Kind.EVENT })
        assertTrue(a.isMain(t[1]))
        assertFalse(a.isMain(t[0]))
        assertTrue(a.isActive(ms(18, 23)))
        assertFalse(a.isActive(ms(19, 0, 1)))
        // Будильник у «весь день» звенит в сам день, а не накануне.
        assertTrue(a.copy(alert = Alert.ALARM).rings(t[1], ms(18, 9)))
    }

    @Test fun dayReminderAfterTimedEventIsDropped() {
        val morning = Reminder(3, "Врач", ms(18, 8), days = listOf(0, 1), dayTime = 9 * 60, handledUntil = created)
        assertFalse(morning.dayFits(0))
        assertEquals(listOf(ms(17, 9)), morning.early().map { it.time })
    }

    @Test fun hoursBeforeTimedEvent() {
        val h = Reminder(4, "Встреча", event, before = listOf(60, 180), handledUntil = created)
        assertEquals(listOf(ms(18, 16), ms(18, 18)), h.early().map { it.time })
        assertTrue(h.copy(allDay = true, at = ms(18, 0)).early().isEmpty())
    }

    @Test fun migrateFromOneOne() {
        // 1.1: «за 1 ч, за 1 день, за 2 дня» к событию в 19:00 → часы отдельно, дни — в 19:00.
        val (hours, days, dayTime) = migrateBefore(listOf(60, 1440, 2880), event)
        assertEquals(listOf(60), hours)
        assertEquals(listOf(1, 2), days)
        assertEquals(19 * 60, dayTime)
        assertEquals(DEFAULT_DAY_TIME, migrateBefore(listOf(60), event).third)
    }

    @Test fun specificDatesEachRemindAtTheirTime() {
        val d = listOf(17L, 20L, 23L).map { java.time.LocalDate.of(2026, 10, it.toInt()).toEpochDay() }
        val r = Reminder(5, "Цветы", ms(23, 0), allDay = true, dates = d, dayTime = 10 * 60 + 30, handledUntil = created)
        assertEquals(listOf(ms(17, 10, 30), ms(20, 10, 30), ms(23, 10, 30)), r.triggers().map { it.time })
        // Каждый день — главное срабатывание: будильник, повтор и голос работают для всех.
        assertTrue(r.triggers().all { r.isMain(it) })
        assertTrue(r.isActive(ms(23, 20)))
        assertFalse(r.isActive(ms(24, 0, 1)))
    }
}
