package com.galaxyrio.gracelauncher.assistant

import java.nio.charset.StandardCharsets
import org.junit.Assert.*
import org.junit.Test

class WikipediaContentTest {
    @Test fun coverageKeepsBothCompactFactsAcrossSelectedBudgets() {
        assertCompactFactsRetained(densityFixture())
    }

    @Test fun shortOneTermNoiseDoesNotDisplaceCompactFacts() {
        val noise = (1..20).joinToString("") { "<p>baseline note $it.</p>" }
        assertCompactFactsRetained(densityFixture(noise))
    }

    @Test fun unrelatedFragmentsSharingMatchingHeadingsDoNotDisplaceCompactFacts() {
        val noise = (1..12).joinToString("") { "<p>Unrelated archive item $it.</p>" }
        val html = densityFixture("<h2>Baseline control treatment</h2>$noise")
        assertCompactFactsRetained(html, listOf(569, 700, 1_024))
        val result = extractBytes(html, "baseline control treatment", 400)
        assertTrue(result.text.contains("response is"))
        assertFalse(result.text.contains("Unrelated archive"))
        assertTrue(result.text.contains("[Partial:"))
        assertWithinBudget(result, 400)
    }

    @Test fun pinnedDerivedCaptionStructureKeepsBothFactsAcrossSelectedBudgets() {
        // Structure/factual captions from page 79369474, revision 1381253826, section 1;
        // synthetic introduction avoids copying the captured article's prose or media.
        val introduction = "An actor and actress introduce winners. " + "Background context. ".repeat(20)
        val html = """
            <div class="mw-parser-output">
              <div class="mw-heading mw-heading2"><h2>Winners and nominees</h2></div>
              <p>$introduction</p>
              <div class="mw-heading mw-heading3"><h3>Awards</h3></div>
              <figure><figcaption><a>Michael B. Jordan</a>, Best Actor winner</figcaption></figure>
              <figure><figcaption><a>Jessie Buckley</a>, Best Actress winner</figcaption></figure>
            </div>
        """.trimIndent()
        for (budget in listOf(400, 569, 700, 1_024)) {
            val result = extractBytes(html, "Best Actor Best Actress winners", budget)
            assertTrue("actor at $budget", result.text.contains("Michael B. Jordan, Best Actor winner"))
            assertTrue("actress at $budget", result.text.contains("Jessie Buckley, Best Actress winner"))
            assertTrue(result.text.contains("Section: Winners and nominees > Awards"))
            assertWithinBudget(result, budget)
            assertNull(result.failureReason)
            assertEquals(result.partial, result.text.contains("[Partial:"))
        }
    }

    @Test fun equalCoveragePrefersDocumentOrderDeterministically() {
        val first = "alpha " + "x".repeat(12)
        val second = "alpha beta " + "y".repeat(27)
        val third = "alpha beta " + "z".repeat(27)
        val html = "<p>$first</p><p>$second</p><p>$third</p><p>${"unrelated ".repeat(30)}</p>"
        val result = extractBytes(html, "alpha beta", 154)
        assertTrue(result.text.contains(second))
        assertFalse(result.text.contains(first))
        assertFalse(result.text.contains(third))
        assertTrue(result.partial)
        assertWithinBudget(result, 154)
        assertEquals(result, extractBytes(html, "alpha beta", 154))
    }

    @Test fun equalGainPerCostPrefersHigherGain() {
        // Equal body lengths/supports and IDF; costs 10 and 20, gains proportional to 2 and 4 terms.
        val small = "a b x yy"
        val large = "a b c d" + ".".repeat(11)
        val balancing = "c d " + "z".repeat(200)
        val result = extractBytes("<p>$small</p><p>$large</p><p>$balancing</p>", "a b c d", 134)
        assertTrue(result.text.contains(large))
        assertFalse(result.text.contains(small))
        assertWithinBudget(result, 134)
    }

    @Test fun duplicateAddsNoGainAndComplementaryTemplateCaptionsSurvive() {
        val first = "Baseline control response is 12 units."
        val second = "Baseline treatment response is 24 units."
        val html = "<p>$first</p><p>$first</p><p>$second</p><p>${"background ".repeat(80)}</p>"
        val result = extractBytes(html, "baseline control treatment", 400)
        assertEquals(1, Regex(Regex.escape(first)).findAll(result.text).count())
        assertTrue(result.text.contains(second))
        assertTrue(result.partial)
        assertWithinBudget(result, 400)
    }

