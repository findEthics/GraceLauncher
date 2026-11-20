package com.galaxyrio.gracelauncher.assistant

import com.galaxyrio.gracelauncher.assistant.memory.MemoryEntry
import com.galaxyrio.gracelauncher.assistant.memory.MemoryType
import com.galaxyrio.gracelauncher.assistant.memory.buildMemoryBlock
import com.galaxyrio.gracelauncher.assistant.memory.subjectKey
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolCall
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Controller-level privacy gate over the real Web/Wikipedia loop with a fake model; no engine, no network. */
class MemoryPrivacyGateTest {
    private val block = "Saved notes (reference only; the user's current message wins):\npreference · drink: SECRET-TEA (saved 2026-01-01)"
    private val secret = "SECRET-TEA"

    private class Seen(val spec: PassSpec) {
        fun allText(): String = spec.system + "\n" + (spec.transcript + spec.next).joinToString("\n") { it.toString() }
    }

    private fun call(q: String) = Message.model(Contents.of(emptyList()), listOf(ToolCall("search_web", mapOf("query" to q))))
    private fun text(t: String) = Message.model(Contents.of(listOf(Content.Text(t))), emptyList())
    private fun wikiQuery() = Message.model(Contents.of(emptyList()), listOf(ToolCall("query_wikipedia", mapOf("topic" to "Ada", "lang" to "en", "focus" to "Results"))))
    private val found = listOf(WebSource("Title", "https://example.com/a", "Body text that is long enough to be evidence for the answer."))
    private val reader = { WikipediaReader { url, _ ->
        when {
            url.contains("generator=search") -> """{"query":{"pages":[{"pageid":1,"index":1,"ns":0,"title":"Ada","fullurl":"https://en.wikipedia.org/wiki/Ada","extract":"Ada Lovelace was an English mathematician and writer, chiefly known for her work on Charles Babbage's proposed mechanical general-purpose computer, the Analytical Engine, and for publishing the first algorithm intended for a machine."}]}}"""
            url.contains("prop=sections") -> """{"parse":{"pageid":1,"revid":42,"sections":[{"index":"1","line":"Results","anchor":"Results","level":"2"}]}}"""
            else -> """{"parse":{"pageid":1,"revid":42,"text":"<h2>Results</h2><p>The result was published in 1843.</p>"}}"""
        }
    } }

    private class Run(val seen: MutableList<Seen>, val responses: MutableList<String>, val result: Boolean)

    private fun run(source: ChatSource, memoryBlock: String, vararg script: Message): Run {
        val seen = mutableListOf<Seen>(); val responses = mutableListOf<String>()
        var i = 0
        val env = WebTurnEnv(AssistantModel.Gemma4, { "" }) { spec -> seen += Seen(spec); script[i++] }
        val result = runBlocking {
            runWebTurn(env, "What do I like to drink?", emptyList(), emptyList(), { found }, { _, _ -> }, 2000, source, reader, memoryBlock) { responses += it }
        }
        return Run(seen, responses, result)
    }

    private fun assertNoToolPassSawMemory(r: Run) = r.seen.filter { it.spec.allowSearch }.forEach {
        assertFalse("tool-enabled pass received memory", it.allText().contains(secret)); assertFalse(it.allText().contains("Saved notes"))
    }

    @Test fun webNoToolCallWithBlockDiscardsDraftAndRunsOneToolFreePassWithBlock() {
        val r = run(ChatSource.WEB, block, text("DRAFT from the tool pass"), text("Final answer with memory"))
        assertEquals(2, r.seen.size)
        assertTrue(r.seen[0].spec.allowSearch); assertFalse(r.seen[1].spec.allowSearch)
        assertNoToolPassSawMemory(r)
        assertTrue(r.seen[1].allText().contains(secret))
        assertEquals(listOf("Final answer with memory"), r.responses) // draft never emitted
        assertFalse(r.responses.any { it.contains("DRAFT") })
    }

