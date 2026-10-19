package com.galaxyrio.gracelauncher.assistant

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class AlarmSkillRuntimeTest {
    @Test fun bundledJavaScriptReturnsOnlyAnAlarmProposal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val action = AlarmSkillRunner(context).execute(
            AlarmSkillInvocation("index.html", """{"time":"07:30","label":"standup"}"""),
        )
        assertEquals("createAlarm", action.name)
        assertEquals(mapOf("time" to "07:30", "label" to "standup"), action.arguments)
    }

    @Test fun bundledSkillDefinitionAndScriptAreIncluded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(true, AlarmSkillRunner.instructions(context).contains("run_js"))
        assertEquals(true, context.assets.open("skills/set-alarm/scripts/index.html").use { it.readBytes().isNotEmpty() })
    }

    @Test fun inputIsValidatedAndJavaScriptSyntaxInLabelsStaysData() = runBlocking {
        val runner = AlarmSkillRunner(InstrumentationRegistry.getInstrumentation().targetContext)
        for (data in listOf("{}", """{"time":"24:00"}""", """{"time":"07:30","script":"other.js"}""")) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { runner.execute(AlarmSkillInvocation("index.html", data)) }
            }
        }
        val label = "\"); throw new Error('injected'); // </script>"
        val data = JSONObject().put("time", "07:30").put("label", label).toString()
        assertEquals(label, runner.execute(AlarmSkillInvocation("index.html", data)).arguments["label"])
    }
}