    @Test fun bestSingletonBeatsGreedyCheapSubset() {
        val cheap = "alpha beta"
        val singleton = "alpha beta gamma delta " + ".".repeat(50)
        val html = "<p>$cheap</p><p>$singleton</p><p>${"unrelated ".repeat(60)}</p>"
        val result = extractBytes(html, "alpha beta gamma delta", 190)
        assertTrue(result.text.contains(singleton))
        assertFalse(result.text.startsWith("$cheap\n"))
        assertWithinBudget(result, 190)
    }

    @Test fun oneTermFocusUsesBodyEvidence() {
        val result = extractBytes("<p>${"background ".repeat(50)}</p><p>Chlorophyll absorbs light.</p>", "chlorophyll", 180)
        assertTrue(result.text.contains("Chlorophyll absorbs light."))
        assertFalse(result.text.contains("fallback"))
        assertWithinBudget(result, 180)
    }

    @Test fun headingContextIsEligibleButZeroOverlapStillFallsBackToDocumentOrder() {
        // Changed with plan A1: section headings are context, so a heading match is real (eligible) overlap, not the fallback.
        val html = "<h2>Alpha beta</h2><p>First actual passage.</p><p>${"Other passage. ".repeat(30)}</p>"
        val zero = extractBytes(html, "unmatched", 300)
        assertTrue(zero.text.contains("Document-order fallback"))
        assertTrue(zero.text.contains("First actual passage."))
        val heading = extractBytes(html, "alpha beta", 300)
        assertFalse(heading.text.contains("Document-order fallback"))
        assertTrue(heading.text.contains("Section: Alpha beta"))
        assertTrue(heading.partial)
        assertWithinBudget(heading, 300)
    }

    @Test fun tableCaptionCountsAsContextEvidenceButNeverAsBody() {
        // Changed with plan A1: caption/header context makes a row eligible (weight 0.5) instead of forcing the fallback.
        val html = "<table><caption>Alpha beta</caption><tr><th>Item</th><th>Value</th></tr>" +
            "<tr><td>First</td><td>12</td></tr><tr><td>Second</td><td>24</td></tr></table>"
        val zero = extractBytes(html, "unmatched", 340)
        assertTrue(zero.text.contains("Document-order fallback"))
        assertTrue(zero.text.contains("Headers: Item | Value"))
        assertTrue(zero.text.contains("Table caption: Alpha beta"))
        assertWithinBudget(zero, 340)
        val caption = extractBytes(html, "alpha beta", 340)
        assertFalse(caption.text.contains("Document-order fallback"))
        assertTrue(caption.text.contains("Table caption: Alpha beta"))
        assertWithinBudget(caption, 340)
    }

    @Test fun unicodeBodyFoldingAndByteBudgets() {
        val html = "<p>${"unrelated ".repeat(100)}</p><p>Straße ς 東京 evidence.</p>"
        // 160 -> 200: each non-ASCII byte now costs a whole estimated token, so the legacy 160-byte budget cannot hold the marker plus passage.
        for (budget in listOf(200, 400, 569)) {
            val result = extractBytes(html, "STRASSE σ 東京", budget)
            assertTrue(result.text.contains("Straße ς 東京 evidence."))
            assertWithinBudget(result, budget)
        }
    }

    private fun densityFixture(noise: String = ""): String {
        val introduction = "Baseline control treatment " + "background ".repeat(38)
        val control = "Baseline control response is 12 units under the specified laboratory conditions with calibrated equipment."
        val treatment = "Baseline treatment response is 24 units under the specified laboratory conditions with calibrated equipment."
        return "<p>$introduction</p>$noise<p>$control</p><p>$treatment</p>"
    }

    private fun assertCompactFactsRetained(html: String, budgets: List<Int> = listOf(400, 569, 700, 1_024)) {
        for (budget in budgets) {
            val result = extractBytes(html, "baseline control treatment", budget)
            assertTrue("control at $budget", result.text.contains("Baseline control response is 12 units"))
            assertTrue("treatment at $budget", result.text.contains("Baseline treatment response is 24 units"))
            assertTrue(result.text.indexOf("Baseline control response") < result.text.indexOf("Baseline treatment response"))
            assertWithinBudget(result, budget)
            assertNull(result.failureReason)
            assertEquals(result.partial, result.text.contains("[Partial:"))
        }
    }

