package com.galaxyrio.gracelauncher.assistant

import com.google.ai.edge.litertlm.ToolCall
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Pure fixtures also runnable on the host with org.json, without an engine or Android device. */
class WikipediaTurnFixturesTest {
    private fun query() = ToolCall("query_wikipedia", mapOf("topic" to "Wahl 2026", "lang" to "de", "focus" to "Ergebnisse"))
    private fun read(ids: List<String> = listOf("91")) = ToolCall("read_wikipedia_sections", mapOf("sections" to ids, "focus" to "Ergebnisse"))
    private fun reader(lead: String = "Eine Wahl fand im Jahr 2026 statt. ".repeat(15), missingBody: Boolean = false, requests: MutableList<String> = mutableListOf()) = WikipediaReader { url, _ ->
        requests += url
        when {
            url.contains("generator=search") -> """{"query":{"pages":[{"pageid":1,"index":1,"ns":0,"title":"Wahl 2026","fullurl":"https://de.wikipedia.org/wiki/Wahl_2026","extract":"$lead"}]}}"""
            url.contains("prop=sections") -> """{"parse":{"pageid":1,"revid":42,"sections":[${(1..90).joinToString(",") { """{"index":"$it","line":"Geschichte","anchor":"History_$it","level":"2"}""" }},{"index":"91","line":"Ergebnisse","anchor":"Ergebnisse","level":"2"}]}}"""
            missingBody -> """{"error":{"code":"missing"}}"""
            else -> """{"parse":{"pageid":1,"revid":42,"text":"<h2>Ergebnisse</h2><table><caption>Resultate 2026</caption><tr><th>Partei</th><th>Sitze</th></tr><tr><td>Alpha</td><td>42</td></tr></table><p>Dies sind die ausgewiesenen Ergebnisse.</p>"}}"""
        }
    }

    @Test fun rankedLateHeadingAndActualPublishedContentRespectCompleteBudgets() = runBlocking {
        val requests = mutableListOf<String>()
        val turn = WikipediaTurn(reader(requests = requests))
        val discovery = turn.execute(query(), 1100).toString()
        assertTrue(discovery.toByteArray().size <= 1100)
        assertTrue(discovery.contains("91: Ergebnisse")); assertTrue(discovery.contains("omitted"))
        assertEquals(1, turn.sources.size)
        assertTrue(turn.sources.single().content.toByteArray().size > 320)
        val body = turn.execute(read(), 1400).toString()
        assertTrue(body.toByteArray().size <= 1400)
        for (fact in listOf("Partei", "Sitze", "Alpha", "42", "[2]")) assertTrue(body, body.contains(fact))
        assertEquals(2, turn.sources.size); assertTrue(turn.sources.last().url.endsWith("#Ergebnisse"))
        assertTrue(requests.last().contains("oldid=42")); assertFalse(requests.any { it.contains("focus=") })
    }

    @Test fun navigationOnlyHasNoEvidenceAndReadFailureRetainsOnlyPublishedLead() = runBlocking {
        val navigation = WikipediaTurn(reader(lead = ""))
        assertTrue(navigation.execute(query(), 1000).toString().contains("Navigation"))
        assertTrue(navigation.sources.isEmpty())
        val turn = WikipediaTurn(reader(missingBody = true))
        turn.execute(query(), 1100)
        try { turn.execute(read(), 1200); fail() } catch (_: WebSearchFailure) { }
        assertEquals(1, turn.sources.size); assertFalse(turn.sources.single().url.contains("#"))
    }

    @Test fun unapprovedIdsAndRevisionArgumentsCannotCauseReadAndNewTurnHasNoCapability() = runBlocking {
        val requests = mutableListOf<String>()
        val turn = WikipediaTurn(reader(requests = requests))
        turn.execute(query(), 1100)
        for (call in listOf(read(listOf("999")), ToolCall("read_wikipedia_sections", read().arguments + ("revision" to 99)))) {
            try { turn.execute(call, 1200); fail() } catch (_: WebSearchFailure) { }
        }
        assertEquals(2, requests.size)
        val nextTurn = WikipediaTurn(reader(requests = requests))
        try { nextTurn.execute(read(), 1200); fail() } catch (_: WebSearchFailure) { }
        assertEquals(2, requests.size)
    }

    @Test fun bothRecoveryEnvelopesRequireExactTypesAndSchemas() {
        val query = "<|tool_call>call:query_wikipedia>{\"topic\":\"Ada's work\",\"lang\":\"en\",\"focus\":\"Results\"}"
        val read = "<|tool_call>call:read_wikipedia_sections>{\"sections\":[\"1\"],\"focus\":\"Results\"}"
        assertEquals("query_wikipedia", WikipediaSearch.recoveredCall(query)?.name)
        assertEquals(listOf("1"), WikipediaSearch.recoveredCall(read)?.arguments?.get("sections"))
        for (bad in listOf(query + read, query + " trailing", read.replace("[\"1\"]", "\"1\""), read.replace("[\"1\"]", "[1]"),
            read.replace("{", "{\"revision\":42,"), query.replace("en", "EN"), read.replace("[\"1\"]", "[\"1\",\"1\"]"))) assertNull(bad, WikipediaSearch.recoveredCall(bad))
    }

    @Test fun twoSectionsPublishAtMostThreeSourcesAndCarryPartialMarkers() = runBlocking {
        val turn = WikipediaTurn(reader())
        turn.execute(query(), 1100)
        val result = turn.execute(read(listOf("91", "1")), 1800).toString()
        assertTrue(result.toByteArray().size <= 1800)
        assertEquals(3, turn.sources.size)
        assertTrue(result.contains("[2]")); assertTrue(result.contains("[3]"))
        assertTrue(result.contains("Partei")); assertTrue(result.contains("partial excerpts"))
        assertTrue(turn.sources.drop(1).all { it.url.startsWith("https://de.wikipedia.org/wiki/Wahl_2026#") })
    }
}
