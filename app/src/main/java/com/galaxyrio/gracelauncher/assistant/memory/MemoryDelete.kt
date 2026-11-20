package com.galaxyrio.gracelauncher.assistant.memory

internal enum class DeleteKind { SOFT, ERASE }

/** Pick resolver (≤5 numbered cards) + confirm machine. Erase/purge need a second confirm; soft delete commits on pick. */
internal sealed interface DeleteState {
    fun pick(n: Int): DeleteState = this
    fun confirm(): DeleteState = this
    fun cancel(): DeleteState = this

    data class Picking(val kind: DeleteKind, val cards: List<MemoryEntry>) : DeleteState {
        override fun pick(n: Int): DeleteState = cards.getOrNull(n - 1)?.let { afterPick(kind, it) } ?: this
        override fun cancel(): DeleteState = Cancelled
    }

    data class Confirm(val kind: DeleteKind, val card: MemoryEntry) : DeleteState {
        override fun confirm(): DeleteState = Commit(kind, card)
        override fun cancel(): DeleteState = Cancelled
    }

    data class Commit(val kind: DeleteKind, val card: MemoryEntry) : DeleteState
    data object Cancelled : DeleteState
    data object NoMatch : DeleteState
}

private fun afterPick(kind: DeleteKind, card: MemoryEntry): DeleteState =
    if (kind == DeleteKind.ERASE) DeleteState.Confirm(kind, card) else DeleteState.Commit(kind, card)

/** [shown] = numbered cards already on screen; an `Index` target refers to them. */
internal fun startDelete(kind: DeleteKind, target: MemoryTarget?, entries: List<MemoryEntry>, shown: List<MemoryEntry>): DeleteState =
    when (target) {
        is MemoryTarget.Index -> shown.getOrNull(target.n - 1)?.let { afterPick(kind, it) } ?: DeleteState.NoMatch
        is MemoryTarget.Words -> matchActive(target.text, entries, 5).let { if (it.isEmpty()) DeleteState.NoMatch else DeleteState.Picking(kind, it) }
        null -> recentActive(entries, 5).let { if (it.isEmpty()) DeleteState.NoMatch else DeleteState.Picking(kind, it) }
    }

// ---- local chain operations (the confirmed result of the state machine / Save) ----

internal enum class OutboxKind { REMEMBER, FORGET, PURGE }

/** `key` is stable per client UUID, so re-queuing/re-pushing the same op cannot duplicate server rows. */
internal data class OutboxOp(
    val kind: OutboxKind,
    val entry: MemoryEntry? = null,
    val supersedeId: String? = null,
    val targetId: String? = null,
    val attempts: Int = 0,
) {
    val key: String get() = "${kind.name}:${entry?.id ?: targetId}"
}

internal data class LocalResult(val changed: List<MemoryEntry>, val removedIds: Set<String>, val op: OutboxOp)

/** Save: nothing happens before this call. Supersede closes the target only while it is still active. */
internal fun saveLocally(entries: List<MemoryEntry>, neu: MemoryEntry, supersedeId: String?): LocalResult {
    val target = supersedeId?.let { id -> entries.firstOrNull { it.id == id && it.status == MemoryStatus.ACTIVE } }
    val op = OutboxOp(OutboxKind.REMEMBER, entry = neu, supersedeId = supersedeId)
    if (target == null) return LocalResult(listOf(neu), emptySet(), op)
    val closed = target.copy(status = MemoryStatus.SUPERSEDED, validTo = neu.validFrom, supersededBy = neu.id)
    return LocalResult(listOf(closed, neu.copy(supersedes = target.id)), emptySet(), op)
}

/** Soft delete: row kept, `invalidated`, `valid_to=now`; no-op (null) if absent or not active. */
internal fun softDeleteLocally(entries: List<MemoryEntry>, id: String, now: Long): LocalResult? {
    val e = entries.firstOrNull { it.id == id && it.status == MemoryStatus.ACTIVE } ?: return null
    return LocalResult(listOf(e.copy(status = MemoryStatus.INVALIDATED, validTo = now)), emptySet(), OutboxOp(OutboxKind.FORGET, targetId = id))
}

/** Whole chain, both directions. */
internal fun chainIds(entries: List<MemoryEntry>, id: String): Set<String> {
    val byId = entries.associateBy { it.id }
    val seen = linkedSetOf<String>()
    val todo = ArrayDeque(listOf(id))
    while (todo.isNotEmpty()) {
        val cur = todo.removeFirst()
        val e = byId[cur] ?: continue
        if (seen.add(cur)) listOfNotNull(e.supersedes, e.supersededBy).forEach(todo::addLast)
    }
    return seen
}

internal fun purgeLocally(entries: List<MemoryEntry>, id: String): LocalResult? {
    val ids = chainIds(entries, id)
    if (ids.isEmpty()) return null
    return LocalResult(emptyList(), ids, OutboxOp(OutboxKind.PURGE, targetId = id))
}

internal fun historyOf(entries: List<MemoryEntry>, id: String): List<MemoryEntry> =
    chainIds(entries, id).let { ids -> entries.filter { it.id in ids }.sortedBy { it.validFrom } }