    @Test fun repeatedTableHeadersCannotExpandCandidatesBeyondParserBudgets() {
        val headers = (1..60).joinToString("") { row ->
            "<tr>" + (1..30).joinToString("") { column -> "<th>Header-$row-$column-${"h".repeat(330)}</th>" } + "</tr>"
        }
        val rows = (1..200).joinToString("") { row ->
            "<tr>" + (1..30).joinToString("") { "<td>value-$row-$it</td>" } + "</tr>"
        }
        val html = "<p>Outside meaningful evidence.</p><table><thead>$headers</thead><tbody>$rows</tbody></table>"
        assertTrue(html.toByteArray(StandardCharsets.UTF_8).size < 1_048_576)
        val result = extractBytes(html, "Outside meaningful evidence", 4_096)
        assertTrue(result.text.contains("Outside meaningful evidence."))
        assertFalse(result.text.contains("value-"))
        assertTrue(result.partial)
        assertTrue(result.text.contains("Partial:"))
        assertWithinBudget(result, 4_096)
    }

    @Test fun cumulativeCandidateBudgetAppliesBeforeRepeatedSectionExpansion() {
        val heading = "Context " + "h".repeat(8_000)
        val html = "<h2>$heading</h2>" + (1..300).joinToString("") { "<p>Passage $it.</p>" }
        val result = extractBytes(html, "Passage", 4_000_000)
        assertTrue(result.text.contains("Passage 1."))
        assertFalse(result.text.contains("Passage 300."))
        assertTrue(result.partial)
        assertWithinBudget(result, 1_048_576 + 1_024)
    }

    @Test fun explicitHeaderAssociationsAreSkippedRatherThanReversed() {
        val html = """
            <p>Outside evidence.</p><table>
              <tr><th id="winner">Winner</th><th id="runner">Runner-up</th></tr>
              <tr><td headers="runner">Alice</td><td headers="winner">Bob</td></tr>
            </table>
        """.trimIndent()
        val result = extractBytes(html, "Alice Winner", 1_024)
        assertTrue(result.text.contains("Outside evidence."))
        assertFalse(result.text.contains("Alice"))
        assertFalse(result.text.contains("Bob"))
        assertTrue(result.partial)
        assertTrue(result.text.contains("Partial:"))
        assertWithinBudget(result, 1_024)
    }

    @Test fun hiddenRowsGroupsAndCellsCannotLeakOrShiftHeaderAssociations() {
        for (attribute in listOf("hidden", "aria-hidden='true'", "style='display:none'", "style='visibility:hidden'", "class='noprint'")) {
            for (body in listOf(
                "<tbody><tr $attribute><td rowspan='2'>Alice</td><td>Bob</td></tr><tr><td>Carol</td></tr></tbody>",
                "<tbody $attribute><tr><td>Alice</td><td>Bob</td></tr></tbody><tbody><tr><td>Carol</td><td>Dave</td></tr></tbody>",
                "<tbody><tr><td $attribute>Alice</td><td>Bob</td></tr></tbody>",
            )) {
                val result = extractBytes("<p>Outside evidence.</p><table><thead><tr><th>Winner</th><th>Runner-up</th></tr></thead>$body</table>", "Winner", 1_024)
                assertTrue(result.text.contains("Outside evidence."))
                for (name in listOf("Alice", "Bob", "Carol", "Dave")) assertFalse(result.text.contains(name))
                assertTrue(result.partial)
                assertTrue(result.text.contains("Partial:"))
                assertWithinBudget(result, 1_024)
            }
        }
    }

    @Test fun visibleRowsKeepPhysicalHeadersAndHiddenInlineTextIsOmitted() {
        val result = extractBytes("<table><tr><th>Winner</th><th>Runner-up</th></tr><tr><td>Alice<span hidden>secret</span></td><td>Bob</td></tr></table>", "Alice", 1_024)
        assertTrue(result.text.contains("Winner: Alice"))
        assertTrue(result.text.contains("Runner-up: Bob"))
        assertFalse(result.text.contains("secret"))
        assertFalse(result.partial)
        assertWithinBudget(result, 1_024)
    }

