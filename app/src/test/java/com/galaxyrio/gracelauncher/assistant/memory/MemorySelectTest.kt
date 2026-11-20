package com.galaxyrio.gracelauncher.assistant.memory

import org.junit.Assert.*
import org.junit.Test

internal fun entry(
    id: String, subject: String, body: String = subject, type: MemoryType = MemoryType.FACT,
    validFrom: Long = 1_000, status: MemoryStatus = MemoryStatus.ACTIVE, occurredAt: Long? = null,
    seq: Long = 0, conflict: Boolean = false, supersedes: String? = null, supersededBy: String? = null,
) = MemoryEntry(
    id = id, type = type, subject = subject, subjectKey = subjectKey(subject), body = body, occurredAt = occurredAt,
    validFrom = validFrom, status = status, supersedes = supersedes, supersededBy = supersededBy, changeSeq = seq, conflict = conflict,
)

class MemorySelectTest {
    private fun draft(type: MemoryType, subject: String, body: String = subject) = MemoryDraft(type, subject, body, null)

    // ---- replace candidates ----
    @Test fun exactKeyDefaultOnOnlyForFactPreferencePlace() {
        for (t in listOf(MemoryType.FACT, MemoryType.PREFERENCE, MemoryType.PLACE)) {
            val c = replaceCandidates(draft(t, "Café  Crème"), listOf(entry("a", "cafe creme", type = t))).single()
            assertTrue("$t", c.exact && c.defaultOn)
        }
        for (t in listOf(MemoryType.EVENT, MemoryType.NOTE)) {
            val c = replaceCandidates(draft(t, "Dentist"), listOf(entry("a", "dentist", type = t))).single()
            assertTrue("$t", c.exact && !c.defaultOn)
        }
    }

    @Test fun exactKeyIsTypeScopedAndActiveOnly() {
        val all = listOf(
            entry("a", "Car", type = MemoryType.PLACE),
            entry("b", "Car", type = MemoryType.FACT, status = MemoryStatus.SUPERSEDED),
            entry("c", "Car", type = MemoryType.FACT, status = MemoryStatus.INVALIDATED),
        )
        assertTrue(replaceCandidates(draft(MemoryType.FACT, "Car"), all).none { it.exact })
    }

    @Test fun lexicalCandidatesAreSameTypeDefaultOffAndAtMostThree() {
        val d = draft(MemoryType.PLACE, "Car location", "my car is parked on level 3 garage north")
        val all = listOf(
            entry("1", "Parking spot", "car parked level 2", MemoryType.PLACE, 10),
            entry("2", "Garage", "north garage car", MemoryType.PLACE, 20),
            entry("3", "Level", "level 3 garage", MemoryType.PLACE, 30),
            entry("4", "Car stuff", "car garage north level", MemoryType.PLACE, 40),
            entry("5", "Parking spot", "car parked level 3", MemoryType.FACT, 50), // other type
            entry("6", "Unrelated", "bananas", MemoryType.PLACE, 60),
        )
        val c = replaceCandidates(d, all)
        assertEquals(3, c.size)
        assertTrue(c.none { it.exact || it.defaultOn })
        assertFalse(c.any { it.entry.id == "5" || it.entry.id == "6" })
    }

    @Test fun exactMatchIsNotRepeatedAsLexicalAndLexicalComesAfterExact() {
        val d = draft(MemoryType.PREFERENCE, "Milk", "I prefer oat milk")
        val all = listOf(entry("x", "Drink", "oat milk please", MemoryType.PREFERENCE, 5), entry("e", "milk", "soy", MemoryType.PREFERENCE, 1))
        val c = replaceCandidates(d, all)
        assertEquals(listOf("e", "x"), c.map { it.entry.id })
        assertEquals(listOf(true, false), c.map { it.defaultOn })
    }

    @Test fun atMostOnePickAndDefaultIsExactDefaultOn() {
        val d = draft(MemoryType.FACT, "Wifi", "wifi password router")
        val c = replaceCandidates(d, listOf(entry("e", "wifi", "old"), entry("l", "Router", "router password")))
        assertEquals("e", defaultReplaceId(c))
        assertEquals("l", pickReplace(c, "l")); assertNull(pickReplace(c, "zzz")); assertNull(pickReplace(c, null))
        assertNull(defaultReplaceId(replaceCandidates(d, listOf(entry("l", "Router", "router password")))))
    }

