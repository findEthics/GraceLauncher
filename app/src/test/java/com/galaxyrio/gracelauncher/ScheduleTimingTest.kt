package com.galaxyrio.gracelauncher

import com.galaxyrio.gracelauncher.data.CountdownUnit
import com.galaxyrio.gracelauncher.data.ScheduleEvent
import com.galaxyrio.gracelauncher.data.agendaDays
import com.galaxyrio.gracelauncher.data.eventCountdown
import com.galaxyrio.gracelauncher.data.visibleScheduleEvents
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleTimingTest {
    private val now = Instant.parse("2026-09-22T12:00:00Z")
    private fun event(start: Instant, end: Instant, allDay: Boolean = false) = ScheduleEvent(
        id = start.epochSecond, title = "Movie night", startsAt = start, endsAt = end,
        isAllDay = allDay, location = null, calendarColor = null,
    )

    @Test
    fun agendaIncludesEveryOverlappingOccurrenceInChronologicalOrder() {
        val expired = event(now.minusSeconds(7200), now.minusSeconds(3600))
        val endedAtNow = event(now.minusSeconds(3600), now)
        val ongoing = event(now.minusSeconds(600), now.plusSeconds(1800))
        val startsNow = event(now, now)
        val later = event(now.plusSeconds(7200), now.plusSeconds(10800))
        val soon = event(now.plusSeconds(1680), now.plusSeconds(5280))

        assertEquals(
            listOf(ongoing, startsNow, soon, later),
            visibleScheduleEvents(listOf(later, expired, startsNow, ongoing, endedAtNow, soon), now, ZoneId.of("UTC")),
        )
        assertEquals(28, eventCountdown(soon, now).value)
    }

    @Test
    fun agendaUsesAnElapsedHalfOpen24HourWindowAndIncludesInstantOccurrences() {
        val start = Instant.parse("2026-03-08T06:30:00Z")
        val horizon = start.plus(Duration.ofHours(24))
        val atNow = event(start, start)
        val futureZero = event(start.plusSeconds(12 * 3600L), start.plusSeconds(12 * 3600L))
        val endsAtHorizon = event(horizon.minusSeconds(1), horizon)
        val justBeforeHorizon = event(horizon.minusNanos(1), horizon.plusSeconds(1))
        val beforeNow = event(start.minusNanos(1), start.minusNanos(1))
        val endedAtNow = event(start.minusSeconds(30), start)
        val beginsAtHorizon = event(horizon, horizon.plusSeconds(1))
        val atHorizon = event(horizon, horizon)
        val afterHorizon = event(horizon.plusNanos(1), horizon.plusNanos(1))

        assertEquals(
            listOf(atNow, futureZero, endsAtHorizon, justBeforeHorizon),
            visibleScheduleEvents(
                listOf(afterHorizon, beginsAtHorizon, beforeNow, atHorizon, justBeforeHorizon, endedAtNow, endsAtHorizon, futureZero, atNow),
                start,
                ZoneId.of("America/New_York"),
            ),
        )
    }

    @Test
    fun recurringOccurrencesWithTheSameProviderIdAreNotDeduplicated() {
        val first = event(now.plusSeconds(3600), now.plusSeconds(7200))
        val second = event(now.plusSeconds(23 * 3600L), now.plusSeconds(25 * 3600L)).copy(id = first.id)

        assertEquals(listOf(first, second), visibleScheduleEvents(listOf(second, first), now, ZoneId.of("UTC")))
    }

    @Test
    fun countdownNeverShowsZeroMinutesBeforeAnEvent() {
        assertEquals(CountdownUnit.LessThanMinute,
            eventCountdown(event(now.plusSeconds(10), now.plusSeconds(3610)), now).unit)
        assertEquals(CountdownUnit.Ongoing,
            eventCountdown(event(now.minusSeconds(10), now.plusSeconds(3590)), now).unit)
    }

    @Test
    fun allDayDatesRespectTheUsersTimezone() {
        val allDay = event(
            Instant.parse("2026-09-23T00:00:00Z"),
            Instant.parse("2026-09-24T00:00:00Z"),
            allDay = true,
        )
        val shanghai = ZoneId.of("Asia/Shanghai")
        assertEquals(4, eventCountdown(allDay, now, shanghai).value)
        assertEquals(CountdownUnit.Hours, eventCountdown(allDay, now, shanghai).unit)
        assertEquals(CountdownUnit.AllDay,
            eventCountdown(allDay, Instant.parse("2026-09-22T16:00:00Z"), shanghai).unit)
        assertEquals(listOf(allDay), visibleScheduleEvents(listOf(allDay), now, shanghai))
        assertEquals(emptyList<ScheduleEvent>(),
            visibleScheduleEvents(listOf(allDay), Instant.parse("2026-09-23T16:00:00Z"), shanghai))
    }

    @Test
    fun allDayBoundariesConvertUtcDatesInNegativeOffsetsBeforeFiltering() {
        val previousLocalDay = event(
            Instant.parse("2026-09-22T00:00:00Z"), Instant.parse("2026-09-23T00:00:00Z"), allDay = true,
        )
        val currentLocalDay = event(
            Instant.parse("2026-09-23T00:00:00Z"), Instant.parse("2026-09-24T00:00:00Z"), allDay = true,
        )
        val nextLocalDay = event(
            Instant.parse("2026-09-24T00:00:00Z"), Instant.parse("2026-09-25T00:00:00Z"), allDay = true,
        )

        assertEquals(
            listOf(previousLocalDay, currentLocalDay),
            visibleScheduleEvents(
                listOf(currentLocalDay, nextLocalDay, previousLocalDay),
                Instant.parse("2026-09-23T05:00:00Z"),
                ZoneId.of("America/Los_Angeles"),
            ),
        )
    }

    @Test
    fun allDayOccurrencesRespectDstLocalMidnightsAndElapsedWindow() {
        val transitionDay = event(
            Instant.parse("2026-03-08T00:00:00Z"), Instant.parse("2026-03-09T00:00:00Z"), allDay = true,
        )
        val nextDay = event(
            Instant.parse("2026-03-09T00:00:00Z"), Instant.parse("2026-03-10T00:00:00Z"), allDay = true,
        )
        val outside = event(
            Instant.parse("2026-03-10T00:00:00Z"), Instant.parse("2026-03-11T00:00:00Z"), allDay = true,
        )

        assertEquals(
            listOf(transitionDay, nextDay),
            visibleScheduleEvents(
                listOf(outside, nextDay, transitionDay),
                Instant.parse("2026-03-09T02:00:00Z"),
                ZoneId.of("America/New_York"),
            ),
        )
    }

    @Test
    fun agendaExpandsAllDayEventsButNotTheirExclusiveEndDate() {
        val holiday = event(Instant.parse("2026-09-25T00:00:00Z"), Instant.parse("2026-09-28T00:00:00Z"), true)
        val today = LocalDate.parse("2026-09-22")
        val days = agendaDays(listOf(holiday), today, ZoneId.of("America/Los_Angeles"))
        assertEquals(listOf(today, today.plusDays(3), today.plusDays(4), today.plusDays(5)), days.keys.toList())
        assertEquals(emptyList<ScheduleEvent>(), days[today])
        assertEquals(listOf(holiday), days[today.plusDays(3)])
    }

    @Test
    fun agendaKeepsOnlyVisibleDaysAndSeparatesRecurringInstances() {
        val today = LocalDate.parse("2026-09-22")
        val old = event(Instant.parse("2026-09-20T08:00:00Z"), Instant.parse("2026-09-20T09:00:00Z"))
        val first = event(Instant.parse("2026-09-22T08:00:00Z"), Instant.parse("2026-09-22T09:00:00Z"))
        val second = first.copy(startsAt = first.startsAt.plusSeconds(86400), endsAt = first.endsAt.plusSeconds(86400))
        val distant = event(Instant.parse("2026-10-10T08:00:00Z"), Instant.parse("2026-10-10T09:00:00Z"))
        val days = agendaDays(listOf(old, first, second, distant), today, ZoneId.of("UTC"))
        assertEquals(listOf(today, today.plusDays(1)), days.keys.toList())
        assertEquals(listOf(first), days[today])
        assertEquals(listOf(second), days[today.plusDays(1)])
    }
}