    @Test fun lateRelevantPassageWinsWithinByteBudgetAndIsMarkedPartial() {
        val html = """
            <div class="mw-parser-output">
              <h2>General background</h2>
              <p>Unrelated boilerplate about the history of the surrounding subject.</p>
              <ul><li>General summary</li><li>Other topic<ul><li>Nested unrelated detail</li></ul></li></ul>
              <h2>Optical result</h2>
              <p>The chlorophyll absorption maximum is 680 nm under the stated conditions.</p>
            </div>
        """.trimIndent()

        val result = extractBytes(html, "chlorophyll absorption maximum", 220)

        assertTrue(result.text.contains("Optical result"))
        assertTrue(result.text.contains("680 nm"))
        assertFalse(result.text.contains("Unrelated boilerplate"))
        assertTrue(result.partial)
        assertTrue(result.text.contains("Partial:"))
        assertWithinBudget(result, 220)
    }

    @Test fun proseDirectBodyTextAndNestedListsKeepTheirStructure() {
        val html = """
            <div class="mw-parser-output">
              Introductory body text.
              <p>A separate prose paragraph.</p>
              <ol><li>First <em>neutral emphasis</em></li><li>Second<ul><li>Nested evidence</li></ul></li></ol>
            </div>
        """.trimIndent()

        val result = extractBytes(html, "Nested evidence", 1_024)

        assertTrue(result.text.contains("Introductory body text."))
        assertTrue(result.text.contains("A separate prose paragraph."))
        assertTrue(result.text.contains("1. First *neutral emphasis*"))
        assertTrue(result.text.contains("2. Second"))
        assertTrue(result.text.contains("  - Nested evidence"))
        assertFalse(result.partial)
        assertWithinBudget(result, 1_024)
    }

    @Test fun categoryEntryTableKeepsColumnAndRowHeaders() {
        val html = """
            <table class="wikitable">
              <caption>2024 Academy Awards (Oscars)</caption>
              <thead><tr><th>Category</th><th>Recipient</th></tr></thead>
              <tbody><tr><th scope="row">Best Picture</th><td>Oppenheimer</td></tr></tbody>
            </table>
        """.trimIndent()

        val result = extractBytes(html, "Oppenheimer Best Picture", 1_024)

        assertTrue(result.text.contains("Headers: Category | Recipient"))
        assertTrue(result.text.contains("Row header: Best Picture"))
        assertTrue(result.text.contains("Recipient: Oppenheimer"))
        assertTrue(result.text.contains("2024 Academy Awards (Oscars)"))
        assertFalse(result.partial)
        assertWithinBudget(result, 1_024)
    }

    @Test fun scientificTablePreservesSpansNestedListsAndNeutralEmphasisWithLegend() {
        val html = """
            <table>
              <caption>Element concentration</caption>
              <thead>
                <tr><th rowspan="2">Sample</th><th colspan="2">Concentration (mg/L)</th></tr>
                <tr><th>Control</th><th>Treatment</th></tr>
              </thead>
              <tbody>
                <tr><th scope="row" rowspan="2">Sample A</th><td>1.25</td><td><strong>2.50</strong><ul><li>Type A</li><li>Type B<ul><li>replicate</li></ul></li></ul></td></tr>
                <tr><td>1.50</td><td>3.00</td></tr>
                <tr><th scope="row">Sample B</th><td>0.5</td><td>0.75</td></tr>
              </tbody>
            </table>
            <p class="legend">Bold cells denote estimated values only.</p>
        """.trimIndent()

        val result = extractBytes(html, "Sample A 2.50 replicate treatment", 4_096)

        assertTrue(result.text.contains("Concentration (mg/L) / Control"))
        assertTrue(result.text.contains("Concentration (mg/L) / Treatment: **2.50**"))
        assertTrue(result.text.contains("Row header: Sample A"))
        assertTrue(result.text.contains("  - replicate"))
        assertTrue(result.text.contains("Bold cells denote estimated values only."))
        assertFalse(result.text.contains("winner"))
        assertFalse(result.partial)
        assertWithinBudget(result, 4_096)
    }

