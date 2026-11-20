package com.galaxyrio.gracelauncher.assistant

import com.galaxyrio.gracelauncher.R
import com.galaxyrio.gracelauncher.assistant.memory.GateAction
import com.galaxyrio.gracelauncher.assistant.memory.GateInput
import com.galaxyrio.gracelauncher.assistant.memory.GateSource
import com.galaxyrio.gracelauncher.assistant.memory.memoryGate
import com.galaxyrio.gracelauncher.assistant.memory.systemWithMemory
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolCall
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One model pass of the Web/Wikipedia loop. [allowSearch] = tools are registered (they can reach third parties, so
 * [system] and [next] NEVER contain the memory block then). [system] already includes the block for tool-free passes.
 */
internal class PassSpec(
    val allowSearch: Boolean, val wiki: Boolean, val firstLookup: Boolean,
    val system: String, val transcript: List<Message>, val next: Message,
)

/** What the loop needs from its owner (the controller on device, a fake in JVM tests). */
internal class WebTurnEnv(
    val selected: AssistantModel,
    val string: (Int) -> String,
    val pass: suspend (PassSpec) -> Message,
)

private const val NO_SEARCH_WEB = "\nNo more searches are available. Answer now. If search failed, use only offline knowledge and explicitly state that it was not verified."
private const val NO_SEARCH_WIKI = "\nNo more retrievals. Use only this turn's evidence; if insufficient, say so or ask for clarification. Never use offline knowledge."
// Extra tool-free pass after a discarded draft when this turn retrieved nothing: the model chose not to search.
private const val NO_SEARCH_NEEDED = "\nNo search is available for this reply. Answer now; if you did not search, do not claim you did."

/**
 * Each inference step can retry on CPU (inside [WebTurnEnv.pass]); true means a nonblank final Wikipedia response with current-turn
 * sources. Network requests stay outside retries. Privacy gate: memory never reaches a pass with tools; a tool-enabled pass that ends
 * without a tool call while [memoryBlock] is non-empty is discarded (never emitted) and one extra tool-free pass runs with the evidence
 * so far plus the block.
 */
