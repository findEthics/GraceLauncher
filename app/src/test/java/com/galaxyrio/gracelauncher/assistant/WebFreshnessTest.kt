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

    @Test fun searchParametersKeepNewsPolicyAndUseGeneralSearchForEvents() {
        assertEquals(SearchParams("news", "day"), TavilySearch.resolveSearchParams("germany news today"))
        assertEquals(SearchParams("news", "week"), TavilySearch.resolveSearchParams("germany news this week"))
        assertEquals(SearchParams(timeRange = "day", country = "germany"), TavilySearch.resolveSearchParams("What happened in Germany today?"))
        assertEquals(SearchParams(timeRange = "month"), TavilySearch.resolveSearchParams("who won the latest F1 race"))
        for (query in listOf("what is happening", "events tonight", "what is happening right now")) {
            assertEquals(query, SearchParams(timeRange = "day"), TavilySearch.resolveSearchParams(query))
        }
        assertEquals(SearchParams(timeRange = "week", country = "india"), TavilySearch.resolveSearchParams("events in India this week"))
        for (query in listOf("recent results", "race winner", "events this month")) {
            assertEquals(query, SearchParams(timeRange = "month"), TavilySearch.resolveSearchParams(query))
        }
        for (query in listOf("history today", "historical events this week", "on this day", "retrospective this month", "current standings", "race score")) {
            assertEquals(query, SearchParams(), TavilySearch.resolveSearchParams(query))
        }
        assertEquals(SearchParams(country = "germany"), TavilySearch.resolveSearchParams("Germany history today"))
    }

    @Test fun liveFinanceUsesNewsWithoutUnsupportedCountryBoost() {
        for (query in listOf("bitcoin price", "BTC today", "ETH now", "latest crypto", "stock price", "live share price",
            "current exchange rate", "Japan stock price", "financial markets today")) {
            assertEquals(query, SearchParams("news", "day"), TavilySearch.resolveSearchParams(query))
        }
        assertEquals(SearchParams(), TavilySearch.resolveSearchParams("how does bitcoin work"))
        assertEquals(SearchParams(), TavilySearch.resolveSearchParams("stockholm architecture"))
    }

    @Test fun countryBoostUsesWholeNamesOnlyAndIsNotAppliedToNews() {
        for (country in listOf("germany", "india", "japan", "united states", "united kingdom", "france", "canada", "australia",
            "brazil", "italy", "spain", "china", "south korea", "mexico", "russia", "ukraine")) {
            assertEquals(SearchParams(country = country), TavilySearch.resolveSearchParams("facts about ${country.uppercase()}"))
            assertEquals(SearchParams("news", "day"), TavilySearch.resolveSearchParams("$country news today"))
        }
        for (query in listOf("Indianapolis", "Japanology", "Germany and France geography")) {
            assertNull(query, TavilySearch.resolveSearchParams(query).country)
        }
    }

    @Test fun excerptCleaningMakesRoomForProseWithoutChangingOrdinaryEvidence() {
        val prose = "Officials announced a new rail link. Commuters will have shorter journeys."
        val clutter = "Home > World > Germany\nSkip to main content\n![logo](https://example.org/logo.png)\n" +
            "We use cookies to improve your experience. Accept all cookies | Manage cookie settings\n\n"
        assertEquals(prose, TavilySearch.cleanExcerpt(clutter + prose))
        assertEquals("First fact. Second fact.", TavilySearch.cleanExcerpt("First fact. ![ad](https://example.org/ad.png)  Second\n fact."))
        for (text in listOf("evidence", "x".repeat(400), "é😀 complete fact.")) assertEquals(text, TavilySearch.cleanExcerpt(text))
    }

    @Test fun concreteArticlesBeatHubsAndEqualRankKeepsProviderOrder() {
        val article = source("Concrete report").copy(url = "https://example.org/article", content = "A substantive complete report. ".repeat(8))
        val hubs = listOf("topics/world", "where/germany", "category/world", "tag/news", "all-time-winners", "results/2025/")
            .map { article.copy(url = "https://example.org/$it") } +
            listOf(article.copy(title = "Germany - BBC News", url = "https://example.org/bbc-news"),
                article.copy(title = "Latest News, Germany", url = "https://example.org/latest"))
        val first = article.copy(title = "City opens rail line - BBC News", url = "https://example.com/a")
        val second = article.copy(url = "https://example.com/c")
        assertEquals(listOf(first, second), TavilySearch.rankedSources(hubs + first + second).take(2))
        assertEquals(hubs.distinctBy { it.title }, TavilySearch.rankedSources(hubs))
        val ordinary = listOf(first, second).map { it.copy(publishedDate = null, content = "evidence") }
        assertEquals(ordinary, TavilySearch.rankedSources(ordinary))
    }

    @Test fun publicationYearDateAndSubstantiveExcerptsImproveCandidateRank() {
        val undated = source("Undated short").copy(url = "https://example.org/undated", publishedDate = null)
        val substantive = undated.copy(title = "Undated substantive", url = "https://example.org/substantive", content = "Complete fact. ".repeat(12))
        val dated = source("Dated").copy(url = "https://example.org/dated", publishedDate = "2025-01-01")
        val recent = source("Recent").copy(url = "https://example.org/recent", publishedDate = "${java.time.Year.now().value}-01-01")
        assertEquals(listOf(recent, dated, substantive, undated), TavilySearch.rankedSources(listOf(undated, substantive, dated, recent)))
    }

    @Test fun pirateStreamTitlesRankBelowOrdinaryReportsEvenWhenNewer() {
        val spam = listOf("[[[NBC^Sports]] Watch", "Watch now ]]]", "^^^ live", "ＬＩＶＥ Germany", "Fullwidth ！ stream")
            .map { source(it).copy(url = "https://spam.example/${it.hashCode()}") }
        val report = source("Germany transport deal").copy(publishedDate = null)
        assertEquals(listOf(report) + spam, TavilySearch.rankedSources(spam + report))
    }

    @Test fun duplicateNormalizedHostAndTitleCannotCrowdOutDistinctCandidates() {
        val first = source("Tokyo Population 2026").copy(url = "https://www.worldpopulationreview.com/cities/japan/tokyo")
        val duplicate = first.copy(title = "  TOKYO   Population 2026  ", url = "https://worldpopulationreview.com/cities/tokyo")
        val differentHost = first.copy(url = "https://example.com/tokyo")
        val differentTitle = first.copy(title = "Tokyo metropolitan population", url = "https://www.worldpopulationreview.com/metro/tokyo")
        assertEquals(listOf(first, differentHost, differentTitle), TavilySearch.rankedSources(listOf(first, duplicate, differentHost, differentTitle)))
    }

    @Test fun replySelectionDeduplicatesAndFiltersBeforeFillingTheRemainingSlots() {
        val existing = List(8) { index -> source("Report $index").copy(
            url = "https://first.example/story/$index", content = "Substantive evidence for report $index. ".repeat(5)) }
        val duplicate = existing.first().copy(title = "  REPORT   0  ", url = "https://www.first.example/alternate")
        val invalid = source("Unsafe").copy(url = "javascript:alert(1)")
        val additions = List(5) { index -> source("New report $index").copy(
            url = "https://new.example/story/$index", content = "Substantive new evidence $index.") }

        val selected = TavilySearch.selectReplySources(existing, listOf(duplicate, invalid) + additions)

        assertEquals(existing.map { it.url } + additions.take(2).map { it.url }, selected.displayed.map { it.url })
        assertEquals(listOf(1, 9, 10), selected.numbers)
        assertEquals(listOf(existing.first().url) + additions.take(2).map { it.url }, selected.evidence.map { it.url })
    }

    @Test fun replySelectionCapsTenDisplayedResultsAndThreeEvidenceItemsPerQuery() {
        val candidates = List(20) { index -> source("Story $index").copy(
            url = "https://example$index.org/story", content = "Substantive evidence for story $index.") }

        val selected = TavilySearch.selectReplySources(emptyList(), candidates)

        assertEquals(10, selected.displayed.size)
        assertEquals(listOf(1, 2, 3), selected.numbers)
        assertEquals(candidates.take(3).map { it.url }, selected.evidence.map { it.url })
        assertTrue(selected.displayed.all { WebSource.safeUrl(it.url) && it.content.isNotBlank() })
    }
}