    @Test fun malformedHtmlIsRecoveredWhileHiddenReferencesAndEditJunkAreDropped() {
        val html = """
            <div class="mw-parser-output">
              <p>Visible &amp; safe <strong>bold</strong> <em>neutral</em><sup class="reference">[1]</sup>\u0000\u0001</p>
              <script>alert('must not appear')</script><style>.secret { color:red }</style>
              <div hidden>hidden fact</div><span aria-hidden="true">aria secret</span>
              <span style="display:none">style secret</span><nav>navigation secret</nav>
              <span class="mw-editsection">[edit]</span><a href="?action=edit">edit link</a>
              <p>Recovered <b>after</b>
            </div>
        """.trimIndent()

        val result = extractBytes(html, "Visible recovered", 1_024)

        assertTrue(result.text.contains("Visible & safe **bold** *neutral*"))
        assertTrue(result.text.contains("Recovered **after**"))
        for (hidden in listOf("must not appear", "secret", "[1]", "edit link", "[edit]")) assertFalse(result.text.contains(hidden))
        assertTrue(result.text.none { Character.isISOControl(it.code) && it != '\n' && it != '\t' })
        assertFalse(result.partial)
        assertWithinBudget(result, 1_024)
    }

    @Test fun nestedOrInvalidTablesAreSkippedRatherThanFlattened() {
        val nested = """
            <p>Usable prose remains available.</p>
            <table><tr><th>Field</th><th>Value</th></tr>
              <tr><th>Safe row</th><td>yes</td></tr>
              <tr><th>Nested row</th><td><table><tr><td>misleading nested value</td></tr></table></td></tr>
            </table>
        """.trimIndent()
        val nestedResult = extractBytes(nested, "Safe row misleading", 1_024)
        assertTrue(nestedResult.text.contains("Usable prose remains available."))
        assertFalse(nestedResult.text.contains("Safe row"))
        assertFalse(nestedResult.text.contains("misleading nested value"))
        assertTrue(nestedResult.partial)
        assertTrue(nestedResult.text.contains("Partial:"))

        for (badSpan in listOf("rowspan=0", "colspan=999999")) {
            val html = "<p>Safe passage.</p><table><tr><th>Header</th></tr><tr><td $badSpan>untrusted table cell</td></tr></table>"
            val result = extractBytes(html, "Safe passage", 1_024)
            assertTrue(result.text.contains("Safe passage."))
            assertFalse(result.text.contains("untrusted table cell"))
            assertTrue(result.partial)
        }

        val overwide = "<p>Outside table fact.</p><table><tr>${(1..31).joinToString("") { "<td>cell$it</td>" }}</tr></table>"
        val wideResult = extractBytes(overwide, "Outside table", 1_024)
        assertTrue(wideResult.text.contains("Outside table fact."))
        assertFalse(wideResult.text.contains("cell31"))
        assertTrue(wideResult.partial)

        val layout = "<div class='mw-parser-output'><div class='grid-row'><span>Temperature</span><span>20°C</span></div></div>"
        val layoutResult = extractBytes(layout, "Temperature 20°C", 1_024)
        assertTrue(layoutResult.partial)
        assertFalse(layoutResult.text.contains("Temperature 20°C"))
        assertTrue(layoutResult.text.contains("Temperature"))
        assertTrue(layoutResult.text.contains("20°C"))
    }

    @Test fun oversizedTablesAndInputHaveTruthfulPartialMarkers() {
        val rows = (1..301).joinToString("") { "<tr><td>row-$it</td></tr>" }
        val table = "<table><thead><tr><th>Item</th></tr></thead><tbody>$rows</tbody></table>"
        val tableResult = extractBytes(table, "row-301", 512)
        assertTrue(tableResult.partial)
        assertTrue(tableResult.text.contains("Partial:"))
        assertFalse(tableResult.text.contains("row-301"))
        assertWithinBudget(tableResult, 512)

        val oversized = "<p>Early evidence is preserved.</p>" + " ".repeat(1_048_600)
        val inputResult = extractBytes(oversized, "Early evidence", 256)
        assertTrue(inputResult.partial)
        assertTrue(inputResult.text.contains("Early evidence is preserved."))
        assertTrue(inputResult.text.contains("Partial:"))
        assertWithinBudget(inputResult, 256)
    }

    @Test fun unicodeCaseFoldingMatchesMultilingualFocus() {
        val html = "<p>Die Straße ist bekannt.</p><p>Élodie untersucht 研究者-Daten.</p>"
        val result = extractBytes(html, "STRASSE ÉLODIE 研究者", 1_024)

        assertTrue(result.text.contains("Straße"))
        assertTrue(result.text.contains("Élodie"))
        assertTrue(result.text.contains("研究者"))
        assertFalse(result.text.contains("Document-order fallback"))
        assertWithinBudget(result, 1_024)
    }

