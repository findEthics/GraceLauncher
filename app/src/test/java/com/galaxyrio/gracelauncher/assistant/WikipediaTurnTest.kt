package com.galaxyrio.gracelauncher.assistant

import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.tool
import kotlin.reflect.jvm.kotlinFunction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WikipediaTurnTest {
    @Test fun actualSdkExposesQueryOnlyThenBothToolsWithoutChangingQueryArguments() {
        for (first in listOf(true, false, true)) {
            val tools = WikipediaSearch.tools(first)
            val expected = if (first) setOf("query_wikipedia") else setOf("query_wikipedia", "read_wikipedia_sections")
            val provider = tool(tools)
            val schemas = provider.javaClass.methods.single { it.name.startsWith("provideTools") }.apply { isAccessible = true }.invoke(provider) as Map<*, *>
            assertEquals(expected, schemas.keys)
            val methods = tools.javaClass.declaredMethods.filter { it.getAnnotation(Tool::class.java) != null }
            assertEquals(expected, methods.map { it.name }.toSet())
            val query = methods.single { it.name == "query_wikipedia" }
            assertEquals(listOf("topic", "lang", "focus"), query.kotlinFunction!!.parameters.mapNotNull { it.name })
            assertEquals(listOf(String::class.java, String::class.java, String::class.java), query.parameterTypes.toList())
            assertTrue(query.parameterAnnotations.all { row -> row.any { it is ToolParam } })
        }
        assertTrue(AssistantController.WIKIPEDIA_INSTRUCTIONS.contains("first call query_wikipedia"))
    }

    @Test fun invalidArgumentsAndMissingCapabilityNeverReachHttp() = runBlocking {
        var requests = 0
        val turn = WikipediaTurn(WikipediaReader { _, _ -> requests++; error("Unexpected HTTP") })
        val args = mapOf("sections" to listOf("1"), "focus" to "results")
        for (call in listOf(ToolCall("read_wikipedia_sections", args), ToolCall("read_wikipedia_sections", args + ("revision" to 42)),
            ToolCall("read_wikipedia_sections", args + ("sections" to "1")), ToolCall("read_wikipedia_sections", args + ("sections" to listOf(1))),
            ToolCall("read_wikipedia_sections", args + ("sections" to listOf("1", "1"))), ToolCall("query_wikipedia", mapOf("topic" to "Ada", "lang" to "en")))) {
            try { turn.execute(call, 1000); fail("Accepted invalid capability/arguments") } catch (_: WebSearchFailure) { }
        }
        assertEquals(0, requests); assertTrue(turn.sources.isEmpty())
    }

    @Test fun insufficientBudgetFailsBeforeHttpIncludingUnicodeQuestionAllowance() = runBlocking {
        var requests = 0
        val turn = WikipediaTurn(WikipediaReader { _, _ -> requests++; error("Unexpected HTTP") })
        val call = ToolCall("query_wikipedia", mapOf("topic" to "Ada", "lang" to "en", "focus" to "results"))
        for (budget in listOf(-1, 0, 255)) {
            try { turn.execute(call, budget); fail() } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, requests)
        assertTrue(AssistantController.CHAT_INPUT_TOKENS - estimateTokens("界".repeat(1000)) - 512 < 0)
    }

    @Test fun validDualToolArgumentsAndFocusStayLocal() {
        WikipediaSearch.validateCall("query_wikipedia", mapOf("topic" to "2026 election", "lang" to "de", "focus" to "Ergebnisse"))
        WikipediaSearch.validateCall("read_wikipedia_sections", mapOf("sections" to listOf("1", "99"), "focus" to "Ergebnisse"))
        val url = WikipediaReader.discoveryRequestUrl("2026 election", "de")
        assertFalse(url.contains("Ergebnisse")); assertFalse(url.contains("focus"))
    }

    @Test fun realTurnBudgetChargesBothCallsAndPriorResultsWithoutHalvingBody() {
        val query = ToolCall("query_wikipedia", mapOf("topic" to "Wahl 2026", "lang" to "de", "focus" to "Ergebnisse"))
        val read = ToolCall("read_wikipedia_sections", mapOf("sections" to listOf("91"), "focus" to "Ergebnisse"))
        val budget = AssistantController.wikipediaContextTokens("Ergebnisse?", emptyList(), 2860)
        val afterDiscovery = budget - AssistantController.wikipediaCallTokens(query) - 1100 - AssistantController.wikipediaCallTokens(read)
        assertTrue(afterDiscovery > 700) // same number as before, now estimated tokens (~3x the ASCII bytes)
        assertTrue(afterDiscovery + 1100 + AssistantController.wikipediaCallTokens(query) + AssistantController.wikipediaCallTokens(read) <= budget)
        assertTrue(AssistantController.wikipediaContextTokens("界".repeat(1000), emptyList(), 2860) < 0)
        val old = ChatTurn(1, "Wahl?", false, "Frühere Antwort", complete = true, inContext = true, source = ChatSource.WIKIPEDIA,
            sources = listOf(WebSource("Wahl — Ergebnisse", "https://de.wikipedia.org/wiki/Wahl#Ergebnisse", "Alt", provider = ChatSource.WIKIPEDIA)))
        assertEquals(budget - AssistantController.usedHistoryTokens(listOf(old)), AssistantController.wikipediaContextTokens("Ergebnisse?", listOf(old), 2860))
        assertTrue(AssistantController.historyResponse(old).contains("Ergebnisse"))
    }
}
