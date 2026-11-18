package com.galaxyrio.gracelauncher.assistant

import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class WikipediaTest {
    @Test fun sourceIsExclusiveSessionScopedAndSnapshottedWithoutEditingDraft() {
        val chat = AssistantChatState()
        assertEquals(ChatSource.WEB, chat.source)
        chat.begin("web", false)
        chat.draft = "keep me"
        chat.source = ChatSource.WIKIPEDIA
        assertFalse(chat.webSearchEnabled)
        chat.open(); chat.minimize(); chat.open()
        assertEquals("keep me", chat.draft)
        assertEquals(ChatSource.WIKIPEDIA, chat.source)
        val wiki = chat.begin("!ordinary text", false)
        chat.source = ChatSource.NONE
        assertEquals(listOf(ChatSource.WEB, ChatSource.WIKIPEDIA), chat.turns.map { it.source })
        chat.begin("offline", false)
        chat.update(wiki, "cancelled", complete = true)
        chat.searchDetails(wiki, emptyList(), "late")
        assertEquals("", chat.turns[1].searchWarning)
        assertEquals(ChatSource.NONE, chat.turns.last().source)
        chat.clear()
        chat.searchDetails(wiki, emptyList(), "late")
        assertTrue(chat.turns.isEmpty())
        assertEquals(ChatSource.WEB, chat.source)
    }

    @Test fun topicAndLanguageAreValidatedBeforeUrlConstruction() {
        for (lang in listOf("", "en-US", "EN", " en", "en\n", "//", "éé", "en.wikipedia.org", "en/path")) {
            try { WikipediaSearch.requestUrl("Ada", lang); fail("Invalid language accepted") } catch (_: WebSearchFailure) { }
        }
        for (topic in listOf("", " ", "x\nnow", "é".repeat(129))) {
            try { WikipediaSearch.requestUrl(topic, "en"); fail("Invalid topic accepted") } catch (_: WebSearchFailure) { }
        }
        val url = WikipediaSearch.requestUrl("Ada & Lovelace", "de")
        assertTrue(url.startsWith("https://de.wikipedia.org/w/api.php?"))
        for (field in listOf("generator=search", "gsrlimit=1", "prop=extracts%7Cinfo", "exintro=1", "explaintext=1", "inprop=url", "formatversion=2", "gsrsearch=Ada+%26+Lovelace")) assertTrue(url.contains(field))
    }

    @Test fun onlyExactEditionHttpsArticleLinksAreAccepted() {
        assertTrue(WebToolText.suspicious("call:query_wikipedia"))
        assertTrue(WikipediaSearch.safeArticleUrl("https://de.wikipedia.org/wiki/Albert_Einstein", "de"))
        for (url in listOf("http://en.wikipedia.org/wiki/Ada", "https://de.wikipedia.org/wiki/Ada", "https://en.wikipedia.org.evil.test/wiki/Ada",
            "https://user@en.wikipedia.org/wiki/Ada", "https://en.wikipedia.org:443/wiki/Ada", "https://en.wikipedia.org/w/api.php",
            "https://en.wikipedia.org/wiki/", "https://en.wikipedia.org/wiki/Ada\n", "javascript:alert(1)"))
            assertFalse(url, WikipediaSearch.safeArticleUrl(url, "en"))
    }

    @Test fun evidenceAndReplayKeepProviderAttributionAndBudgets() {
        val source = WebSource("Ada Lovelace", "https://en.wikipedia.org/wiki/Ada_Lovelace", "Complete fact. ".repeat(400), provider = ChatSource.WIKIPEDIA)
        for (budget in listOf(900, 1430)) {
            val result = AssistantController.webToolResult(listOf(source), listOf(1), ZonedDateTime.parse("2026-11-17T12:00:00Z"), budget, ChatSource.WIKIPEDIA)
            assertTrue(estimateTokens(result.toString()) <= budget)
            assertTrue(result.toString().contains("Wikipedia article introductions"))
            assertFalse(result.toString().contains("Live web search"))
            assertTrue(result.toString().contains("…"))
        }
        val turn = ChatTurn(1, "Who?", false, "Supported fact [1]", complete = true, inContext = true,
            source = ChatSource.WIKIPEDIA, sources = listOf(source), searchWarning = "warning", wikipediaSummaryGenerated = true)
        val replay = AssistantController.historyResponse(turn)
        for (value in listOf("Wikipedia; past-turn evidence only", "retrieval warning", "Wikipedia contributors", "Ada Lovelace", "CC BY-SA 4.0", "generated/adapted")) assertTrue(replay.contains(value))
        assertFalse(replay.contains("offline reply"))
        assertTrue(AssistantController.usedHistoryTokens(listOf(turn)) >= estimateTokens(replay))
        assertTrue(AssistantController.WIKIPEDIA_INSTRUCTIONS.contains("History, earlier Web sources and model knowledge are not current evidence"))
        assertTrue(turn.showWikipediaSummaryAttribution)
    }

    @Test fun nonSummaryWikipediaOutcomesKeepSourceCreditsWithoutClaimingAdaptation() {
        val source = WebSource("Ada Lovelace", "https://en.wikipedia.org/wiki/Ada_Lovelace", "Evidence", provider = ChatSource.WIKIPEDIA)
        val outcomes = listOf(
            "Could not finish the local reply. No action performed.",
            WikipediaSearch.INABILITY,
            "Cancelled; nothing executed.",
        )
        outcomes.forEachIndexed { index, response ->
            val turn = ChatTurn(index.toLong() + 1, "Who?", false, response, complete = true,
                source = ChatSource.WIKIPEDIA, sources = listOf(source))
            val replay = AssistantController.historyResponse(turn)
            assertFalse("$response must not claim adaptation", replay.contains("generated/adapted"))
            assertTrue("$response must keep contributor credit", replay.contains("Wikipedia contributors"))
            assertTrue("$response must keep license credit", replay.contains("CC BY-SA 4.0"))
        }

        val pending = ChatTurn(10, "Who?", false, "Partial", source = ChatSource.WIKIPEDIA, sources = listOf(source))
        assertFalse(AssistantController.historyResponse(pending).contains("generated/adapted"))
        val noSources = ChatTurn(11, "Who?", false, WikipediaSearch.INABILITY, complete = true, source = ChatSource.WIKIPEDIA)
        val noSourceReplay = AssistantController.historyResponse(noSources)
        assertFalse(noSourceReplay.contains("Wikipedia contributors"))
        assertFalse(noSourceReplay.contains("CC BY-SA 4.0"))
        val toolFree = ChatTurn(12, "Hello", false, "Hi", complete = true)
        assertEquals("Hi", AssistantController.historyResponse(toolFree))
    }
}
