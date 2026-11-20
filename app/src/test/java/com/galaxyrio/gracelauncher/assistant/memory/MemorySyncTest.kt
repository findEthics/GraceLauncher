package com.galaxyrio.gracelauncher.assistant.memory

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Fake server mirroring the SQL semantics that matter here: idempotent by id, supersede only while target active, seq on every change. */
internal class FakeRpc : MemoryRpc {
    val rows = linkedMapOf<String, MemoryEntry>()
    val purged = mutableListOf<Pair<String, Long>>()
    var seq = 0L
    var failNext = 0
    var failAfterApplyNext = 0
    val calls = mutableListOf<String>()
    val sinceSeen = mutableListOf<Long>()
    val purgeConfirms = mutableListOf<String>()

    private fun bump(e: MemoryEntry) = e.copy(changeSeq = ++seq).also { rows[e.id] = it }

    override suspend fun remember(entry: MemoryEntry, supersedeId: String?): RememberResult {
        calls += "remember:${entry.id}"
        if (failNext > 0) { failNext--; throw IOException("down") }
        if (!rows.containsKey(entry.id)) {
            bump(entry.copy(supersedes = null, supersededBy = null, status = MemoryStatus.ACTIVE, validTo = null))
            if (supersedeId != null) {
                val t = rows[supersedeId]
                if (t != null && t.status == MemoryStatus.ACTIVE) {
                    bump(t.copy(status = MemoryStatus.SUPERSEDED, validTo = entry.validFrom, supersededBy = entry.id))
                    bump(rows.getValue(entry.id).copy(supersedes = t.id))
                } else if (t != null) {
                    if (failAfterApplyNext > 0) { failAfterApplyNext--; throw IOException("lost reply") }
                    return RememberResult(true, null)
                }
            }
        }
        if (failAfterApplyNext > 0) { failAfterApplyNext--; throw IOException("lost reply") }
        return RememberResult(false, null)
    }

    override suspend fun forget(id: String): Boolean {
        calls += "forget:$id"
        if (failNext > 0) { failNext--; throw IOException("down") }
        val e = rows[id]?.takeIf { it.status == MemoryStatus.ACTIVE } ?: return false
        bump(e.copy(status = MemoryStatus.INVALIDATED)); return true
    }

    override suspend fun purge(id: String, confirm: String): Int {
        calls += "purge:$id"; purgeConfirms += confirm
        if (failNext > 0) { failNext--; throw IOException("down") }
        val ids = chainIds(rows.values.toList(), id)
        ids.forEach { rows.remove(it); purged += it to ++seq }
        return ids.size
    }

    override suspend fun changes(sinceSeq: Long): MemoryChanges {
        calls += "changes"; sinceSeen += sinceSeq
        if (failNext > 0) { failNext--; throw IOException("down") }
        val es = rows.values.filter { it.changeSeq > sinceSeq }.sortedBy { it.changeSeq }
        val ps = purged.filter { it.second > sinceSeq }
        return MemoryChanges(es, ps.map { it.first }, maxOf(sinceSeq, es.maxOfOrNull { it.changeSeq } ?: 0, ps.maxOfOrNull { it.second } ?: 0))
    }

    override suspend fun recall(query: String, limit: Int) = rows.values.filter { it.status == MemoryStatus.ACTIVE }.take(limit)
    override suspend fun history(id: String) = historyOf(rows.values.toList(), id)
    override suspend fun export() = rows.values.toList()
}

internal class FakeStore : MemoryLocalStore {
    val entries = linkedMapOf<String, MemoryEntry>()
    val ops = mutableListOf<OutboxOp>()
    var cursor = 0L
    var syncedAt: Long? = null
    override fun entries() = entries.values.toList()
    override fun upsert(items: List<MemoryEntry>) { items.forEach { entries[it.id] = it } }
    override fun delete(ids: Set<String>) { ids.forEach(entries::remove) }
    override fun outbox() = ops.toList()
    override fun enqueue(op: OutboxOp) { if (ops.none { it.key == op.key }) ops += op }
    override fun removeOp(key: String) { ops.removeAll { it.key == key } }
    override fun bumpAttempts(key: String) { ops.replaceAll { if (it.key == key) it.copy(attempts = it.attempts + 1) else it } }
    override fun loadCursor() = cursor
    override fun saveCursor(value: Long) { cursor = value }
    override fun lastSyncAt() = syncedAt
    override fun setLastSyncAt(value: Long) { syncedAt = value }
}

class MemorySyncTest {
    private val store = FakeStore()
    private val rpc = FakeRpc()
    private var token: Boolean = true
    private var clock = 5_000L
    private val sync = MemorySync(store, { if (token) rpc else null }, { clock })

    private fun save(e: MemoryEntry, supersede: String? = null) = sync.record(saveLocally(store.entries(), e, supersede))

