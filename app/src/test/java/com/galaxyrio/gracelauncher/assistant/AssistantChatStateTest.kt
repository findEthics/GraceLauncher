package com.galaxyrio.gracelauncher.assistant

import org.junit.Assert.*
import org.junit.Test

class AssistantChatStateTest {
    @Test fun onlyAnExplicitLeadingBangIsAnAction() {
        assertEquals("Set a timer for five minutes", AssistantChatState.actionRequest("!Set a timer for five minutes"))
        assertEquals("Set a timer for five minutes", AssistantChatState.actionRequest("! Set a timer for five minutes"))
        for (text in listOf("Set a timer for five minutes", "Quote: !call John Smith", "Say hello!", " !call John Smith")) {
            assertNull(AssistantChatState.actionRequest(text))
        }
    }
    @Test fun actionBodiesRemainSingleLineAndBounded() {
        for (text in listOf("", " ", "Turn on\nthe flashlight", "Call\rJohn Smith", "\tCall John Smith", "x".repeat(1001))) {
            try { AssistantChatState.validateActionRequest(text); fail("Invalid action accepted") }
            catch (_: IllegalArgumentException) { }
        }
        AssistantChatState.validateActionRequest("Turn on the flashlight")
        AssistantChatState.validateActionRequest("x".repeat(1000))
    }
    @Test fun shortcutPreservesDraftAndSeedsOnlyOnePrefix() {
        val chat = AssistantChatState()
        chat.open(true)
        assertEquals("!", chat.draft)
        chat.open(true)
        assertEquals("!", chat.draft)
        chat.draft = "Set a timer for five minutes"
        chat.open(true)
        assertEquals("!Set a timer for five minutes", chat.draft)
        assertEquals(chat.draft.length, chat.input.selection.end)
    }
    @Test fun minimizeRetainsButCloseClearsAndLateResultsCannotEnterNextSession() {
        val chat = AssistantChatState()
        val old = chat.begin("!Call John Smith", true)
        chat.draft = "unsent draft"
        chat.open(); chat.minimize()
        assertFalse(chat.visible)
        assertEquals("unsent draft", chat.draft)
        assertEquals(1, chat.turns.size)
        chat.clear(); chat.open()
        val fresh = chat.begin("hello", false)
        chat.update(old, "old action result", complete = true, inContext = true)
        assertEquals(listOf(fresh), chat.turns.map { it.id })
        assertEquals("", chat.turns.single().response)
        assertEquals("", chat.draft)
    }
    @Test fun rollingDropsCompleteOldTurnsNotCurrentOrFailedTurns() {
        val chat = AssistantChatState()
        val first = chat.begin("old", false)
        chat.update(first, "reply", complete = true, inContext = true)
        val second = chat.begin("new", false)
        chat.update(second, "reply", complete = true, inContext = true)
        val failed = chat.begin("!bad action", true)
        chat.update(failed, "Nothing executed", complete = true)
        chat.begin("current", false)
        assertEquals(listOf(second), chat.context(72).map { it.id })
        assertTrue(chat.contextDropped)
        assertEquals(4, chat.turns.size) // Transcript remains visible, but is not saved.
        assertEquals(listOf(second), chat.context(1000).map { it.id }) // Dropped context never silently returns.
    }
    @Test fun terminalOutcomesCannotBeOverwrittenByLateChunks() {
        val chat = AssistantChatState()
        val id = chat.begin("hello", false)
        chat.update(id, "Cancelled", complete = true)
        chat.update(id, "late reply", complete = true, inContext = true)
        assertEquals("Cancelled", chat.turns.single().response)
        assertTrue(chat.context(1000).isEmpty())
    }
}