    // ---- matching / recall cards ----
    @Test fun matchActiveIsLexicalActiveOnlyAtMostFiveNewestAmongEqualScores() {
        val all = (1..7).map { entry("m$it", "Milk $it", "oat milk", validFrom = it * 10L) } +
            entry("gone", "Milk old", "milk", status = MemoryStatus.INVALIDATED) + entry("n", "Bread", "bread")
        val r = matchActive("milk", all, 5)
        assertEquals(listOf("m7", "m6", "m5", "m4", "m3"), r.map { it.id })
    }

    @Test fun recallAloneIsTenMostRecentNewestFirstAndFlagsConflict() {
        val all = (1..12).map { entry("e$it", "S$it", validFrom = it * 10L, conflict = it == 12) }
        val r = recallCards("", all)
        assertEquals(10, r.size); assertEquals("e12", r.first().id); assertTrue(r.first().conflict)
        assertEquals("e3", r.last().id)
    }

    @Test fun matchIgnoresStopwordsAndDiacritics() {
        val all = listOf(entry("a", "Café", "Kaffee im Café"))
        assertEquals(listOf("a"), matchActive("cafe", all, 5).map { it.id })
        assertTrue(matchActive("the and ich", all, 5).isEmpty())
    }

    // ---- delete / erase state machine ----
    private val cards = listOf(entry("a", "Milk", "oat milk", validFrom = 3), entry("b", "Bread", "rye bread", validFrom = 2), entry("c", "Milk run", "milk", validFrom = 1))

    @Test fun softDeleteWordsThenPickCommitsWithoutSecondConfirm() {
        val s1 = startDelete(DeleteKind.SOFT, MemoryTarget.Words("milk"), cards, emptyList())
        assertEquals(listOf("a", "c"), (s1 as DeleteState.Picking).cards.map { it.id })
        val s2 = s1.pick(2)
        assertEquals(DeleteState.Commit(DeleteKind.SOFT, cards[2]), s2)
    }

    @Test fun eraseNeedsSecondConfirmAndCancelNeverCommits() {
        val picking = startDelete(DeleteKind.ERASE, MemoryTarget.Words("bread"), cards, emptyList())
        val confirm = picking.pick(1)
        assertEquals(DeleteState.Confirm(DeleteKind.ERASE, cards[1]), confirm)
        assertEquals(DeleteState.Commit(DeleteKind.ERASE, cards[1]), confirm.confirm())
        assertEquals(DeleteState.Cancelled, confirm.cancel())
        assertEquals(DeleteState.Cancelled, picking.cancel())
        assertFalse("confirm from Picking must not commit", picking.confirm() is DeleteState.Commit)
        assertEquals(DeleteState.Cancelled, DeleteState.Cancelled.confirm())
    }

    @Test fun pickOutOfRangeStaysPickingAndCommitIsTerminal() {
        val p = startDelete(DeleteKind.SOFT, MemoryTarget.Words("milk"), cards, emptyList())
        assertSame(p, p.pick(0)); assertSame(p, p.pick(3))
        val c = p.pick(1)
        assertSame(c, c.pick(1)); assertSame(c, c.confirm())
    }

    @Test fun indexTargetRefersToCardsAlreadyShown() {
        val s = startDelete(DeleteKind.SOFT, MemoryTarget.Index(2), cards, shown = listOf(cards[1], cards[0]))
        assertEquals(DeleteState.Commit(DeleteKind.SOFT, cards[0]), s)
        val e = startDelete(DeleteKind.ERASE, MemoryTarget.Index(1), cards, shown = listOf(cards[1]))
        assertEquals(DeleteState.Confirm(DeleteKind.ERASE, cards[1]), e)
        assertEquals(DeleteState.NoMatch, startDelete(DeleteKind.SOFT, MemoryTarget.Index(3), cards, shown = listOf(cards[1])))
    }

