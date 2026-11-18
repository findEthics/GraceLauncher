package com.galaxyrio.gracelauncher.assistant

import org.junit.Assert.*
import org.junit.Test

class WebFollowUpTest {
    @Test fun shortFollowUpRetainsPreviousAnswerAndNumberedSources() {
        val chat = AssistantChatState()
        val id = chat.begin("what's the population of Tokyo", false)
        chat.searchDetails(id, listOf(WebSource("Tokyo Population", "https://example.org/tokyo", "evidence")), "")
        chat.update(id, "Tokyo has a large metropolitan population. ".repeat(5), complete = true, inContext = true)
        val inputTokens = estimateTokens("tell me more about the first source")
        val history = chat.context(AssistantController.historyContextTokens(inputTokens, true))
        assertEquals(id, history.single().id)
        assertFalse(chat.contextDropped)
        assertTrue(AssistantController.historyResponse(history.single()).contains("Sources: [1]Tokyo Population"))
        assertTrue(AssistantController.historyResponse(history.single()).contains("(example.org)"))
        val used = AssistantController.usedHistoryTokens(history)
        val webBudget = minOf(AssistantController.MAX_WEB_CONTEXT_TOKENS, AssistantController.CHAT_INPUT_TOKENS - inputTokens - used)
        assertTrue(webBudget >= AssistantController.WEB_CONTEXT_TOKENS)
        assertTrue(inputTokens + used + webBudget <= AssistantController.CHAT_INPUT_TOKENS)
    }

    @Test fun historyBudgetAlwaysReservesWebMinimumAndKeepsOfflineBudget() {
        for (inputTokens in 0..AssistantController.CHAT_INPUT_TOKENS - AssistantController.WEB_CONTEXT_TOKENS) {
            val historyTokens = AssistantController.historyContextTokens(inputTokens, true)
            assertTrue(historyTokens in 0..900)
            assertTrue(inputTokens + historyTokens + AssistantController.WEB_CONTEXT_TOKENS <= AssistantController.CHAT_INPUT_TOKENS)
            assertEquals(AssistantController.CHAT_INPUT_TOKENS - inputTokens, AssistantController.historyContextTokens(inputTokens, false))
        }
    }

    @Test fun tenDisplayedSourcesKeepTheirNumberAndDomainMappingForFollowUps() {
        val chat = AssistantChatState()
        val id = chat.begin("news today", false)
        val sources = List(10) { index -> WebSource("Story $index", "https://source$index.example/story", "evidence") }
        chat.searchDetails(id, sources, "")
        chat.update(id, "A concise answer [1].", complete = true, inContext = true)

        val inputTokens = estimateTokens("tell me more about source [10]")
        val history = chat.context(AssistantController.historyContextTokens(inputTokens, true))
        val turn = history.single()
        val sourceTokens = AssistantController.historySourceTokens(history, turn)
        val replay = AssistantController.historyResponse(turn, sourceTokens)

        assertEquals(480, sourceTokens)
        for (index in sources.indices) {
            assertTrue(replay.contains("[${index + 1}]Story $index (source$index.example)"))
        }
        assertTrue(estimateTokens(replay) <= estimateTokens(turn.response) + sourceTokens)
        assertFalse(chat.contextDropped)
    }
}
