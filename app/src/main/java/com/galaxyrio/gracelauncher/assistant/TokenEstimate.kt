package com.galaxyrio.gracelauncher.assistant

import java.text.BreakIterator
import java.util.Locale

private fun utf8Size(codePoint: Int) = when {
    codePoint < 0x80 -> 1
    codePoint < 0x800 -> 2
    codePoint < 0x10000 -> 3
    else -> 4
}

/** Cautious Gemma token estimate: ASCII ~3 chars/token; every non-ASCII UTF-8 byte counts as one token. */
internal fun estimateTokens(text: String): Int {
    var ascii = 0
    var other = 0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        index += Character.charCount(codePoint)
        if (codePoint < 0x80) ascii++ else other += utf8Size(codePoint)
    }
    return (ascii + 2) / 3 + other
}

/** Longest code-point-safe prefix whose estimate is at most [maxTokens]; no ellipsis. */
internal fun tokenPrefix(text: String, maxTokens: Int): String {
    var ascii = 0
    var other = 0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val nextAscii = ascii + if (codePoint < 0x80) 1 else 0
        val nextOther = other + if (codePoint < 0x80) 0 else utf8Size(codePoint)
        if ((nextAscii + 2) / 3 + nextOther > maxTokens) break
        ascii = nextAscii; other = nextOther
        index += Character.charCount(codePoint)
    }
    return text.substring(0, index)
}

/** Ellipsis marks incomplete evidence; never presents a cut word as complete. */
internal fun truncateToTokens(text: String, maxTokens: Int): String {
    if (estimateTokens(text) <= maxTokens) return text
    val ellipsisTokens = estimateTokens("…")
    if (maxTokens < ellipsisTokens) return ""
    val prefix = tokenPrefix(text, maxTokens - ellipsisTokens)
    val words = BreakIterator.getWordInstance(Locale.ROOT).apply { setText(text) }
    val end = words.preceding(prefix.length + 1).coerceAtLeast(0)
    return text.substring(0, end).trimEnd() + "…"
}