    @Test fun noMatchAndBareCommandListsFiveMostRecent() {
        assertEquals(DeleteState.NoMatch, startDelete(DeleteKind.SOFT, MemoryTarget.Words("zebra"), cards, emptyList()))
        val many = (1..8).map { entry("x$it", "S$it", validFrom = it * 10L) }
        val s = startDelete(DeleteKind.SOFT, null, many, emptyList()) as DeleteState.Picking
        assertEquals(listOf("x8", "x7", "x6", "x5", "x4"), s.cards.map { it.id })
        assertEquals(DeleteState.NoMatch, startDelete(DeleteKind.SOFT, null, emptyList(), emptyList()))
    }

    // ---- local chain ops ----
    @Test fun saveWithSupersedeClosesTargetAndLinksChain() {
        val old = entry("o", "Wifi", validFrom = 100)
        val neu = entry("n", "Wifi", validFrom = 200)
        val r = saveLocally(listOf(old), neu, "o")
        val byId = r.changed.associateBy { it.id }
        assertEquals(MemoryStatus.SUPERSEDED, byId.getValue("o").status)
        assertEquals(200L, byId.getValue("o").validTo); assertEquals("n", byId.getValue("o").supersededBy)
        assertEquals("o", byId.getValue("n").supersedes); assertEquals(MemoryStatus.ACTIVE, byId.getValue("n").status)
        assertEquals(OutboxKind.REMEMBER, r.op.kind); assertEquals("o", r.op.supersedeId); assertEquals("n", r.op.entry?.id)
    }

    @Test fun saveWithInactiveOrMissingTargetJustAdds() {
        val gone = entry("o", "Wifi", status = MemoryStatus.INVALIDATED)
        val r = saveLocally(listOf(gone), entry("n", "Wifi", validFrom = 200), "o")
        assertEquals(listOf("n"), r.changed.map { it.id }); assertNull(r.changed.single().supersedes)
        assertEquals(listOf("n"), saveLocally(emptyList(), entry("n", "x"), null).changed.map { it.id })
    }

    @Test fun softDeleteInvalidatesButKeepsRowAndQueuesForget() {
        val e = entry("a", "Milk", validFrom = 1)
        val r = softDeleteLocally(listOf(e), "a", now = 500)!!
        assertEquals(MemoryStatus.INVALIDATED, r.changed.single().status); assertEquals(500L, r.changed.single().validTo)
        assertEquals(OutboxKind.FORGET, r.op.kind); assertEquals("a", r.op.targetId)
        assertNull(softDeleteLocally(listOf(r.changed.single()), "a", 600)) // already inactive
        assertNull(softDeleteLocally(listOf(e), "nope", 600))
    }

    @Test fun purgeRemovesWholeChainBothDirectionsAndQueuesPurge() {
        val chain = listOf(
            entry("1", "W", status = MemoryStatus.SUPERSEDED, supersededBy = "2"),
            entry("2", "W", status = MemoryStatus.SUPERSEDED, supersedes = "1", supersededBy = "3"),
            entry("3", "W", supersedes = "2"),
        )
        val other = entry("9", "Other")
        for (start in listOf("1", "2", "3")) {
            val r = purgeLocally(chain + other, start)!!
            assertEquals(setOf("1", "2", "3"), r.removedIds); assertEquals(OutboxKind.PURGE, r.op.kind); assertEquals(start, r.op.targetId)
        }
        assertNull(purgeLocally(chain, "missing"))
        assertEquals(setOf("1", "2", "3"), chainIds(chain + other, "2"))
    }

    @Test fun historyIsChainOrderedOldestFirst() {
        val chain = listOf(entry("3", "W", validFrom = 30, supersedes = "2"), entry("1", "W", validFrom = 10, status = MemoryStatus.SUPERSEDED, supersededBy = "2"), entry("2", "W", validFrom = 20, status = MemoryStatus.SUPERSEDED, supersedes = "1", supersededBy = "3"))
        assertEquals(listOf("1", "2", "3"), historyOf(chain + entry("9", "x"), "3").map { it.id })
    }
}
