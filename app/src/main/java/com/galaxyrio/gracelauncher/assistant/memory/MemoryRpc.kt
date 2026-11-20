package com.galaxyrio.gracelauncher.assistant.memory

import java.time.Instant
import java.time.OffsetDateTime

/** Names/arguments of the Phase 1 SQL functions (server/supabase/grace_memory/003_functions.sql). Phase 3's HTTP adapter uses these. */
internal object MemoryRpcContract {
    val FUNCTIONS = listOf("memory_remember", "memory_forget", "memory_purge", "memory_recall", "memory_history", "memory_changes", "memory_export")
    val REMEMBER_ARGS = listOf("p_entry", "p_supersede_id")
    val FORGET_ARGS = listOf("p_id")
    val PURGE_ARGS = listOf("p_id", "p_confirm")
    val RECALL_ARGS = listOf("p_query", "p_limit")
    val HISTORY_ARGS = listOf("p_id")
    val CHANGES_ARGS = listOf("p_since_seq")
    const val PURGE_CONFIRM = "PURGE"
    const val TOKEN_HEADER = "x-grace-token"
    const val PROFILE = "grace_memory"
    const val SOURCE = "launcher"
}

/** `memory_remember` result: `{conflict, id, head_id?}`. */
internal data class RememberResult(val conflict: Boolean, val headId: String?)

/** `memory_changes` result: entries + purged ids (tombstones) + `max_seq`. */
internal data class MemoryChanges(val entries: List<MemoryEntry>, val purgedIds: List<String>, val maxSeq: Long)

/** Permanent server refusal (e.g. `bad_entry`): retrying cannot help. Network/auth failures are plain exceptions. */
internal class MemoryRpcRejected(message: String) : Exception(message)

/** Android adapter (Phase 3) sends the token in `x-grace-token` + `Content-Profile: grace_memory`; never in args/URL. */
internal interface MemoryRpc {
    suspend fun remember(entry: MemoryEntry, supersedeId: String?): RememberResult
    suspend fun forget(id: String): Boolean
    suspend fun purge(id: String, confirm: String): Int
    suspend fun recall(query: String, limit: Int): List<MemoryEntry>
    suspend fun history(id: String): List<MemoryEntry>
    suspend fun changes(sinceSeq: Long): MemoryChanges
    suspend fun export(): List<MemoryEntry>
}

/** `p_entry` for memory_remember. Server derives subject_key, status, seq. */
internal fun MemoryEntry.toRpcEntry(): Map<String, Any?> = mapOf(
    "id" to id,
    "type" to type.wire,
    "subject" to subject,
    "body" to body,
    "occurred_at" to occurredAt?.let { Instant.ofEpochMilli(it).toString() },
    "valid_from" to Instant.ofEpochMilli(validFrom).toString(),
    "source" to source,
)

private fun ts(v: Any?): Long? = (v as? String)?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/** One server row (already JSON-decoded to a map) → entry; null if a required column is missing/invalid. */
internal fun entryFromRpc(row: Map<String, Any?>): MemoryEntry? {
    val id = row["id"] as? String ?: return null
    val type = MemoryType.fromWire(row["type"] as? String) ?: return null
    val status = MemoryStatus.fromWire(row["status"] as? String) ?: return null
    val subject = row["subject"] as? String ?: return null
    val body = row["body"] as? String ?: return null
    val validFrom = ts(row["valid_from"]) ?: return null
    return MemoryEntry(
        id = id, type = type, subject = subject, subjectKey = (row["subject_key"] as? String) ?: subjectKey(subject), body = body,
        occurredAt = ts(row["occurred_at"]), validFrom = validFrom, validTo = ts(row["valid_to"]), status = status,
        supersedes = row["supersedes"] as? String, supersededBy = row["superseded_by"] as? String,
        source = (row["source"] as? String) ?: MemoryRpcContract.SOURCE, createdAt = ts(row["created_at"]) ?: validFrom,
        changeSeq = (row["change_seq"] as? Number)?.toLong() ?: 0L, serverConflict = row["conflict"] == true,
    )
}
