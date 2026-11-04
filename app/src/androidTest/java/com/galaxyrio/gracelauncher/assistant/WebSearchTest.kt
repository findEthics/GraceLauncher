package com.galaxyrio.gracelauncher.assistant

import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolCall
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WebSearchTest {
    private fun searchCall(query: String) = Message.model(Contents.of(emptyList()), listOf(ToolCall("search_web", mapOf("query" to query))))

    @Test fun retrospectiveSelectionKeepsNumberingBoundsAndExplicitHistory() = runBlocking {
        val controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, this)
        try {
            val current = WebSource("Current report", "https://example.org/current", "Current evidence. ".repeat(40), "2026-11-04")
            val historical = WebSource("Today in History: a tower opens", "https://example.org/history", "Historical evidence.", "2026-11-04")
            val other = current.copy(title = "Other report", url = "https://example.org/other")
            val last = current.copy(title = "Latest report", url = "https://example.org/latest")
            for (request in listOf("summary of world news today", "history news today", "when did the tower open?")) {
                val filtered = request == "summary of world news today"
                var steps = 0
                var searches = 0
                var shown = emptyList<WebSource>()
                controller.inferWebChat(request, search = {
                    searches++
                    if (searches == 1) listOf(current, historical, other) else listOf(other, historical, last)
                }, inferStep = { _, next, allow ->
                    if (steps > 0) {
                        val result = (next.contents.contents.single() as Content.ToolResponse).response.toString()
                        assertTrue(result.toByteArray(Charsets.UTF_8).size <= AssistantController.webContextBytes(request.toByteArray(Charsets.UTF_8).size) / 2)
                        assertEquals(!filtered, result.contains("Today in History"))
                        assertTrue(result.contains("[${if (steps == 1) 1 else if (filtered) 2 else 3}] Headline: ${if (steps == 1) "Current" else "Other"} report (published: 2026-11-04)\nExcerpt: "))
                        assertTrue(result.contains("[${if (steps == 1) if (filtered) 2 else 3 else if (filtered) 3 else 4}] Headline: ${if (steps == 1) "Other" else "Latest"} report"))
                        assertFalse(result.contains('\uFFFD'))
                    }
                    steps++
                    if (allow) searchCall(if (request == "when did the tower open?") "tower opening date" else "world news today")
                    else Message.model("Fake completion; no model adequacy claim")
                }, onDetails = { sources, warning -> assertEquals("", warning); shown = sources }, onResponse = {})
                assertEquals(2, searches)
                assertEquals(3, steps)
                assertEquals(if (filtered) listOf(current.url, other.url, last.url) else listOf(current.url, historical.url, other.url, last.url), shown.map { it.url })
            }
        } finally { controller.close() }
    }

    @Test fun allRetrospectiveNewsUsesExistingInsufficientResultsPath() = runBlocking {
        val controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, this)
        try {
            var steps = 0
            var searches = 0
            var warning = ""
            controller.inferWebChat("news today", search = {
                searches++
                listOf(WebSource("Today in History: an opening", "https://example.org/history", "Historical evidence.", "2026-11-04"))
            }, inferStep = { _, next, allow ->
                if (steps++ == 0) searchCall("news today") else {
                    assertFalse(allow)
                    assertTrue((next.contents.contents.single() as Content.ToolResponse).response.toString().contains("error"))
                    Message.model("Unverified offline reply")
                }
            }, onDetails = { sources, value -> assertTrue(sources.isEmpty()); warning = value }, onResponse = {})
            assertEquals(1, searches)
            assertEquals(2, steps)
            assertTrue(warning.isNotBlank())
        } finally { controller.close() }
    }

    @Test fun dynamicBudgetKeepsTwoCompleteDatedResultsForLongAndShortInputs() = runBlocking {
        val controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, this)
        try {
            for (input in listOf("summary of world news today", "é".repeat(636))) {
                var searches = 0
                var steps = 0
                var toolBytes = 0
                val reserve = AssistantController.webContextBytes(input.toByteArray(Charsets.UTF_8).size)
                controller.inferWebChat(input, search = {
                    searches++
                    List(3) { WebSource("t".repeat(60), "https://example.org/$searches/$it", "é😀".repeat(200), "d".repeat(40)) }
                }, inferStep = { transcript, next, allow ->
                    assertEquals(steps * 2, transcript.size)
                    if (steps == 0) assertEquals(input, (next.contents.contents.single() as Content.Text).text)
                    else {
                        assertEquals("search_web", transcript.last().toolCalls.single().name)
                        val response = (next.contents.contents.single() as Content.ToolResponse).response
                        val bytes = response.toString().toByteArray(Charsets.UTF_8).size
                        assertTrue(bytes <= reserve / 2)
                        toolBytes += bytes
                        assertTrue(response.toString().contains("[${if (steps == 1) 1 else 4}] "))
                        assertTrue(response.toString().contains("[${if (steps == 1) 3 else 6}] "))
                        assertTrue(response.toString().contains("(published: ${"d".repeat(40)})"))
                        assertFalse(response.toString().contains('\uFFFD'))
                    }
                    steps++
                    if (allow) searchCall("news $steps") else Message.model("Evidence only [1] [4]")
                }, onDetails = { sources, warning -> assertEquals("", warning); assertEquals(searches * 3, sources.size) }, onResponse = {})
                assertEquals(2, searches)
                assertEquals(3, steps)
                assertTrue(toolBytes <= reserve)
            }
        } finally { controller.close() }
    }

    @Test fun primaryHeadlinesKeepTheirOwnMarkedExcerpts() = runBlocking {
        val controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, this)
        try {
            val headline = "Regional transport authority approves a new railway timetable for winter service"
            val content = "Primary railway details remain supported. ".repeat(20)
            var steps = 0
            controller.inferWebChat("news", search = {
                listOf(WebSource(headline, "https://example.org/rail", content, "2026-11-03"),
                    WebSource("Health agency publishes guidance", "https://example.org/health", "Primary health details."))
            }, inferStep = { _, next, _ ->
                if (steps++ == 0) searchCall("news") else {
                    val result = (next.contents.contents.single() as Content.ToolResponse).response.toString()
                    assertTrue(result.contains("[1] Headline: $headline (published: 2026-11-03)\nExcerpt: "))
                    val excerpt = result.substringAfter("\nExcerpt: ").substringBefore("\n\n")
                    assertTrue(excerpt.endsWith("…"))
                    assertTrue(content.startsWith(excerpt.removeSuffix("…")))
                    assertTrue(excerpt.toByteArray(Charsets.UTF_8).size <= 320)
                    assertTrue(result.contains("[2] Headline: Health agency publishes guidance\nExcerpt: Primary health details."))
                    Message.model("Fake reply; no model grounding claim")
                }
            }, onDetails = { sources, warning ->
                assertEquals("", warning)
                assertEquals(AssistantController.utf8Prefix(headline, 60), sources.first().title)
                assertEquals(320, sources.first().content.toByteArray(Charsets.UTF_8).size)
            }, onResponse = {})
            assertEquals(2, steps)
        } finally { controller.close() }
    }

    @Test fun publishedDateReachesToolResult() = runBlocking {
        val controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, this)
        try {
            var steps = 0
            controller.inferWebChat("news", search = {
                listOf(WebSource("Source", "https://example.org", "evidence", "2026-11-03"))
            }, inferStep = { _, next, _ ->
                if (steps++ == 0) searchCall("news") else {
                    val result = next.contents.contents.single() as Content.ToolResponse
                    assertTrue(result.response.toString().contains("[1] Headline: Source (published: 2026-11-03)\nExcerpt: evidence"))
                    assertTrue(result.response.toString().contains("Live web search results retrieved on "))
                    Message.model("Summary [1]")
                }
            }, onResponse = {})
            assertEquals(2, steps)
        } finally { controller.close() }
    }

    @Test fun textEnvelopeUsesGeneratedQueryAndStructuredTranscript() = runBlocking {
        val controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, this)
        try {
            var searches = 0
            var steps = 0
            val displayed = mutableListOf<String>()
            val turn = controller.chat.begin("raw user text", false)
            controller.inferWebChat("raw user text", search = { query ->
                searches++; assertEquals("World news summary today", query)
                listOf(WebSource("Source", "https://example.org", "evidence"))
            }, inferStep = { transcript, next, allow ->
                steps++
                if (steps == 1) Message.model("<|tool_call>call:search_web>World news summary today")
                else {
                    assertTrue(allow)
                    assertEquals("search_web", transcript.last().toolCalls.single().name)
                    assertEquals("World news summary today", transcript.last().toolCalls.single().arguments["query"])
                    assertEquals("search_web", (next.contents.contents.single() as Content.ToolResponse).name)
                    Message.model("Summary [1]")
                }
            }, onResponse = { displayed += it; controller.chat.update(turn, it) })
            assertEquals(1, searches); assertEquals(2, steps)
            assertEquals(listOf("Summary [1]"), displayed)
            controller.chat.update(turn, displayed.last(), complete = true, inContext = true)
            assertEquals("Summary [1]", controller.chat.context(4096).single().response)
        } finally { controller.close() }
    }

    @Test fun malformedAndFinalSplitMarkersNeverDispatchOrDisplay() = runBlocking {
        val controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, this)
        try {
            for (bad in listOf("<|tool_call>call:other>news", "quoted <|tool_call>call:search_web>news",
                "<|tool_call>call:search_web>\"news\"", "<|tool_call>call:search_web>" + "x".repeat(257))) {
                var warning = ""; var result = ""; var steps = 0
                controller.inferWebChat("news", search = { fail("Must not dispatch"); emptyList() },
                    inferStep = { _, _, _ -> steps++; Message.model(bad) },
                    onDetails = { _, value -> warning = value }, onResponse = { result = it })
                assertEquals(1, steps); assertTrue(warning.isNotBlank()); assertFalse(result.contains("<|"))
            }
            var searches = 0; var steps = 0; var warning = ""; val displayed = mutableListOf<String>()
            controller.inferWebChat("news", search = {
                searches++; listOf(WebSource("Source", "https://example.org/$searches", "evidence"))
            }, inferStep = { _, _, allow ->
                steps++
                if (steps <= 2) searchCall("query $steps") else {
                    assertFalse(allow)
                    val buffer = WebReplyBuffer()
                    listOf("<|to", "ol_call>call:", "search_web>third").forEach(buffer::append)
                    Message.model(buffer.result())
                }
            }, onDetails = { _, value -> warning = value }, onResponse = { displayed += it })
            assertEquals(2, searches); assertEquals(3, steps); assertTrue(warning.isNotBlank())
            assertEquals(1, displayed.size); assertFalse(displayed.single().contains("<|"))
            var mixedWarning = ""; var mixedAnswer = ""
            controller.inferWebChat("news", search = { fail("Mixed proposal dispatched"); emptyList() },
                inferStep = { _, _, _ -> Message.model(Contents.of(listOf(Content.Text("text"))),
                    listOf(ToolCall("search_web", mapOf("query" to "news")))) },
                onDetails = { _, value -> mixedWarning = value }, onResponse = { mixedAnswer = it })
            assertTrue(mixedWarning.isNotBlank()); assertFalse(mixedAnswer.contains("text"))
            searches = 0; steps = 0; warning = ""
            controller.inferWebChat("news", search = {
                searches++; listOf(WebSource("Source", "https://example.org/$searches", "evidence"))
            }, inferStep = { _, _, allow -> steps++; if (allow) searchCall("query $steps") else searchCall("third") },
                onDetails = { _, value -> warning = value }, onResponse = {})
            assertEquals(2, searches); assertEquals(3, steps); assertTrue(warning.isNotBlank())
        } finally { controller.close() }
    }

    @Test fun failedFakeSearchRetainsWarningAndDisablesTools() = runBlocking {
        val controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, this)
        try {
            var calls = 0; var steps = 0; var warning = ""; var answer = ""
            controller.inferWebChat("news", search = { calls++; throw WebSearchFailure(WebSearchFailure.Reason.LIMIT) },
                inferStep = { _, _, allow -> steps++; if (steps == 1) searchCall("generated") else {
                    assertFalse(allow); Message.model("Unverified offline reply")
                } }, onDetails = { _, value -> warning = value }, onResponse = { answer = it })
            assertEquals(1, calls); assertEquals(2, steps); assertTrue(warning.isNotBlank())
            assertEquals("Unverified offline reply", answer)
        } finally { controller.close() }
    }

    @Test fun searchLoopEnforcesTwoRequestsAndFeedsStructuredToolResults() = runBlocking {
        val controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, this)
        try {
            var calls = 0
            var steps = 0
            var answer = ""
            var shown = emptyList<WebSource>()
            controller.inferWebChat("compare releases", search = {
                calls++
                listOf(WebSource("Source $calls", "https://example.org/$calls", "é😀".repeat(200)))
            }, inferStep = { transcript, next, allow ->
                if (steps > 0) {
                    assertTrue(transcript.last().toolCalls.isNotEmpty())
                    val result = next.contents.contents.single() as Content.ToolResponse
                    assertEquals("search_web", result.name)
                    assertTrue(result.response.toString().toByteArray(Charsets.UTF_8).size < 1000)
                }
                steps++
                if (steps <= 2) { assertTrue(allow); searchCall("release $steps") }
                else { assertFalse(allow); Message.model("Final answer [1] [2]") }
            }, onDetails = { sources, warning -> shown = sources; assertEquals("", warning) }, onResponse = { answer = it })
            assertEquals(2, calls); assertEquals(3, steps); assertEquals(2, shown.size)
            assertTrue(shown.all { it.content.toByteArray(Charsets.UTF_8).size == 320 })
            assertEquals("Final answer [1] [2]", answer)
        } finally { controller.close() }
    }

    @Test fun parallelOrUnknownToolProposalsNeverDispatchAndFallBackWithWarning() = runBlocking {
        val controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, this)
        try {
            for (calls in listOf(listOf(ToolCall("turnOnFlashlight", emptyMap())),
                listOf(ToolCall("search_web", mapOf("query" to "one")), ToolCall("search_web", mapOf("query" to "two"))))) {
                var steps = 0
                var warned = false
                controller.inferWebChat("news", search = { fail("Invalid proposal reached dispatch"); emptyList() },
                    inferStep = { _, _, allow ->
                        steps++
                        if (steps == 1) Message.model(Contents.of(emptyList()), calls)
                        else { assertFalse(allow); Message.model("Unverified offline reply") }
                    }, onDetails = { _, warning -> warned = warning.isNotBlank() }, onResponse = {})
                assertTrue(warned); assertEquals(2, steps)
            }
        } finally { controller.close() }
    }

    @Test fun cancellationDuringSearchStopsWithoutLateReplyOrDetails() = runBlocking {
        val controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, this)
        try {
            val started = CompletableDeferred<Unit>()
            var details = false
            var reply = false
            val work = launch {
                controller.inferWebChat("news", search = { started.complete(Unit); awaitCancellation() },
                    inferStep = { _, _, _ -> searchCall("Android news") },
                    onDetails = { _, _ -> details = true }, onResponse = { reply = true })
            }
            started.await(); work.cancelAndJoin()
            assertFalse(details); assertFalse(reply); assertTrue(work.isCancelled)
        } finally { controller.close() }
    }

    @Test fun resultParsingRejectsUnsafeLinksAndBoundsSources() {
        val sources = TavilySearch.parse("""{"results":[
            {"title":"unsafe","url":"javascript:alert(1)","content":"ignore"},
            {"title":"Example","url":"https://example.org","content":"evidence","published_date":"2026-11-03"},
            {"title":"duplicate","url":"https://example.org","content":"same"},
            {"title":"outside limit","url":"https://example.com","content":"ignored"}]}""")
        assertEquals(1, sources.size)
        assertEquals("Example", sources.single().title)
        assertEquals("2026-11-03", sources.single().publishedDate)
        try { TavilySearch.parse("""{"results":[]}"""); fail("Empty evidence accepted") }
        catch (e: WebSearchFailure) { assertEquals(WebSearchFailure.Reason.RESULTS, e.reason) }
    }

    @Test fun liveTavilyReturnsSafeStructuredSources() = runBlocking {
        val sources = TavilySearch.search("Android developer documentation Jetpack Compose")
        assertTrue(sources.size in 1..3)
        assertTrue(sources.all { WebSource.safeUrl(it.url) && it.content.isNotBlank() })
    }

    @Test fun realGemmaAndTavilyProduceAnswerWithSources() = runBlocking {
        val controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, this)
        try {
            assertTrue("Verified Gemma4 must be present", controller.ready())
            var calls = 0
            var answer = ""
            var sources = emptyList<WebSource>()
            controller.inferWebChat("Search the web for the official Android Jetpack Compose documentation and briefly explain what Compose is.",
                search = { query -> calls++; TavilySearch.search(query) },
                onDetails = { found, warning -> assertEquals("", warning); sources = found }, onResponse = { answer = it })
            assertTrue(calls in 1..2); assertTrue(sources.isNotEmpty()); assertTrue(answer.isNotBlank())
            assertTrue(answer.contains("Compose", true))
        } finally { controller.close() }
    }

    @Test fun gemmaChoosesSearchQueryFromContextAndUsesReturnedEvidence() = runBlocking {
        val controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, this)
        try {
            assertTrue("Verified Gemma4 must be present", controller.ready())
            var calls = 0
            var answer = ""
            var shown = emptyList<WebSource>()
            val history = listOf(ChatTurn(1, "We are discussing the Android operating system.", false,
                "Understood.", complete = true, inContext = true))
            controller.inferWebChat("Search for its latest release. Use the web because offline information may be outdated.", history,
                search = { query ->
                    calls++
                    assertTrue("Query must resolve the subject", query.contains("Android", ignoreCase = true))
                    listOf(WebSource("Synthetic release bulletin", "https://example.org/android", "The release codename in this test bulletin is ORCHID. The version is 99."))
                }, onDetails = { sources, warning -> assertEquals("", warning); shown = sources }, onResponse = { answer = it })
            assertTrue("Model must search for explicitly requested current facts", calls in 1..2)
            assertEquals(1, shown.size)
            assertTrue("Model must use supplied evidence", answer.contains("ORCHID", true) || answer.contains("99"))
            assertTrue(controller.lastTokenCount in 1 until AssistantController.CONTEXT_TOKENS)
        } finally { controller.close() }
    }

    @Test fun gemmaSkipsSearchForGreeting() = runBlocking {
        val controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, this)
        try {
            assertTrue("Verified Gemma4 must be present", controller.ready())
            var answer = ""
            controller.inferWebChat("Hello! Reply with one short greeting. No web facts needed.",
                search = { fail("Greeting must not search"); emptyList() }, onResponse = { answer = it })
            assertTrue(answer.isNotBlank())
        } finally { controller.close() }
    }

    @Test fun failedSearchRetainsWarningAndAllowsOfflineAnswer() = runBlocking {
        val controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, this)
        try {
            assertTrue("Verified Gemma4 must be present", controller.ready())
            var calls = 0
            var warning = ""
            var answer = ""
            controller.inferWebChat("Search the web for today's Android news, then briefly explain what Android is.",
                search = { calls++; throw WebSearchFailure(WebSearchFailure.Reason.LIMIT) },
                onDetails = { sources, value -> assertTrue(sources.isEmpty()); warning = value }, onResponse = { answer = it })
            assertEquals(1, calls)
            assertTrue("Failure must remain visible separately from answer", warning.isNotBlank())
            assertTrue("Warned offline reply must be permitted", answer.isNotBlank())
        } finally { controller.close() }
    }
}
