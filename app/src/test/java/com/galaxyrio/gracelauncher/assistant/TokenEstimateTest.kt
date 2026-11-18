package com.galaxyrio.gracelauncher.assistant

import org.junit.Assert.*
import org.junit.Test

class TokenEstimateTest {
    @Test fun asciiCountsAboutThreeCharactersPerToken() {
        assertEquals(0, estimateTokens(""))
        assertEquals(1, estimateTokens("a"))
        assertEquals(1, estimateTokens("abc"))
        assertEquals(2, estimateTokens("abcd"))
        assertEquals(34, estimateTokens("x".repeat(100)))
    }

    @Test fun everyNonAsciiUtf8ByteCountsAsOneToken() {
        assertEquals(2, estimateTokens("ü")) // 2 bytes
        assertEquals(3, estimateTokens("東")) // 3 bytes
        assertEquals(4, estimateTokens("😀")) // surrogate pair, 4 bytes, counted once
        assertEquals(1 + 2, estimateTokens("abc") + estimateTokens("ä"))
        assertEquals(9, estimateTokens("Größe über")) // 7 ASCII -> 3, ö ß ü -> 6
    }

    @Test fun truncationNeverSplitsCodePointsAndMarksTheCut() {
        val text = "é😀".repeat(100)
        for (limit in 0..60) {
            val out = truncateToTokens(text, limit)
            assertTrue(estimateTokens(out) <= limit)
            assertFalse(out.contains('\uFFFD'))
            assertTrue(out.none { Character.isSurrogate(it) } || out.codePoints().allMatch { it != 0xFFFD })
            val core = out.removeSuffix("…")
            assertTrue(text.startsWith(core))
        }
        assertEquals(text, truncateToTokens(text, estimateTokens(text)))
    }

    @Test fun truncationPrefersAWordBoundary() {
        val text = "First complete fact. Another incomplete claim about a city."
        val out = truncateToTokens(text, 12)
        assertTrue(out.endsWith("…"))
        assertTrue(out.removeSuffix("…").let { text.startsWith(it) && (it.length == text.length || text[it.length] == ' ' || text[it.length - 1] == ' ') })
        assertTrue(estimateTokens(out) <= 12)
        assertEquals("", truncateToTokens("Extraordinary", 0))
    }

    @Test fun tokenPrefixIsExactAndBoundarySafe() {
        assertEquals("abc", tokenPrefix("abcdef", 1))
        assertEquals("abcdef", tokenPrefix("abcdef", 2))
        assertEquals("é", tokenPrefix("é😀x", 2))
        assertEquals("é😀", tokenPrefix("é😀x", 6))
    }
}
