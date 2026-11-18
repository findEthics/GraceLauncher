package com.galaxyrio.gracelauncher.assistant

import com.google.ai.edge.litertlm.ToolCall
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real MediaWiki responses under src/test/resources/wikipedia (see its README); asserts the evidence Gemma would receive. */
class WikipediaRetrievalFixtureTest {
    private fun fixture(case: String, name: String) =
        File("src/test/resources/wikipedia/$case/$name").also { assertTrue("missing $it", it.isFile) }.readText()

    private fun reader(case: String) = WikipediaReader { url, _ ->
        when {
            url.contains("generator=search") -> fixture(case, "discovery.json")
            url.contains("prop=sections") -> fixture(case, "sections.json")
            else -> fixture(case, "section-" + Regex("[?&]section=([0-9]+)").find(url)!!.groupValues[1] + ".json")
        }
    }

    private fun query(topic: String, focus: String) = ToolCall("query_wikipedia", mapOf("topic" to topic, "lang" to "en", "focus" to focus))
    private fun read(focus: String, vararg ids: String) = ToolCall("read_wikipedia_sections", mapOf("sections" to ids.toList(), "focus" to focus))

    /** Mirrors the real controller: same allowance formula, per-call framing, discovery cap 1100. */
    private fun run(case: String, question: String, topic: String, focus: String, vararg sections: String): List<String> = runBlocking {
        val turn = WikipediaTurn(reader(case))
        val budget = AssistantController.wikipediaContextTokens(question, emptyList(), AssistantController.MAX_WEB_CONTEXT_TOKENS)
        var spent = 0
        val out = mutableListOf<String>()
        for (call in listOfNotNull(query(topic, focus), sections.takeIf { it.isNotEmpty() }?.let { read(focus, *it) })) {
            spent += AssistantController.wikipediaCallTokens(call)
            val allowance = budget - spent
            val result = turn.execute(call, if (call.name == "query_wikipedia") minOf(1100, allowance) else allowance)
            spent += estimateTokens(result.toString())
            out += result.getValue("untrusted_sources")
        }
        out
    }

    @Test fun oscarsSectionDeliversWinnersFilmsMarkerAndLegend() {
        val q = "Who won Best Actor and Best Actress at the 2026 Oscars and for which films?"
        val evidence = run("oscars", q, "98th Academy Awards", "Best Actor Best Actress winners films", "2").last()
        for (needle in listOf("Michael B. Jordan", "Sinners", "Jessie Buckley", "Hamnet", "Winners are listed first")) {
            assertTrue("missing $needle in:\n$evidence", evidence.contains(needle))
        }
        assertTrue(evidence.contains("‡") || evidence.contains("**Michael B. Jordan"))
        val allowance = AssistantController.wikipediaContextTokens(q, emptyList(), 2860) -
            AssistantController.wikipediaCallTokens(query("98th Academy Awards", "x")) - 1100 -
            AssistantController.wikipediaCallTokens(read("x", "2"))
        // Limits are unchanged numbers now measured in estimated tokens (~3 ASCII bytes each); before: ~690 bytes.
        assertTrue("allowance $allowance tokens", allowance * 3 >= 2 * 690)
    }

    @Test fun actorAwardNavigationUsesParentArrowAndSection13HasThe2025Row() {
        val parts = run("actor-award", "Who won Best Actor in 2026?", "Academy Award for Best Actor", "2020s winners", "13")
        assertTrue(parts.first().contains("13 Winners and nominees › 2020s"))
        val section = parts.last()
        assertTrue(section.contains("Michael B. Jordan"))
        assertTrue(section.contains("Sinners"))
        assertTrue(section.contains("98th"))
    }

    @Test fun tokyoNavigationListsSection0FirstAndInfoboxHasPopulation() {
        val parts = run("tokyo", "What is the population of Tokyo?", "Tokyo", "population", "0")
        val nav = parts.first().substringAfter("Navigation only")
        assertTrue(nav.contains("26 Demographics"))
        assertTrue(nav.lines().first { it.matches(Regex("^[0-9]+ .*")) }.startsWith("0 "))
        assertTrue(parts.last().contains("14,254,039"))
        assertTrue(parts.last().contains("May 2025"))
    }

    @Test fun beatlesMembersListKeepsAllFourPrincipalMembersWithInstruments() {
        val section = run("beatles", "Who were the members of the Beatles and what did they play?", "The Beatles", "members instruments", "33").last()
        for (needle in listOf("John Lennon", "vocals, guitars, keyboards, harmonica, bass", "Paul McCartney", "George Harrison", "sitar", "Ringo Starr", "drums, percussion")) {
            assertTrue("missing $needle in:\n$section", section.contains(needle))
        }
    }

    @Test fun curieAndGreaterTokyoLeadsStillCarryTheirHeadlineFacts() {
        val curie = run("curie", "Marie Curie Nobel prizes?", "Marie Curie", "nobel").first()
        for (needle in listOf("1903", "Physics", "1911", "Chemistry")) assertTrue(curie.contains(needle))
        val greater = run("greater-tokyo", "Population of Greater Tokyo?", "Greater Tokyo Area", "population").first()
        assertTrue(greater.contains("36,954,512"))
    }

    @Test fun navigationIsPackedOnceWithinTheDiscoveryAllowance() {
        val first = run("tokyo", "What is the population of Tokyo?", "Tokyo", "population").single()
        assertTrue(estimateTokens(first) <= 1100)
        assertEquals(1, Regex("Navigation only").findAll(first).count())
    }
}
