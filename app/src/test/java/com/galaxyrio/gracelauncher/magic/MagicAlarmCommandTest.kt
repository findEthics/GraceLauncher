package com.galaxyrio.gracelauncher.magic

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class MagicAlarmCommandTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun parse(text: String, hour: Int = 13) = MagicAlarmCommand.parse(text,
        nowMillis = Instant.parse("2026-11-13T${(hour - 1).toString().padStart(2, '0')}:00:00Z").toEpochMilli(), zoneId = zone)

    @Test fun calendarTimesAndAllAlarmPrefixesAreConsistent() {
        val cases = mapOf(
            "wake me at 7" to (19 to 0), "wake me at 7am" to (7 to 0),
            "wake me at 7:30 am" to (7 to 30), "alarm 7:30" to (19 to 30),
            "alarm 19:30" to (19 to 30), "alarm 7am" to (7 to 0), "alarm 7" to (19 to 0),
            "alarm at 8:15" to (20 to 15), "alarm at 6:30pm" to (18 to 30),
            "set alarm for 6:45 am" to (6 to 45), "set alarm for 7" to (19 to 0),
            "wake me up at 7" to (19 to 0), "set an alarm for 7" to (19 to 0),
            "alarm for 7" to (19 to 0), "  ALARM 07:30  " to (7 to 30),
        )
        for ((text, time) in cases) assertEquals(text,
            MagicAlarmCommand.Alarm(time.first, time.second, tomorrow = time.first < 13), parse(text))
    }

    @Test fun labelsAndSpokenFractionsWorkWithoutInference() {
        assertEquals(MagicAlarmCommand.Alarm(7, 30, "Breakfast", tomorrow = true), parse("alarm Breakfast 7:30am"))
        assertEquals(MagicAlarmCommand.Alarm(19, 30), parse("alarm half past seven"))
        assertEquals(MagicAlarmCommand.Alarm(19, 45), parse("alarm quarter to 8"))
        assertEquals(MagicAlarmCommand.Alarm(7, 30, tomorrow = true), parse("alarm half past seven am"))
    }

    @Test fun ambiguousTimesChooseNearestUpcomingAndLiteralExactNowIsRefused() {
        assertEquals(MagicAlarmCommand.Alarm(7, 0), parse("alarm 7", hour = 5))
        assertEquals(MagicAlarmCommand.Alarm(7, 0, tomorrow = true), parse("alarm 7", hour = 20))
        assertNull(parse("alarm 13:00"))
    }

    @Test fun ambiguityIncludesColonAndTwelveButNotLiteralSignals() {
        for (text in listOf("alarm at 7:00", "alarm seven", "alarm 7"))
            assertEquals(text, MagicAlarmCommand.Alarm(19, 0), parse(text))
        assertEquals(MagicAlarmCommand.Alarm(12, 0), parse("alarm 12", hour = 5))
        assertEquals(MagicAlarmCommand.Alarm(0, 0, tomorrow = true), parse("alarm 12"))
        assertEquals(MagicAlarmCommand.Alarm(19, 0), parse("alarm 7", hour = 7))
        for (text in listOf("alarm 07:00", "alarm at 07:00", "alarm 7 a.m.", "alarm 7am"))
            assertEquals(text, MagicAlarmCommand.Alarm(7, 0, tomorrow = true), parse(text))
        assertEquals(MagicAlarmCommand.Alarm(0, 30, tomorrow = true), parse("alarm 0:30"))
        assertEquals(MagicAlarmCommand.Alarm(12, 0, tomorrow = true), parse("alarm noon"))
        assertEquals(MagicAlarmCommand.Alarm(0, 0, tomorrow = true), parse("alarm midnight"))
    }

    @Test fun namedDayMustMatchClocksNextOccurrence() {
        assertEquals(MagicAlarmCommand.Alarm(7, 0, tomorrow = true), parse("alarm tomorrow 7am"))
        assertEquals(MagicAlarmCommand.WrongDay(19, 30, tomorrow = false), parse("alarm tomorrow 19:30"))
        assertEquals(MagicAlarmCommand.WrongDay(7, 0, tomorrow = true), parse("alarm today 7am"))
        assertEquals(MagicAlarmCommand.Alarm(7, 0, tomorrow = true), parse("alarm tomorrow 7am", hour = 22))
        assertEquals(MagicAlarmCommand.Alarm(7, 0, tomorrow = true), parse("alarm tomorrow 7", hour = 20))
        assertEquals(MagicAlarmCommand.WrongDay(7, 0, tomorrow = false), parse("alarm tomorrow 7", hour = 6))
        assertEquals(MagicAlarmCommand.Alarm(19, 0), parse("alarm today 7"))
        assertEquals(MagicAlarmCommand.Alarm(7, 0, tomorrow = true), parse("alarm Saturday 7am"))
        assertEquals(MagicAlarmCommand.WrongDay(7, 0, tomorrow = true), parse("alarm Monday 7am"))
        assertEquals(MagicAlarmCommand.Alarm(7, 0, tomorrow = true), parse("alarm Nov 14 7am"))
        assertEquals(MagicAlarmCommand.WrongDay(19, 30, tomorrow = false), parse("alarm 11/14 19:30"))
        assertEquals(MagicAlarmCommand.WrongDay(19, 30, tomorrow = false), parse("alarm in 3 days 19:30"))
    }

    @Test fun englishAlarmsAreIndependentOfDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val english = parse("alarm 7am")
            Locale.setDefault(Locale.GERMANY)
            assertEquals(MagicAlarmCommand.Alarm(7, 0, tomorrow = true), parse("alarm 7am"))
            assertEquals(english, parse("alarm 7am"))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun timersAcceptCompactSpacedSpokenAndCompoundDurations() {
        for ((text, seconds) in mapOf("timer 10 minutes" to 600, "timer 10m" to 600,
            "timer 5min" to 300, "timer 30s" to 30, "timer 2h" to 7200,
            "timer 1h 30m" to 5400, " TIMER FOR five minutes " to 300,
            "timer 1s" to 1, "timer 24h" to 86400))
            assertEquals(text, MagicAlarmCommand.Timer(seconds), parse(text))
    }

    @Test fun invalidUnsupportedAmbiguousAndOutOfRangeInputDoesNotDispatch() {
        for (text in listOf("", "alarm", "alarm tomorrow", "timer", "timer for", "timer 0s", "timer 25h",
            "timer 9999999999999999999999999h", "timer 10", "timer 10monkeys", "timer 1m or 2m",
            "alarm at 25", "alarm 7:99am", "alarm 7am or 8am", "call Alex", "!alarm 7am", "alarm 7am\ntimer 10m",
            "alarm Nov 14", "alarm 7am\u0000", "alarm " + "a".repeat(1001)))
            assertNull(text, parse(text))
    }
}