    @Test fun requeueEnqueuesOnlyUnconfirmedRowsWithoutOps() = runBlocking {
        store.upsert(listOf(entry("orphan", "A", seq = 0), entry("synced", "B", seq = 7), entry("queued", "C", seq = 0)))
        store.enqueue(OutboxOp(OutboxKind.REMEMBER, entry("queued", "C")))
        assertEquals(1, sync.requeueUnconfirmed())
        assertEquals(listOf("REMEMBER:queued", "REMEMBER:orphan"), store.ops.map { it.key })
        assertEquals(0, sync.requeueUnconfirmed()) // idempotent
    }

    @Test fun requeuedInvalidatedRowAlsoQueuesForgetAfterRemember() = runBlocking {
        store.upsert(listOf(entry("gone", "A", seq = 0, status = MemoryStatus.INVALIDATED)))
        assertEquals(1, sync.requeueUnconfirmed())
        assertEquals(listOf("REMEMBER:gone", "FORGET:gone"), store.ops.map { it.key })
        assertEquals(0, sync.requeueUnconfirmed())
        assertEquals(SyncOutcome.Synced, sync.sync())
        assertEquals(MemoryStatus.INVALIDATED, rpc.rows.getValue("gone").status)
    }

    @Test fun syncLocalNowWithoutTokenIsLocalOnlyAndKeepsOutbox() = runBlocking {
        token = false
        store.upsert(listOf(entry("orphan", "A", seq = 0)))
        assertEquals(SyncOutcome.LocalOnly, sync.syncLocalNow())
        assertTrue(rpc.calls.isEmpty()); assertEquals(1, store.ops.size)
    }

    @Test fun syncLocalNowPushesOrphans() = runBlocking {
        store.upsert(listOf(entry("orphan", "A", seq = 0)))
        assertEquals(SyncOutcome.Synced, sync.syncLocalNow())
        assertTrue(rpc.rows.containsKey("orphan")); assertTrue(store.ops.isEmpty())
    }

    @Test fun noTokenIsLocalOnlyAndNothingIsSentButLocalWritesWork() = runBlocking {
        token = false
        save(entry("a", "Milk"))
        assertEquals(SyncOutcome.LocalOnly, sync.sync())
        assertTrue(rpc.calls.isEmpty()); assertEquals(1, store.ops.size); assertEquals(1, store.entries.size)
        assertEquals(MemorySyncStatus.LocalOnly, sync.status())
    }

    @Test fun pushThenPullStoresMaxSeqAndSyncedStatus() = runBlocking {
        save(entry("a", "Milk", validFrom = 100))
        assertEquals(MemorySyncStatus.Pending(1), sync.status())
        assertEquals(SyncOutcome.Synced, sync.sync())
        assertTrue(rpc.calls.indexOf("remember:a") < rpc.calls.indexOf("changes"))
        assertEquals(rpc.seq, store.cursor); assertEquals(0, store.ops.size)
        assertEquals(MemorySyncStatus.Synced(5_000L), sync.status())
        assertEquals(rpc.seq, store.entries.getValue("a").changeSeq)
    }

    @Test fun pullUsesSinceSeqMinusFiftyFloorZeroAndCursorNeverMovesBack() = runBlocking {
        sync.sync(); assertEquals(0L, rpc.sinceSeen.last())
        store.cursor = 30; sync.sync(); assertEquals(0L, rpc.sinceSeen.last())
        store.cursor = 500; sync.sync(); assertEquals(450L, rpc.sinceSeen.last())
        assertEquals(500L, store.cursor) // empty pull returns since (450) as max_seq → monotonic guard keeps 500
    }

    @Test fun repullIsIdempotentAndUpsertsById() = runBlocking {
        repeat(3) { rpc.rows["r$it"] = entry("r$it", "S$it").copy(changeSeq = ++rpc.seq) }
        sync.sync(); store.cursor = 0; sync.sync(); sync.sync()
        assertEquals(3, store.entries.size)
    }

    @Test fun pushIsIdempotentByUuidAfterLostReply() = runBlocking {
        save(entry("a", "Milk"))
        rpc.failAfterApplyNext = 1
        assertEquals(SyncOutcome.Failed, sync.sync())
        assertEquals(1, store.ops.size); assertEquals(1, store.ops.single().attempts); assertEquals(1, rpc.rows.size)
        assertEquals(SyncOutcome.Synced, sync.sync())
        assertEquals(1, rpc.rows.size); assertEquals(0, store.ops.size)
        assertEquals(2, rpc.calls.count { it == "remember:a" })
    }

    @Test fun enqueueIsIdempotentPerOp() {
        val op = OutboxOp(OutboxKind.FORGET, targetId = "a")
        store.enqueue(op); store.enqueue(op); assertEquals(1, store.ops.size)
    }

