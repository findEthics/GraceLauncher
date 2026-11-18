package com.galaxyrio.gracelauncher.assistant

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WikipediaReaderTest {
    @Test fun discoverySendsOnlyTopicAndValidatedEdition() {
        val url = WikipediaReader.discoveryRequestUrl("Ada & Lovelace", "de")
        for (field in listOf("gsrlimit=3", "exchars=700", "exlimit=3", "ppprop=disambiguation", "gsrsearch=Ada+%26+Lovelace")) assertTrue(url.contains(field))
        for (lang in listOf("", "EN", "en.evil", "en:443", "eng")) {
            try { WikipediaReader.discoveryRequestUrl("Ada", lang); fail() } catch (_: WebSearchFailure) { }
        }
    }

    @Test fun rankingUsesUnicodeAndParentContextAcrossWholeIndex() {
        val headings = (1..90).map { WikipediaHeading("$it", "History", "History_$it", 2, "History") } +
            WikipediaHeading("91", "Ergebnisse", "Ergebnisse", 3, "Überblick / Ergebnisse")
        assertEquals("91", WikipediaReader.rankHeadings(headings, "ÜBERBLICK").first().id)
        assertEquals(headings, WikipediaReader.rankHeadings(headings, "unmatched"))
    }

    @Test fun failedRequestsAndCallsConsumeBudgetWithoutRetries() = runBlocking {
        var requests = 0
        val reader = WikipediaReader { _, cap ->
            assertEquals(65_536, cap); requests++
            throw WebSearchFailure(WebSearchFailure.Reason.NETWORK)
        }
        repeat(3) {
            try { reader.discover("Ada", "en", "local focus"); fail() } catch (_: WebSearchFailure) { }
        }
        assertEquals(2, requests)
        assertEquals(2, reader.httpRequests)
        assertEquals(2, reader.logicalCalls)
    }

    @Test fun cancellationPropagatesAndConsumesOneRequest() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val reader = WikipediaReader { _, _ -> entered.complete(Unit); awaitCancellation() }
        val job = launch { reader.discover("Ada", "en", "local") }
        entered.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, reader.httpRequests)
        assertEquals(1, reader.logicalCalls)
    }

    @Test fun concurrentCallsCannotRaceTurnBudget() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val reader = WikipediaReader { _, _ -> entered.complete(Unit); awaitCancellation() }
        val job = launch { reader.discover("Ada", "en", "") }
        entered.await()
        try { reader.discover("other", "en", ""); fail() } catch (_: WebSearchFailure) { }
        assertEquals(1, reader.httpRequests)
        assertEquals(1, reader.logicalCalls)
        job.cancelAndJoin()
    }
}
