package ru.napomni.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ModelTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private fun ms(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private val created = ms(15, 15, 42)
    private val event = ms(18, 19)
    private val r = Reminder(1, "ДР", event, listOf(1440, 2880), handledUntil = created)

    @Test fun firstTriggerIsTwoDaysBefore() {
        assertEquals(ms(16, 19), r.nextTrigger()!!.time)
        assertNull(r.dueAt(ms(16, 18, 59)))
    }

    @Test fun firesThenMovesOn() {
        val t = r.dueAt(ms(16, 19))!!
        assertEquals(Kind.BEFORE, t.kind)
        assertEquals(2880, t.beforeMin)
        val after = r.handled(ms(16, 19), t)
        assertTrue(after.beforeShown(2880))
        assertFalse(after.beforeShown(1440))
        assertEquals(ms(17, 19), after.nextTrigger()!!.time)
    }

    @Test fun earlyAlarmStillCounts() {
        assertEquals(Kind.BEFORE, r.dueAt(ms(16, 19) - 500)!!.kind)
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

    @Test fun beforeAndSilentDoNotNag() {
        val rep = r.copy(repeat = true)
        val before = rep.dueAt(ms(16, 19))!!
        assertNull(rep.handled(ms(16, 19), before).nagAt)
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
        val before = Trigger(ms(17, 19), Kind.BEFORE, 1440)
        assertEquals(Alert.SOUND, a.alertFor(before))
        assertFalse(a.rings(before, ms(17, 19)))
        val ev = Trigger(event, Kind.EVENT)
        assertTrue(a.rings(ev, event + MINUTE))
        // Телефон был выключен: про событие часовой давности — уведомлением, без звона.
        assertFalse(a.rings(ev, event + 60 * MINUTE))
    }

    @Test fun effectsOnlyForEvent() {
        val v = r.copy(speak = true)
        assertFalse(v.effects(Trigger(ms(17, 19), Kind.BEFORE, 1440)))
        assertTrue(v.effects(Trigger(event, Kind.EVENT)))
        assertFalse(v.copy(alert = Alert.SILENT).effects(Trigger(event, Kind.EVENT)))
    }
}
