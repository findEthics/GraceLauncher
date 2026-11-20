package com.galaxyrio.gracelauncher.assistant

import com.galaxyrio.gracelauncher.assistant.SlashCommand.*
import org.junit.Assert.*
import org.junit.Test

class SlashCommandsTest {
    private val all = listOf(WEB, WIKIPEDIA, HEALTH, REMEMBER, RECALL, FORGET, PURGE, HISTORY)

    @Test fun loneSlashShowsAllRowsInFixedOrder() = assertEquals(all, slashSuggestions("/", true))
    @Test fun healthRowOnlyWithToken() = assertEquals(all - HEALTH, slashSuggestions("/", false))
    @Test fun prefixMatches() {
        assertEquals(listOf(WEB, WIKIPEDIA), slashSuggestions("/w", true))
        assertEquals(listOf(WIKIPEDIA), slashSuggestions("/wi", true))
        assertEquals(listOf(FORGET), slashSuggestions("/del", true))
        assertEquals(listOf(PURGE), slashSuggestions("/er", true))
        assertEquals(listOf(REMEMBER), slashSuggestions("/sa", true))
        assertEquals(listOf(WIKIPEDIA), slashSuggestions("/wiki", true))
        assertEquals(listOf(RECALL, REMEMBER).sortedBy { all.indexOf(it) }, slashSuggestions("/re", true))
    }
    @Test fun caseInsensitive() = assertEquals(listOf(HISTORY), slashSuggestions("/HIS", true))
    @Test fun healthHiddenWhenNoTokenEvenOnExactPrefix() = assertTrue(slashSuggestions("/hea", false).isEmpty())
    @Test fun noMatchOrNonTriggerIsEmpty() {
        for (s in listOf("/xyz", "", "hello", "/web ", "/web x", "/\n", "/ ", " /", "/w\n", "/1", "x/web"))
            assertTrue("'$s'", slashSuggestions(s, true).isEmpty())
    }

    @Test fun sourcePrefix() {
        assertEquals(ChatSource.WEB to "tokyo weather", parseSourcePrefix("/web tokyo weather", true))
        assertEquals(ChatSource.WIKIPEDIA to "Tokyo", parseSourcePrefix("/wikipedia Tokyo", false))
        assertEquals(ChatSource.WIKIPEDIA to "Tokyo", parseSourcePrefix("/wiki Tokyo", false))
        assertEquals(ChatSource.HEALTH to "how am I", parseSourcePrefix("/health how am I", true))
        assertEquals(ChatSource.WEB to "a\nb", parseSourcePrefix("/WEB   a\nb", true))
    }
    @Test fun sourcePrefixRejects() {
        assertNull(parseSourcePrefix("/health x", false))
        assertNull(parseSourcePrefix("/web", true))
        assertNull(parseSourcePrefix("/webx foo", true))
        assertNull(parseSourcePrefix("hello /web x", true))
        assertNull(parseSourcePrefix("/remember milk", true))
    }
    @Test fun bareSourceCommandWithTrailingSpaceHasNoRest() {
        assertEquals(ChatSource.WEB to "", parseSourcePrefix("/web  ", true))
    }
}
