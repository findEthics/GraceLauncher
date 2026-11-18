package com.galaxyrio.gracelauncher.assistant

import org.junit.Assert.*
import org.junit.Test

class SourceReferenceTest {
    private fun refs(text: String) = parseSourceRefs(text)

    @Test fun englishNounsWithDigitsAndNumberLists() {
        assertEquals(listOf(6), refs("What does source 6 say?"))
        assertEquals(listOf(6), refs("What does source #6 say?"))
        assertEquals(listOf(6), refs("Tell me about SOURCE no. 6"))
        assertEquals(listOf(2, 6), refs("compare sources 2 and 6"))
        assertEquals(listOf(2, 6), refs("compare sources 2, 6"))
        assertEquals(listOf(2, 6), refs("results 2 & 6 please"))
        assertEquals(listOf(2, 6), refs("link 2 or 6?"))
        assertEquals(listOf(1, 2, 3), refs("sources 1, 2 and 3"))
        assertEquals(listOf(4), refs("summarize article 4"))
        assertEquals(listOf(5), refs("what is reference 5 about"))
        assertEquals(listOf(7), refs("open ref 7"))
        assertEquals(listOf(6), refs("What does source 6 say? Keep the answer grounded in that source."))
    }

    @Test fun bracketsAndOrdinals() {
        assertEquals(listOf(6), refs("what about [6]?"))
        assertEquals(listOf(2, 6), refs("and [2][6]?"))
        assertEquals(listOf(1), refs("the first source please"))
        assertEquals(listOf(3), refs("tell me about the third article"))
        assertEquals(listOf(10), refs("the tenth link"))
        assertEquals(listOf(21), refs("the 21st result"))
        assertEquals(listOf(2), refs("the 2nd reference"))
    }

    @Test fun germanPhrasings() {
        assertEquals(listOf(6), refs("Was steht in Quelle 6?"))
        assertEquals(listOf(6), refs("Was steht in Quelle Nr. 6?"))
        assertEquals(listOf(2, 6), refs("Vergleiche Quellen 2 und 6"))
        assertEquals(listOf(3), refs("Ergebnis 3 bitte"))
        assertEquals(listOf(1), refs("Was sagt die erste Quelle?"))
        assertEquals(listOf(2), refs("Worum geht es im zweiten Artikel?"))
        assertEquals(listOf(5), refs("Und der fünfte Link?"))
        assertEquals(listOf(6), refs("Was steht in der 6. Quelle?"))
        assertEquals(listOf(10), refs("im zehnten Ergebnis"))
    }

    @Test fun distinctInMentionOrderAndNumbersOutsideRangeAreIgnored() {
        assertEquals(listOf(6, 2), refs("source 6 and source 2 and source 6"))
        assertNull(refs("source 100"))
        assertNull(refs("source 0"))
        assertNull(refs("source 1000"))
        assertEquals(listOf(4), refs("sources 4 and 100"))
        assertEquals(listOf(1, 2, 3, 4), refs("sources 1, 2, 3, 4"))
    }

    @Test fun plainChatAndNumbersBeforeTheNounAreNotReferences() {
        assertNull(refs("I have 6 sources of income"))
        assertNull(refs("hello there"))
        assertNull(refs("What is 6 times 7?"))
        assertNull(refs("Tell me about the source of the Nile"))
        assertNull(refs("There are 3 links in the chain"))
        assertNull(refs("What happened in 2024?"))
        assertNull(refs("Ich habe 6 Quellen"))
        assertNull(refs("resource 6"))
        assertNull(refs("the sourcerer 6"))
        assertNull(refs("first aid"))
        assertNull(refs("the first of May"))
        assertNull(refs("[]"))
        assertNull(refs("[abc]"))
    }
}
