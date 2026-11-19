package com.galaxyrio.gracelauncher.assistant

private val closedThink = Regex("(?s)<think>.*?</think>")
private val openThink = Regex("(?s)<think>.*$")

/** Hides reasoning blocks (also a still-open one while streaming); safe for display. */
internal fun stripThinking(raw: String): String = raw.replace(closedThink, "").replace(openThink, "").trim()

/** Final model text, or null (fail closed) when raw tool-call markup leaked into a tool-free reply. */
internal fun cleanModelText(raw: String): String? {
    val text = stripThinking(raw)
    return if (text.contains("<tool_call>") || text.contains("</tool_call>")) null else text
}
