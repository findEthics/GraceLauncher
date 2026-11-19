package com.galaxyrio.gracelauncher.assistant

import org.junit.Assert.*
import org.junit.Test

/** Prompt-contract guards from real device misses; only the device can prove model compliance. */
class ModelPromptRegressionTest {
    private fun captured(model: String, case: String) = javaClass.classLoader!!
        .getResourceAsStream("model-matrix/$model/$case.txt")!!.bufferedReader().use { it.readText().trim() }

    @Test fun qwen25MustRetrieveRatherThanRepeatTheCapturedOfflineGuess() {
        assertFalse(captured("Qwen2_5_1_5B", "web").contains("Han Kang", true))
        assertEquals(null, WebToolText.query(captured("Qwen2_5_1_5B", "web")))
        val instructions = modelRetrievalInstructions(AssistantModel.Qwen2_5_1_5B, "base", ChatSource.WEB)
        assertTrue(instructions.contains("must call search_web"))
        assertTrue(instructions.contains("before answering"))
    }

    @Test fun qwen25MustStartWikipediaWithTheLookupTool() {
        assertNull(WikipediaSearch.recoveredCall(captured("Qwen2_5_1_5B", "wikipedia")))
        val instructions = modelRetrievalInstructions(AssistantModel.Qwen2_5_1_5B, "base", ChatSource.WIKIPEDIA)
        assertTrue(instructions.contains("must call query_wikipedia"))
        assertTrue(instructions.contains("before answering"))
    }

    @Test fun gemmaSavedSourcePromptRequiresTheHeadlineMissingFromTheCapturedReply() {
        assertFalse(captured("Gemma4", "source-1").contains("Australian Capital Territory", true))
        val instructions = modelSavedInstructions(AssistantModel.Gemma4, SourceFollowUp.INSTRUCTIONS)
        assertTrue(instructions.contains("Repeat each source's exact Headline"))
    }

    @Test fun unrelatedModelPromptsRemainUnchanged() {
        assertEquals("base", modelRetrievalInstructions(AssistantModel.Gemma4, "base", ChatSource.WEB))
        assertEquals("base", modelSavedInstructions(AssistantModel.Qwen3_1_7B, "base"))
    }

    @Test fun qwen25HasAnExplicitValidatedTextCallFormatWhenNativeCallingIsMissed() {
        // Cycle 1 returned the same captured guesses without a native call.
        val web = modelRetrievalInstructions(AssistantModel.Qwen2_5_1_5B, "base", ChatSource.WEB)
        assertTrue(web.contains("<|tool_call>call:search_web>"))
        val wiki = modelRetrievalInstructions(AssistantModel.Qwen2_5_1_5B, "base", ChatSource.WIKIPEDIA)
        assertTrue(wiki.contains("<|tool_call>call:query_wikipedia>"))
    }

    @Test fun qwen25KeepsTheRequestedYearThroughRetrievalAndSynthesis() {
        assertFalse(captured("Qwen2_5_1_5B", "web-cycle2").contains("Han Kang", true))
        for (first in listOf(true, false)) {
            val instructions = modelRetrievalInstructions(AssistantModel.Qwen2_5_1_5B, "base", ChatSource.WEB, first)
            assertTrue(instructions.startsWith("Preserve every explicit year"))
            assertTrue(instructions.contains("not the latest winner"))
        }
    }
}
