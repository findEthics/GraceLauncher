package com.galaxyrio.gracelauncher.assistant.memory

internal enum class GateSource { NONE, WEB, WIKIPEDIA, HEALTH, SAVED_SOURCES }

internal enum class GateAction { KEEP_DRAFT, DISCARD_AND_RUN_TOOL_FREE_WITH_MEMORY, WIKIPEDIA_UNABLE, CONTINUE_TOOL_LOOP }

/** One model pass. [hasEvidence] = current sources/citations/retrieved evidence exist for this turn. */
internal data class GateInput(
    val source: GateSource,
    val passHadTools: Boolean,
    val modelReturnedToolCall: Boolean,
    val hasEvidence: Boolean,
    val memoryBlockEmpty: Boolean,
)

internal data class GateDecision(val includeBlock: Boolean, val action: GateAction)

/**
 * Pure privacy gate. Memory never enters a pass with tools registered (those passes can reach third parties).
 * A tool-enabled pass that ends with no tool call and a non-empty block has its draft discarded; the caller then runs
 * ONE tool-free pass with the evidence so far + the block. Wikipedia with no evidence stays "unable" (no extra pass).
 */
internal fun memoryGate(i: GateInput): GateDecision {
    if (!i.passHadTools) return GateDecision(includeBlock = !i.memoryBlockEmpty, action = GateAction.KEEP_DRAFT)
    val action = when {
        i.modelReturnedToolCall -> GateAction.CONTINUE_TOOL_LOOP
        i.source == GateSource.WIKIPEDIA && !i.hasEvidence -> GateAction.WIKIPEDIA_UNABLE
        (i.source == GateSource.WEB || i.source == GateSource.WIKIPEDIA) && !i.memoryBlockEmpty ->
            GateAction.DISCARD_AND_RUN_TOOL_FREE_WITH_MEMORY
        else -> GateAction.KEEP_DRAFT
    }
    return GateDecision(includeBlock = false, action = action)
}

internal data class ChatTurn(val user: String, val reply: String)

/** Per-pass input only: block first, the user's live message last. Never persisted. */
internal fun passInput(userText: String, memoryBlock: String): String =
    if (memoryBlock.isEmpty()) userText else "$memoryBlock\n\nUser message: $userText"

/** System text for a tool-free pass. The block is built fresh per turn and never stored in chat history/context. */
internal fun systemWithMemory(system: String, memoryBlock: String): String =
    if (memoryBlock.isEmpty()) system else "$system\n\n$memoryBlock"

/** History keeps only user text + reply (fix #7): the block is not an input here by construction. */
internal fun recordTurn(history: List<ChatTurn>, userText: String, reply: String): List<ChatTurn> = history + ChatTurn(userText, reply)
