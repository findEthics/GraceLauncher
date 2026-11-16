package com.galaxyrio.gracelauncher.assistant

import org.junit.Assert.*
import org.junit.Test

class WebFollowUpTest {
    @Test fun shortFollowUpRetainsPreviousAnswerAndNumberedSources() {
        val chat = AssistantChatState()
        val id = chat.begin("what's the population of Tokyo", false)
        chat.searchDetails(id, listOf(WebSource("Tokyo Population", "https://example.org/tokyo", "evidence")), "")
        chat.update(id, "Tokyo has a large metropolitan population. ".repeat(5), complete = true, inContext = true)
        val inputBytes = "tell me more about the first source".toByteArray().size
        val history = chat.context(AssistantController.historyContextBytes(inputBytes, true))
        assertEquals(id, history.single().id)
        assertFalse(chat.contextDropped)
        assertTrue(AssistantController.historyResponse(history.single()).contains("Sources: [1]Tokyo Population"))
        assertTrue(AssistantController.historyResponse(history.single()).contains("(example.org)"))
        val used = AssistantController.usedHistoryBytes(history)
        val webBudget = minOf(AssistantController.MAX_WEB_CONTEXT_BYTES, AssistantController.CHAT_INPUT_BYTES - inputBytes - used)
        assertTrue(webBudget >= AssistantController.WEB_CONTEXT_BYTES)
        assertTrue(inputBytes + used + webBudget <= AssistantController.CHAT_INPUT_BYTES)
    }

    @Test fun historyBudgetAlwaysReservesWebMinimumAndKeepsOfflineBudget() {
        for (inputBytes in 0..AssistantController.CHAT_INPUT_BYTES - AssistantController.WEB_CONTEXT_BYTES) {
            val historyBytes = AssistantController.historyContextBytes(inputBytes, true)
            assertTrue(historyBytes in 0..900)
            assertTrue(inputBytes + historyBytes + AssistantController.WEB_CONTEXT_BYTES <= AssistantController.CHAT_INPUT_BYTES)
            assertEquals(AssistantController.CHAT_INPUT_BYTES - inputBytes, AssistantController.historyContextBytes(inputBytes, false))
        }
    }
}
