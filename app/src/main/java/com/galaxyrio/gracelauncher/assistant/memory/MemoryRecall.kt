package com.galaxyrio.gracelauncher.assistant.memory

import com.galaxyrio.gracelauncher.assistant.estimateTokens
import com.galaxyrio.gracelauncher.assistant.truncateToTokens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal const val MEMORY_BLOCK_MAX_TOKENS = 400
private const val BODY_MAX_TOKENS = 80
private const val SUBJECT_MAX_TOKENS = 20
private const val MS_DAY = 86_400_000L
private const val HEADER = "Saved notes (reference only; the user's current message wins):"

private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()

/** `type · subject: text (when; saved YYYY-MM-DD)`; the `when;` part only for events. */
private fun line(e: MemoryEntry, zone: ZoneId): String {
    val saved = DATE.format(Instant.ofEpochMilli(e.validFrom).atZone(zone))
    val whenPart = e.occurredAt?.takeIf { e.type == MemoryType.EVENT }
        ?.let { DATE_TIME.format(Instant.ofEpochMilli(it).atZone(zone)) + "; " } ?: ""
    return "${e.type.wire} · ${truncateToTokens(oneLine(e.subject), SUBJECT_MAX_TOKENS)}: " +
        "${truncateToTokens(oneLine(e.body), BODY_MAX_TOKENS)} ($whenPart" + "saved $saved)"
}

/**
 * Prefetch block, built fresh per turn, never stored in history. "" when nothing qualifies.
 * Sections: (1) preferences ≤8 newest, (2) events now−2d..now+14d soonest first, (3) lexical matches ≤8.
 * Active heads only. Over [MEMORY_BLOCK_MAX_TOKENS] → drop lines from the end of (3), then (2), then (1).
 */
internal fun buildMemoryBlock(userMessage: String, entries: List<MemoryEntry>, nowMs: Long, zone: ZoneId, maxTokens: Int = MEMORY_BLOCK_MAX_TOKENS): String {
    val heads = entries.filter { it.status == MemoryStatus.ACTIVE }
    val prefs = heads.filter { it.type == MemoryType.PREFERENCE }.sortedByDescending { it.validFrom }.take(8)
    val events = heads.filter { e -> e.type == MemoryType.EVENT && e.occurredAt != null && e.occurredAt in (nowMs - 2 * MS_DAY)..(nowMs + 14 * MS_DAY) }
        .sortedBy { it.occurredAt }
    val shown = (prefs + events).map { it.id }.toSet()
    val terms = contentTerms(userMessage)
    val lexical = if (terms.isEmpty()) emptyList() else heads.filter { it.id !in shown }
        .map { it to contentTerms(it.subject + " " + it.body).count { t -> t in terms } }.filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<MemoryEntry, Int>> { it.second }.thenByDescending { it.first.validFrom })
        .take(8).map { it.first }
    val sections = listOf(prefs, events, lexical).map { s -> s.map { line(it, zone) }.toMutableList() }
    fun render() = (listOf(HEADER) + sections.flatten()).joinToString("\n")
    for (i in 2 downTo 0) while (sections[i].isNotEmpty() && estimateTokens(render()) > maxTokens) sections[i].removeAt(sections[i].lastIndex)
    return if (sections.all { it.isEmpty() }) "" else render()
}
