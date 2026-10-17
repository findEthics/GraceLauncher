package com.galaxyrio.gracelauncher.assistant

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Real model inference only. Never dispatches tools, writes providers or places calls. */
class AssistantOfflineTest {
    @Test fun verifiedGemmaRoutesCoreCommandsWithinContext() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = AssistantController(context, this)
        try {
            assertTrue("Seed the verified Gemma4 model for this test", controller.files.ready(AssistantModel.Gemma4))
            val alarmNow = LocalDateTime.now()
            val alarmAt = alarmNow.plusMinutes(90)
            val alarmDay = if (alarmAt.toLocalDate() > alarmNow.toLocalDate()) " tomorrow" else " today"
            val cases = listOf(
                "Open Wi-Fi settings" to "openWifiSettings",
                "Turn on the flashlight" to "turnOnFlashlight",
                "Turn off the flashlight" to "turnOffFlashlight",
                "Set a timer for five minutes" to "createTimer",
                "Set an alarm for ${alarmAt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))}$alarmDay" to "createAlarm",
                "Call John Smith on mobile" to "callContact",
                "Call John Smith" to "callContact",
                "Create a contact Ada Lovelace phone +442012345678" to "createContact",
                "Create a contact Grace Hopper phone +12025550123" to "createContact",
                "Draft an email to person@example.com subject Hello body Test" to "sendEmail",
                "Show Paris on the map" to "showLocationOnMap",
                "Add a calendar event Dentist tomorrow at 3 PM" to "createCalendarEvent",
            )
            val filter = InstrumentationRegistry.getArguments().getString("actionCase")
            val selected = cases.filter { filter == null || it.second == filter }
            assertTrue("Unknown action case", selected.isNotEmpty())
            for ((request, expected) in selected) {
                val action = try { controller.infer(AssistantModel.Gemma4, request) }
                    catch (e: Exception) { throw AssertionError("Inference case $expected failed before dispatch", e) }
                assertEquals(expected, action.name)
                MobileActionExecutor.validateRequest(action, request)
                assertTrue(controller.lastTokenCount in 1 until AssistantController.CONTEXT_TOKENS - 256)
            }
            if (filter == null) for (request in listOf("Turn on airplane mode", "Call John Smith and turn on the flashlight", "Set a timer")) {
                var rejected = false
                try {
                    val action = controller.infer(AssistantModel.Gemma4, request)
                    MobileActionExecutor.validateRequest(action, request)
                } catch (_: IllegalArgumentException) { rejected = true }
                assertTrue("Unsupported/incomplete command must not dispatch", rejected)
            }
        } finally { controller.close() }
    }

    @Test fun alarmOnlyInferenceSeparatesProposalAndPolicyFailuresWithoutDispatch() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = AssistantController(context, this)
        try {
            assertTrue("Verified Gemma4 must be present", controller.files.ready(AssistantModel.Gemma4))
            val now = LocalDateTime.now()
            val seven = LocalTime.of(7, 0)
            val sevenToday = seven.takeIf { now.toLocalTime().isBefore(it) }
            val sevenDay = if (sevenToday == null) " tomorrow" else ""
            val future = now.plusMinutes(90)
            val futureDay = if (future.toLocalDate() > now.toLocalDate()) " tomorrow" else " today"
            val cases = listOf<Triple<String, String, LocalTime?>>(
                Triple("ambiguous-short", "alarm 7:00", null),
                Triple("reported-hour-am", "alarm 7am", sevenToday),
                Triple("reported-minute-am", "alarm 7:00am", sevenToday),
                Triple("future-hour-am", "alarm 7am$sevenDay", seven),
                Triple("future-minute-am", "alarm 7:00am$sevenDay", seven),
                Triple("future-24h", "alarm 07:00$sevenDay", seven),
                Triple("future-full-request", "Set an alarm for ${future.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))}$futureDay", future.toLocalTime().withSecond(0).withNano(0)),
            ).distinctBy { it.second }
            val failures = mutableListOf<String>()
            for ((case, request, expected) in cases) {
                var stage = "inference/schema"
                try {
                    val action = controller.infer(AssistantModel.Gemma4, request)
                    stage = "request-policy"
                    MobileActionExecutor.validateRequest(action, request, now)
                    if (expected == null || action.name != "createAlarm" || ActionInputValidation.parseLocalTime(action.arguments["time"].orEmpty()) != expected) {
                        failures += "$case: unexpected accepted proposal"
                    } else println("Alarm check $case: accepted; not dispatched")
                } catch (e: IllegalArgumentException) {
                    // Fixed validator reasons only; never print raw requests, model output, or SDK exception text.
                    val reason = when (e.message) {
                        "Expected exactly one action" -> "no-single-proposal"
                        "Action arguments do not match the required fields." -> "required-fields"
                        "Action arguments must be strings." -> "argument-types"
                        "Required action arguments cannot be blank." -> "blank-required-field"
                        "Use a valid local alarm time in HH:mm format." -> "alarm-time-format"
                        "Say an explicit alarm time that matches. No alarm was set." -> "time-grounding"
                        "Alarms are only for a time ahead today, or tomorrow after that time passed today. No alarm was set." -> "alarm-day"
                        else -> "invalid-proposal"
                    }
                    if (expected == null) println("Alarm check $case: rejected at $stage ($reason); not dispatched")
                    else failures += "$case: $stage ($reason)"
                } catch (e: Exception) {
                    failures += "$case: $stage (${e.javaClass.simpleName})"
                }
            }
            assertTrue("Alarm checks failed: ${failures.joinToString("; ")}", failures.isEmpty())
        } finally { controller.close() }
    }

    @Test fun toolFreeChatStreamsAndRemembersOnlySuppliedSessionContext() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = AssistantController(context, this)
        try {
            assertTrue("Verified Gemma4 must be present", controller.files.ready(AssistantModel.Gemma4))
            val request = "Remember the code word ORCHID. Reply only OK."
            var response = ""
            var chunks = 0
            controller.inferChat(request) { response = it; if (it.isNotBlank()) chunks++ }
            assertTrue("Chat must produce text", response.isNotBlank())
            assertTrue(chunks > 0)
            val history = listOf(ChatTurn(1, request, false, response, complete = true, inContext = true))
            response = ""
            controller.inferChat("What code word did I give you? Reply with the word only.", history) { response = it }
            assertTrue("Current-session context must be available", response.contains("ORCHID", ignoreCase = true))
            assertTrue(controller.lastTokenCount in 1 until AssistantController.CONTEXT_TOKENS)
            response = ""
            controller.inferChat("How do timers work? Answer in one short sentence.") { response = it }
            assertTrue("Discussing actions must remain ordinary chat", response.isNotBlank())
        } finally { controller.close() }
    }
}
