package com.galaxyrio.gracelauncher.assistant

import org.junit.Assert.*
import org.junit.Test

class AssistantChatStateTest {
    @Test fun searchSelectionIsSessionScopedAndActionsStayLocal() {
        val chat = AssistantChatState()
        assertFalse(chat.webSearchEnabled)
        chat.webSearchEnabled = true
        val first = chat.begin("news", false)
        chat.searchDetails(first, listOf(WebSource("Source", "https://example.org", "text")), "warning")
        chat.update(first, "answer", complete = true, inContext = true)
        chat.open(); chat.minimize(); chat.open()
        assertTrue(chat.webSearchEnabled)
        assertTrue(chat.turns.first().webSearch)
        chat.begin("!Turn on the flashlight", true)
        assertFalse(chat.turns.last().webSearch)
        assertTrue(chat.webSearchEnabled)
        chat.webSearchEnabled = false
        chat.begin("offline", false)
        assertFalse(chat.turns.last().webSearch)
        chat.clear()
        chat.searchDetails(first, emptyList(), "late")
        assertFalse(chat.webSearchEnabled)
        assertTrue(chat.turns.isEmpty())
    }
    @Test fun sourceLinksAndQueriesAreValidatedAndUnicodeBoundsPreserveCharacters() {
        for (url in listOf("javascript:alert(1)", "file:///etc/passwd", "intent://example", "https://user:pass@example.org", "https:///broken"))
            assertFalse(WebSource.safeUrl(url))
        assertTrue(WebSource.safeUrl("https://example.org/page?q=test"))
        for (query in listOf("", "x".repeat(257), "news\nnow")) {
            try { TavilySearch.validateQuery(query); fail("Invalid query accepted") } catch (_: WebSearchFailure) { }
        }
        TavilySearch.validateQuery("current Android news")
        assertEquals("é😀", AssistantController.utf8Prefix("é😀x", 6))
        assertEquals("é", AssistantController.utf8Prefix("é😀x", 5))
        val excerpt = AssistantController.utf8Prefix("é😀".repeat(200), 320)
        assertEquals(320, excerpt.toByteArray(Charsets.UTF_8).size)
        assertEquals("é😀".repeat(53) + "é", excerpt)
    }
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
    @Test fun webBudgetPreservesInputCapacityAndRollsCompleteHistory() {
        assertEquals(1272, AssistantController.CHAT_INPUT_BYTES - AssistantController.WEB_CONTEXT_BYTES)
        assertEquals(2860, AssistantController.webContextBytes(32))
        assertEquals(1800, AssistantController.webContextBytes(1272))
        val chat = AssistantChatState()
        val old = chat.begin("old", false)
        chat.update(old, "x".repeat(300), complete = true, inContext = true)
        chat.begin("x".repeat(1272), false)
        assertTrue(chat.context(AssistantController.CHAT_INPUT_BYTES - 1272 - AssistantController.webContextBytes(1272)).isEmpty())
        assertTrue(chat.contextDropped)
        assertEquals(2, chat.turns.size)
        assertFalse(chat.turns.last().complete)
        try { AssistantController.webContextBytes(1273); fail("Oversize web input accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun webRuntimeGuardKeepsStrictContextAndOutputAllowance() {
        AssistantController.requireWebContextFits(1000, 2519)
        for (bytes in listOf(2520, 2521)) {
            try { AssistantController.requireWebContextFits(1000, bytes); fail("Full context accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun completeDatedToolEvidenceFitsDynamicBudgetsWithoutCuttingUnicode() {
        val now = java.time.ZonedDateTime.parse("2026-11-03T12:00:00Z")
        val sources = List(3) { WebSource("t".repeat(60), "https://example.org/$it", "é😀".repeat(200), "d".repeat(40)) }
        for (inputBytes in listOf(32, 1272)) {
            val reserve = AssistantController.webContextBytes(inputBytes)
            val result = AssistantController.webToolResult(sources, listOf(4, 5, 6), now, reserve / 2)
            assertTrue(2 * result.toString().toByteArray(Charsets.UTF_8).size <= reserve)
            val lines = result.getValue("untrusted_sources").split("\n", limit = 2)
            val blocks = lines[1].split("\n\n")
            assertEquals(3, blocks.size)
            assertEquals(AssistantController.webResultsHeader(now), lines.first())
            for (index in 1..3) {
                assertTrue(blocks[index - 1].startsWith("[${index + 3}] Headline: ${sources[index - 1].title} (published: ${sources[index - 1].publishedDate})\nExcerpt: "))
                val excerpt = blocks[index - 1].substringAfter("\nExcerpt: ")
                assertTrue(excerpt.endsWith("…"))
                assertTrue(sources[index - 1].content.startsWith(excerpt.removeSuffix("…")))
                assertFalse(excerpt.contains('\uFFFD'))
                assertTrue(excerpt.toByteArray(Charsets.UTF_8).size >= 100)
                assertTrue(excerpt.toByteArray(Charsets.UTF_8).size <= 320)
            }
        }
        val typical = sources.map { it.copy(publishedDate = "2026-11-03") }
        val result = AssistantController.webToolResult(typical, listOf(1, 2, 3), now, 1430)
        assertTrue(result.getValue("untrusted_sources").lines().drop(1).all {
            if (it.startsWith("Excerpt: ")) it.removePrefix("Excerpt: ").toByteArray(Charsets.UTF_8).size in 315..320 else true
        })
    }
    @Test fun maximalHeadlinesAndDatesKeepAllThreeEvidenceBlocksAtFullInputCapacity() {
        val now = java.time.ZonedDateTime.parse("2026-11-03T12:00:00Z")
        val sources = List(3) { WebSource("H".repeat(96), "https://example.org/$it", "Known complete fact. ".repeat(30), "d".repeat(40)) }
        for (inputBytes in listOf(32, 1272)) {
            val budget = AssistantController.webContextBytes(inputBytes) / 2
            val result = AssistantController.webToolResult(sources, listOf(1, 2, 3), now, budget)
            val bytes = result.toString().toByteArray(Charsets.UTF_8).size
            assertTrue(bytes <= budget)
            val blocks = result.getValue("untrusted_sources").substringAfter("\n").split("\n\n")
            assertEquals(3, blocks.size)
            for ((index, block) in blocks.withIndex()) {
                assertTrue(block.startsWith("[${index + 1}] Headline: ${sources[index].title}"))
                assertTrue(block.contains("(published: ${sources[index].publishedDate})\nExcerpt: "))
                val excerpt = block.substringAfter("\nExcerpt: ")
                assertTrue(excerpt.endsWith("…"))
                assertTrue(excerpt.length > 3)
                assertTrue(sources[index].content.startsWith(excerpt.removeSuffix("…")))
            }
            println("Evidence budget=$budget, complete map=$bytes bytes, excerpt=" + blocks.first().substringAfter("\nExcerpt: "))
        }
    }
    @Test fun evidenceCutsMarkIncompleteWordsWithoutSplittingUnicode() {
        assertEquals("City Tehran.", AssistantController.evidencePrefix("City Tehran.", 12))
        assertEquals("City…", AssistantController.evidencePrefix("City Tehran.", 10))
        assertEquals("…", AssistantController.evidencePrefix("Extraordinary", 8))
        assertEquals("é…", AssistantController.evidencePrefix("é😀x", 6))
        val text = "First complete fact. Another incomplete claim about a city."
        val short = AssistantController.evidencePrefix(text, 35)
        assertEquals("First complete fact. Another…", short)
        assertTrue(short.toByteArray(Charsets.UTF_8).size <= 35)
        val result = AssistantController.webToolResult(listOf(WebSource("Primary transport headline", "https://example.org", text)),
            listOf(2), java.time.ZonedDateTime.parse("2026-11-03T12:00:00Z"), 900)
        assertTrue(result.getValue("untrusted_sources").contains("[2] Headline: Primary transport headline\nExcerpt: $text"))
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
