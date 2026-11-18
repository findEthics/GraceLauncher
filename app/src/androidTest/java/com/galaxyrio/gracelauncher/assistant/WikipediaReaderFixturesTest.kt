package com.galaxyrio.gracelauncher.assistant

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WikipediaReaderFixturesTest {
    private fun page(id: Int, index: Int, title: String = "Article$id", extract: String = "Actual lead evidence.", disambiguation: Boolean = false) =
        JSONObject().put("pageid", id).put("index", index).put("ns", 0).put("title", title)
            .put("fullurl", "https://en.wikipedia.org/wiki/Article$id").put("extract", extract)
            .put("pageprops", JSONObject().apply { if (disambiguation) put("disambiguation", "") })
    private fun search(vararg pages: JSONObject) = JSONObject().put("query", JSONObject().put("pages", JSONArray(pages.toList()))).toString()
    private fun heading(id: String, title: String = "Results", anchor: String = "Results", level: String = "2") =
        JSONObject().put("index", id).put("line", title).put("anchor", anchor).put("level", level)
    private fun index(vararg headings: JSONObject) = JSONObject().put("parse", JSONObject().put("pageid", 1).put("revid", 9876).put("sections", JSONArray(headings.toList())))
    private fun body(html: String = "<p>Actual section evidence.</p>") = JSONObject().put("parse", JSONObject().put("pageid", 1).put("text", html)).toString()
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected closed failure") } catch (_: WebSearchFailure) { }
    }

    @Test fun numericOrderAndDisambiguationPresenceSelectFirstRealPage() = runBlocking {
        val queue = ArrayDeque(listOf(search(page(3, 3), page(2, 1, disambiguation = true), page(1, 2)), index(heading("7")).toString()))
        val reader = WikipediaReader { _, _ -> queue.removeFirst() }
        val result = reader.discover("primary topic 2026", "en", "Results")
        assertEquals(1L, result.page.pageId)
        assertEquals(listOf("Article2", "Article3"), result.alternativeTitles)
        assertEquals("Actual lead evidence.", result.lead!!.content)
        assertEquals(9876L, result.page.revision)
        assertEquals(2, reader.httpRequests)
    }

    @Test fun discoveryReturnsEveryHeadingWithSynthetic0AndTurnPacksNavigationOnce() = runBlocking {
        val headings = (1..80).map { heading("$it", "Unrelated heading $it") } + heading("81", "Übersicht") + heading("82", "Ergebnisse", level = "3")
        val queue = ArrayDeque(listOf(search(page(1, 1)), index(*headings.toTypedArray()).toString()))
        val reader = WikipediaReader { _, _ -> queue.removeFirst() }
        val result = reader.discover("topic", "en", "ÜBERSICHT Ergebnisse")
        assertEquals("0", result.headings.first().id)
        assertEquals(83, result.headings.size) // synthetic 0 plus 82 discovered headings
        assertTrue(result.lead!!.content.toByteArray().size <= 500)
    }

    @Test fun emptyLeadIsNotEvidenceButBodyCanBeReadAtPinnedRevision() = runBlocking {
        val urls = mutableListOf<String>()
        val queue = ArrayDeque(listOf(search(page(1, 1, extract = "")), index(heading("07", anchor = "Über / results")).toString(), body()))
        val reader = WikipediaReader { url, _ -> urls += url; queue.removeFirst() }
        val discovery = reader.discover("topic", "en", "local secret focus")
        assertNull(discovery.lead)
        val read = reader.read(discovery.page, listOf("07"), "evidence", 700)
        assertTrue(urls.last().contains("oldid=9876&section=07"))
        assertFalse(urls.any { it.contains("secret") || it.contains("focus") })
        assertEquals("https://en.wikipedia.org/wiki/Article1#Über%20/%20results", read.single().source!!.url)
        assertTrue(read.single().source!!.content.contains("Actual section evidence."))
    }

    @Test fun unknownIdAndForeignNativeCapabilityFailBeforeRequests() = runBlocking {
        for (foreign in listOf(false, true)) {
            val queue = ArrayDeque(listOf(search(page(1, 1)), index(heading("07")).toString()))
            val reader = WikipediaReader { _, _ -> queue.removeFirst() }
            val page = reader.discover("topic", "en", "").page
            val target = if (foreign) WikipediaReader { _, _ -> error("No network allowed") } else reader
            rejected { target.read(page, listOf(if (foreign) "07" else "7"), "", 700) }
            assertEquals(if (foreign) 0 else 2, target.httpRequests)
        }
    }

    @Test fun twoPinnedSectionsRespectCombinedBudgetAndFourRequestLimit() = runBlocking {
        val queue = ArrayDeque(listOf(search(page(1, 1)), index(heading("1"), heading("2")).toString(), body("<p>First evidence.</p>"), body("<p>Second evidence.</p>")))
        val reader = WikipediaReader { _, limit -> assertTrue(limit in listOf(65_536, 1_048_576)); queue.removeFirst() }
        val page = reader.discover("topic", "en", "").page
        val results = reader.read(page, listOf("1", "2"), "evidence", 200)
        assertTrue(results.mapNotNull { it.source }.sumOf { it.content.toByteArray().size } <= 200)
        assertEquals(4, reader.httpRequests)
        rejected { reader.discover("again", "en", "") }
        assertEquals(4, reader.httpRequests)
    }

    @Test fun missingMalformedWarningsErrorsAndOversizeSearchFailClosed() = runBlocking {
        val valid = search(page(1, 1))
        for (input in listOf("{}", "{", "[]", "$valid garbage", search(), search(page(1, 1, disambiguation = true)),
            "{\"error\":{}}", JSONObject(valid).put("warnings", JSONObject().put("query", JSONObject().put("*", "Unknown warning"))).toString(),
            valid.replace("\"index\":1", "\"index\":\"1\""), valid.replace("\"ns\":0", "\"ns\":0.5"),
            search(page(1, 1).put("pageprops", "invalid")), " ".repeat(65_537))) {
            val reader = WikipediaReader { _, _ -> input }
            rejected { reader.discover("topic", "en", "") }
            assertEquals(1, reader.httpRequests)
        }
    }

    @Test fun onlyRecognizedSectionIndexDeprecationsAreAllowed() = runBlocking {
        val deprecation = JSONObject().put("parse", JSONObject().put("warnings", "\"prop=sections\" has been deprecated. Please use \"prop=tocdata\" instead."))
            .put("main", JSONObject().put("warnings", "Subscribe to the mediawiki-api-announce mailing list at <https://lists.wikimedia.org/postorius/lists/mediawiki-api-announce.lists.wikimedia.org/> for notice of API deprecations and breaking changes. Use [[Special:ApiFeatureUsage]] to see usage of deprecated features by your application."))
        for (known in listOf(true, false)) {
            val warning = if (known) deprecation else JSONObject().put("parse", JSONObject().put("*", "Unknown warning"))
            val queue = ArrayDeque(listOf(search(page(1, 1)), index(heading("1")).put("warnings", warning).toString()))
            val reader = WikipediaReader { _, _ -> queue.removeFirst() }
            if (known) assertEquals(9876L, reader.discover("topic", "en", "").page.revision)
            else rejected { reader.discover("topic", "en", "") }
        }
    }

    @Test fun emptyAndOversizedSectionJsonNeverBecomeEvidence() = runBlocking {
        for (response in listOf(body("<nav>Navigation only</nav>"), " ".repeat(1_048_577), "{\"parse\":{\"text\":{\"*\":\"legacy shape\"}}}",
            body().replace("\"pageid\":1", "\"pageid\":2"), JSONObject(body()).apply { getJSONObject("parse").put("revid", 9999) }.toString())) {
            val queue = ArrayDeque(listOf(search(page(1, 1)), index(heading("1")).toString(), response))
            val reader = WikipediaReader { _, _ -> queue.removeFirst() }
            val page = reader.discover("topic", "en", "").page
            if (response.contains("Navigation only")) {
                val result = reader.read(page, listOf("1"), "", 500).single()
                assertNull(result.source); assertNotNull(result.failureReason)
            } else rejected { reader.read(page, listOf("1"), "", 500) }
            assertEquals(3, reader.httpRequests)
        }
    }

    @Test fun articleLinksRejectQueryFragmentTraversalAndForeignHost() = runBlocking {
        for (url in listOf("https://en.wikipedia.org/wiki/../evil", "https://en.wikipedia.org/wiki/%2e%2e/evil", "https://en.wikipedia.org/wiki/Article#model", "https://en.wikipedia.org/wiki/Article?redirect=x", "https://de.wikipedia.org/wiki/Article")) {
            val fixture = page(1, 1).put("fullurl", url)
            val reader = WikipediaReader { _, _ -> search(fixture) }
            rejected { reader.discover("topic", "en", "") }
        }
    }
}