    @Test fun noMatchUsesLabeledDocumentOrderAndEmptyOrTooSmallEvidenceFailsExplicitly() {
        val fallback = extractBytes("<p>First passage.</p><p>Second passage.</p>", "unmatched intent", 1_024)
        assertTrue(fallback.text.contains("Document-order fallback"))
        assertTrue(fallback.text.indexOf("First passage") < fallback.text.indexOf("Second passage"))
        assertFalse(fallback.partial)

        val noContent = extractBytes("<nav>Navigation only</nav><script>active code</script>", "facts", 128)
        assertEquals("", noContent.text)
        assertNotNull(noContent.failureReason)

        val tooSmall = extractBytes("<p>Complete evidence.</p>", "evidence", 8)
        assertEquals("", tooSmall.text)
        assertTrue(tooSmall.partial)
        assertNotNull(tooSmall.failureReason)

        val unicodeHtml = "<p>東京の研究結果。</p>"
        val unicodeFull = extractBytes(unicodeHtml, "東京", 1_024)
        val exactBytes = unicodeFull.text.toByteArray(StandardCharsets.UTF_8).size
        val unicodeTooSmall = extractBytes(unicodeHtml, "東京", exactBytes - 1)
        assertEquals("", unicodeTooSmall.text)
        assertTrue(unicodeTooSmall.partial)
        assertNotNull(unicodeTooSmall.failureReason)
        assertTrue(extractBytes("<p>fact</p>", "fact", 0).text.isEmpty())
    }

    @Test fun traversalLimitNeverReturnsPartialChunkOrUnmarkedText() {
        val emptyNodes = "<span></span>".repeat(50_000)
        val html = "<div class='mw-parser-output'><p>$emptyNodes</p><p>Late evidence.</p></div>"

        val result = extractBytes(html, "Late evidence", 512)

        assertEquals("", result.text)
        assertTrue(result.partial)
        assertNotNull(result.failureReason)
        assertWithinBudget(result, 512)
    }

    // ---- Plan A1–A3, A5: item-level lists, context scoring, oversized rows, legends, infoboxes ----

    @Test fun listItemsAreSeparateCandidatesWithTheirLabelAsContext() {
        val filler = (1..40).joinToString("") { "<p>Unrelated background paragraph number $it about weather.</p>" }
        val html = "<h2>Members</h2><p><b>Principal members</b></p>" +
            "<ul><li>John Lennon – vocals, guitars</li><li>Paul McCartney – vocals, bass</li><li>George Harrison – guitars</li><li>Ringo Starr – drums</li></ul>$filler"
        val result = WikipediaContent.extract(html, "members instruments Lennon", 90)
        assertTrue(result.text, result.text.contains("John Lennon"))
        assertTrue(result.text, result.text.contains("List label: **Principal members**"))
        assertFalse(result.text.contains("Unrelated background"))
        assertTrue(result.partial)
        assertTrue(estimateTokens(result.text) <= 90)
    }

    @Test fun siblingsOfAPickedListItemFillRemainingBudgetInDocumentOrder() {
        val html = "<p><b>Principal members</b></p><ul><li>John Lennon – vocals</li><li>Paul McCartney – bass</li>" +
            "<li>George Harrison – guitars</li><li>Ringo Starr – drums</li></ul><p>${"Unrelated filler sentence. ".repeat(80)}</p>"
        val result = WikipediaContent.extract(html, "Lennon principal members", 140)
        for (name in listOf("Lennon", "McCartney", "Harrison", "Starr")) assertTrue("$name in ${result.text}", result.text.contains(name))
        assertTrue(result.text.indexOf("Lennon") < result.text.indexOf("McCartney"))
        assertFalse(result.text.contains("Unrelated filler"))
    }

    @Test fun nestedListItemsCarryTheirParentAsContext() {
        val html = "<ul><li>Best Actor<ul><li>Alice – Film One</li><li>Bob – Film Two</li></ul></li></ul><p>${"filler words here. ".repeat(80)}</p>"
        val result = WikipediaContent.extract(html, "Best Actor Alice", 60)
        assertTrue(result.text, result.text.contains("Alice – Film One"))
        assertTrue(result.text, result.text.contains("Parent item: Best Actor"))
    }

