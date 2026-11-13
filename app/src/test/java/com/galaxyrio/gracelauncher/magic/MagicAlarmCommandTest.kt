package com.galaxyrio.gracelauncher.magic

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class MagicAlarmCommandTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun parse(text: String) = MagicAlarmCommand.parse(text, "en", Instant.parse("2026-11-13T12:00:00Z").toEpochMilli(), zone)

    @Test fun calendarTimesAndAllAlarmPrefixesAreConsistent() {
        val cases = mapOf(
            "wake me at 7" to (19 to 0), "wake me at 7am" to (7 to 0),
            "wake me at 7:30 am" to (7 to 30), "alarm 7:30" to (7 to 30),
            "alarm 19:30" to (19 to 30), "alarm 7am" to (7 to 0), "alarm 7" to (19 to 0),
            "alarm at 8:15" to (8 to 15), "alarm at 6:30pm" to (18 to 30),
            "set alarm for 6:45 am" to (6 to 45), "set alarm for 7" to (19 to 0),
            "wake me up at 7" to (19 to 0), "set an alarm for 7" to (19 to 0),
            "alarm for 7" to (19 to 0), "  ALARM 07:30  " to (7 to 30),
        )
        for ((text, time) in cases) assertEquals(text, MagicAlarmCommand.Alarm(time.first, time.second), parse(text))
    }

    @Test fun labelsAndSpokenFractionsWorkWithoutInference() {
        assertEquals(MagicAlarmCommand.Alarm(7, 30, "Breakfast"), parse("alarm Breakfast 7:30am"))
        assertEquals(MagicAlarmCommand.Alarm(19, 30), parse("alarm half past seven"))
        assertEquals(MagicAlarmCommand.Alarm(7, 45), parse("alarm quarter to 8"))
        assertEquals(MagicAlarmCommand.Alarm(7, 30), parse("alarm half past seven am"))
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
            "alarm at 25", "alarm 7:99am", "alarm 7am or 8am", "call Alex", "!alarm 7am", "alarm 7am\ntimer 10m"))
            assertNull(text, parse(text))
    }
}
