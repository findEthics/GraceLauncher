package com.galaxyrio.gracelauncher.assistant.memory

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Written and compiled only (Gate D): needs a device/emulator for real SQLite FTS4. */
class MemoryMirrorDaoTest {
    private lateinit var db: MemoryMirrorDatabase
    private lateinit var store: RoomMemoryStore

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, MemoryMirrorDatabase::class.java).build()
        store = RoomMemoryStore(db.dao()) { db.runInTransaction { it() } }
    }
    @After fun close() { db.close() }

    private fun e(id: String, subject: String, body: String, seq: Long = 0) =
        MemoryEntry(id, MemoryType.FACT, subject, subjectKey(subject), body, validFrom = 1_000, changeSeq = seq)

    @Test fun upsertIsIdempotentByIdAndKeepsFtsInSync() {
        store.upsert(listOf(e("a", "Parking", "level three"), e("b", "Tea", "green")))
        store.upsert(listOf(e("a", "Parking", "level four", seq = 5)))
        assertEquals(2, store.entries().size)
        assertEquals(listOf("a"), db.dao().search("four"))
        assertTrue(db.dao().search("three").isEmpty())
        store.delete(setOf("a"))
        assertTrue(db.dao().search("four").isEmpty())
    }

    @Test fun outboxIsIdempotentOrderedAndCursorSurvives() {
        val op = OutboxOp(OutboxKind.REMEMBER, entry = e("a", "S", "B"))
        store.enqueue(op); store.enqueue(op); store.enqueue(OutboxOp(OutboxKind.FORGET, targetId = "z"))
        assertEquals(listOf(OutboxKind.REMEMBER, OutboxKind.FORGET), store.outbox().map { it.kind })
        assertEquals("a", store.outbox().first().entry?.id)
        store.bumpAttempts(op.key); assertEquals(1, store.outbox().first().attempts)
        store.removeOp(op.key); assertEquals(1, store.outbox().size)
        store.saveCursor(42); store.setLastSyncAt(7)
        assertEquals(42L, store.loadCursor()); assertEquals(7L, store.lastSyncAt())
        store.saveCursor(50); assertEquals(7L, store.lastSyncAt())
    }
}