    @Test fun oversizedRowSplitsIntoCellItemsAndKeepsAContiguousPrefixWithTheLegend() {
        val items = { name: String -> "<ul><li><b>Winner $name – <i>Film $name</i> ‡</b><ul>" +
            (1..4).joinToString("") { "<li>Nominee $name$it – Movie $name$it</li>" } + "</ul></li></ul>" }
        val html = "<h3>Awards</h3><p>Winners are listed first and marked with a double dagger (‡).</p>" +
            "<table class=\"wikitable\"><tr><td><div><b>Best Actor</b></div>${items("A")}</td><td><div><b>Best Actress</b></div>${items("B")}</td></tr></table>"
        val full = WikipediaContent.extract(html, "Best Actor Best Actress winners films", 4_000)
        assertFalse(full.partial)
        val result = WikipediaContent.extract(html, "Best Actor Best Actress winners films", 150)
        assertTrue(result.text, result.text.contains("Winner A"))
        assertTrue(result.text, result.text.contains("Winners are listed first"))
        assertTrue(result.text, result.text.contains("‡"))
        assertTrue(result.partial)
        // Contiguous prefix: nominee N is only present when every earlier item of the same cell is.
        val present = (1..4).map { result.text.contains("Nominee A$it ") }
        assertEquals(present.sortedDescending(), present)
        assertTrue(estimateTokens(result.text) <= 150)
    }

    @Test fun legendParagraphDirectlyAboveATableIsEmittedOnceAheadOfSelectedRows() {
        val rows = (1..6).joinToString("") { "<tr><td>Row$it value ${"detail ".repeat(10)}</td></tr>" }
        val html = "<h3>Results</h3><p>Winners are marked ‡ in this table.</p><table><tr><th>Item</th></tr>$rows</table>"
        val result = WikipediaContent.extract(html, "Row2 Row4", 150)
        assertEquals(result.text, 1, Regex("Winners are marked").findAll(result.text).count())
        assertTrue(result.text, result.text.indexOf("Winners are marked") < result.text.indexOf("Row2"))
        assertTrue(estimateTokens(result.text) <= 150)
        val sectionHeaderOnly = WikipediaContent.extract("<h3>Results</h3><p>Prose a long way off ${"x ".repeat(400)}</p><p>${"y ".repeat(300)}</p><table><tr><th>Item</th></tr>$rows</table>", "Row2", 150)
        assertFalse(sectionHeaderOnly.text.contains("Winners are marked"))
    }

    @Test fun infoboxRowsBecomeLabelValueWithHeaderContextAndNestedRowsAreDroppedAlone() {
        val html = """
            <table class="infobox"><tbody>
              <tr><th colspan="2">Tokyo</th></tr>
              <tr><td colspan="2" class="maptable"><table><tr><td>map gallery</td></tr></table></td></tr>
              <tr><th scope="row">Country</th><td>Japan</td></tr>
              <tr><th colspan="2" class="infobox-header">Population (May 2025)</th></tr>
              <tr><th scope="row">&#160;•&#160;Total</th><td>14,254,039</td></tr>
              <tr><th scope="row">&#160;•&#160;Rank</th><td>1st in Japan</td></tr>
            </tbody></table>
            <p>${"Long unrelated prose about history and weather. ".repeat(60)}</p>
        """.trimIndent()
        val result = WikipediaContent.extract(html, "population", 60)
        assertTrue(result.text, result.text.contains("Total: 14,254,039"))
        assertTrue(result.text, result.text.contains("Infobox: Population (May 2025)"))
        assertFalse(result.text.contains("map gallery"))
        assertFalse(result.text.contains("Long unrelated prose"))
        val all = WikipediaContent.extract(html, "population", 4_000)
        assertTrue(all.text, all.text.contains("Country: Japan"))
        assertTrue(all.partial) // the dropped map row is reported
    }

    /** Legacy tests were tuned in UTF-8 bytes; 3 ASCII bytes ~ 1 estimated token, so the same relative budget keeps their intent. */
    private fun tokensFor(bytes: Int) = (bytes + 2) / 3

    private fun extractBytes(html: String, focus: String, bytes: Int) = WikipediaContent.extract(html, focus, tokensFor(bytes))

    private fun assertWithinBudget(result: WikipediaContentResult, maxUtf8Bytes: Int) {
        assertTrue(estimateTokens(result.text) <= tokensFor(maxUtf8Bytes))
    }
}
