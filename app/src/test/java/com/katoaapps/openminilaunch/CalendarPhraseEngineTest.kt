package com.katoaapps.openminilaunch

import com.katoaapps.openminilaunch.features.calendar.engine.CalendarPhraseEngine
import com.katoaapps.openminilaunch.features.calendar.model.CalendarParseIssue
import com.katoaapps.openminilaunch.features.calendar.model.CalendarParseRequest
import com.katoaapps.openminilaunch.features.calendar.model.CalendarParseResult
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarPhraseEngineTest {
    private val now = ZonedDateTime.of(
        2026,
        8,
        18,
        10,
        0,
        0,
        0,
        ZoneId.of("America/Los_Angeles"),
    )

    @Test fun reportedNamedDatePhraseIsConfident() {
        val result = parse("dr appointment sept 15th 9am")

        assertTrue(result is CalendarParseResult.Success)
        assertEquals("dr appointment", result.draft.title)
        assertEquals(dateTime(2026, 9, 15, 9, 0), result.draft.startMillis)
    }

    @Test fun bothEnglishVariantsAcceptBothNamedDateOrdersWithClocks() {
        for (language in listOf("en-US", "en-GB")) {
            for (date in listOf("30 nov", "nov 30", "30 NOVEMBER", "November 30")) {
                for (time in listOf("09:00", "at 9am")) {
                    val text = "meeting $date $time"
                    val result = parse(text, language)
                    assertTrue("$language: $text: $result", result is CalendarParseResult.Success)
                    assertEquals("meeting", result.draft.title)
                    assertEquals(dateTime(2026, 11, 30, 9, 0), result.draft.startMillis)
                    assertEquals(dateTime(2026, 11, 30, 10, 0), result.draft.endMillis)
                    assertEquals(false, result.draft.allDay)
                }
            }
        }
    }

    @Test fun namedDatesAcceptOrdinalsCaseAndExplicitYears() {
        for (language in listOf("en-US", "en-GB")) {
            for (date in listOf("30th nOv 2028", "NOV 30TH, 2028", "30TH November 2028", "november 30th 2028")) {
                val result = parse("meeting $date at 9am", language)
                assertTrue("$language: $date: $result", result is CalendarParseResult.Success)
                assertEquals("meeting", result.draft.title)
                assertEquals(dateTime(2028, 11, 30, 9, 0), result.draft.startMillis)
            }
        }
    }

    @Test fun invalidNamedDatesWithClocksRequireReviewWithoutTimeOnlyFallback() {
        for (language in listOf("en-US", "en-GB")) {
            for (date in listOf("31 nov", "30 feb", "31st November 2027", "30th February 2028", "nov 31", "feb 30")) {
                for (time in listOf("09:00", "at 9am")) {
                    val result = parse("meeting $date $time", language)
                    assertTrue("$language: $date $time: $result", result is CalendarParseResult.NeedsReview)
                    assertEquals(CalendarParseIssue.INVALID_DATE, (result as CalendarParseResult.NeedsReview).issue)
                    assertEquals("meeting $date", result.draft.title)
                    assertEquals(null, result.draft.startMillis)
                    assertEquals(null, result.draft.endMillis)
                }
            }
        }
    }

    @Test fun namedDateSpansPreserveTitleAndApplyDuration() {
        for (language in listOf("en-US", "en-GB")) {
            for (date in listOf("30 nov", "nov 30")) {
                val result = parse("planning $date at 9am for 30 minutes", language)
                assertTrue("$language: $date: $result", result is CalendarParseResult.Success)
                assertEquals("planning", result.draft.title)
                assertEquals(dateTime(2026, 11, 30, 9, 0), result.draft.startMillis)
                assertEquals(dateTime(2026, 11, 30, 9, 30), result.draft.endMillis)
            }
        }
    }

    @Test fun yearlessNamedDatesUseNextOccurrenceAndKeepDateOnlyAllDay() {
        for (language in listOf("en-US", "en-GB")) {
            for (date in listOf("30 nov", "nov 30")) {
                for ((current, year) in listOf(now to 2026, now.withMonth(12) to 2027)) {
                    val result = parse("meeting $date", language, current)
                    assertTrue("$language: $date: $result", result is CalendarParseResult.Success)
                    assertEquals("meeting", result.draft.title)
                    assertEquals(dateTime(year, 11, 30, 0, 0), result.draft.startMillis)
                    assertEquals(dateTime(year, 12, 1, 0, 0), result.draft.endMillis)
                    assertEquals(true, result.draft.allDay)
                }
            }
        }
    }

    @Test fun sameDayNamedDateDoesNotRollPastTimeIntoTomorrow() {
        for (language in listOf("en-US", "en-GB")) {
            for (date in listOf("30 nov", "nov 30")) {
                val result = parse("meeting $date at 9am", language, now.withMonth(11).withDayOfMonth(30))
                assertTrue("$language: $date: $result", result is CalendarParseResult.Success)
                assertEquals(dateTime(2026, 11, 30, 9, 0), result.draft.startMillis)
            }
        }
    }

    @Test fun namedDatesPreserveRequestedZoneAndDefaultDuration() {
        val current = now.withZoneSameInstant(ZoneId.of("Europe/Berlin"))
        for (language in listOf("en-US", "en-GB")) {
            val result = CalendarPhraseEngine.parse(CalendarParseRequest(
                text = "meeting 30 nov 09:00", defaultTitle = "New event", languageTag = language,
                now = current, defaultDuration = Duration.ofMinutes(45),
            ))
            assertTrue("$language: $result", result is CalendarParseResult.Success)
            val start = ZonedDateTime.of(2026, 11, 30, 9, 0, 0, 0, current.zone)
            assertEquals(start.toInstant().toEpochMilli(), result.draft.startMillis)
            assertEquals(start.plusMinutes(45).toInstant().toEpochMilli(), result.draft.endMillis)
        }
    }

    @Test fun numericDateOrderingRemainsLocaleSpecific() {
        for ((language, month, day) in listOf(Triple("en-US", 11, 12), Triple("en-GB", 12, 11))) {
            val result = parse("meeting 11/12 at 9am", language)
            assertTrue("$language: $result", result is CalendarParseResult.Success)
            assertEquals(dateTime(2026, month, day, 9, 0), result.draft.startMillis)
        }
    }

    @Test fun separateNamedDatesStillRequireConflictReview() {
        for (language in listOf("en-US", "en-GB")) {
            for (dates in listOf("nov 30 nov 30", "30 nov 30 nov", "nov 30 dec 1", "30 nov 1 dec")) {
                val result = parse("meeting $dates at 9am", language)
                assertTrue("$language: $dates: $result", result is CalendarParseResult.NeedsReview)
                assertEquals(CalendarParseIssue.CONFLICTING_TEMPORAL_PHRASES, (result as CalendarParseResult.NeedsReview).issue)
                assertEquals(null, result.draft.startMillis)
            }
        }
    }

    @Test fun reportedNextWeekdayPhraseIsConfident() {
        val result = parse("dr appointment next wed 8am")

        assertTrue(result is CalendarParseResult.Success)
        assertEquals(dateTime(2026, 8, 19, 8, 0), result.draft.startMillis)
    }

    @Test fun unsupportedDateCannotDegradeIntoTimeOnly() {
        val result = parse("review next month at 5pm")

        assertTrue(result is CalendarParseResult.NeedsReview)
        assertEquals(CalendarParseIssue.UNSUPPORTED_TEMPORAL_PHRASE, (result as CalendarParseResult.NeedsReview).issue)
        assertEquals("review next month", result.draft.title)
        assertEquals(null, result.draft.startMillis)
    }

    @Test fun ordinaryForPhraseStaysInTitle() {
        val result = parse("movie for friends tomorrow at 7pm")

        assertTrue(result is CalendarParseResult.Success)
        assertEquals("movie for friends", result.draft.title)
    }

    @Test fun numericDurationIsRemovedFromTitleAndApplied() {
        val result = parse("planning tomorrow at 9am for 30 minutes")

        assertTrue(result is CalendarParseResult.Success)
        assertEquals("planning", result.draft.title)
        assertEquals(dateTime(2026, 8, 19, 9, 0), result.draft.startMillis)
        assertEquals(dateTime(2026, 8, 19, 9, 30), result.draft.endMillis)
    }

    @Test fun multipleDatesRequireReview() {
        val result = parse("planning tomorrow next Friday at 9am")

        assertTrue(result is CalendarParseResult.NeedsReview)
        assertEquals(
            CalendarParseIssue.CONFLICTING_TEMPORAL_PHRASES,
            (result as CalendarParseResult.NeedsReview).issue,
        )
    }

    @Test fun noTemporalPhraseStaysUntimed() {
        val result = parse("planning session for launch")

        assertTrue(result is CalendarParseResult.NoTemporalPhrase)
        assertEquals("planning session for launch", result.draft.title)
        assertEquals(null, result.draft.startMillis)
    }

    @Test fun unsupportedEnglishRegionDoesNotFallBackToUsNumericDates() {
        val result = CalendarPhraseEngine.parse(
            CalendarParseRequest(
                text = "appointment 15/9 at 9am",
                defaultTitle = "New event",
                languageTag = "en-AU",
                now = now,
            ),
        )

        assertTrue(result is CalendarParseResult.NeedsReview)
        assertEquals(
            CalendarParseIssue.UNSUPPORTED_LANGUAGE,
            (result as CalendarParseResult.NeedsReview).issue,
        )
        assertEquals("appointment 15/9 at 9am", result.draft.title)
    }

    private fun parse(text: String, languageTag: String = "en-US", now: ZonedDateTime = this.now) = CalendarPhraseEngine.parse(
        CalendarParseRequest(
            text = text,
            defaultTitle = "New event",
            languageTag = languageTag,
            now = now,
        ),
    )

    private fun dateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, now.zone).toInstant().toEpochMilli()
}
