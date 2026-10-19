package com.galaxyrio.gracelauncher.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileActionDefaultsTest {
    @Test
    fun toolMethodsDefaultOptionalFieldsToBlank() {
        val queued = mutableListOf<MobileAction>()
        val tools = MobileActionTools { queued += it }

        tools.createTimer("5 minutes")
        tools.createAlarm("07:30")
        tools.callContact("Alex Smith")
        tools.createContact("Ada")

        assertEquals(
            listOf(
                mapOf("duration" to "5 minutes", "label" to ""),
                mapOf("time" to "07:30", "label" to ""),
                mapOf("contactName" to "Alex Smith", "phoneLabel" to ""),
                mapOf("firstName" to "Ada", "lastName" to "", "phoneNumber" to "", "email" to ""),
            ),
            queued.map { it.arguments },
        )
    }

    @Test
    fun timerLabelDefaultsWhenOmitted() {
        assertEquals(
            "",
            MobileAction.fromToolCall("createTimer", mapOf("duration" to "5 minutes")).arguments["label"],
        )
    }

    @Test
    fun alarmLabelDefaultsWhenOmitted() {
        assertEquals(
            "",
            MobileAction.fromToolCall("createAlarm", mapOf("time" to "07:30")).arguments["label"],
        )
    }

    @Test
    fun callPhoneLabelDefaultsWhenOmitted() {
        assertEquals(
            "",
            MobileAction.fromToolCall("callContact", mapOf("contactName" to "Alex Smith")).arguments["phoneLabel"],
        )
    }

    @Test
    fun explicitTimerLabelIsPreserved() {
        assertEquals(
            "study",
            MobileAction.fromToolCall("createTimer", mapOf("duration" to "5 minutes", "label" to "study")).arguments["label"],
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun timerDurationRemainsRequired() {
        MobileAction.fromToolCall("createTimer", emptyMap())
    }

    @Test(expected = IllegalArgumentException::class)
    fun alarmTimeRemainsRequired() {
        MobileAction.fromToolCall("createAlarm", emptyMap())
    }

    @Test(expected = IllegalArgumentException::class)
    fun callContactNameRemainsRequired() {
        MobileAction.fromToolCall("callContact", emptyMap())
    }

    @Test(expected = IllegalArgumentException::class)
    fun explicitNullOptionalFieldStillFails() {
        MobileAction.fromToolCall(
            "createTimer",
            mapOf<String, Any?>("duration" to "5 minutes", "label" to null),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongTypeStillFails() {
        MobileAction.fromToolCall(
            "createTimer",
            mapOf<String, Any?>("duration" to "5 minutes", "label" to 1),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun extraArgumentStillFails() {
        MobileAction.fromToolCall(
            "createTimer",
            mapOf("duration" to "5 minutes", "extra" to "value"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun contactFirstNameIsNotInventedOrDefaulted() {
        MobileAction.fromToolCall("createContact", mapOf("lastName" to "Lovelace", "phoneNumber" to "+442012345678"))
    }

    @Test fun contactKeepsProvidedNameAndDefaultsOnlyOptionalFields() {
        val action = MobileAction.fromToolCall("createContact", mapOf("firstName" to "Ada", "phoneNumber" to "+442012345678"))
        assertEquals(mapOf("firstName" to "Ada", "lastName" to "", "phoneNumber" to "+442012345678", "email" to ""), action.arguments)
    }
}
