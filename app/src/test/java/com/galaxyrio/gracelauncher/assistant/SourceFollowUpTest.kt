package com.galaxyrio.gracelauncher.assistant

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SourceFollowUpTest {
    private fun cards(n: Int, provider: ChatSource = ChatSource.WEB) = List(n) { i ->
        WebSource("Story ${i + 1}", "https://site${i + 1}.example/page", "Distinct excerpt for card ${i + 1}. " + "More detail. ".repeat(30),
            provider = if (provider == ChatSource.WIKIPEDIA) ChatSource.WIKIPEDIA else ChatSource.WEB)
    }

    private fun sourcedTurn(id: Long, n: Int, source: ChatSource = ChatSource.WEB, inContext: Boolean = false, numbers: List<Int>? = null) =
        ChatTurn(id, "question $id", false, "answer [1]", complete = true, inContext = inContext, source = source,
            sources = cards(n, source), sourceNumbers = numbers)

    @Test fun resolvesAgainstTheLatestSourcedTurnEvenWhenItRolledOutOfContext() {
        val old = sourcedTurn(1, 3, inContext = true)
        val latest = sourcedTurn(2, 6, inContext = false)
        val plain = ChatTurn(3, "thanks", false, "welcome", complete = true, inContext = true)
        val outcome = SourceFollowUp.resolve("What does source 6 say? Keep the answer grounded in that source.", listOf(old, latest, plain))
        outcome as SourceFollowUp.Resolved
        assertEquals(listOf(6), outcome.numbers)
        assertEquals("Story 6", outcome.sources.single().title)
        assertEquals(ChatSource.WEB, outcome.type)
    }

    @Test fun answerUsesOnlyTheSavedExcerptAndNeverSearches() = runBlocking {
        val turns = listOf(sourcedTurn(1, 6))
        val resolved = SourceFollowUp.resolve("What does source 6 say?", turns) as SourceFollowUp.Resolved
        var system = ""; var content = ""; var history = emptyList<ChatTurn>()
        var shown: Triple<ChatSource, List<WebSource>, List<Int>>? = null; var reply = ""
        val generated = SourceFollowUp.answer("What does source 6 say?", turns, resolved, 2000, "UNVERIFIED", "WARN",
            infer = { s, h, c -> system = s; history = h; content = c; "Card six says so [6]." },
            onResolved = { t, s, n -> shown = Triple(t, s, n) }, onDetails = { _, _ -> fail("must not warn") }, onResponse = { reply = it })
        assertFalse(generated) // Web-derived: no Wikipedia summary attribution
        assertEquals("Card six says so [6].", reply)
        assertTrue(content.contains(SourceFollowUp.EVIDENCE_HEADER))
        assertTrue(content.contains("[6] Headline: Story 6"))
        assertTrue(content.contains("Excerpt: Distinct excerpt for card 6."))
        assertFalse(content.contains("card 5"))
        assertTrue(system.contains("Chat has no tools"))
        assertFalse(system.contains("search_web"))
        assertEquals(listOf(6), shown!!.third)
        assertEquals(ChatSource.WEB, shown!!.first)
        assertEquals(turns, history)
    }

    @Test fun excerptIsTheFullSavedTextNotTheThreeHundredTwentyByteWebCap() {
        val evidence = SourceFollowUp.evidence(cards(1), listOf(6), 1_800)
        val excerpt = evidence.substringAfter("Excerpt: ")
        assertTrue(excerpt.toByteArray().size > 320)
        assertTrue(estimateTokens(evidence) <= 1_800)
    }

    @Test fun evidenceSharesTheBudgetAcrossReferencesAndKeepsOriginalNumbers() {
        val evidence = SourceFollowUp.evidence(cards(6).filterIndexed { i, _ -> i in listOf(1, 5) }, listOf(2, 6), 900)
        assertTrue(estimateTokens(evidence) <= 900)
        assertTrue(evidence.contains("[2] Headline: Story 2") && evidence.contains("[6] Headline: Story 6"))
        assertTrue(evidence.indexOf("[2]") < evidence.indexOf("[6]"))
    }

    @Test fun nonexistentNumberAndTooManyReferencesGiveFixedOutcomesWithoutInference() {
        val turns = listOf(sourcedTurn(1, 4))
        val missing = SourceFollowUp.resolve("source 9", turns) as SourceFollowUp.Missing
        assertEquals(listOf(9), missing.missing); assertEquals(4, missing.shown)
        val some = SourceFollowUp.resolve("sources 2 and 9 and 11", turns) as SourceFollowUp.Missing
        assertEquals(listOf(9, 11), some.missing)
        assertEquals(SourceFollowUp.TooMany, SourceFollowUp.resolve("sources 1, 2, 3, 4", turns))
    }

    @Test fun withoutAnEarlierSourcedTurnTheNormalRouteIsUnchanged() {
        assertNull(SourceFollowUp.resolve("What does source 6 say?", emptyList()))
        assertNull(SourceFollowUp.resolve("What does source 6 say?", listOf(ChatTurn(1, "hi", false, "hello", complete = true))))
        assertNull(SourceFollowUp.resolve("tell me a story", listOf(sourcedTurn(1, 4))))
        // Action turns never count as the sourced target.
        assertNull(SourceFollowUp.resolve("source 1", listOf(sourcedTurn(1, 4).copy(action = true))))
    }

    @Test fun aSecondFollowUpResolvesTheSameOriginalNumberFromTheReshownCard() {
        val first = sourcedTurn(1, 6)
        val second = sourcedTurn(2, 1, numbers = listOf(6)).copy(sources = listOf(first.sources[5]))
        val again = SourceFollowUp.resolve("explain source 6 more", listOf(first, second)) as SourceFollowUp.Resolved
        assertEquals("Story 6", again.sources.single().title)
        assertEquals(listOf(6), again.numbers)
        val missing = SourceFollowUp.resolve("source 2", listOf(first, second)) as SourceFollowUp.Missing
        assertEquals(1, missing.shown)
    }

    @Test fun citingANonDisplayedNumberFailsClosedKeepingTheCards() = runBlocking {
        val turns = listOf(sourcedTurn(1, 6))
        val resolved = SourceFollowUp.resolve("source 6?", turns) as SourceFollowUp.Resolved
        var reply = ""; var warning = ""; var kept = emptyList<WebSource>()
        val generated = SourceFollowUp.answer("source 6?", turns, resolved, 2000, "UNVERIFIED", "WARN",
            infer = { _, _, _ -> "Wrong, see [2] and [6]." }, onResolved = { _, _, _ -> },
            onDetails = { s, w -> kept = s; warning = w }, onResponse = { reply = it })
        assertFalse(generated)
        assertEquals("UNVERIFIED", reply); assertEquals("WARN", warning)
        assertEquals(resolved.sources, kept)
        // Markers or blank output fail closed as well.
        for (bad in listOf("", "   ", "<|tool_call>call:search_web>{}")) {
            var out = ""
            SourceFollowUp.answer("source 6?", turns, resolved, 2000, "UNVERIFIED", "WARN",
                infer = { _, _, _ -> bad }, onResolved = { _, _, _ -> }, onDetails = { _, _ -> }, onResponse = { out = it })
            assertEquals("UNVERIFIED", out)
        }
    }

    @Test fun wikipediaDerivedSourcesKeepTheirTypeSoCreditsAndAttributionApply() = runBlocking {
        val turns = listOf(sourcedTurn(1, 2, source = ChatSource.WIKIPEDIA))
        val resolved = SourceFollowUp.resolve("What does the second source say?", turns) as SourceFollowUp.Resolved
        assertEquals(ChatSource.WIKIPEDIA, resolved.type)
        var shown: ChatSource? = null
        val generated = SourceFollowUp.answer("q", turns, resolved, 2000, "U", "W",
            infer = { _, _, _ -> "From the article [2]." }, onResolved = { t, _, _ -> shown = t }, onDetails = { _, _ -> }, onResponse = { })
        assertTrue(generated)
        assertEquals(ChatSource.WIKIPEDIA, shown)
    }

    @Test fun chatStateShowsReusedCardsUnderTheirOriginalNumbersAndReplaysThem() {
        val chat = AssistantChatState() // Web chip on by default for the sourced turn
        val earlier = chat.begin("news", false)
        chat.searchDetails(earlier, cards(6), "")
        chat.update(earlier, "Six stories [1].", complete = true, inContext = false)
        chat.source = ChatSource.NONE // chip removed; the follow-up must still work
        val resolved = SourceFollowUp.resolve("source 6", chat.turns) as SourceFollowUp.Resolved
        val turn = chat.begin("source 6", false)
        chat.searchDetails(turn, resolved.sources, "", resolved.numbers, resolved.type)
        chat.update(turn, "Card six says so [6].", complete = true, inContext = true)
        val shown = chat.turns.last()
        assertEquals(ChatSource.WEB, shown.source)
        assertEquals(6, shown.number(0))
        assertEquals(1, shown.sources.size)
        assertTrue(AssistantController.historyResponse(shown).contains("[6]Story 6"))
        val wiki = shown.copy(source = ChatSource.WIKIPEDIA, sources = listOf(shown.sources[0].copy(provider = ChatSource.WIKIPEDIA)))
        assertTrue(AssistantController.historyResponse(wiki).contains("[6]Story 6"))
        assertEquals(1, chat.turns.first().number(0))
    }

    @Test fun oldPromptLineAboutSearchingForNumberedSourcesIsGone() {
        assertFalse(AssistantController.WEB_INSTRUCTIONS.contains("numbered source from the previous reply"))
        assertFalse(AssistantController.WEB_INSTRUCTIONS.contains("search for that specific source title"))
    }
}