    @Test fun failedPushKeepsOrderAndStopsBeforePull() = runBlocking {
        save(entry("a", "A")); save(entry("b", "B"))
        rpc.failNext = 1
        assertEquals(SyncOutcome.Failed, sync.sync())
        assertEquals(listOf("remember:a"), rpc.calls); assertEquals(2, store.ops.size)
        sync.sync(); assertEquals(setOf("a", "b"), rpc.rows.keys)
    }

    @Test fun tombstonePullDeletesLocally() = runBlocking {
        save(entry("a", "Milk")); sync.sync()
        assertTrue("a" in store.entries)
        rpc.purge("a", "PURGE"); sync.sync()
        assertFalse("a" in store.entries)
    }

    @Test fun purgeRemovesChainLocallyNowQueuesServerPurgeAndRetriesUntilConfirmed() = runBlocking {
        save(entry("o", "W", validFrom = 1)); save(entry("n", "W", validFrom = 2), supersede = "o"); sync.sync()
        sync.record(purgeLocally(store.entries(), "n")!!)
        assertTrue(store.entries.isEmpty()); assertEquals(listOf(OutboxKind.PURGE), store.ops.map { it.kind })
        rpc.failNext = 1; assertEquals(SyncOutcome.Failed, sync.sync()); assertEquals(1, store.ops.size)
        assertEquals(SyncOutcome.Synced, sync.sync())
        assertTrue(rpc.rows.isEmpty()); assertEquals(listOf("PURGE"), rpc.purgeConfirms.distinct()); assertTrue(store.ops.isEmpty())
    }

    @Test fun purgeAfterUnsentWritesReplaysInOrderSoServerChainIsErased() = runBlocking {
        save(entry("o", "W", validFrom = 1)); sync.sync()
        token = false
        save(entry("n", "W", validFrom = 2), supersede = "o")
        sync.record(softDeleteLocally(store.entries(), "n", 9)!!)
        sync.record(purgeLocally(store.entries(), "n")!!)
        assertEquals(listOf(OutboxKind.REMEMBER, OutboxKind.FORGET, OutboxKind.PURGE), store.ops.map { it.kind })
        assertTrue(store.entries.isEmpty())
        token = true; sync.sync()
        assertTrue(rpc.rows.isEmpty()); assertTrue(store.ops.isEmpty()); assertTrue(store.entries.isEmpty())
    }

    @Test fun softDeletePushesForgetAndRoundTrips() = runBlocking {
        save(entry("a", "Milk")); sync.sync()
        sync.record(softDeleteLocally(store.entries(), "a", 9)!!); sync.sync()
        assertEquals(MemoryStatus.INVALIDATED, rpc.rows.getValue("a").status)
        assertEquals(MemoryStatus.INVALIDATED, store.entries.getValue("a").status)
    }

    @Test fun supersedeRoundTripsThroughServer() = runBlocking {
        save(entry("o", "W", validFrom = 1)); save(entry("n", "W", validFrom = 2), supersede = "o"); sync.sync()
        assertEquals(MemoryStatus.SUPERSEDED, store.entries.getValue("o").status); assertEquals("o", store.entries.getValue("n").supersedes)
    }

    @Test fun serverConflictTrueFlagsTheEntry() = runBlocking {
        // another device already invalidated/superseded the target on the server
        rpc.rows["o"] = entry("o", "W", status = MemoryStatus.INVALIDATED).copy(changeSeq = ++rpc.seq)
        save(entry("n", "W", validFrom = 2), supersede = "o")
        store.entries["o"] = entry("o", "W") // local still thinks it is active
        sync.sync()
        assertTrue(store.entries.getValue("n").conflict)
    }

    @Test fun twoActiveHeadsOfOneKeyAreFlaggedForSingleValuedTypesOnly() = runBlocking {
        rpc.rows["a"] = entry("a", "Wifi").copy(changeSeq = ++rpc.seq, validFrom = 1)
        rpc.rows["b"] = entry("b", "wifi").copy(changeSeq = ++rpc.seq, validFrom = 2)
        rpc.rows["e1"] = entry("e1", "Dentist", type = MemoryType.EVENT).copy(changeSeq = ++rpc.seq)
        rpc.rows["e2"] = entry("e2", "Dentist", type = MemoryType.EVENT).copy(changeSeq = ++rpc.seq)
        sync.sync()
        assertTrue(store.entries.getValue("a").conflict && store.entries.getValue("b").conflict)
        assertFalse(store.entries.getValue("e1").conflict || store.entries.getValue("e2").conflict)
        // resolving one head clears the derived flag
        rpc.rows["a"] = rpc.rows.getValue("a").copy(status = MemoryStatus.INVALIDATED, changeSeq = ++rpc.seq)
        sync.sync()
        assertFalse(store.entries.getValue("b").conflict)
    }

