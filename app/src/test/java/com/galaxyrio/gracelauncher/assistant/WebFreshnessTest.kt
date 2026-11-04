package com.galaxyrio.gracelauncher.assistant

import org.junit.Assert.*
import org.junit.Test

class WebFreshnessTest {
    private fun source(title: String) = WebSource(title, "https://example.org/story", "Evidence", "2026-11-04")

    @Test fun currentNewsOmitsExplicitRetrospectivesDespiteFreshPublication() {
        for (title in listOf("Today in History: November 3, a tower opens", "THIS DAY IN HISTORY: a treaty",
            "On this day in history: an election", "On this day: a discovery", "Anniversary: a historic opening",
            "A retrospective of the railway", "A look back at the first flight", "Looking back: the opening", "Ten years ago today")) {
            assertTrue(title, TavilySearch.selectNewsSources("summary of world news today", "world news today", listOf(source(title))).isEmpty())
        }
    }

    @Test fun explicitHistoryAndOrdinarySearchesKeepRetrospectives() {
        val sources = listOf(source("Today in History: a tower opens"))
        for (request in listOf("history news today", "historical news", "anniversary news today", "anniversaries today",
            "retrospectives in the news", "what happened on this day?")) {
            assertEquals(request, sources, TavilySearch.selectNewsSources(request, "world news today", sources))
        }
        assertEquals(sources, TavilySearch.selectNewsSources("when did the tower open?", "tower opening date", sources))
    }

    @Test fun newsScopeUsesRequestOrGeneratedQueryWithoutChangingRequestPolicy() {
        val sources = listOf(source("Today in History: a tower opens"))
        assertTrue(TavilySearch.selectNewsSources("headlines today", "world briefing", sources).isEmpty())
        assertTrue(TavilySearch.selectNewsSources("what is happening?", "world news today", sources).isEmpty())
        assertEquals("day", TavilySearch.newsTimeRange("history news today"))
        assertNull(TavilySearch.newsTimeRange("history today"))
    }

    @Test fun currentCommemorativeReportsAndIncidentalHistoryStayEligible() {
        val sources = listOf(source("City unveils memorial on opening anniversary"),
            source("Museum announces new history exhibition"), source("Aid deal announced").copy(content = "Related: Today in History: a tower opens"))
        assertEquals(sources, TavilySearch.selectNewsSources("news today", "news today", sources))
    }

    @Test fun selectsNewsRangeByWords() {
        assertEquals("day", TavilySearch.newsTimeRange("world news today"))
        assertEquals("day", TavilySearch.newsTimeRange("germany news today"))
        assertEquals("week", TavilySearch.newsTimeRange("germany news this week"))
        assertEquals("day", TavilySearch.newsTimeRange("BREAKING HEADLINES"))
        assertNull(TavilySearch.newsTimeRange("weather today"))
        assertNull(TavilySearch.newsTimeRange("newspaper today"))
    }

    @Test fun validatesUntrustedPublishedDates() {
        assertEquals("2026-11-03T08:30:00+00:00", TavilySearch.publishedDate("2026-11-03T08:30:00+00:00"))
        for (invalid in listOf("2026\n11", "2026\u202e11", "2026\u206611", "x".repeat(41), "2026<script>", "")) {
            assertNull(TavilySearch.publishedDate(invalid))
        }
    }
}
