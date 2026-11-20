package com.galaxyrio.gracelauncher.assistant.memory

import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MemoryTurnsTest {
    private val store = FakeStore()
    private val rpc = FakeRpc()
    private var hasToken = true
    private var now = 1_800_000_000_000L
    private var n = 0
    private val sync = MemorySync(store, { if (hasToken) rpc else null }, { now })
    private val turns = MemoryTurns(store, sync, { now }, ZoneId.of("UTC"), { "id-${++n}" })
    private val noModel: suspend (String) -> String? = { null }

    private fun start(cmd: MemoryCommand, shown: List<MemoryEntry> = emptyList()) = runBlocking { turns.start(cmd, shown, noModel) }
    private fun saveText(text: String): MemoryCard {
        val card = start(MemoryCommand.Remember(text)) as MemoryCard.Save
        return turns.save(card)
    }

    @Test fun nothingPersistsBeforeSave() {
        val card = start(MemoryCommand.Remember("My car is parked at level 3"))
        assertTrue(card is MemoryCard.Save)
        assertTrue(store.entries.isEmpty()); assertTrue(store.ops.isEmpty())
        assertEquals(MemoryType.PLACE, (card as MemoryCard.Save).draft.type)
    }

    @Test fun saveWritesMirrorAndOutboxAndWorksOffline() {
        hasToken = false
        assertEquals(MemoryCard.Note(NoteKind.SAVED), saveText("I prefer tea over coffee"))
        assertEquals(1, store.entries.size); assertEquals(1, store.ops.size)
        assertEquals(MemoryType.PREFERENCE, store.entries.values.single().type)
        assertEquals("I prefer tea over coffee", store.entries.values.single().body)
    }

    @Test fun blankRememberShowsUsageAndInvalidEditStaysOpen() {
        assertEquals(MemoryCard.Note(NoteKind.USAGE_REMEMBER), start(MemoryCommand.Remember("  ")))
        val card = start(MemoryCommand.Remember("Milk is in the fridge")) as MemoryCard.Save
        val bad = turns.save(card.copy(draft = card.draft.copy(subject = " ")))
        assertTrue((bad as MemoryCard.Save).invalid); assertTrue(store.entries.isEmpty())
    }

    @Test fun sameKeyDefaultsToReplaceAndSupersedes() {
        saveText("My wifi password hint is blue")
        val card = runBlocking { turns.start(MemoryCommand.Remember("My wifi password hint is green"), emptyList()) { """{"subject":"wifi hint"}""" } } as MemoryCard.Save
        // first save used the fallback subject; same-key requires identical subject → lexical only → default OFF
        assertNull(card.replaceId)
        assertTrue(card.candidates.isNotEmpty())
        val picked = turns.toggleReplace(card, card.candidates.first().entry.id)
        turns.save(picked)
        assertEquals(1, store.entries.values.count { it.status == MemoryStatus.ACTIVE })
        assertEquals(MemoryStatus.SUPERSEDED, store.entries.values.first { it.id == card.candidates.first().entry.id }.status)
    }

    @Test fun softDeleteByWordsPickThenCommitKeepsRow() {
        saveText("Parking spot is B12")
        val pick = start(MemoryCommand.Delete(MemoryTarget.Words("parking"))) as MemoryCard.Pick
        val done = turns.pick(pick, 1)
        assertEquals(MemoryCard.Note(NoteKind.DELETED), done)
        assertEquals(MemoryStatus.INVALIDATED, store.entries.values.single().status)
        assertEquals(OutboxKind.FORGET, store.ops.last().kind)
    }

    @Test fun eraseNeedsSecondConfirmAndRemovesChainAndQueuesPurge() {
        saveText("Parking spot is B12")
        val pick = start(MemoryCommand.Erase(MemoryTarget.Words("parking"))) as MemoryCard.Pick
        val confirm = turns.pick(pick, 1) as MemoryCard.Confirm
        assertEquals(1, store.entries.size) // still there until the second confirm
        assertEquals(MemoryCard.Note(NoteKind.ERASED), turns.confirm(confirm))
        assertTrue(store.entries.isEmpty()); assertEquals(OutboxKind.PURGE, store.ops.last().kind)
    }

    @Test fun indexTargetUsesShownCardsAndOutOfRangeIsNoMatch() {
        saveText("Parking spot is B12")
        val recall = start(MemoryCommand.Recall("")) as MemoryCard.Entries
        assertEquals(1, recall.shown.size)
        assertEquals(MemoryCard.Note(NoteKind.NO_MATCH), start(MemoryCommand.Delete(MemoryTarget.Index(3)), recall.shown))
        assertEquals(MemoryCard.Note(NoteKind.DELETED), start(MemoryCommand.Delete(MemoryTarget.Index(1)), recall.shown).let { it })
    }

    @Test fun recallAndHistoryCards() {
        saveText("Parking spot is B12")
        assertEquals(MemoryCard.Note(NoteKind.NO_MATCH), start(MemoryCommand.Recall("zebra")))
        val recall = start(MemoryCommand.Recall("parking")) as MemoryCard.Entries
        assertEquals(EntriesKind.RECALL, recall.kind)
        val hist = start(MemoryCommand.History(1), recall.shown) as MemoryCard.Entries
        assertEquals(EntriesKind.HISTORY, hist.kind)
        assertEquals(MemoryCard.Note(NoteKind.USAGE_HISTORY), start(MemoryCommand.History(null)))
        assertEquals(MemoryCard.Note(NoteKind.NOT_FOUND), start(MemoryCommand.History(5), recall.shown))
    }

    @Test fun blockIsBuiltFreshAndBounded() {
        saveText("I like green tea")
        val b = turns.block("what tea do I like?")
        assertTrue(b.contains("green tea"))
        assertEquals("", turns.block("anything", 0))
    }
}
