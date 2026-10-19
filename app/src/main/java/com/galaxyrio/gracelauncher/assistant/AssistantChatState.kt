package com.galaxyrio.gracelauncher.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal data class ChatTurn(
    val id: Long, val request: String, val action: Boolean,
    val response: String = "", val complete: Boolean = false, val inContext: Boolean = false,
)

/** RAM only: never use a saved-state registry, preferences, or a database for this state. */
class AssistantChatState {
    var visible by mutableStateOf(false); private set
    internal var input by mutableStateOf(TextFieldValue())
    var draft: String
        get() = input.text
        set(value) { input = TextFieldValue(value, TextRange(value.length)) }
    internal var turns by mutableStateOf<List<ChatTurn>>(emptyList()); private set
    var contextDropped by mutableStateOf(false); private set
    private var nextId = 0L // Never reuse an id after Close; late completions cannot enter the new chat.

    fun open(actionShortcut: Boolean = false) {
        if (actionShortcut && !draft.startsWith('!')) draft = "!$draft"
        visible = true
    }
    fun minimize() { visible = false }
    fun clear() { visible = false; draft = ""; turns = emptyList(); contextDropped = false }
    internal fun begin(request: String, action: Boolean): Long {
        val turn = ChatTurn(++nextId, request, action)
        turns = turns + turn
        draft = ""
        return turn.id
    }
    internal fun update(id: Long, response: String, complete: Boolean = false, inContext: Boolean = false) {
        turns = turns.map { if (it.id == id && !it.complete) it.copy(response = response, complete = complete, inContext = inContext) else it }
    }
    internal fun response(id: Long) = turns.firstOrNull { it.id == id }?.response.orEmpty()

    internal fun context(byteBudget: Int): List<ChatTurn> {
        val eligible = turns.filter { it.complete && it.inContext }.toMutableList()
        // ponytail: UTF-8 bytes + template allowance roll early; use exact input tokenization when the SDK exposes it.
        var bytes = eligible.sumOf { it.request.toByteArray(Charsets.UTF_8).size + it.response.toByteArray(Charsets.UTF_8).size + 64 }
        val removed = mutableSetOf<Long>()
        while (bytes > byteBudget && eligible.isNotEmpty()) {
            val old = eligible.removeAt(0)
            bytes -= old.request.toByteArray(Charsets.UTF_8).size + old.response.toByteArray(Charsets.UTF_8).size + 64
            removed += old.id
        }
        if (removed.isNotEmpty()) {
            turns = turns.map { if (it.id in removed) it.copy(inContext = false) else it }
            contextDropped = true
        }
        return eligible
    }

    companion object {
        /** Only the first literal ! is authorization; conversation text is never promoted to an action. */
        internal fun actionRequest(message: String): String? = if (message.startsWith('!')) message.drop(1).trimStart(' ') else null
        internal fun validateActionRequest(request: String) {
            require(request.isNotBlank() && request.length <= 1000 && request.none(Char::isISOControl)) {
                "Use one complete single-line action after !, at most 1,000 characters. Nothing executed."
            }
        }
    }
}
