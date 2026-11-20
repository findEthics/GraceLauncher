package com.galaxyrio.gracelauncher.assistant.memory

import org.json.JSONObject

/** [MemoryLocalStore] over [MemoryDao]. Blocking: call off the main thread. [inTransaction] is `db::runInTransaction` in production. */
internal class RoomMemoryStore(
    private val dao: MemoryDao,
    private val inTransaction: (() -> Unit) -> Unit = { it() },
) : MemoryLocalStore {
    override fun entries(): List<MemoryEntry> = dao.entries().mapNotNull(::toEntry)

    override fun upsert(items: List<MemoryEntry>) {
        if (items.isEmpty()) return
        inTransaction {
            val existing = dao.rowRefs().associate { it.id to it.rowId }
            val (old, new) = items.map { it.toRow() }.partition { it.id in existing }
            if (old.isNotEmpty()) dao.update(old.map { it.copy(rowId = existing.getValue(it.id)) })
            if (new.isNotEmpty()) dao.insert(new)
        }
    }

    override fun delete(ids: Set<String>) { if (ids.isNotEmpty()) dao.delete(ids.toList()) }

    override fun outbox(): List<OutboxOp> = dao.outbox().mapNotNull { r ->
        val kind = OutboxKind.entries.firstOrNull { it.name == r.kind } ?: return@mapNotNull null
        OutboxOp(kind, r.entryJson?.let(::entryFromStored), r.supersedeId, r.targetId, r.attempts)
    }

    override fun enqueue(op: OutboxOp) {
        inTransaction {
            dao.enqueue(OutboxRow(op.key, (dao.maxOutboxSeq() ?: 0L) + 1, op.kind.name, op.entry?.let(::entryToStored), op.supersedeId, op.targetId, op.attempts))
        }
    }

    override fun removeOp(key: String) = dao.removeOp(key)
    override fun bumpAttempts(key: String) = dao.bumpAttempts(key)
    override fun loadCursor(): Long = dao.state()?.cursor ?: 0L
    override fun saveCursor(value: Long) = dao.putState(SyncStateRow(0, value, dao.state()?.lastSyncAt))
    override fun lastSyncAt(): Long? = dao.state()?.lastSyncAt
    override fun setLastSyncAt(value: Long) = dao.putState(SyncStateRow(0, dao.state()?.cursor ?: 0L, value))
}

internal fun MemoryEntry.toRow() = EntryRow(
    id = id, type = type.wire, subject = subject, subjectKey = subjectKey, body = body, occurredAt = occurredAt,
    validFrom = validFrom, validTo = validTo, status = status.wire, supersedes = supersedes, supersededBy = supersededBy,
    source = source, createdAt = createdAt, changeSeq = changeSeq, conflict = conflict, serverConflict = serverConflict,
)

internal fun toEntry(r: EntryRow): MemoryEntry? = MemoryEntry(
    id = r.id, type = MemoryType.fromWire(r.type) ?: return null, subject = r.subject, subjectKey = r.subjectKey, body = r.body,
    occurredAt = r.occurredAt, validFrom = r.validFrom, validTo = r.validTo, status = MemoryStatus.fromWire(r.status) ?: return null,
    supersedes = r.supersedes, supersededBy = r.supersededBy, source = r.source, createdAt = r.createdAt,
    changeSeq = r.changeSeq, conflict = r.conflict, serverConflict = r.serverConflict,
)

/** Outbox payload: the entry as saved (immutable fields matter for the push). */
internal fun entryToStored(e: MemoryEntry): String = JSONObject()
    .put("id", e.id).put("type", e.type.wire).put("subject", e.subject).put("subjectKey", e.subjectKey).put("body", e.body)
    .put("occurredAt", e.occurredAt ?: JSONObject.NULL).put("validFrom", e.validFrom).put("source", e.source).put("createdAt", e.createdAt)
    .toString()

internal fun entryFromStored(json: String): MemoryEntry? = try {
    val o = JSONObject(json)
    MemoryEntry(
        id = o.getString("id"), type = MemoryType.fromWire(o.getString("type")) ?: return null, subject = o.getString("subject"),
        subjectKey = o.getString("subjectKey"), body = o.getString("body"),
        occurredAt = if (o.isNull("occurredAt")) null else o.getLong("occurredAt"), validFrom = o.getLong("validFrom"),
        source = o.getString("source"), createdAt = o.getLong("createdAt"),
    )
} catch (_: Exception) { null }
