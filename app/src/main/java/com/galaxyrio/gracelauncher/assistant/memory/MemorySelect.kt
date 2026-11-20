package com.galaxyrio.gracelauncher.assistant.memory

internal data class ReplaceCandidate(val entry: MemoryEntry, val exact: Boolean, val defaultOn: Boolean)

private fun entryTerms(e: MemoryEntry): Set<String> = contentTerms(e.subject + " " + e.body)

/** Number of the query's content terms present in the entry's subject+body (folded, whole-token). */
private fun score(terms: Set<String>, e: MemoryEntry): Int = entryTerms(e).count { it in terms }

private val NEWEST_FIRST = compareByDescending<MemoryEntry> { it.validFrom }

/**
 * Exact `type+subject_key` active matches (default ON only for the newest one, and only for fact/preference/place),
 * then up to 3 lexical same-type active matches (always default OFF). Deterministic, model-free.
 */
internal fun replaceCandidates(draft: MemoryDraft, entries: List<MemoryEntry>): List<ReplaceCandidate> {
    val active = entries.filter { it.status == MemoryStatus.ACTIVE && it.type == draft.type }
    val key = subjectKey(draft.subject)
    val singleValued = draft.type in setOf(MemoryType.FACT, MemoryType.PREFERENCE, MemoryType.PLACE)
    val exact = active.filter { it.subjectKey == key }.sortedWith(NEWEST_FIRST)
    val terms = contentTerms(draft.subject + " " + draft.body)
    val lexical = active.filter { e -> exact.none { it.id == e.id } }
        .map { it to score(terms, it) }.filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<MemoryEntry, Int>> { it.second }.thenByDescending { it.first.validFrom })
        .take(3).map { it.first }
    return exact.mapIndexed { i, e -> ReplaceCandidate(e, true, singleValued && i == 0) } +
        lexical.map { ReplaceCandidate(it, false, false) }
}

internal fun defaultReplaceId(c: List<ReplaceCandidate>): String? = c.firstOrNull { it.defaultOn }?.entry?.id

/** At most one pick; unknown/absent id → no replace. */
internal fun pickReplace(c: List<ReplaceCandidate>, id: String?): String? = c.firstOrNull { it.entry.id == id }?.entry?.id

/** Active entries matching the query's content terms: best score first, then newest. Blank/stopword-only → empty. */
internal fun matchActive(query: String, entries: List<MemoryEntry>, limit: Int): List<MemoryEntry> {
    val terms = contentTerms(query)
    if (terms.isEmpty()) return emptyList()
    return entries.filter { it.status == MemoryStatus.ACTIVE }.map { it to score(terms, it) }.filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<MemoryEntry, Int>> { it.second }.thenByDescending { it.first.validFrom })
        .take(limit).map { it.first }
}

internal fun recentActive(entries: List<MemoryEntry>, limit: Int): List<MemoryEntry> =
    entries.filter { it.status == MemoryStatus.ACTIVE }.sortedWith(NEWEST_FIRST).take(limit)

/** `/recall <words>` cards (≤10, newest first); `/recall` alone → 10 most recent. Conflict flags ride on the entries. */
internal fun recallCards(query: String, entries: List<MemoryEntry>): List<MemoryEntry> =
    if (query.isBlank()) recentActive(entries, 10) else matchActive(query, entries, 10).sortedWith(NEWEST_FIRST)
