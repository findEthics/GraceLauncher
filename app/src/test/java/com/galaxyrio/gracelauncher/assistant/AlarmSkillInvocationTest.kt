package com.galaxyrio.gracelauncher.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmSkillInvocationTest {
    @Test fun onlyTheBundledEntryPointCanBeSelected() {
        val call = AlarmSkillInvocation.fromToolCall(
            "run_js",
            mapOf("scriptName" to "index.html", "data" to "{\"time\":\"07:30\"}"),
        )
        assertEquals(AlarmSkillInvocation("index.html", "{\"time\":\"07:30\"}"), call)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOtherScriptPaths() {
        AlarmSkillInvocation.fromToolCall(
            "run_js",
            mapOf("scriptName" to "../../downloaded.html", "data" to "{}"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsToolCallsOtherThanRunJs() {
        AlarmSkillInvocation.fromToolCall(
            "createAlarm",
            mapOf("scriptName" to "index.html", "data" to "{}"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpectedToolArguments() {
        AlarmSkillInvocation.fromToolCall(
            "run_js",
            mapOf("scriptName" to "index.html", "data" to "{}", "source" to "javascript"),
        )
    }
}
