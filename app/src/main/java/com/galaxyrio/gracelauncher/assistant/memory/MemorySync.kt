package com.galaxyrio.gracelauncher.assistant.memory

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Local mirror + outbox + cursor. Phase 3 backs this with Room (`memory_mirror.db`); tests use an in-memory fake. */
internal interface MemoryLocalStore {
    fun entries(): List<MemoryEntry>
    fun upsert(items: List<MemoryEntry>)
    fun delete(ids: Set<String>)
    fun outbox(): List<OutboxOp>
    /** Idempotent per [OutboxOp.key]. */
    fun enqueue(op: OutboxOp)
    fun removeOp(key: String)
    fun bumpAttempts(key: String)
    fun loadCursor(): Long
    fun saveCursor(value: Long)
    fun lastSyncAt(): Long?
    fun setLastSyncAt(value: Long)
}

internal sealed interface MemorySyncStatus {
    data object LocalOnly : MemorySyncStatus
    data class Synced(val at: Long) : MemorySyncStatus
    /** Token present but not yet synced (also 0 pending before the first sync). */
    data class Pending(val count: Int) : MemorySyncStatus
}

internal enum class SyncOutcome { Synced, LocalOnly, Failed }

private const val OVERLAP = 50L

/**
 * [rpc] returns null when no `hc_` token exists (local-only: nothing is sent, outbox and local rows are kept).
 * Push the outbox in order (idempotent by client UUID), then pull `memory_changes(cursor − 50)`.
 */
internal class MemorySync(
    private val store: MemoryLocalStore,
    private val rpc: () -> MemoryRpc?,
    private val clock: () -> Long,
) {
    private val lock = Mutex()

    /** Commit a confirmed local change: mirror first (works offline), then queue it for the server. */
    fun record(result: LocalResult) {
        store.upsert(result.changed)
        store.delete(result.removedIds)
        store.enqueue(result.op)
    }

    fun status(): MemorySyncStatus {
        if (rpc() == null) return MemorySyncStatus.LocalOnly
        val pending = store.outbox().size
        val at = store.lastSyncAt()
        return if (pending == 0 && at != null) MemorySyncStatus.Synced(at) else MemorySyncStatus.Pending(pending)
    }

    suspend fun sync(): SyncOutcome = lock.withLock {
        val client = rpc() ?: return SyncOutcome.LocalOnly
        try {
            push(client)
            pull(client)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            return SyncOutcome.Failed
        } catch (_: PushFailed) {
            return SyncOutcome.Failed
        }
        store.setLastSyncAt(clock())
        SyncOutcome.Synced
    }

    /**
     * Rows the server never confirmed (`changeSeq == 0`) that have no queued op get their REMEMBER (plus FORGET if soft-deleted) back.
     * Re-pushing is safe: the server ignores a repeated id. Oldest first, so a supersede target is pushed before its successor.
     */
    suspend fun requeueUnconfirmed(): Int = lock.withLock {
        val queued = store.outbox().flatMap { listOfNotNull(it.entry?.id, it.targetId) }.toSet()
        var n = 0
        for (e in store.entries().filter { it.changeSeq == 0L && it.id !in queued }.sortedBy { it.validFrom }) {
            store.enqueue(OutboxOp(OutboxKind.REMEMBER, entry = e, supersedeId = e.supersedes))
            if (e.status == MemoryStatus.INVALIDATED) store.enqueue(OutboxOp(OutboxKind.FORGET, targetId = e.id))
            n++
        }
        n
    }

    /** "Sync memory now": re-queue orphans, then the normal push + pull. */
    suspend fun syncLocalNow(): SyncOutcome { requeueUnconfirmed(); return sync() }

    private class PushFailed : Exception()

    private suspend fun push(client: MemoryRpc) {
        for (op in store.outbox()) {
            try {
                when (op.kind) {
                    OutboxKind.REMEMBER -> {
                        val e = op.entry ?: error("REMEMBER without entry")
                        if (client.remember(e, op.supersedeId).conflict) {
                            store.entries().firstOrNull { it.id == e.id }?.let { store.upsert(listOf(it.copy(serverConflict = true))) }
                        }
                    }
                    OutboxKind.FORGET -> client.forget(op.targetId ?: error("FORGET without id"))
                    OutboxKind.PURGE -> client.purge(op.targetId ?: error("PURGE without id"), MemoryRpcContract.PURGE_CONFIRM)
                }
                store.removeOp(op.key)
            } catch (_: MemoryRpcRejected) {
                store.removeOp(op.key) // permanent refusal must not block the queue
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                store.bumpAttempts(op.key)
                throw PushFailed()
            }
        }
    }

    private suspend fun pull(client: MemoryRpc) {
        val cursor = store.loadCursor()
        val changes = client.changes((cursor - OVERLAP).coerceAtLeast(0))
        val local = store.entries().associateBy { it.id }
        // Server rows are canonical; the app-only server-conflict flag survives re-pulls.
        store.upsert(changes.entries.map { e -> e.copy(serverConflict = e.serverConflict || local[e.id]?.serverConflict == true) })
        store.delete(changes.purgedIds.toSet())
        refreshConflictFlags()
        store.saveCursor(maxOf(cursor, changes.maxSeq))
    }

    /** `conflict` = server said so (while active) or another active head shares a single-valued type+subject_key. */
    private fun refreshConflictFlags() {
        val all = store.entries()
        val dupes = all.filter { it.status == MemoryStatus.ACTIVE && it.type in SINGLE_VALUED }
            .groupBy { it.type to it.subjectKey }.filterValues { it.size > 1 }.values.flatten().map { it.id }.toSet()
        val updated = all.mapNotNull { e ->
            val want = e.status == MemoryStatus.ACTIVE && (e.serverConflict || e.id in dupes)
            if (want != e.conflict) e.copy(conflict = want) else null
        }
        if (updated.isNotEmpty()) store.upsert(updated)
    }

    private companion object {
        val SINGLE_VALUED = setOf(MemoryType.FACT, MemoryType.PREFERENCE, MemoryType.PLACE)
    }
}
