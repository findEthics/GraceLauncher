package com.galaxyrio.gracelauncher.assistant

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ActionRequestPolicyTest {
    private val now = LocalDateTime.of(2026, 7, 1, 9, 0)
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun ok(name: String, request: String, vararg args: Pair<String, String>, at: LocalDateTime = now, zone: ZoneId = berlin) =
        ActionRequestPolicy.validate(MobileAction(name, mapOf(*args)), request, at, zone)

    private fun bad(name: String, request: String, vararg args: Pair<String, String>, at: LocalDateTime = now, zone: ZoneId = berlin) {
        try {
            ok(name, request, *args, at = at, zone = zone)
            fail("Expected rejection: $request")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun emptyRequestAndMismatchedToolsAreRejected() {
        bad("turnOnFlashlight", "")
        bad("turnOnFlashlight", "   ")
        bad("turnOnFlashlight", "open wifi settings")
        bad("openWifiSettings", "turn on the flashlight")
        ok("turnOnFlashlight", "Turn on the flashlight")
        ok("turnOffFlashlight", "torch off please")
        bad("turnOnFlashlight", "turn off the flashlight")
    }

    @Test fun negationAndMultipleCommandsAreRejected() {
        bad("turnOnFlashlight", "don't turn on the flashlight")
        bad("turnOnFlashlight", "do not turn on the flashlight")
        bad("turnOnFlashlight", "turn on the flashlight and open wifi settings")
        bad("turnOnFlashlight", "turn on the flashlight then call Ada")
        bad("openWifiSettings", "open wifi settings; turn on the torch")
        bad("turnOnFlashlight", "turn on airplane mode and the flashlight")
        bad("turnOnFlashlight", "is the flashlight on")
        bad("turnOnFlashlight", "turn on the flashlight and buy milk")
        bad("openWifiSettings", "turn off wifi")
    }

    @Test fun timerMustMatchExplicitDuration() {
        ok("createTimer", "set a timer for 5 minutes", "duration" to "5 minutes", "label" to "")
        ok("createTimer", "timer for 1 hour 30 minutes", "duration" to "90m", "label" to "")
        ok("createTimer", "set a timer for 24 hours", "duration" to "24 hours", "label" to "")
        ok("createTimer", "start a 10 second timer", "duration" to "10s", "label" to "")
        bad("createTimer", "set a timer for 5 minutes", "duration" to "6 minutes", "label" to "")
        bad("createTimer", "set a timer for 25 hours", "duration" to "25 hours", "label" to "")
        bad("createTimer", "set a timer for 0 seconds", "duration" to "0 seconds", "label" to "")
        bad("createTimer", "set a timer for 5 or 10 minutes", "duration" to "5 minutes", "label" to "")
        bad("createTimer", "set a timer", "duration" to "5 minutes", "label" to "")
        bad("createTimer", "set a timer for 5 minutes", "duration" to "5 minutes", "label" to "pasta")
        ok("createTimer", "set a pasta timer for 5 minutes", "duration" to "5 minutes", "label" to "pasta")
    }

    @Test fun alarmDayRules() {
        ok("createAlarm", "set an alarm for 22:30", "time" to "22:30", "label" to "")
        bad("createAlarm", "set an alarm for 10:30", "time" to "10:30", "label" to "") // ambiguous 12h/24h
        ok("createAlarm", "set an alarm for 10:30 am", "time" to "10:30", "label" to "")
        bad("createAlarm", "set an alarm for 10:30 am", "time" to "10:31", "label" to "")
        ok("createAlarm", "set an alarm for 8:30 am", "time" to "08:30", "label" to "") // passed today, implicitly tomorrow
        bad("createAlarm", "set an alarm for 8:30 am today", "time" to "08:30", "label" to "")
        ok("createAlarm", "set an alarm for 8:30 am tomorrow", "time" to "08:30", "label" to "")
        ok("createAlarm", "wake me up tomorrow at 8:30 am", "time" to "08:30", "label" to "")
        bad("createAlarm", "set an alarm for 10:30 am tomorrow", "time" to "10:30", "label" to "") // still ahead today
        bad("createAlarm", "set an alarm for 8:30 am on monday", "time" to "08:30", "label" to "")
        bad("createAlarm", "set an alarm for 8:30 am every day", "time" to "08:30", "label" to "")
        bad("createAlarm", "set an alarm", "time" to "08:30", "label" to "")
    }

    @Test fun daylessAlarmFormsUseTheNextOccurrence() {
        for (request in listOf("alarm 7am", "alarm 7:00am", "alarm 07:00", "wake me up at 7am")) {
            ok("createAlarm", request, "time" to "07:00", "label" to "", at = now.withHour(5))
            ok("createAlarm", request, "time" to "07:00", "label" to "")
            bad("createAlarm", "$request today", "time" to "07:00", "label" to "")
            ok("createAlarm", "$request tomorrow", "time" to "07:00", "label" to "")
        }
        bad("createAlarm", "alarm 7:00", "time" to "07:00", "label" to "", at = now.withHour(5))
        ok("createAlarm", "alarm 1am tomorrow", "time" to "01:00", "label" to "", at = now.withHour(23).withMinute(30))
        ok("createAlarm", "alarm 1am", "time" to "01:00", "label" to "", at = now.withHour(23).withMinute(30))
    }

    @Test fun alarmOccurrenceRollsDatesWithoutOverridingExplicitDays() {
        val seven = LocalTime.of(7, 0)
        val today = now.toLocalDate().atTime(seven)
        val request = "wake me up at 7am"
        assertEquals(today, ActionInputValidation.alarmOccurrence(seven, now.withHour(5), request))
        assertEquals(today.plusDays(1), ActionInputValidation.alarmOccurrence(seven, now, request))
        assertEquals(today, ActionInputValidation.alarmOccurrence(seven, now.withHour(5), "$request today"))
        assertNull(ActionInputValidation.alarmOccurrence(seven, now, "$request today"))
        assertEquals(today.plusDays(1), ActionInputValidation.alarmOccurrence(seven, now, "$request tomorrow"))
        assertNull(ActionInputValidation.alarmOccurrence(seven, now.withHour(5), "$request tomorrow"))
        assertNull(ActionInputValidation.alarmOccurrence(seven, today, request))
        assertNull(ActionInputValidation.alarmOccurrence(seven, now, "$request today tomorrow"))
        val yearEnd = LocalDateTime.of(2026, 12, 31, 23, 30)
        assertEquals(LocalDateTime.of(2027, 1, 1, 1, 0),
            ActionInputValidation.alarmOccurrence(LocalTime.of(1, 0), yearEnd, "alarm 1am"))
    }

    @Test fun callTargetNeedsWholeNameAndLabel() {
        val a = "callContact"
        ok(a, "call John Smith", "contactName" to "John Smith", "phoneLabel" to "")
        ok(a, "Please call john smith on mobile", "contactName" to "John Smith", "phoneLabel" to "mobile")
        ok(a, "call Ada at her cell", "contactName" to "Ada", "phoneLabel" to "Mobile")
        bad(a, "call John Smith", "contactName" to "John", "phoneLabel" to "")
        bad(a, "call John", "contactName" to "John Smith", "phoneLabel" to "")
        bad(a, "call Johnson", "contactName" to "John", "phoneLabel" to "")
        bad(a, "call John Smith on work", "contactName" to "John Smith", "phoneLabel" to "")
        bad(a, "call John Smith", "contactName" to "John Smith", "phoneLabel" to "home")
        bad(a, "call John Smith on home", "contactName" to "John Smith", "phoneLabel" to "work")
        bad(a, "call 911", "contactName" to "911", "phoneLabel" to "")
        bad(a, "call John and Mary", "contactName" to "John", "phoneLabel" to "")
        bad(a, "text John Smith", "contactName" to "John Smith", "phoneLabel" to "")
        assertEquals(ActionRequestPolicy.CallTarget("john smith", "work"), ActionRequestPolicy.callTarget("call John Smith on his work number"))
    }

    @Test fun contactValuesMustBeGrounded() {
        val c = "createContact"
        val req = "create a contact Ada Lovelace phone +44 20 1234 5678 email ada@example.com"
        ok(c, req, "firstName" to "Ada", "lastName" to "Lovelace", "phoneNumber" to "+44 20 1234 5678", "email" to "ada@example.com")
        ok(c, "add a contact Ada Lovelace phone 020 1234 5678", "firstName" to "Ada", "lastName" to "Lovelace", "phoneNumber" to "02012345678", "email" to "")
        bad(c, req, "firstName" to "Ada", "lastName" to "Lovelace", "phoneNumber" to "+44 20 1234 5679", "email" to "ada@example.com")
        bad(c, req, "firstName" to "Ada", "lastName" to "Lovelace", "phoneNumber" to "+44 20 1234 5678", "email" to "other@example.com")
        bad(c, req, "firstName" to "Ada", "lastName" to "Lovelace", "phoneNumber" to "+44 20 1234 5678", "email" to "")
        bad(c, req, "firstName" to "Grace", "lastName" to "Hopper", "phoneNumber" to "+44 20 1234 5678", "email" to "ada@example.com")
        bad(c, "create a contact Ada Lovelace", "firstName" to "Ada", "lastName" to "Lovelace", "phoneNumber" to "+44 20 1234 5678", "email" to "")
    }

    @Test fun calendarTitleAndTimeMustBeGroundedAndDstSafe() {
        val e = "createCalendarEvent"
        ok(e, "add a calendar event dentist at 3 pm", "datetime" to "2026-07-01T15:00:00", "title" to "dentist")
        ok(e, "add calendar event dentist tomorrow at 3 pm", "datetime" to "2026-07-02T15:00:00", "title" to "Dentist")
        bad(e, "add calendar event dentist at 3 pm", "datetime" to "2026-07-01T15:00:00", "title" to "Doctor")
        bad(e, "add calendar event dentist at 3 pm", "datetime" to "2026-07-01T16:00:00", "title" to "dentist")
        bad(e, "add calendar event dentist at 8 am", "datetime" to "2026-07-01T08:00:00", "title" to "dentist") // past
        bad(e, "add calendar event dentist at 3 pm on friday", "datetime" to "2026-07-03T15:00:00", "title" to "dentist")
        bad(e, "add calendar event dentist at 3 pm", "datetime" to "2026-07-01T15:00:00", "title" to "at")
        bad(e, "add calendar event dentist at 3 pm for one hour", "datetime" to "2026-07-01T15:00:00", "title" to "dentist")
        // DST gap 02:30 on 2026-03-29 in Berlin and ambiguous 02:30 on 2026-10-25.
        val gapNow = LocalDateTime.of(2026, 3, 28, 12, 0)
        bad(e, "add calendar event dentist tomorrow at 2:30 am", "datetime" to "2026-03-29T02:30:00", "title" to "dentist", at = gapNow)
        val overlapNow = LocalDateTime.of(2026, 10, 24, 12, 0)
        bad(e, "add calendar event dentist tomorrow at 2:30 am", "datetime" to "2026-10-25T02:30:00", "title" to "dentist", at = overlapNow)
    }

    @Test fun emailMustBeInRequest() {
        val a = arrayOf("to" to "bob@example.com", "subject" to "Hi", "body" to "Hello")
        ok("sendEmail", "send an email to bob@example.com", *a)
        bad("sendEmail", "send an email to someone", *a)
        bad("sendEmail", "send an email to alice@example.com", *a)
    }

    @Test fun containsTokensUsesBoundaries() {
        assertTrue(ActionRequestPolicy.containsTokens("call john smith now", "John Smith"))
        assertFalse(ActionRequestPolicy.containsTokens("call johnson", "John"))
        assertFalse(ActionRequestPolicy.containsTokens("call john", ""))
        assertEquals(ActionRequestPolicy.nameKey("Ada Lovelace"), ActionRequestPolicy.nameKey("lovelace  ADA"))
    }
}
