package com.galaxyrio.gracelauncher.assistant

import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WikipediaSearchTest {
    private fun query() = ToolCall("query_wikipedia", mapOf("topic" to "Ada", "lang" to "en", "focus" to "Results"))
    private fun read(ids: List<String> = listOf("1")) = ToolCall("read_wikipedia_sections", mapOf("sections" to ids, "focus" to "Results"))
    private fun proposal(call: ToolCall) = Message.model(Contents.of(emptyList()), listOf(call))
    private fun reader(lead: String = "Ada was a mathematician.", requests: MutableList<String> = mutableListOf()) = WikipediaReader { url, _ ->
        requests += url
        when {
            url.contains("generator=search") -> """{"query":{"pages":[{"pageid":1,"index":1,"ns":0,"title":"Ada","fullurl":"https://en.wikipedia.org/wiki/Ada","extract":"$lead"}]}}"""
            url.contains("prop=sections") -> """{"parse":{"pageid":1,"revid":42,"sections":[{"index":"1","line":"Results","anchor":"Results","level":"2"}]}}"""
            else -> """{"parse":{"pageid":1,"revid":42,"text":"<h2>Results</h2><p>The result was published in 1843.</p>"}}"""
        }
    }

    @Test fun recoveryAndBothReflectedSchemasAreExact() {
        val query = "<|tool_call>call:query_wikipedia>{\"topic\":\"Ada's work\",\"lang\":\"en\",\"focus\":\"Results\"}"
        assertEquals("query_wikipedia", WikipediaSearch.recoveredCall(query)?.name)
        assertEquals("read_wikipedia_sections", WikipediaSearch.recoveredCall("<|tool_call>call:read_wikipedia_sections>{\"sections\":[\"1\"],\"focus\":\"Results\"}")?.name)
        for (bad in listOf(query + " trailing", query + query, query.replace("en", "EN"), query.replace("{", "{\"revision\":1,"))) assertNull(WikipediaSearch.recoveredCall(bad))
        for (method in WikipediaTools::class.java.declaredMethods.filter { it.name in setOf("query_wikipedia", "read_wikipedia_sections") }) {
            assertNotNull(method.getAnnotation(Tool::class.java))
            assertTrue(method.parameterAnnotations.all { row -> row.any { it is ToolParam } })
        }
    }

    @Test fun firstLookupAdvertisesOnlyQueryThroughActualSdkProvider() {
        for (first in listOf(true, false)) {
            val tools = WikipediaSearch.tools(first)
            val expected = if (first) setOf("query_wikipedia") else setOf("query_wikipedia", "read_wikipedia_sections")
            val provider = tool(tools)
            val schemas = provider.javaClass.methods.single { it.name.startsWith("provideTools") }.apply { isAccessible = true }.invoke(provider) as Map<*, *>
            assertEquals(expected, schemas.keys)
            for (method in tools.javaClass.declaredMethods.filter { it.name in expected }) {
                assertNotNull(method.getAnnotation(Tool::class.java))
                assertTrue(method.parameterAnnotations.all { row -> row.any { it is ToolParam } })
            }
        }
    }

    @Test fun discoverReadFinalAndLeadFastPathUseSharedManualLoop() = runBlocking {
        val controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, this)
        try {
            for (bodyRead in listOf(false, true)) {
                var step = 0; var response = ""; var shown = emptyList<WebSource>()
                val requests = mutableListOf<String>()
                val generated = controller.inferWebChat("Ada?", source = ChatSource.WIKIPEDIA, wikipediaReader = { reader(requests = requests) },
                    search = { fail("Tavily dispatched"); emptyList() }, inferStep = { _, next, allow ->
                        when (step++) {
                            0 -> proposal(query())
                            1 -> {
                                assertTrue((next.contents.contents.single() as Content.ToolResponse).response.toString().contains("Navigation"))
                                if (bodyRead) proposal(read()) else Message.model("Ada was a mathematician [1].")
                            }
                            else -> { assertFalse(allow); Message.model("Published in 1843 [2].") }
                        }
                    }, onDetails = { items, _ -> shown = items }, onResponse = { response = it })
                assertTrue(generated)
                assertEquals(if (bodyRead) 3 else 2, requests.size)
                assertEquals(if (bodyRead) 2 else 1, shown.size)
                if (bodyRead) { assertTrue(requests.last().contains("oldid=42")); assertEquals("https://en.wikipedia.org/wiki/Ada#Results", shown.last().url) }
                assertTrue(response.contains(if (bodyRead) "1843" else "mathematician"))
            }
        } finally { controller.close() }
    }

    @Test fun noCurrentEvidenceEvenWithOldHistoryCannotAnswer() = runBlocking {
        val controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, this)
        try {
            val old = ChatTurn(1, "old", false, "Old answer", complete = true, inContext = true, source = ChatSource.WEB,
                sources = listOf(WebSource("Old", "https://example.org", "old evidence")))
            for (navigation in listOf(false, true)) {
                var step = 0; var response = ""; val requests = mutableListOf<String>()
                val generated = controller.inferWebChat("Ada?", listOf(old), source = ChatSource.WIKIPEDIA, wikipediaReader = { reader(lead = "", requests = requests) },
                    inferStep = { _, _, _ -> if (step++ == 0 && navigation) proposal(query()) else Message.model("Memory answer") },
                    onResponse = { response = it })
                assertFalse(generated)
                assertEquals(WikipediaSearch.INABILITY, response)
                assertEquals(if (navigation) 2 else 1, step)
                assertEquals(if (navigation) 2 else 0, requests.size)
            }
        } finally { controller.close() }
    }

    @Test fun invalidParallelAndMixedFirstProposalsNeverCauseRetrievalOrCorrection() = runBlocking {
        val controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, this)
        try {
            for (first in listOf(
                proposal(ToolCall("query_wikipedia", query().arguments - "lang")),
                Message.model(Contents.of(emptyList()), listOf(query(), query())),
                Message.model(Contents.of(listOf(Content.Text("untrusted answer"))), listOf(query())),
                Message.model("<|tool_call>call:query_wikipedia>{bad}"),
                proposal(read()),
            )) {
                val requests = mutableListOf<String>(); var steps = 0; var response = ""
                val generated = controller.inferWebChat("Ada?", source = ChatSource.WIKIPEDIA, wikipediaReader = { reader(requests = requests) },
                    inferStep = { _, _, _ -> if (steps++ == 0) first else Message.model("Memory answer") },
                    onResponse = { response = it })
                assertFalse(generated)
                assertTrue(requests.isEmpty())
                assertTrue(steps in 1..2)
                assertEquals(WikipediaSearch.INABILITY, response)
            }
        } finally { controller.close() }
    }

    @Test fun queryAgainUsesBudgetAndThirdProposalCannotDispatch() = runBlocking {
        val controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, this)
        try {
            var step = 0; var response = ""; val requests = mutableListOf<String>()
            val generated = controller.inferWebChat("Ada?", source = ChatSource.WIKIPEDIA, wikipediaReader = { reader(requests = requests) },
                inferStep = { _, _, _ -> step++; proposal(query()) }, onResponse = { response = it })
            assertFalse(generated)
            assertEquals(4, requests.size); assertEquals(3, step); assertEquals(WikipediaSearch.INABILITY, response)
        } finally { controller.close() }
    }

    @Test fun unsupportedCitationsFailClosedWithoutSummaryAndCurrentCitationsPass() = runBlocking {
        val controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, this)
        try {
            for ((reply, accepted) in listOf(
                "Ada was a mathematician [1]; results were published in 1843 [2]." to true,
                "Ada was a mathematician [1]; results were published in 1843 [4]." to false,
            )) {
                var step = 0; var response = ""; var shown = emptyList<WebSource>(); var warning = ""
                val generated = controller.inferWebChat("Ada?", source = ChatSource.WIKIPEDIA,
                    search = { fail("Tavily dispatched"); emptyList() }, wikipediaReader = { reader() },
                    inferStep = { _, _, allow ->
                        when (step++) {
                            0 -> proposal(query())
                            1 -> proposal(read())
                            else -> { assertFalse(allow); Message.model(reply) }
                        }
                    }, onDetails = { items, message -> shown = items; warning = message },
                    onResponse = { response = it })

                assertEquals(3, step)
                assertEquals(2, shown.size)
                assertEquals(accepted, generated)
                assertEquals(if (accepted) reply else WikipediaSearch.INABILITY, response)
                assertEquals(if (accepted) "" else WikipediaSearch.WARNING, warning)
                val chat = AssistantChatState().apply { source = ChatSource.WIKIPEDIA }
                val turn = chat.begin("Ada?", false)
                chat.searchDetails(turn, shown, warning)
                chat.update(turn, response, complete = true, inContext = true, wikipediaSummaryGenerated = generated)
                val published = chat.turns.single()
                assertEquals(accepted, published.showWikipediaSummaryAttribution)
                val replay = AssistantController.historyResponse(published)
                assertTrue(replay.contains("Wikipedia contributors"))
                assertTrue(replay.contains("CC BY-SA 4.0"))
                assertEquals(accepted, replay.contains("generated/adapted"))
            }
        } finally { controller.close() }
    }

    @Test fun cancellationNeverPublishesEvidenceOrResponse() = runBlocking {
        val controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, this)
        try {
            val entered = CompletableDeferred<Unit>()
            val pending = launch {
                controller.inferWebChat("Ada?", source = ChatSource.WIKIPEDIA,
                    wikipediaReader = { WikipediaReader { _, _ -> entered.complete(Unit); awaitCancellation() } },
                    inferStep = { _, _, _ -> proposal(query()) }, onDetails = { _, _ -> fail("Cancelled evidence") }, onResponse = { fail("Cancelled response") })
            }
            entered.await(); pending.cancelAndJoin()
        } finally { controller.close() }
    }
}