internal suspend fun runWebTurn(
    env: WebTurnEnv, text: String, history: List<ChatTurn>, initialTranscript: List<Message>,
    search: suspend (String) -> List<WebSource>, onDetails: suspend (List<WebSource>, String) -> Unit,
    webBudget: Int, source: ChatSource, wikipediaReader: () -> WikipediaReader, memoryBlock: String,
    onResponse: suspend (String) -> Unit,
): Boolean {
    require(source != ChatSource.NONE)
    val wiki = source == ChatSource.WIKIPEDIA
    val gateSource = if (wiki) GateSource.WIKIPEDIA else GateSource.WEB
    val wikiTurn = if (wiki) WikipediaTurn(wikipediaReader()) else null
    val transcript = initialTranscript.toMutableList()
    val sources = mutableListOf<WebSource>()
    var warning = ""
    var next = Message.user(text)
    val now = ZonedDateTime.now()
    val webInstructions = if (wiki) AssistantController.WIKIPEDIA_INSTRUCTIONS + "\nCurrent local date: ${now.toLocalDate()}." else AssistantController.webInstructions(now)
    val memoryTokens = estimateTokens(memoryBlock)
    val resultBudget = minOf(webBudget, AssistantController.MAX_WEB_CONTEXT_TOKENS,
        AssistantController.CHAT_INPUT_TOKENS - estimateTokens(text) - AssistantController.usedHistoryTokens(history) - memoryTokens) / 2
    // CHAT_INPUT_TOKENS already reserves 512 tokens; reserve 512 more for the dual schema/instructions.
    val wikiBudget = AssistantController.wikipediaContextTokens(text, history, webBudget)
    var wikiSpent = 0
    fun textOf(m: Message) = stripThinking(m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text })
    fun retrieval(step: Int, suffix: String) = modelRetrievalInstructions(env.selected, webInstructions, source, step == 0) + suffix
    for (step in 0..2) {
        currentCoroutineContext().ensureActive()
        val allowSearch = step < 2 && warning.isBlank()
        val suffix = if (allowSearch) "" else if (wiki) NO_SEARCH_WIKI else NO_SEARCH_WEB
        val includeBlock = memoryGate(GateInput(gateSource, allowSearch, false, sources.isNotEmpty(), memoryBlock.isEmpty())).includeBlock
        val system = retrieval(step, suffix).let { if (includeBlock) systemWithMemory(it, memoryBlock) else it }
        val reply = env.pass(PassSpec(allowSearch, wiki, step == 0, system, transcript.toList(), next))
        currentCoroutineContext().ensureActive()
        val replyText = textOf(reply)
        val recoveredCall = if (allowSearch && reply.toolCalls.isEmpty()) {
            if (wiki) WikipediaSearch.recoveredCall(replyText)
            else WebToolText.query(replyText)?.let { ToolCall("search_web", mapOf("query" to it)) }
        } else null
        val invalidOutput = (!allowSearch && reply.toolCalls.isNotEmpty()) ||
            (reply.toolCalls.isNotEmpty() && replyText.isNotBlank()) ||
            (recoveredCall == null && (WebToolText.suspicious(replyText) ||
                (wiki && replyText.contains("call:read_wikipedia_sections", ignoreCase = true))))
        if (invalidOutput) {
            warning = if (wiki) WikipediaSearch.WARNING else env.string(R.string.assistant_search_request_error)
            onDetails(sources.toList(), warning)
            onResponse(if (wiki) WikipediaSearch.INABILITY else "Web search request was rejected. Please try again.")
            return false
        }
        val effectiveReply = if (recoveredCall != null) Message.model(Contents.of(emptyList()), listOf(recoveredCall)) else reply
        if (effectiveReply.toolCalls.isEmpty()) {
            val hasEvidence = sources.isNotEmpty() && (!wiki || WikipediaSearch.citationsResolve(replyText, sources.size))
            val action = if (allowSearch) memoryGate(GateInput(gateSource, true, false, hasEvidence, memoryBlock.isEmpty())).action
                else GateAction.KEEP_DRAFT
            if (action == GateAction.DISCARD_AND_RUN_TOOL_FREE_WITH_MEMORY) {
                // The draft came from a pass with tools: drop it unseen, answer once more without tools and with the memory block.
                val extraSuffix = if (sources.isEmpty() && !wiki) NO_SEARCH_NEEDED else if (wiki) NO_SEARCH_WIKI else NO_SEARCH_WEB
                val extra = env.pass(PassSpec(false, wiki, false,
                    systemWithMemory(retrieval(1, extraSuffix), memoryBlock), transcript.toList(), next))
                currentCoroutineContext().ensureActive()
                val extraText = textOf(extra)
                if (extra.toolCalls.isNotEmpty() || WebToolText.suspicious(extraText) ||
                    (wiki && extraText.contains("call:read_wikipedia_sections", ignoreCase = true))) {
                    warning = if (wiki) WikipediaSearch.WARNING else env.string(R.string.assistant_search_request_error)
                    onDetails(sources.toList(), warning)
                    onResponse(if (wiki) WikipediaSearch.INABILITY else "Web search request was rejected. Please try again.")
                    return false
                }
                if (wiki && !WikipediaSearch.citationsResolve(extraText, sources.size)) {
                    warning = WikipediaSearch.WARNING
                    onDetails(sources.toList(), warning)
                    onResponse(WikipediaSearch.INABILITY)
                    return false
                }
                onResponse(extraText)
                return wiki && sources.isNotEmpty() && extraText.isNotBlank()
            }
            if (wiki && (sources.isEmpty() || !WikipediaSearch.citationsResolve(replyText, sources.size))) {
                warning = WikipediaSearch.WARNING
                onDetails(sources.toList(), warning)
                onResponse(WikipediaSearch.INABILITY)
                return false
            } else {
                onResponse(replyText)
                return wiki && sources.isNotEmpty() && replyText.isNotBlank()
            }
        }
        require(allowSearch) { "Search budget exhausted" }
        transcript += next
        transcript += effectiveReply
        var toolResult: Map<String, String>
        val call = effectiveReply.toolCalls.singleOrNull()
        try {
            if (call == null) throw WebSearchFailure(WebSearchFailure.Reason.REQUEST)
            if (wiki) {
                // Count complete arguments, model/tool framing and all earlier retrieval messages.
                wikiSpent += AssistantController.wikipediaCallTokens(call)
                val allowance = wikiBudget - wikiSpent
                toolResult = wikiTurn!!.execute(call, if (call.name == "query_wikipedia") minOf(1100, allowance) else allowance)
                wikiSpent += estimateTokens(toolResult.toString())
                sources.clear(); sources += wikiTurn.sources
                if (wikiTurn.partial) warning = "Wikipedia section evidence is partial or unavailable; unsupported details were not verified."
            } else {
                if (call.name != "search_web" || call.arguments.keys != setOf("query"))
                    throw WebSearchFailure(WebSearchFailure.Reason.REQUEST)
                val query = call.arguments["query"] as? String ?: throw WebSearchFailure(WebSearchFailure.Reason.REQUEST)
                TavilySearch.validateQuery(query)
                if (sources.size >= TavilySearch.MAX_REPLY_RESULTS) {
                    toolResult = mapOf("notice" to "The ten-source reply limit is full. Use only previously supplied evidence or say it is insufficient.")
                } else {
                    val found = search(query)
                    currentCoroutineContext().ensureActive()
                    if (found.isEmpty()) throw WebSearchFailure(WebSearchFailure.Reason.RESULTS)
                    val candidates = TavilySearch.selectNewsSources(text, query, found)
                    val selection = TavilySearch.selectReplySources(sources, candidates)
                    if (selection.evidence.isEmpty()) throw WebSearchFailure(WebSearchFailure.Reason.RESULTS)
                    val bounded = AssistantController.boundedWebToolResult(selection.evidence, selection.numbers, now, resultBudget, source)
                    sources.clear(); sources += selection.displayed
                    toolResult = bounded.response
                }
            }
        } catch (e: CancellationException) {
            throw e
        }
        catch (e: Exception) {
            val reason = (e as? WebSearchFailure)?.reason
            warning = if (wiki) "Wikipedia retrieval failed; only previously published evidence from this turn is available. Missing section facts are unverified." else env.string(when (reason) {
                WebSearchFailure.Reason.KEY -> R.string.assistant_search_key_error
                WebSearchFailure.Reason.LIMIT -> R.string.assistant_search_limit_error
                WebSearchFailure.Reason.RESULTS -> R.string.assistant_search_results_error
                WebSearchFailure.Reason.REQUEST -> R.string.assistant_search_request_error
                else -> R.string.assistant_search_network_error
            })
            toolResult = mapOf("error" to if (wiki) "Retrieval failed. Only earlier evidence from THIS turn may be used. Missing section facts are unverified; state insufficiency. No offline knowledge." else "Search failed. Do not claim fresh verification. Answer from offline knowledge with a warning.")
        }
        onDetails(sources.toList(), warning)
        // Invalid/parallel proposals are never dispatched; each gets an error response.
        next = Message.tool(Contents.of(effectiveReply.toolCalls.map { Content.ToolResponse(it.name, toolResult) }))
    }
    return false
}
