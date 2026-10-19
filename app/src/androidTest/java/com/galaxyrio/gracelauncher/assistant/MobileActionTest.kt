package com.galaxyrio.gracelauncher.assistant

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import android.net.MailTo
import android.net.Uri
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDateTime
import java.util.TimeZone
import android.provider.AlarmClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileActionTest {
    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid action to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected; never include private arguments in failure messages.
        }
    }

    private fun email(to: String = "person@example.com", subject: String = "Subject", body: String = "Body") =
        MobileAction("sendEmail", mapOf("to" to to, "subject" to subject, "body" to body))

    @Test fun rejectsUnknownNamesKeysAndNonStringToolArguments() {
        rejects { MobileActionExecutor.validate(MobileAction("arbitraryIntent", emptyMap())) }
        rejects { MobileActionExecutor.validate(MobileAction("openWifiSettings", mapOf("uri" to "intent:"))) }
        rejects { MobileActionExecutor.validate(email().copy(arguments = mapOf("to" to "person@example.com"))) }
        rejects { MobileAction.fromToolCall("sendEmail", mapOf("to" to 42)) }
        rejects { MobileAction.fromToolCall("openWifiSettings", mapOf("unexpected" to null)) }
        rejects { MobileActionExecutor.intentFor(MobileAction("sendEmail", email().arguments + ("bcc" to "other@example.com"))) }
    }

    @Test fun rejectsMalformedBlankOversizeAndControlValues() {
        listOf("", "not-an-email", "a@example.com,b@example.com", "a@example.com\r\nBcc:x@example.com").forEach {
            rejects { MobileActionExecutor.validate(email(to = it)) }
        }
        rejects { MobileActionExecutor.validate(email(subject = " ")) }
        rejects { MobileActionExecutor.validate(email(body = "\t\n")) }
        rejects { MobileActionExecutor.validate(email(subject = "x".repeat(2001))) }
        rejects { MobileActionExecutor.validate(email(body = "x".repeat(8001))) }
        listOf("\u0000", "\r", "\u007f", "\u0085").forEach {
            rejects { MobileActionExecutor.validate(email(body = "body$it")) }
        }
        rejects { MobileActionExecutor.validate(email(subject = "subject\nline")) }
        rejects { MobileActionExecutor.validate(MobileAction("showLocationOnMap", mapOf("location" to "\t"))) }
        MobileActionExecutor.validate(email(subject = "x".repeat(2000), body = "x".repeat(8000)))
        MobileActionExecutor.validate(email(body = "First line\nSecond\tcolumn"))
    }

    @Test fun contactRequiresNameAndValidContactDetail() {
        val fields = mapOf("firstName" to "Ada", "lastName" to "", "phoneNumber" to "+44 20 1234 5678", "email" to "")
        MobileActionExecutor.validate(MobileAction("createContact", fields))
        rejects { MobileActionExecutor.validate(MobileAction("createContact", fields + ("firstName" to ""))) }
        rejects { MobileActionExecutor.validate(MobileAction("createContact", fields + ("phoneNumber" to ""))) }
        rejects { MobileActionExecutor.validate(MobileAction("createContact", fields + ("phoneNumber" to "call-me"))) }
        rejects { MobileActionExecutor.validate(MobileAction("createContact", fields + ("email" to "bad"))) }
        // Direct provider save: no editor intent is exposed, and nothing is written here.
        assertNull(MobileActionExecutor.intentFor(MobileAction("createContact", fields)))
        assertArrayEquals(arrayOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS),
            MobileActionExecutor.requiredPermissions(MobileAction("createContact", fields)))
    }

    @Test fun emailAndMapUrisEncodeDataWithoutInjectingParameters() {
        val action = email(to = "person+tag@example.com", subject = "A & bcc=other@example.com?#%", body = "One\nTwo\t&subject=changed + %")
        val intent = requireNotNull(MobileActionExecutor.intentFor(action))
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        val draft = MailTo.parse(requireNotNull(intent.data).toString())
        assertEquals(action.arguments["to"], draft.to)
        assertEquals(action.arguments["subject"], intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(action.arguments["body"], intent.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(draft.headers["bcc"])
        assertNull(draft.cc)
        assertNull(intent.component)
        assertNull(intent.`package`)
        assertEquals(0, intent.flags)
        val location = "Cafe &q=elsewhere?# + 50%"
        val map = requireNotNull(MobileActionExecutor.intentFor(MobileAction("showLocationOnMap", mapOf("location" to location))))
        assertEquals(Intent.ACTION_VIEW, map.action)
        assertEquals("geo:0,0?q=${Uri.encode(location)}", map.data.toString())
        val settings = requireNotNull(MobileActionExecutor.intentFor(MobileAction("openWifiSettings", emptyMap())))
        assertEquals(Settings.ACTION_WIFI_SETTINGS, settings.action)
    }

    @Test fun calendarStrictlyParsesAndUsesCurrentTimezoneRejectingDstGaps() {
        val original = TimeZone.getDefault()
        fun event(datetime: String) = MobileAction("createCalendarEvent", mapOf("datetime" to datetime, "title" to "Meeting"))
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            listOf("2026-02-30T10:00:00", "2026-03-29T02:30:00", "2026-01-01T24:00:00", "2026-01-01T10:00:00Z", "not-a-date").forEach {
                rejects { MobileActionExecutor.validate(event(it)) }
            }
            val action = event("2026-07-01T10:30:00")
            MobileActionExecutor.validate(action)
            assertNull(MobileActionExecutor.intentFor(action)) // direct save, no calendar editor
            assertEquals(2, MobileActionExecutor.requiredPermissions(action).size)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun newToolsValidateRequestAndExposeClockIntentsWithSkipUi() {
        val now = LocalDateTime.of(2026, 7, 1, 9, 0)
        val timer = MobileAction("createTimer", mapOf("duration" to "5 minutes", "label" to ""))
        MobileActionExecutor.validateRequest(timer, "set a timer for 5 minutes", now)
        rejects { MobileActionExecutor.validateRequest(timer, "", now) }
        rejects { MobileActionExecutor.validateRequest(timer, "set a timer for 6 minutes", now) }
        val intent = requireNotNull(MobileActionExecutor.intentFor(timer))
        assertEquals(AlarmClock.ACTION_SET_TIMER, intent.action)
        assertEquals(300, intent.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
        assertTrue(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
        val alarm = MobileAction("createAlarm", mapOf("time" to "22:30", "label" to ""))
        MobileActionExecutor.validateRequest(alarm, "alarm 10:30pm", now)
        val alarmIntent = requireNotNull(MobileActionExecutor.intentFor(alarm))
        assertEquals(AlarmClock.ACTION_SET_ALARM, alarmIntent.action)
        assertEquals(22, alarmIntent.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(30, alarmIntent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertTrue(alarmIntent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
        assertFalse(alarmIntent.hasExtra(AlarmClock.EXTRA_DAYS))
        var launches = 0
        val capturingContext = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun startActivity(intent: Intent) {
                launches++
                assertEquals(AlarmClock.ACTION_SET_ALARM, intent.action)
                assertEquals(22, intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
                assertEquals(30, intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
                assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
            }
        }
        assertEquals("Alarm requested from Clock; not confirmed set.",
            MobileActionExecutor.execute(capturingContext, alarm, "alarm 10:30pm", now))
        assertEquals(1, launches) // Captured only; never starts Clock or schedules a real alarm.
        val call = MobileAction("callContact", mapOf("contactName" to "John", "phoneLabel" to ""))
        rejects { MobileActionExecutor.validateRequest(call, "call John Smith", now) }
        assertNull(MobileActionExecutor.intentFor(call))
        assertEquals(3, MobileActionExecutor.requiredPermissions(call).size)
    }

    @Test fun executionUsesSafeTaskFlagsAndGenericFailuresWithoutLaunchingApps() {
        var launched: Intent? = null
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun startActivity(intent: Intent) { launched = intent }
        }
        val action = email()
        val request = "send an email to person@example.com"
        assertTrue(action.execute(context).startsWith("Action not performed")) // empty request fails safely
        assertNull(launched)
        action.execute(context, request)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, requireNotNull(launched).flags)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = object : Activity() {
                override fun startActivity(intent: Intent) { launched = intent }
            }
            action.execute(activity, request)
            assertEquals(0, requireNotNull(launched).flags)
        }
        launched = null
        MobileAction("unknown", emptyMap()).execute(context, request)
        assertNull(launched)
        val unavailable = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException("private details") }
        }
        assertEquals("No installed app can open this action.", action.execute(unavailable, request))
        val restricted = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw SecurityException("private details") }
        }
        assertEquals("Action not performed: permission is missing or access is restricted.", action.execute(restricted, request))
    }

    @Test fun toolsOnlyQueueAllActions() {
        val queued = mutableListOf<MobileAction>()
        val tools = MobileActionTools { queued.add(it) }
        val results = listOf(
            tools.turnOnFlashlight(), tools.turnOffFlashlight(),
            tools.createContact("Ada", "Lovelace", "+44 20 1234 5678", "ada@example.com"),
            tools.sendEmail("person@example.com", "Subject", "Body"),
            tools.showLocationOnMap("London"), tools.openWifiSettings(),
            tools.createCalendarEvent("2026-07-01T10:00:00", "Meeting"),
            tools.createTimer("5 minutes", ""), tools.createAlarm("22:30", ""), tools.callContact("Ada Lovelace", ""),
        )
        assertEquals(listOf("turnOnFlashlight", "turnOffFlashlight", "createContact", "sendEmail", "showLocationOnMap", "openWifiSettings", "createCalendarEvent", "createTimer", "createAlarm", "callContact"), queued.map { it.name })
        results.forEach { assertEquals(mapOf("result" to "queued"), it) }
        assertTrue(queued[0].needsCamera)
        assertTrue(queued[1].needsCamera)
        queued.drop(2).forEach { assertFalse(it.needsCamera) }
        assertNull(MobileActionExecutor.intentFor(queued[0]))
        assertNull(MobileActionExecutor.intentFor(queued[1]))
        val input = mutableMapOf<String, Any?>("location" to "London")
        val copied = MobileAction.fromToolCall("showLocationOnMap", input)
        input["location"] = "Paris"
        assertEquals("London", copied.arguments["location"])
    }
}