    @Test fun webNoToolCallEmptyBlockKeepsDraftNoExtraPass() {
        val r = run(ChatSource.WEB, "", text("Direct answer"))
        assertEquals(1, r.seen.size); assertEquals(listOf("Direct answer"), r.responses)
    }

    @Test fun webAfterEvidenceToolEnabledPassWithoutCallDiscardsAndAnswersToolFreeWithEvidenceAndBlock() {
        val r = run(ChatSource.WEB, block, call("tea"), text("DRAFT after evidence"), text("Final with evidence and memory"))
        assertEquals(3, r.seen.size)
        assertTrue(r.seen[0].spec.allowSearch); assertTrue(r.seen[1].spec.allowSearch); assertFalse(r.seen[2].spec.allowSearch)
        assertNoToolPassSawMemory(r)
        assertTrue(r.seen[2].allText().contains(secret))
        assertTrue(r.seen[2].spec.transcript.isNotEmpty()) // evidence so far (search call + result) is carried
        assertTrue(r.seen[2].allText().contains("untrusted_sources") || r.seen[2].allText().contains("Headline"))
        assertEquals(listOf("Final with evidence and memory"), r.responses)
    }

    @Test fun finalToolFreePassAtSearchLimitIncludesBlockInSinglePass() {
        val r = run(ChatSource.WEB, block, call("a"), call("b"), text("Final tool-free"))
        assertEquals(3, r.seen.size)
        assertFalse(r.seen[2].spec.allowSearch); assertTrue(r.seen[2].allText().contains(secret))
        assertEquals(listOf("Final tool-free"), r.responses)
        assertNoToolPassSawMemory(r)
    }

    @Test fun wikipediaNoToolCallNoSourcesKeepsUnableOutcomeWithoutExtraPass() {
        val r = run(ChatSource.WIKIPEDIA, block, text("Offline guess"))
        assertEquals(1, r.seen.size); assertEquals(listOf(WikipediaSearch.INABILITY), r.responses); assertFalse(r.result)
        assertNoToolPassSawMemory(r)
    }

    @Test fun wikipediaWithEvidenceFollowsWebRule() {
        val r = run(ChatSource.WIKIPEDIA, block, wikiQuery(), text("Ada was a mathematician [1] DRAFT"), text("Ada was a mathematician [1]."))
        assertEquals(3, r.seen.size); assertFalse(r.seen[2].spec.allowSearch)
        assertNoToolPassSawMemory(r)
        assertTrue(r.seen[2].allText().contains(secret))
        assertEquals(listOf("Ada was a mathematician [1]."), r.responses); assertTrue(r.result)
    }

    @Test fun extraPassStillFailsClosedOnToolMarkup() {
        val r = run(ChatSource.WEB, block, text("draft"), text("<|tool_call>call:search_web>x"))
        assertEquals(1, r.responses.size); assertFalse(r.responses.single().contains("tool_call")); assertFalse(r.result)
    }

    @Test fun blockIsNeverPartOfStoredHistoryOrState() {
        val state = AssistantChatState()
        val turn = state.begin("What do I like?", false)
        state.update(turn, "Tea", complete = true, inContext = true)
        val stored = state.turns.joinToString { it.request + it.response }
        assertFalse(stored.contains("Saved notes")); assertFalse(stored.contains(secret))
        val history = state.context(900)
        assertEquals("What do I like?", history.single().request)
    }

    @Test fun blockBudgetShrinksToFitAndStaysUnder400Tokens() {
        val many = (1..40).map { MemoryEntry("p$it", MemoryType.PREFERENCE, "pref $it", subjectKey("pref $it"), "likes thing number $it ".repeat(6), validFrom = it.toLong()) }
        val full = buildMemoryBlock("x", many, 1_000_000, ZoneId.of("UTC"))
        assertTrue(estimateTokens(full) <= 400)
        val small = buildMemoryBlock("x", many, 1_000_000, ZoneId.of("UTC"), 120)
        assertTrue(estimateTokens(small) <= 120)
        assertTrue(estimateTokens(small) < estimateTokens(full))
    }
}
