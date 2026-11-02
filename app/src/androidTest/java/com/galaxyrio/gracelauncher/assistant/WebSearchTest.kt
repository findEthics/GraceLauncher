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
            {"title":"Example","url":"https://example.org","content":"evidence"},
            {"title":"duplicate","url":"https://example.org","content":"same"},
            {"title":"outside limit","url":"https://example.com","content":"ignored"}]}""")
        assertEquals(1, sources.size)
        assertEquals("Example", sources.single().title)
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
