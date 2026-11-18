package com.galaxyrio.gracelauncher.assistant

import org.junit.Assert.*
import org.junit.Test

class WikipediaCitationTest {
    @Test fun acceptsOnlyDisplayedCurrentTurnReferencesAndIgnoresOrdinaryBrackets() {
        assertTrue(WikipediaSearch.citationsResolve("Answer without citations [notes].", 2))
        assertTrue(WikipediaSearch.citationsResolve("First fact [1], second fact [2].", 2))
        assertFalse(WikipediaSearch.citationsResolve("Unsupported fact [3].", 2))
    }

    @Test fun rejectsZeroAndOverlongCitationNumbersWithoutOverflow() {
        assertFalse(WikipediaSearch.citationsResolve("Unsupported [0].", 2))
        assertFalse(WikipediaSearch.citationsResolve("Unsupported [999999999999999999999999999999999999].", 2))
    }

    @Test fun oneInvalidReferenceRejectsAnOtherwiseValidReply() {
        assertFalse(WikipediaSearch.citationsResolve("Supported [1], unsupported [4].", 2))
    }

    @Test fun citationScanPreservesUnicodeAroundReferences() {
        assertTrue(WikipediaSearch.citationsResolve("Réponse étayée [1] — café 😀.", 2))
    }
}
