package com.galaxyrio.gracelauncher.assistant.memory

import com.galaxyrio.gracelauncher.assistant.estimateTokens
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class MemoryRecallTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = ZonedDateTime.of(2026, 11, 20, 10, 0, 0, 0, zone)
    private val nowMs = now.toInstant().toEpochMilli()
    private val day = 86_400_000L
    private fun at(days: Long, hour: Int = 15) = now.plusDays(days).withHour(hour).withMinute(0).toInstant().toEpochMilli()
    private fun block(msg: String, entries: List<MemoryEntry>) = buildMemoryBlock(msg, entries, nowMs, zone)
    private fun lines(b: String) = b.lines().filter { " · " in it }

    @Test fun emptyWhenNothingRelevant() {
        assertEquals("", block("hi", emptyList()))
        assertEquals("", block("hi", listOf(entry("a", "Car", "blue"))))
    }

    @Test fun lineFormatIsExact() {
        val pref = entry("p", "Oat milk", "I prefer oat milk", MemoryType.PREFERENCE, validFrom = nowMs)
        val ev = entry("e", "Dentist", "Dentist tomorrow at 3pm", MemoryType.EVENT, validFrom = nowMs, occurredAt = at(1))
        val b = block("hello", listOf(pref, ev))
        assertEquals(
            listOf(
                "preference · Oat milk: I prefer oat milk (saved 2026-11-20)",
                "event · Dentist: Dentist tomorrow at 3pm (2026-11-21 15:00; saved 2026-11-20)",
            ),
            lines(b),
        )
        assertTrue(b.lines().first().contains("reference", ignoreCase = true))
        assertTrue(b.lines().first().contains("current message", ignoreCase = true))
    }

    @Test fun multilineBodyIsCollapsedToOneLine() {
        val b = block("x", listOf(entry("p", "S", "a\n\nb   c", MemoryType.PREFERENCE, validFrom = nowMs)))
        assertEquals("preference · S: a b c (saved 2026-11-20)", lines(b).single())
    }

    @Test fun sectionOrderAndPreferencesCappedAtEightNewestFirst() {
        val prefs = (1..10).map { entry("p$it", "Pref $it", "v", MemoryType.PREFERENCE, validFrom = nowMs - it * day) }
        val ev = entry("e", "Trip", "trip", MemoryType.EVENT, validFrom = nowMs, occurredAt = at(3))
        val lex = entry("f", "Zebra", "zebra stripes", MemoryType.FACT, validFrom = nowMs)
        val l = lines(block("tell me about zebra", prefs + ev + lex))
        assertEquals(listOf("preference · Pref 1", "preference · Pref 2"), l.take(2).map { it.substringBefore(":") })
        assertEquals(8, l.count { it.startsWith("preference") })
        assertTrue(l[8].startsWith("event · Trip")); assertTrue(l[9].startsWith("fact · Zebra")); assertEquals(10, l.size)
    }

    @Test fun eventWindowIsMinusTwoDaysToPlusFourteenSoonestFirst() {
        val mk = { id: String, d: Long -> entry(id, "Ev $id", "x", MemoryType.EVENT, validFrom = nowMs, occurredAt = at(d, 10)) }
        val all = listOf(mk("far", 15), mk("late", 14), mk("soon", 1), mk("past", -2), mk("tooold", -3), mk("mid", 5))
        val ids = lines(block("hello", all)).map { it.substringAfter("Ev ").substringBefore(":") }
        assertEquals(listOf("past", "soon", "mid", "late"), ids)
    }

    @Test fun onlyActiveChainHeads() {
        val all = listOf(
            entry("h", "Wifi", "new", MemoryType.PREFERENCE, validFrom = 2, supersedes = "o"),
            entry("o", "Wifi", "old", MemoryType.PREFERENCE, validFrom = 1, status = MemoryStatus.SUPERSEDED, supersededBy = "h"),
            entry("d", "Deleted", "gone", MemoryType.PREFERENCE, status = MemoryStatus.INVALIDATED),
        )
        val l = lines(block("hello", all))
        assertEquals(1, l.size); assertTrue(l[0].contains("new")); assertFalse(l[0].contains("old"))
    }

    @Test fun lexicalMatchesRemoveEnglishAndGermanStopwordsCapEightDedupe() {
        val all = listOf(entry("a", "Auto", "Das Auto steht in der Garage")) + (1..10).map { entry("b$it", "Garage $it", "Stellplatz", validFrom = it * 10L) }
        assertEquals(1, lines(block("Wo ist mein Auto?", all)).size)
        assertEquals(8, lines(block("garage", all)).size)
        assertTrue(lines(block("Where is the my of", all)).isEmpty()) // only stopwords
        assertTrue(lines(block("Wo ist das", all)).isEmpty())
        val pref = entry("p", "Garage", "Garage code", MemoryType.PREFERENCE)
        assertEquals(1, lines(block("garage", listOf(pref))).size) // not duplicated across sections
    }

    @Test fun hugeBodyStillWithinBudget() {
        val b = block("hello", listOf(entry("p", "Big", "word ".repeat(5_000), MemoryType.PREFERENCE, validFrom = nowMs)))
        assertTrue(estimateTokens(b) <= 400)
    }

    @Test fun alwaysWithinBudgetAndDropsSection3Then2Then1() {
        val rnd = Random(7)
        repeat(300) {
            fun body() = "w".repeat(rnd.nextInt(5, 400)).chunked(6).joinToString(" ")
            val all = (1..rnd.nextInt(0, 12)).map { entry("p$it", "Pref $it", body(), MemoryType.PREFERENCE, validFrom = nowMs - it) } +
                (1..rnd.nextInt(0, 10)).map { entry("e$it", "Ev $it", body(), MemoryType.EVENT, validFrom = nowMs, occurredAt = at(it.toLong() % 12)) } +
                (1..rnd.nextInt(0, 12)).map { entry("f$it", "Zeb $it", "zebra " + body(), MemoryType.FACT, validFrom = nowMs - it) }
            val b = block("zebra", all)
            assertTrue("tokens=${estimateTokens(b)}", estimateTokens(b) <= 400)
            val l = lines(b)
            val prefs = l.count { it.startsWith("preference") }; val evs = l.count { it.startsWith("event") }; val lex = l.count { it.startsWith("fact") }
            val wantPrefs = minOf(8, all.count { it.type == MemoryType.PREFERENCE })
            val wantEvs = all.count { it.type == MemoryType.EVENT }
            val wantLex = minOf(8, all.count { it.type == MemoryType.FACT })
            if (prefs < wantPrefs) assertTrue("1 dropped => 2,3 gone", evs == 0 && lex == 0)
            if (evs < wantEvs) assertEquals("2 dropped => 3 gone", 0, lex)
            assertTrue(prefs <= wantPrefs && evs <= wantEvs && lex <= wantLex)
        }
    }
}