    @Test fun tokenRemovedKeepsLocalMemoryAndOutboxThenResumes() = runBlocking {
        save(entry("a", "Milk")); sync.sync()
        token = false; save(entry("b", "Bread"))
        assertEquals(SyncOutcome.LocalOnly, sync.sync())
        assertEquals(2, store.entries.size); assertEquals(1, store.ops.size)
        assertEquals(MemorySyncStatus.LocalOnly, sync.status())
        token = true; sync.sync()
        assertTrue("b" in rpc.rows); assertTrue(store.ops.isEmpty())
    }

    @Test fun rejectedOpIsDroppedSoItCannotBlockTheQueue() = runBlocking {
        val bad = object : MemoryRpc by rpc {
            override suspend fun remember(entry: MemoryEntry, supersedeId: String?): RememberResult =
                if (entry.id == "bad") throw MemoryRpcRejected("bad_entry") else rpc.remember(entry, supersedeId)
        }
        val s = MemorySync(store, { bad }, { clock })
        s.record(saveLocally(emptyList(), entry("bad", "x"), null)); s.record(saveLocally(emptyList(), entry("ok", "y"), null))
        assertEquals(SyncOutcome.Synced, s.sync())
        assertTrue(store.ops.isEmpty()); assertTrue("ok" in rpc.rows)
    }

    @Test fun pulledRowDoesNotOverwriteNewerLocalOnlyServerConflictFlagWhileActive() = runBlocking {
        rpc.rows["a"] = entry("a", "Wifi").copy(changeSeq = ++rpc.seq)
        store.entries["a"] = entry("a", "Wifi").copy(serverConflict = true)
        sync.sync()
        assertTrue(store.entries.getValue("a").conflict)
    }

    // ---- RPC contract (SQL: 003_functions.sql) ----
    @Test fun rpcContractNamesAndPayloadShape() {
        assertEquals(
            listOf("memory_remember", "memory_forget", "memory_purge", "memory_recall", "memory_history", "memory_changes", "memory_export"),
            MemoryRpcContract.FUNCTIONS,
        )
        assertEquals(listOf("p_entry", "p_supersede_id"), MemoryRpcContract.REMEMBER_ARGS)
        assertEquals(listOf("p_id"), MemoryRpcContract.FORGET_ARGS)
        assertEquals(listOf("p_id", "p_confirm"), MemoryRpcContract.PURGE_ARGS)
        assertEquals(listOf("p_query", "p_limit"), MemoryRpcContract.RECALL_ARGS)
        assertEquals(listOf("p_id"), MemoryRpcContract.HISTORY_ARGS)
        assertEquals(listOf("p_since_seq"), MemoryRpcContract.CHANGES_ARGS)
        assertEquals("PURGE", MemoryRpcContract.PURGE_CONFIRM)
        assertEquals("x-grace-token", MemoryRpcContract.TOKEN_HEADER); assertEquals("grace_memory", MemoryRpcContract.PROFILE)
        val e = entry("11111111-1111-1111-1111-111111111111", "Dentist", "body", MemoryType.EVENT, validFrom = 1_000, occurredAt = 2_000)
        val m = e.toRpcEntry()
        assertEquals(setOf("id", "type", "subject", "body", "occurred_at", "valid_from", "source"), m.keys)
        assertEquals("event", m["type"]); assertEquals("1970-01-01T00:00:01Z", m["valid_from"]); assertEquals("1970-01-01T00:00:02Z", m["occurred_at"])
        assertNull(entry("x", "S").toRpcEntry()["occurred_at"])
    }

    @Test fun rpcRowParsingMatchesServerColumns() {
        val row = mapOf<String, Any?>(
            "id" to "a", "type" to "place", "subject" to "Car", "subject_key" to "car", "body" to "level 3",
            "occurred_at" to null, "valid_from" to "2026-11-20T10:00:00.123456+00:00", "valid_to" to null, "status" to "active",
            "supersedes" to null, "superseded_by" to "b", "source" to "launcher", "created_at" to "2026-11-20T10:00:00+00:00",
            "updated_at" to "2026-11-20T10:00:00+00:00", "change_seq" to 42, "conflict" to true,
        )
        val e = entryFromRpc(row)!!
        assertEquals(MemoryType.PLACE, e.type); assertEquals(42L, e.changeSeq); assertEquals("b", e.supersededBy)
        assertEquals(java.time.Instant.parse("2026-11-20T10:00:00.123Z").toEpochMilli(), e.validFrom); assertTrue(e.serverConflict)
        assertNull(entryFromRpc(row + ("type" to "bogus"))); assertNull(entryFromRpc(row - "id"))
    }
}
