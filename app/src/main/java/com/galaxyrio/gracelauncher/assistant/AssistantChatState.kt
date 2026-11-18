package com.galaxyrio.gracelauncher.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

enum class ChatSource { WEB, WIKIPEDIA, NONE }

internal data class ChatTurn(
    val id: Long, val request: String, val action: Boolean,
    val response: String = "", val complete: Boolean = false, val inContext: Boolean = false,
    val source: ChatSource = ChatSource.NONE, val sources: List<WebSource> = emptyList(), val searchWarning: String = "",
    val wikipediaSummaryGenerated: Boolean = false,
    /** Displayed number per source; null means 1..n. Set when a "source N" follow-up re-shows earlier cards. */
    val sourceNumbers: List<Int>? = null,
) {
    fun number(index: Int) = sourceNumbers?.getOrNull(index) ?: (index + 1)
    val webSearch get() = source == ChatSource.WEB
    val showWikipediaSummaryAttribution get() = source == ChatSource.WIKIPEDIA && sources.isNotEmpty() &&
        complete && response.isNotBlank() && wikipediaSummaryGenerated
}

/** RAM only: never use a saved-state registry, preferences, or a database for this state. */
class AssistantChatState {
    var visible by mutableStateOf(false); private set
    internal var input by mutableStateOf(TextFieldValue())
    var draft: String
        get() = input.text
        set(value) { input = TextFieldValue(value, TextRange(value.length)) }
    internal var turns by mutableStateOf<List<ChatTurn>>(emptyList()); private set
    var contextDropped by mutableStateOf(false); private set
    var source by mutableStateOf(ChatSource.WEB)
    var webSearchEnabled: Boolean
        get() = source == ChatSource.WEB
        set(value) { source = if (value) ChatSource.WEB else ChatSource.NONE }
    private var nextId = 0L // Never reuse an id after Close; late completions cannot enter the new chat.

    fun open() { visible = true }
    fun minimize() { visible = false }
    fun clear() { visible = false; draft = ""; turns = emptyList(); contextDropped = false; source = ChatSource.WEB }
    internal fun begin(request: String, action: Boolean): Long {
        val turn = ChatTurn(++nextId, request, action, source = if (action) ChatSource.NONE else source)
        turns = turns + turn
        draft = ""
        return turn.id
    }
    internal fun update(id: Long, response: String, complete: Boolean = false, inContext: Boolean = false,
        wikipediaSummaryGenerated: Boolean = false) {
        turns = turns.map { if (it.id == id && !it.complete) it.copy(response = response, complete = complete,
            inContext = inContext, wikipediaSummaryGenerated = wikipediaSummaryGenerated) else it }
    }
    internal fun response(id: Long) = turns.firstOrNull { it.id == id }?.response.orEmpty()
    /** [numbers]/[provider] are only passed when earlier cards are re-shown under their original numbers; null keeps the turn's own. */
    internal fun searchDetails(id: Long, sources: List<WebSource>, warning: String,
        numbers: List<Int>? = null, provider: ChatSource? = null) {
        turns = turns.map {
            if (it.id == id && !it.complete) it.copy(sources = sources, searchWarning = warning,
                sourceNumbers = numbers ?: it.sourceNumbers, source = provider ?: it.source) else it
        }
    }

    internal fun context(tokenBudget: Int): List<ChatTurn> {
        val eligible = turns.filter { it.complete && it.inContext }.toMutableList()
        // Cautious token estimate (estimateTokens) plus template allowance; the SDK has no public text tokenizer.
        var tokens = AssistantController.usedHistoryTokens(eligible)
        val removed = mutableSetOf<Long>()
        while (tokens > tokenBudget && eligible.isNotEmpty()) {
            val old = eligible.removeAt(0)
            tokens = AssistantController.usedHistoryTokens(eligible)
            removed += old.id
        }
        if (removed.isNotEmpty()) {
            turns = turns.map { if (it.id in removed) it.copy(inContext = false) else it }
            contextDropped = true
        }
        return eligible
    }

    companion object {
        internal fun validateActionRequest(request: String) {
            require(request.isNotBlank() && request.length <= 1000 && request.none(Char::isISOControl)) {
                "Use one complete single-line action, at most 1,000 characters. Nothing executed."
            }
        }
    }
}
