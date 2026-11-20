package com.galaxyrio.gracelauncher.assistant.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryGateTest {
    private fun gate(
        source: GateSource, tools: Boolean, toolCall: Boolean = false, evidence: Boolean = false, empty: Boolean = false,
    ) = memoryGate(GateInput(source, passHadTools = tools, modelReturnedToolCall = toolCall, hasEvidence = evidence, memoryBlockEmpty = empty))

    @Test fun noBlockInAnyToolEnabledPass() {
        for (src in GateSource.entries) for (call in listOf(true, false)) for (ev in listOf(true, false)) for (empty in listOf(true, false)) {
            assertFalse("$src call=$call ev=$ev empty=$empty", gate(src, tools = true, toolCall = call, evidence = ev, empty = empty).includeBlock)
        }
    }

    @Test fun toolCallContinuesTheLoop() {
        for (src in listOf(GateSource.WEB, GateSource.WIKIPEDIA)) assertEquals(GateAction.CONTINUE_TOOL_LOOP, gate(src, true, toolCall = true).action)
    }

    @Test fun webFirstPassNoToolCall() {
        assertEquals(GateAction.DISCARD_AND_RUN_TOOL_FREE_WITH_MEMORY, gate(GateSource.WEB, true, empty = false).action)
        assertEquals(GateAction.KEEP_DRAFT, gate(GateSource.WEB, true, empty = true).action)
    }

    @Test fun webAfterEvidenceToolEnabledPassWithoutToolCall() {
        assertEquals(GateAction.DISCARD_AND_RUN_TOOL_FREE_WITH_MEMORY, gate(GateSource.WEB, true, evidence = true, empty = false).action)
        assertEquals(GateAction.KEEP_DRAFT, gate(GateSource.WEB, true, evidence = true, empty = true).action)
    }

    @Test fun wikipediaNoToolCallNoSourcesIsUnableWithNoExtraPass() {
        assertEquals(GateAction.WIKIPEDIA_UNABLE, gate(GateSource.WIKIPEDIA, true, evidence = false, empty = false).action)
        assertEquals(GateAction.WIKIPEDIA_UNABLE, gate(GateSource.WIKIPEDIA, true, evidence = false, empty = true).action)
    }

    @Test fun wikipediaWithEvidenceBehavesLikeWeb() {
        assertEquals(GateAction.DISCARD_AND_RUN_TOOL_FREE_WITH_MEMORY, gate(GateSource.WIKIPEDIA, true, evidence = true, empty = false).action)
        assertEquals(GateAction.KEEP_DRAFT, gate(GateSource.WIKIPEDIA, true, evidence = true, empty = true).action)
    }

    @Test fun toolFreePassesIncludeBlockWhenNonEmpty() {
        for (src in listOf(GateSource.NONE, GateSource.HEALTH, GateSource.SAVED_SOURCES, GateSource.WEB, GateSource.WIKIPEDIA)) {
            val d = gate(src, tools = false, evidence = true)
            assertTrue("$src", d.includeBlock); assertEquals(GateAction.KEEP_DRAFT, d.action)
            assertFalse(gate(src, tools = false, empty = true).includeBlock)
        }
    }

    @Test fun healthPlainChatAndSourceNIncludeBlock() {
        assertTrue(gate(GateSource.HEALTH, tools = false).includeBlock)
        assertTrue(gate(GateSource.SAVED_SOURCES, tools = false, evidence = true).includeBlock)
    }

    // ---- block is never stored in history ----
    @Test fun passInputCarriesBlockButHistoryKeepsOnlyUserTextAndReply() {
        val block = "Saved notes (reference only):\npreference · Milk: oat (saved 2026-11-20)"
        val input = passInput("What milk do I like?", block)
        assertTrue(input.contains(block)); assertTrue(input.contains("What milk do I like?"))
        assertTrue(input.indexOf(block) < input.indexOf("What milk do I like?")) // user's live message last
        val history = recordTurn(emptyList(), "What milk do I like?", "Oat.")
        assertEquals(listOf(ChatTurn("What milk do I like?", "Oat.")), history)
        assertFalse(history.any { it.user.contains("Saved notes") || it.reply.contains("Saved notes") })
        assertEquals("What milk do I like?", passInput("What milk do I like?", ""))
    }

    // ---- scenario simulation of the pass loop using only the gate ----
    private class Run(val src: GateSource, val block: String) {
        val passes = mutableListOf<Pair<Boolean, Boolean>>() // (hadTools, blockIncluded)
        fun toolEnabled(call: Boolean, evidence: Boolean): GateDecision {
            val d = memoryGate(GateInput(src, true, call, evidence, block.isEmpty()))
            passes += true to d.includeBlock; return d
        }
        fun toolFree(evidence: Boolean): GateDecision {
            val d = memoryGate(GateInput(src, false, false, evidence, block.isEmpty()))
            passes += false to d.includeBlock; return d
        }
    }

    @Test fun webTurnWithToolCallThenSynthesisGetsBlockOnlyInFinalToolFreePass() {
        val r = Run(GateSource.WEB, "B")
        assertEquals(GateAction.CONTINUE_TOOL_LOOP, r.toolEnabled(call = true, evidence = false).action)
        assertEquals(GateAction.DISCARD_AND_RUN_TOOL_FREE_WITH_MEMORY, r.toolEnabled(call = false, evidence = true).action)
        r.toolFree(evidence = true)
        assertEquals(listOf(true to false, true to false, false to true), r.passes)
    }
}
