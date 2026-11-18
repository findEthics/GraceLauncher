package com.galaxyrio.gracelauncher.assistant

import java.text.Normalizer
import java.util.Locale
import java.util.TreeMap
import kotlin.math.ln
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

internal data class WikipediaContentResult(
    val text: String,
    val partial: Boolean,
    val failureReason: String? = null,
)

internal object WikipediaContent {
    private const val MAX_INPUT_UTF8_BYTES = 1_048_576
    private const val MAX_VISITED_NODES = 40_000
    private const val MAX_DEPTH = 128
    private const val MAX_LIST_DEPTH = 16
    private const val MAX_CHUNK_UTF8_BYTES = 16_384
    private const val MAX_CANDIDATES = 512
    private const val MAX_CANDIDATE_UTF8_BYTES = 1_048_576
    private const val MAX_TABLE_ROWS = 300
    private const val MAX_TABLE_COLUMNS = 30

    private const val PARTIAL_MARKER = "[Partial: content omitted by byte/parser limits or unsupported-layout handling; passages or rows may be missing.]"
    private const val FALLBACK_MARKER = "[Document-order fallback: focus was empty or had no lexical overlap.]"

    private val tokenPattern = Regex("[\\p{L}\\p{M}\\p{N}]+")
    private val notePrefix = Regex("^\\s*(?:notes?|legend|key|source|footnotes?)\\s*[:\\-]", RegexOption.IGNORE_CASE)
    private val hiddenStyle = Regex("(?:^|;)\\s*(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)\\b", RegexOption.IGNORE_CASE)
    private val skippedTags = setOf(
        "script", "style", "noscript", "template", "nav", "header", "footer", "aside", "form",
        "button", "input", "select", "textarea", "svg", "math", "iframe", "object", "embed",
        "video", "audio", "canvas", "source", "track",
    )
    private val skippedClasses = setOf(
        "reference", "references", "reflist", "mw-references-wrap", "mw-editsection", "mw-editsection-like",
        "navbox", "vertical-navbox", "metadata", "noprint", "sistersitebox", "portal", "catlinks",
        "mw-indicators", "ambox", "authority-control", "printfooter",
    )
    private val skippedIds = setOf("toc", "mw-navigation", "mw-panel", "mw-footer", "mw-head", "contentsub", "sitesub", "catlinks")
    private val blockContainers = setOf(
        "p", "blockquote", "pre", "table", "ul", "ol", "dl", "li", "div", "section", "article", "main",
        "figure", "figcaption", "h1", "h2", "h3", "h4", "h5", "h6", "nav", "header", "footer", "aside",
    )
    private val blockChildren = setOf(
        "p", "blockquote", "pre", "table", "ul", "ol", "dl", "li", "div", "section", "article", "main",
        "figure", "figcaption", "h1", "h2", "h3", "h4", "h5", "h6", "br", "tr", "td", "th", "caption",
    )
    private val rowGroups = setOf("thead", "tbody", "tfoot")
    private val columnScopes = setOf("col", "colgroup")
    private val rowScopes = setOf("row", "rowgroup")
    private val listMarker = Regex("^( *)(?:- |\\d+\\. )(.*)$")
    private const val MAX_LABEL_UTF8_BYTES = 200
    private const val MAX_LEGEND_UTF8_BYTES = 300

    /** [maxTokens] is an estimated-token budget (see [estimateTokens]); parser safety bounds below stay in UTF-8 bytes. */
    fun extract(html: String, focus: String, maxTokens: Int): WikipediaContentResult {
        if (maxTokens <= 0) return WikipediaContentResult("", true, "A positive token budget is required.")

        val boundedHtml = boundInput(html)
        if (boundedHtml.text.isBlank()) {
            return WikipediaContentResult(
                "",
                boundedHtml.truncated,
                if (html.isBlank()) "HTML input is empty." else "No readable article content was found.",
            )
        }

        val document = try {
            Jsoup.parse(boundedHtml.text)
        } catch (_: Exception) {
            return WikipediaContentResult("", true, "HTML could not be parsed safely.")
        }

        val markerTokens = estimateTokens(PARTIAL_MARKER)
        val state = ScanState(partial = boundedHtml.truncated, rowLimit = maxTokens - markerTokens)
        val root = findContentRoot(document.body(), state) ?: document.body()
        val budget = TraversalBudget()
        val candidates = ArrayList<Candidate>()
        val headings = TreeMap<Int, String>()
        walkBlocks(root, 0, headings, candidates, budget, state)

        if (candidates.isEmpty()) {
            return WikipediaContentResult(
                "",
                state.partial,
                if (state.partial) "No complete evidence chunk was recovered within parser limits." else "No readable article content was found.",
            )
        }

        val boundedFocus = focus.take(4_096)
        if (boundedFocus.length != focus.length) state.partial = true
        val focusTerms = tokens(boundedFocus)
        fun overlap(candidate: Candidate) = focusTerms.count { it in candidate.evidenceTerms }
        // Body or context (section path, caption, headers, row header, list label) may satisfy the lexical gate.
        val noOverlap = candidates.none { overlap(it) > 0 }
        val complete = compose(candidates, noOverlap, state.partial)
        if (estimateTokens(complete) <= maxTokens) {
            return WikipediaContentResult(complete, state.partial)
        }

        val contentBudget = maxTokens - markerTokens
        val feasible = candidates.filter { it.standaloneCost() <= contentBudget }
        val gate = minOf(2, feasible.maxOfOrNull(::overlap) ?: 0)
        val fallback = gate == 0
        val plan: Plan
        if (fallback) {
            plan = Plan(candidates, contentBudget - estimateTokens(FALLBACK_MARKER) - 1)
            candidates.forEach { plan.tryAdd(it) }
        } else {
            plan = Plan(candidates, contentBudget)
            val eligible = feasible.filter { overlap(it) >= gate }
            selectCoverage(candidates, eligible, focusTerms, plan)
            fillSiblings(eligible, plan, ::overlap) { c -> focusTerms.count { it in c.bodyTerms } }
        }

        // The plan is an estimate of compose(); verify the exact text and trim the last additions if needed.
        while (plan.selected.isNotEmpty()) {
            val selected = plan.selected
            val partial = state.partial || selected.size < candidates.size
            val output = compose(selected, fallback, partial)
            if (estimateTokens(output) <= maxTokens) return WikipediaContentResult(output, partial)
            plan.dropLast()
        }
        return WikipediaContentResult("", true, "No complete evidence chunk fits the token budget.")
    }

    /** Greedy weighted coverage; a body match supports a term at 1.0, a context-only match (heading, caption, header, label) at 0.5 when no body states the term. */
    private fun selectCoverage(
        candidates: List<Candidate>,
        eligible: List<Candidate>,
        focusTerms: Set<String>,
        plan: Plan,
    ) {
        val terms = focusTerms.toList()
        val weights = terms.map { term ->
            val df = candidates.count { term in it.evidenceTerms }
            ln(1.0 + (candidates.size - df + 0.5) / (df + 0.5))
        }
        val averageLength = candidates.map { it.bodyLength }.average().takeIf { it > 0 } ?: 1.0
        // Context (heading, caption, header, label) only stands in for a term that no candidate's own text contains;
        // otherwise a cheap fragment under a matching heading would outrank the passage that actually states the fact.
        val bodyMatched = terms.filterTo(HashSet()) { term -> candidates.any { term in it.bodyTerms } }
        fun support(candidate: Candidate, term: String): Double {
            val match = when {
                term in candidate.bodyTerms -> 1.0
                term in candidate.contextTerms && term !in bodyMatched -> 0.5
                else -> 0.0
            }
            return match / (1.0 + 1.2 * (0.5 + 0.5 * candidate.bodyLength / averageLength))
        }
        val supports = eligible.associateWith { candidate -> terms.map { support(candidate, it) } }
        val best = DoubleArray(terms.size)
        while (true) {
            var winner: Candidate? = null
            var winnerGain = 0.0
            var winnerCost = 0
            for (candidate in eligible) {
                if (plan.contains(candidate)) continue
                val cost = plan.marginal(candidate)
                if (plan.spent + cost > plan.budget) continue
                val gain = terms.indices.sumOf { weights[it] * maxOf(0.0, supports.getValue(candidate)[it] - best[it]) }
                if (gain <= 0.0) continue
                val comparison = if (winner == null) 1 else (gain * winnerCost).compareTo(winnerGain * cost)
                if (comparison > 0 || (comparison == 0 && (gain > winnerGain ||
                        (gain == winnerGain && candidate.order < winner!!.order)))) {
                    winner = candidate
                    winnerGain = gain
                    winnerCost = cost
                }
            }
            val chosen = winner ?: break
            plan.tryAdd(chosen)
            for (index in terms.indices) best[index] = maxOf(best[index], supports.getValue(chosen)[index])
        }
        val singleton = eligible.minWithOrNull(compareByDescending<Candidate> { candidate ->
            terms.indices.sumOf { weights[it] * supports.getValue(candidate)[it] }
        }.thenBy { it.standaloneCost() }.thenBy { it.order }) ?: return
        val singleValue = terms.indices.sumOf { weights[it] * supports.getValue(singleton)[it] }
        val greedyValue = terms.indices.sumOf { weights[it] * best[it] }
        if (singleValue > greedyValue || (singleValue == greedyValue && plan.costOfSingle(singleton) < plan.spent)) {
            plan.reset()
            plan.tryAdd(singleton)
        }
    }

    /**
     * Leftover budget: first siblings of picked items (same list / split row group, document order, prefix groups stop at the
     * first miss), then any other eligible candidate by overlap then document order. Coverage alone stops once every focus
     * term is matched by a short caption, which would otherwise leave the table row that carries the facts unread.
     */
    private fun fillSiblings(eligible: List<Candidate>, plan: Plan, overlap: (Candidate) -> Int, bodyOverlap: (Candidate) -> Int) {
        val eligibleSet = eligible.toHashSet()
        val groups = plan.selected.filter { it.group >= 0 }.map { it.group }.distinct()
        for (group in groups) {
            val members = plan.selected.firstOrNull { it.group == group }?.let(plan::siblings).orEmpty()
            for (member in members) {
                if (plan.contains(member) || member !in eligibleSet) continue
                if (!plan.tryAdd(member) && member.prefix) break
            }
        }
        // Leftover pass: never a repeat of selected text. Grouped items (list items, table/infobox rows) may qualify through their
        // label/header context; a plain passage must match in its own text, so a bare heading match does not pull in filler.
        for (candidate in eligible.filter { bodyOverlap(it) > 0 || (it.group >= 0 && overlap(it) > 0) }.sortedWith(compareByDescending<Candidate> { overlap(it) }.thenBy { it.order })) {
            if (plan.contains(candidate) || plan.selected.any { it.content == candidate.content }) continue
            plan.tryAdd(candidate)
        }
    }

    private fun findContentRoot(body: Element, state: ScanState): Element? {
        var visited = 0
        var limitReached = false

        fun find(element: Element, depth: Int): Element? {
            if (depth > MAX_DEPTH) {
                limitReached = true
                return null
            }
            if (isSkipped(element)) return null
            if (visited >= MAX_VISITED_NODES) {
                limitReached = true
                return null
            }
            visited++
            if (element.hasClass("mw-parser-output")) return element
            for (child in element.children()) {
                find(child, depth + 1)?.let { return it }
                if (limitReached) return null
            }
            return null
        }

        val found = find(body, 0)
        if (limitReached) state.partial = true
        return found
    }

    private fun walkBlocks(
        node: Node,
        depth: Int,
        headings: TreeMap<Int, String>,
        candidates: MutableList<Candidate>,
        budget: TraversalBudget,
        state: ScanState,
    ) {
        if (state.stopped) return
        if (depth > MAX_DEPTH) {
            state.partial = true
            return
        }
        if (!budget.take()) {
            state.partial = true
            state.stopped = true
            return
        }
        val element = node as? Element ?: return
        if (isSkipped(element)) return

        val tag = element.tagName().lowercase(Locale.ROOT)
        val headingLevel = tag.takeIf { it.length == 2 && it[0] == 'h' }?.get(1)?.digitToIntOrNull()
        if (headingLevel != null && headingLevel in 1..6) {
            val rendered = renderInline(element, budget, state)
            if (rendered.complete && hasMeaningfulText(rendered.text)) {
                headings.keys.removeAll { it >= headingLevel }
                headings[headingLevel] = rendered.text
            } else if (!rendered.complete) {
                state.partial = true
            }
            return
        }

        when (tag) {
            "p", "blockquote", "pre" -> {
                addParagraph(element, tag == "p", headings, candidates, budget, state)
                return
            }
            "ul", "ol", "dl" -> {
                addListCandidates(element, headings, candidates, budget, state)
                return
            }
            "table" -> {
                addTableCandidates(element, headings, candidates, budget, state)
                return
            }
        }

        if (tag in setOf("body", "div", "section", "article", "main") &&
            element.children().isNotEmpty() &&
            element.childNodes().filterIsInstance<TextNode>().none { hasMeaningfulText(it.wholeText) }
        ) {
            if (tag == "div" && element.children().count { child ->
                    child.tagName().lowercase(Locale.ROOT) !in blockContainers && !isSkipped(child)
                } > 1
            ) state.partial = true
            for (child in element.childNodes()) {
                if (!budget.take()) {
                    state.stopped = true
                    return
                }
                if (child is Element) walkBlocks(child, depth + 1, headings, candidates, budget, state)
                if (state.stopped) return
            }
            return
        }

        val inlineRun = ArrayList<Node>()
        fun flushInlineRun() {
            if (inlineRun.isEmpty() || state.stopped) {
                inlineRun.clear()
                return
            }
            val rendered = renderNodes(inlineRun, budget, state)
            inlineRun.clear()
            if (rendered.complete) {
                addCandidate(rendered.text, headings, candidates, state)
            } else {
                state.partial = true
            }
        }

        for (child in element.childNodes()) {
            if (!budget.take()) {
                state.partial = true
                state.stopped = true
                inlineRun.clear()
                return
            }
            val childElement = child as? Element
            if (childElement != null && childElement.tagName().lowercase(Locale.ROOT) in blockContainers) {
                flushInlineRun()
                walkBlocks(childElement, depth + 1, headings, candidates, budget, state)
                if (state.stopped) return
            } else {
                inlineRun += child
            }
        }
        flushInlineRun()
    }

    private fun addParagraph(
        element: Element,
        remember: Boolean,
        headings: TreeMap<Int, String>,
        candidates: MutableList<Candidate>,
        budget: TraversalBudget,
        state: ScanState,
    ) {
        val rendered = renderInline(element, budget, state)
        if (!rendered.complete) {
            state.partial = true
            return
        }
        val added = addCandidate(rendered.text, headings, candidates, state)
        if (remember) {
            state.lastParagraph = element
            state.lastParagraphText = rendered.text
            state.lastParagraphCandidate = added
        }
    }

    /** Every list item (and dt/dd pair) is its own candidate sharing one group, label and parent context. */
    private fun addListCandidates(
        list: Element,
        headings: TreeMap<Int, String>,
        candidates: MutableList<Candidate>,
        budget: TraversalBudget,
        state: ScanState,
    ) {
        val items = ArrayList<ListItem>()
        if (!collectListItems(list, budget, state, 0, null, items)) state.partial = true
        if (items.isEmpty()) return
        val previous = previousContentSibling(list)
        val label = if (previous != null && previous === state.lastParagraph &&
            utf8Length(state.lastParagraphText) <= MAX_LABEL_UTF8_BYTES
        ) state.lastParagraphText.replace(Regex("\\s+"), " ").trim() else null
        val group = state.nextGroup++
        // The label paragraph is the head of its list's group, so a label match pulls the items in.
        if (label != null) state.lastParagraphCandidate?.takeIf { it.group < 0 }?.group = group
        for (item in items) {
            if (state.stopped) return
            addCandidate(
                item.text, headings, candidates, state,
                context = listOfNotNull(label?.let { "List label: $it" }, item.parent?.let { "Parent item: $it" }),
                contextSources = listOfNotNull(label, item.parent),
                group = group, keepIndent = true,
            )
        }
    }

    private fun collectListItems(
        list: Element,
        budget: TraversalBudget,
        state: ScanState,
        listDepth: Int,
        parent: String?,
        out: MutableList<ListItem>,
    ): Boolean {
        if (listDepth >= MAX_LIST_DEPTH || !budget.take()) {
            state.partial = true
            if (budget.exhausted) state.stopped = true
            return false
        }
        var complete = true
        if (list.tagName().equals("dl", ignoreCase = true)) {
            var term = ""
            for (child in list.children()) {
                val rendered = renderNode(child, budget, state, 1, 0)
                if (!rendered.complete) complete = false
                val value = normalizeBlock(rendered.text)
                when (child.tagName().lowercase(Locale.ROOT)) {
                    "dt" -> term = value
                    "dd" -> if (value.isNotBlank()) out += ListItem(if (term.isBlank()) value else "$term: $value", parent)
                }
                if (state.stopped) return false
            }
            return complete
        }
        var number = list.attr("start").toIntOrNull() ?: 1
        val ordered = list.tagName().equals("ol", ignoreCase = true)
        for (item in list.children().filter { it.tagName().equals("li", ignoreCase = true) }) {
            if (!budget.take()) {
                state.partial = true
                state.stopped = true
                return false
            }
            val own = TextBuilder(MAX_CHUNK_UTF8_BYTES)
            val nestedLists = ArrayList<Element>()
            var itemComplete = true
            for (child in item.childNodes()) {
                val childElement = child as? Element
                if (childElement != null && childElement.tagName().lowercase(Locale.ROOT) in setOf("ul", "ol", "dl")) {
                    nestedLists.add(childElement)
                } else {
                    val block = childElement?.tagName()?.lowercase(Locale.ROOT) in blockChildren
                    if (block) own.newline()
                    val rendered = renderNode(child, budget, state, 1, listDepth)
                    if (!rendered.complete) itemComplete = false
                    if (!own.append(rendered.text)) itemComplete = false
                    if (block && rendered.text.isNotEmpty()) own.newline()
                }
                if (state.stopped) return false
            }
            val itemText = normalizeBlock(own.text())
            val marker = if (ordered) "${item.attr("value").toIntOrNull() ?: number}. " else "- "
            if (ordered) number = (item.attr("value").toIntOrNull() ?: number) + 1
            if (!itemComplete) {
                complete = false
                state.partial = true
            } else if (itemText.isNotBlank()) {
                val indent = "  ".repeat(listDepth)
                out += ListItem(itemText.split('\n').mapIndexed { index, line ->
                    if (index == 0) "$indent$marker$line" else "$indent  $line"
                }.joinToString("\n"), parent)
            }
            val here = itemText.lineSequence().firstOrNull().orEmpty().replace(Regex("\\s+"), " ").trim()
                .let { AssistantController.utf8Prefix(it, MAX_LABEL_UTF8_BYTES) }.ifBlank { parent }
            for (nested in nestedLists) if (!collectListItems(nested, budget, state, listDepth + 1, here, out)) complete = false
            if (state.stopped) return false
        }
        return complete
    }

    private fun stripListMarker(text: String): String {
        val lines = text.lines()
        val first = listMarker.find(lines.first())?.groupValues?.get(2) ?: return text
        return (listOf(first) + lines.drop(1).map(String::trim)).joinToString("\n")
    }

    /** Nearest preceding sibling that is real content (skips template styles/links and hidden nodes). */
    private fun previousContentSibling(element: Element): Element? {
        var sibling = element.previousElementSibling()
        var guard = 0
        while (sibling != null && guard++ < 16) {
            val tag = sibling.tagName().lowercase(Locale.ROOT)
            if (tag in setOf("style", "link", "meta") || isSkipped(sibling)) sibling = sibling.previousElementSibling() else return sibling
        }
        return null
    }

    private fun addCandidate(
        body: String,
        headings: TreeMap<Int, String>,
        candidates: MutableList<Candidate>,
        state: ScanState,
        context: List<String> = emptyList(),
        contextSources: List<String> = emptyList(),
        group: Int = -1,
        prefix: Boolean = false,
        keepIndent: Boolean = false,
        legend: String? = null,
        legendKey: Int = -1,
        legendSource: Candidate? = null,
    ): Candidate? {
        val content = if (keepIndent) body.trimEnd() else normalizeBlock(body)
        if (!hasMeaningfulText(content)) return null
        if (candidates.size >= MAX_CANDIDATES) {
            state.partial = true
            state.stopped = true
            return null
        }

        val sectionBytes = headings.values.sumOf(::utf8Length) + maxOf(0, headings.size - 1) * 3
        val contextLines = context.filter(String::isNotBlank)
        val bytes = utf8Length(content) + contextLines.sumOf { utf8Length(it) + 1 } +
            if (sectionBytes > 0) sectionBytes + 10 else 0
        if (bytes > MAX_CHUNK_UTF8_BYTES || bytes > MAX_CANDIDATE_UTF8_BYTES - state.candidateBytes) {
            state.partial = true
            return null
        }
        val section = headings.values.joinToString(" > ")
        val header = buildList {
            if (section.isNotBlank()) add("Section: $section")
            addAll(contextLines)
        }
        state.candidateBytes += bytes
        val bodyTokens = tokenPattern.findAll(fold(content)).map { it.value }.toList()
        val contextTerms = (listOf(section) + contextSources).flatMapTo(HashSet()) { tokens(it) }
        val candidate = Candidate(candidates.size, header, content, bodyTokens.toSet(), contextTerms, bodyTokens.size,
            group, prefix, legend, legendKey, legendSource)
        candidates += candidate
        return candidate
    }

    private fun addTableCandidates(
        table: Element,
        headings: TreeMap<Int, String>,
        candidates: MutableList<Candidate>,
        budget: TraversalBudget,
        state: ScanState,
    ) {
        if (table.classNames().any { it.lowercase(Locale.ROOT).contains("infobox") }) {
            addInfoboxCandidates(table, headings, candidates, budget, state)
            return
        }
        val scan = scanTable(table, budget, state)
        if (!scan.complete || scan.nestedTable || state.stopped) {
            state.partial = true
            return
        }
        if (scan.rowLimitReached) state.partial = true
        val rows = scan.rows
        if (rows.isEmpty()) return

        val groups = rows.map { rowGroup(it, table) }
        val grid = Array(rows.size) { arrayOfNulls<TableCell>(MAX_TABLE_COLUMNS) }
        var columnCount = 0
        var tableBytes = 0

        for (rowIndex in rows.indices) {
            val cells = tableCells(rows[rowIndex])
            var column = 0
            for (cellElement in cells) {
                while (column < MAX_TABLE_COLUMNS && grid[rowIndex][column] != null) column++
                val rowSpan = span(cellElement, "rowspan", MAX_TABLE_ROWS)
                val columnSpan = span(cellElement, "colspan", MAX_TABLE_COLUMNS)
                if (rowSpan == null || columnSpan == null || column >= MAX_TABLE_COLUMNS || column + columnSpan > MAX_TABLE_COLUMNS ||
                    rowIndex + rowSpan > rows.size || (rowIndex until rowIndex + rowSpan).any { groups[it] !== groups[rowIndex] }
                ) {
                    state.partial = true
                    return
                }

                val rendered = renderInline(cellElement, budget, state)
                if (!rendered.complete || state.stopped) {
                    state.partial = true
                    return
                }
                tableBytes += utf8Length(rendered.text)
                if (tableBytes > MAX_CANDIDATE_UTF8_BYTES) {
                    state.partial = true
                    return
                }
                val cell = TableCell(
                    element = cellElement,
                    text = normalizeBlock(rendered.text),
                    isHeader = cellElement.tagName().equals("th", ignoreCase = true),
                    row = rowIndex,
                    column = column,
                    rowSpan = rowSpan,
                    columnSpan = columnSpan,
                    scope = cellElement.attr("scope").lowercase(Locale.ROOT),
                )
                for (r in rowIndex until rowIndex + rowSpan) {
                    for (c in column until column + columnSpan) {
                        if (grid[r][c] != null) {
                            state.partial = true
                            return
                        }
                        grid[r][c] = cell
                    }
                }
                column += columnSpan
                columnCount = maxOf(columnCount, column)
            }
        }

        val headerRows = rows.indices.filterTo(mutableSetOf()) { index ->
            val cells = tableCells(rows[index])
            val inHead = hasRowGroup(rows[index], table, "thead")
            val hasColumnScope = cells.any { it.attr("scope").lowercase(Locale.ROOT) in columnScopes }
            val hasRowScope = cells.any { it.attr("scope").lowercase(Locale.ROOT) in rowScopes }
            val allHeaderCells = cells.isNotEmpty() && cells.all { it.tagName().equals("th", ignoreCase = true) }
            inHead || hasColumnScope || (allHeaderCells && !hasRowScope)
        }

        // Bound repeated/spanning header context before any label joins or row expansion.
        var headerBytes = 0
        for (row in headerRows) {
            for (column in 0 until columnCount) {
                headerBytes += (grid[row][column]?.text?.let(::utf8Length) ?: 0) + 3
                if (headerBytes > MAX_CHUNK_UTF8_BYTES / 2) {
                    state.partial = true
                    return
                }
            }
        }

        val caption = table.children().firstOrNull { it.tagName().equals("caption", ignoreCase = true) }
            ?.let { renderInline(it, budget, state) }
        if (caption != null && !caption.complete) state.partial = true
        val tableNotes = tableNotes(table, budget, state)
        // A short paragraph directly above the table (no heading between) is its legend, shown once ahead of the first selected row.
        val legendParagraph = previousContentSibling(table)?.takeIf {
            it === state.lastParagraph && utf8Length(state.lastParagraphText) <= MAX_LEGEND_UTF8_BYTES
        }?.let { state.lastParagraphText }
        val legend = legendParagraph?.let { "Note or legend: $it" }
        val legendKey = if (legend != null) state.nextTable++ else -1
        val legendSource = if (legend != null) state.lastParagraphCandidate else null
        val notes = tableNotes.filterNot { legendParagraph != null && it.endsWith(legendParagraph) }

        for (rowIndex in rows.indices) {
            if (rowIndex in headerRows) continue
            if (state.stopped) return

            val rowHeaders = LinkedHashSet<String>()
            val dataCells = ArrayList<Pair<TableCell, Int>>()
            var column = 0
            while (column < columnCount) {
                val cell = grid[rowIndex][column]
                if (cell == null) {
                    column++
                    continue
                }
                if (cell.row in headerRows) {
                    column = maxOf(column + 1, cell.column + cell.columnSpan)
                    continue
                }
                val rowHeader = cell.isHeader && (
                    cell.scope in rowScopes || (cell.column == 0 && cell.scope !in columnScopes)
                )
                if (rowHeader) {
                    if (cell.text.isNotBlank()) rowHeaders += cell.text
                } else if (dataCells.none { it.first === cell }) {
                    dataCells += cell to column
                }
                column = maxOf(column + 1, cell.column + cell.columnSpan)
            }

            fun labelOf(cell: TableCell) = (cell.column until cell.column + cell.columnSpan).map { col ->
                columnHeader(grid, headerRows, rowIndex, col).ifBlank { "Column ${col + 1}" }
            }.distinct().joinToString(" + ")

            val mappings = TextBuilder(MAX_CHUNK_UTF8_BYTES)
            if (rowHeaders.isNotEmpty()) {
                mappings.append("Row header: ")
                for ((index, header) in rowHeaders.withIndex()) {
                    if ((index > 0 && !mappings.append(" / ")) || !mappings.append(header)) {
                        state.partial = true
                        return
                    }
                }
            }
            for ((cell, _) in dataCells) {
                if (cell.text.isBlank()) continue
                if ((mappings.isNotEmpty && !mappings.append("\n")) ||
                    !mappings.append(labelOf(cell)) || !mappings.append(": ") || !mappings.append(cell.text.replace("\n", "\n  "))
                ) {
                    state.partial = true
                    return
                }
            }
            if (!mappings.isNotEmpty) continue

            val headers = (0 until columnCount).map { col -> columnHeader(grid, headerRows, rowIndex, col) }
            val context = buildList {
                caption?.text?.takeIf(String::isNotBlank)?.let { add("Table caption: $it") }
                if (headers.any(String::isNotBlank)) add("Headers: ${headers.mapIndexed { i, h -> h.ifBlank { "Column ${i + 1}" } }.joinToString(" | ")}")
                addAll(notes)
            }
            val sources = buildList {
                caption?.text?.let(::add); addAll(headers); addAll(rowHeaders)
            }
            val rowText = mappings.text()
            if (estimateTokens((context + rowText).joinToString("\n")) + 1 <= state.rowLimit) {
                addCandidate(rowText, headings, candidates, state, context, sources,
                    legend = legend, legendKey = legendKey, legendSource = legendSource)
                if (state.stopped) return
                continue
            }

            // Oversized row: one candidate per cell; list cells become one contiguous-prefix group of items.
            val rowContext = context + listOfNotNull(rowHeaders.takeIf { it.isNotEmpty() }?.let { "Row header: ${it.joinToString(" / ")}" })
            for ((cell, _) in dataCells) {
                if (cell.text.isBlank()) continue
                val label = labelOf(cell)
                val lists = cell.element.children().filter { it.tagName().lowercase(Locale.ROOT) in setOf("ul", "ol", "dl") }
                if (lists.isEmpty()) {
                    addCandidate("$label: ${cell.text.replace("\n", "\n  ")}", headings, candidates, state, rowContext, sources + label,
                        legend = legend, legendKey = legendKey, legendSource = legendSource)
                    if (state.stopped) return
                    continue
                }
                val leadingNodes = cell.element.childNodes().takeWhile { !(it is Element && it.tagName().lowercase(Locale.ROOT) in setOf("ul", "ol", "dl")) }
                val leading = renderNodes(leadingNodes, budget, state).takeIf { it.complete }?.text
                    ?.replace(Regex("\\s+"), " ")?.trim().orEmpty().ifBlank { label }
                val items = ArrayList<ListItem>()
                for (list in lists) if (!collectListItems(list, budget, state, 0, null, items)) state.partial = true
                val group = state.nextGroup++
                for (item in items) {
                    if (state.stopped) return
                    addCandidate("$leading: ${stripListMarker(item.text)}", headings, candidates, state, rowContext, sources + leading, group = group, prefix = true,
                        legend = legend, legendKey = legendKey, legendSource = legendSource)
                }
            }
        }
    }

    /** Infobox rows become `Label: value` candidates; full-width header rows become context for the rows that follow. */
    private fun addInfoboxCandidates(
        table: Element,
        headings: TreeMap<Int, String>,
        candidates: MutableList<Candidate>,
        budget: TraversalBudget,
        state: ScanState,
    ) {
        val rows = ArrayList<Element>()
        fun collect(element: Element, depth: Int) {
            for (child in element.children()) {
                if (!budget.take()) {
                    state.partial = true
                    state.stopped = true
                    return
                }
                if (isSkipped(child)) continue
                val tag = child.tagName().lowercase(Locale.ROOT)
                if (tag == "tr") {
                    if (rows.size >= MAX_TABLE_ROWS) state.partial = true else rows.add(child)
                } else if (tag in rowGroups && depth < 2) collect(child, depth + 1)
            }
        }
        collect(table, 0)
        var header: String? = null
        var group = state.nextGroup++
        for (row in rows) {
            if (state.stopped) return
            val cells = tableCells(row)
            if (cells.isEmpty()) continue
            // Drop only this row for nested tables, image maps and map galleries; the rest of the infobox stays usable.
            if (row.getElementsByTag("table").isNotEmpty() || row.getElementsByTag("map").isNotEmpty() ||
                cells.any { cell -> cell.classNames().any { it.lowercase(Locale.ROOT).contains("maptable") } }) {
                state.partial = true
                continue
            }
            val rendered = cells.map { renderInline(it, budget, state) }
            if (state.stopped || rendered.any { !it.complete }) {
                state.partial = true
                continue
            }
            val texts = rendered.map { it.text.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotBlank() }
            if (texts.isEmpty()) continue
            val span = cells[0].attr("colspan").toIntOrNull() ?: 1
            if (cells.size == 1 && cells[0].tagName().equals("th", ignoreCase = true) && span >= 2) {
                header = texts[0]
                group = state.nextGroup++
                continue
            }
            val body = when (texts.size) {
                1 -> texts[0]
                2 -> "${texts[0].replace(Regex("^[•\\s\\u00a0]+"), "").trim()}: ${texts[1]}"
                else -> texts.joinToString(" | ")
            }
            addCandidate(body, headings, candidates, state, context = listOfNotNull(header?.let { "Infobox: $it" }),
                contextSources = listOfNotNull(header), group = group)
        }
    }

    private fun scanTable(table: Element, budget: TraversalBudget, state: ScanState): TableScan {
        val result = TableScan()

        fun visit(node: Node, depth: Int) {
            if (result.stopped || state.stopped) return
            if (depth > MAX_DEPTH) {
                result.complete = false
                result.stopped = true
                state.partial = true
                return
            }
            if (!budget.take()) {
                result.complete = false
                result.stopped = true
                state.partial = true
                state.stopped = true
                return
            }
            val element = node as? Element ?: return
            val tag = element.tagName().lowercase(Locale.ROOT)
            val cell = tag == "th" || tag == "td"
            if (isSkipped(element)) {
                // Removing structural cells/rows can silently change span/header associations.
                if (cell || tag == "tr" || tag in rowGroups) {
                    result.complete = false
                    result.stopped = true
                    state.partial = true
                }
                return
            }
            if (cell && element.hasAttr("headers")) {
                // Explicit ID-based associations are unsupported; never substitute physical columns.
                result.complete = false
                result.stopped = true
                state.partial = true
                return
            }
            if (element !== table && element.tagName().equals("table", ignoreCase = true)) {
                result.nestedTable = true
                result.stopped = true
                state.partial = true
                return
            }
            if (element.tagName().equals("tr", ignoreCase = true)) {
                if (result.rows.size == MAX_TABLE_ROWS) {
                    result.rowLimitReached = true
                    result.stopped = true
                    state.partial = true
                    return
                }
                result.rows.add(element)
            }
            for (child in element.childNodes()) {
                visit(child, depth + 1)
                if (result.stopped || state.stopped) return
            }
        }

        visit(table, 0)
        return result
    }

    private fun tableNotes(table: Element, budget: TraversalBudget, state: ScanState): List<String> {
        val notes = LinkedHashSet<String>()
        var ancestor = table.parent()
        var depth = 0
        while (ancestor != null && depth++ < MAX_DEPTH) {
            if (ancestor.tagName().equals("figure", ignoreCase = true)) {
                ancestor.children().firstOrNull { it.tagName().equals("figcaption", ignoreCase = true) }
                    ?.let { renderNote(it, "Figure caption", budget, state)?.let(notes::add) }
                break
            }
            ancestor = ancestor.parent()
        }

        for (sibling in listOfNotNull(table.previousElementSibling(), table.nextElementSibling())) {
            val tag = sibling.tagName().lowercase(Locale.ROOT)
            if (tag !in setOf("p", "small", "div", "ul", "ol")) continue
            renderNote(sibling, "Note or legend", budget, state)?.let(notes::add)
        }
        return notes.toList()
    }

    private fun renderNote(element: Element, label: String, budget: TraversalBudget, state: ScanState): String? {
        if (isSkipped(element)) return null
        val rendered = renderInline(element, budget, state)
        if (!rendered.complete) {
            state.partial = true
            return null
        }
        val classes = element.classNames().map { it.lowercase(Locale.ROOT) }
        val marked = classes.any { it.contains("legend") || it.contains("note") || it == "key" } || notePrefix.containsMatchIn(rendered.text)
        return rendered.text.takeIf { marked && hasMeaningfulText(it) }?.let { "$label: $it" }
    }

    private fun renderInline(element: Element, budget: TraversalBudget, state: ScanState): RenderedText =
        renderNodes(listOf(element), budget, state)

    private fun renderNodes(nodes: List<Node>, budget: TraversalBudget, state: ScanState): RenderedText {
        val output = TextBuilder(MAX_CHUNK_UTF8_BYTES)
        var complete = true
        for (node in nodes) {
            val element = node as? Element
            val block = element?.tagName()?.lowercase(Locale.ROOT) in blockChildren
            if (block) output.newline()
            val rendered = renderNode(node, budget, state, 0, 0)
            if (!rendered.complete) complete = false
            if (!output.append(rendered.text)) {
                complete = false
                break
            }
            if (block && rendered.text.isNotEmpty()) output.newline()
            if (state.stopped) {
                complete = false
                break
            }
        }
        return RenderedText(normalizeBlock(output.text()), complete)
    }

    private fun renderNode(node: Node, budget: TraversalBudget, state: ScanState, depth: Int, listDepth: Int): RenderedText {
        if (depth > MAX_DEPTH) {
            state.partial = true
            return RenderedText("", false)
        }
        if (!budget.take()) {
            state.partial = true
            state.stopped = true
            return RenderedText("", false)
        }
        if (node is TextNode) {
            val output = TextBuilder(MAX_CHUNK_UTF8_BYTES)
            val complete = output.appendText(node.wholeText)
            return RenderedText(output.text(), complete)
        }
        val element = node as? Element ?: return RenderedText("", true)
        if (isSkipped(element)) return RenderedText("", true)

        val tag = element.tagName().lowercase(Locale.ROOT)
        if (tag == "br") return RenderedText("\n", true)
        if (tag == "ul" || tag == "ol") return renderList(element, budget, state, depth, listDepth)
        if (tag == "dl") return renderDefinitionList(element, budget, state, depth)
        if (tag == "table") {
            state.partial = true
            return RenderedText("", false)
        }

        val output = TextBuilder(MAX_CHUNK_UTF8_BYTES)
        var complete = true
        for (child in element.childNodes()) {
            val childElement = child as? Element
            val block = childElement?.tagName()?.lowercase(Locale.ROOT) in blockChildren
            if (block) output.newline()
            val rendered = renderNode(child, budget, state, depth + 1, listDepth)
            if (!rendered.complete) complete = false
            if (!output.append(rendered.text)) {
                complete = false
                break
            }
            if (block && rendered.text.isNotEmpty()) output.newline()
            if (state.stopped) {
                complete = false
                break
            }
        }

        val inner = output.text()
        val marked = when (tag) {
            "strong", "b" -> surround(inner, "**")
            "em", "i" -> surround(inner, "*")
            else -> inner
        }
        if (utf8Length(marked) > MAX_CHUNK_UTF8_BYTES) complete = false
        return RenderedText(marked, complete)
    }

    private fun renderList(element: Element, budget: TraversalBudget, state: ScanState, depth: Int, listDepth: Int): RenderedText {
        if (listDepth >= MAX_LIST_DEPTH || !budget.take()) {
            state.partial = true
            if (budget.exhausted) state.stopped = true
            return RenderedText("", false)
        }

        val output = TextBuilder(MAX_CHUNK_UTF8_BYTES)
        var complete = true
        var number = element.attr("start").toIntOrNull() ?: 1
        val ordered = element.tagName().equals("ol", ignoreCase = true)
        for (item in element.children().filter { it.tagName().equals("li", ignoreCase = true) }) {
            if (!budget.take()) {
                state.partial = true
                state.stopped = true
                return RenderedText(output.text(), false)
            }
            val itemOutput = TextBuilder(MAX_CHUNK_UTF8_BYTES)
            val nested = ArrayList<String>()
            for (child in item.childNodes()) {
                val childElement = child as? Element
                if (childElement != null && childElement.tagName().lowercase(Locale.ROOT) in setOf("ul", "ol", "dl")) {
                    val rendered = renderNode(child, budget, state, depth + 1, listDepth + 1)
                    if (!rendered.complete) complete = false
                    if (rendered.text.isNotBlank()) nested += rendered.text
                } else {
                    val block = childElement?.tagName()?.lowercase(Locale.ROOT) in blockChildren
                    if (block) itemOutput.newline()
                    val rendered = renderNode(child, budget, state, depth + 1, listDepth)
                    if (!rendered.complete) complete = false
                    if (!itemOutput.append(rendered.text)) complete = false
                    if (block && rendered.text.isNotEmpty()) itemOutput.newline()
                }
                if (state.stopped) return RenderedText(output.text(), false)
            }

            val itemText = normalizeBlock(itemOutput.text())
            val marker = if (ordered) "${item.attr("value").toIntOrNull() ?: number}. " else "- "
            if (ordered) number = (item.attr("value").toIntOrNull() ?: number) + 1
            val indent = "  ".repeat(listDepth)
            if (itemText.isNotBlank()) {
                val lines = itemText.split('\n').mapIndexed { index, line -> if (index == 0) "$indent$marker$line" else "$indent  $line" }
                if (output.isNotEmpty) output.newline()
                if (!output.append(lines.joinToString("\n"))) complete = false
            }
            for (nestedText in nested) {
                if (nestedText.isBlank()) continue
                if (output.isNotEmpty) output.newline()
                if (!output.append(nestedText)) complete = false
            }
            if (!complete || state.stopped) {
                state.partial = true
                return RenderedText(output.text(), false)
            }
        }
        return RenderedText(output.text(), complete)
    }

    private fun renderDefinitionList(element: Element, budget: TraversalBudget, state: ScanState, depth: Int): RenderedText {
        if (!budget.take()) {
            state.partial = true
            state.stopped = true
            return RenderedText("", false)
        }
        val output = TextBuilder(MAX_CHUNK_UTF8_BYTES)
        var term = ""
        var complete = true
        for (child in element.children()) {
            val rendered = renderNode(child, budget, state, depth + 1, 0)
            if (!rendered.complete) complete = false
            val value = normalizeBlock(rendered.text)
            when (child.tagName().lowercase(Locale.ROOT)) {
                "dt" -> term = value
                "dd" -> if (value.isNotBlank()) {
                    if (output.isNotEmpty) output.newline()
                    val line = if (term.isBlank()) value else "$term: $value"
                    if (!output.append(line)) complete = false
                }
            }
            if (state.stopped) return RenderedText(output.text(), false)
        }
        return RenderedText(output.text(), complete)
    }

    private fun columnHeader(
        grid: Array<Array<TableCell?>>,
        headerRows: Set<Int>,
        rowIndex: Int,
        column: Int,
    ): String {
        val headers = LinkedHashSet<String>()
        for (headerRow in headerRows) {
            if (headerRow >= rowIndex) continue
            val text = grid[headerRow][column]?.text?.takeIf(String::isNotBlank) ?: continue
            headers += text
        }
        return headers.joinToString(" / ")
    }

    private fun tableCells(row: Element): List<Element> = row.children().filter {
        it.tagName().equals("th", ignoreCase = true) || it.tagName().equals("td", ignoreCase = true)
    }

    private fun span(cell: Element, name: String, maximum: Int): Int? {
        if (!cell.hasAttr(name) || cell.attr(name).isBlank()) return 1
        return cell.attr(name).toIntOrNull()?.takeIf { it in 1..maximum }
    }

    private fun rowGroup(row: Element, table: Element): Element {
        var ancestor = row.parent()
        var depth = 0
        while (ancestor != null && ancestor !== table && depth++ < MAX_DEPTH) {
            if (ancestor.tagName().lowercase(Locale.ROOT) in rowGroups) return ancestor
            ancestor = ancestor.parent()
        }
        return table
    }

    private fun hasRowGroup(row: Element, table: Element, tag: String): Boolean {
        var ancestor = row.parent()
        var depth = 0
        while (ancestor != null && ancestor !== table && depth++ < MAX_DEPTH) {
            if (ancestor.tagName().equals(tag, ignoreCase = true)) return true
            ancestor = ancestor.parent()
        }
        return false
    }

    private fun isSkipped(element: Element): Boolean {
        val tag = element.tagName().lowercase(Locale.ROOT)
        if (tag in skippedTags || element.hasAttr("hidden") || element.attr("aria-hidden").equals("true", ignoreCase = true)) return true
        if (hiddenStyle.containsMatchIn(element.attr("style"))) return true
        if (element.classNames().any { it.lowercase(Locale.ROOT) in skippedClasses }) return true
        if (element.id().lowercase(Locale.ROOT) in skippedIds) return true
        return tag == "a" && element.attr("href").contains("action=edit", ignoreCase = true)
    }

    private fun compose(candidates: Collection<Candidate>, fallback: Boolean, partial: Boolean): String = buildString {
        if (fallback) append(FALLBACK_MARKER)
        val ordered = candidates.sortedBy(Candidate::order)
        val selected = ordered.toHashSet()
        val legendsShown = HashSet<Int>()
        var previous: Candidate? = null
        for (candidate in ordered) {
            val legend = candidate.legend
            if (legend != null && legendsShown.add(candidate.legendKey) && candidate.legendSource?.let { it in selected } != true) {
                if (isNotEmpty()) append("\n\n")
                append(legend)
                previous = null
            }
            val sameGroup = previous != null && candidate.group >= 0 && previous.group == candidate.group
            val header = candidate.header.filter { it !in (previous?.header ?: emptyList()) }
            if (isNotEmpty()) append(if (sameGroup && header.isEmpty()) "\n" else "\n\n")
            header.forEach { append(it).append('\n') }
            append(candidate.content)
            previous = candidate
        }
        if (partial) {
            if (isNotEmpty()) append("\n\n")
            append(PARTIAL_MARKER)
        }
    }

    private fun tokens(text: String): Set<String> = tokenPattern.findAll(fold(text)).map { it.value }.toSet()

    private fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace("ß", "ss")
        .replace("ς", "σ")

    private fun hasMeaningfulText(text: String): Boolean = text.any { Character.isLetterOrDigit(it.code) }

    private fun normalizeBlock(text: String): String {
        val lines = text.split('\n').map(String::trimEnd).dropWhile(String::isBlank).dropLastWhile(String::isBlank)
        if (lines.isEmpty()) return ""
        return lines.mapIndexed { index, line -> if (index == 0) line.trimStart() else line }.joinToString("\n")
    }

    private fun surround(text: String, marker: String): String {
        val core = text.trim()
        if (core.isEmpty()) return text
        val leading = text.takeWhile(Char::isWhitespace)
        val trailing = text.takeLastWhile(Char::isWhitespace)
        return "$leading$marker$core$marker$trailing"
    }

    private fun boundInput(input: String): BoundedInput {
        val output = StringBuilder(minOf(input.length, MAX_INPUT_UTF8_BYTES))
        var offset = 0
        var bytes = 0
        while (offset < input.length) {
            val original = input.codePointAt(offset)
            val codePoint = if (original in 0xD800..0xDFFF) 0xFFFD else original
            val size = utf8Bytes(codePoint)
            if (bytes + size > MAX_INPUT_UTF8_BYTES) return BoundedInput(output.toString(), true)
            output.appendCodePoint(codePoint)
            bytes += size
            offset += Character.charCount(original)
        }
        return BoundedInput(output.toString(), false)
    }

    private fun utf8Length(text: String): Int {
        var bytes = 0
        var offset = 0
        while (offset < text.length) {
            val original = text.codePointAt(offset)
            val codePoint = if (original in 0xD800..0xDFFF) 0xFFFD else original
            bytes += utf8Bytes(codePoint)
            offset += Character.charCount(original)
        }
        return bytes
    }

    private fun utf8Bytes(codePoint: Int): Int = when {
        codePoint <= 0x7F -> 1
        codePoint <= 0x7FF -> 2
        codePoint <= 0xFFFF -> 3
        else -> 4
    }

    private class Candidate(
        val order: Int,
        val header: List<String>,
        val content: String,
        val bodyTerms: Set<String>,
        val contextTerms: Set<String>,
        val bodyLength: Int,
        var group: Int,
        val prefix: Boolean,
        val legend: String?,
        val legendKey: Int,
        val legendSource: Candidate?,
    ) {
        val evidenceTerms: Set<String> = bodyTerms + contextTerms
        val contentCost: Int = estimateTokens(content) + 1
        val headerCosts: List<Int> = header.map { estimateTokens(it) + 1 }
        val legendCost: Int = if (legend == null) 0 else estimateTokens(legend) + 1
        fun standaloneCost() = contentCost + headerCosts.sum() + legendCost
    }

    private class ListItem(val text: String, val parent: String?)

    /**
     * Selected set with token accounting that mirrors [compose]: header lines equal to the preceding selected
     * candidate's are free, and each table legend is paid once. Prefix groups only ever extend a contiguous
     * document-order prefix. [spent] is an estimate; [extract] verifies the exact composed text.
     */
    private class Plan(candidates: List<Candidate>, val budget: Int) {
        private val members = candidates.filter { it.group >= 0 }.groupBy { it.group }
        private val chosen = ArrayList<Candidate>()
        var spent = 0
            private set
        val selected: List<Candidate> get() = chosen.sortedBy(Candidate::order)
        fun contains(candidate: Candidate) = candidate in chosen
        fun siblings(candidate: Candidate): List<Candidate> = members[candidate.group].orEmpty()

        private fun needed(candidate: Candidate): List<Candidate> =
            if (!candidate.prefix) listOf(candidate) else
                members.getValue(candidate.group).filter { it.order <= candidate.order && it !in chosen }

        private fun costOf(set: Collection<Candidate>): Int {
            var total = 0
            var previous: Candidate? = null
            val legends = HashSet<Int>()
            val inSet = set.toHashSet()
            for (candidate in set.sortedBy(Candidate::order)) {
                total += candidate.contentCost
                candidate.header.forEachIndexed { index, line ->
                    if (previous == null || line !in previous.header) total += candidate.headerCosts[index]
                }
                if (candidate.legend != null && legends.add(candidate.legendKey) && candidate.legendSource?.let { it in inSet } != true)
                    total += candidate.legendCost
                previous = candidate
            }
            return total
        }

        fun marginal(candidate: Candidate): Int = costOf(chosen + needed(candidate)) - spent
        fun tryAdd(candidate: Candidate): Boolean {
            if (candidate in chosen) return true
            val next = chosen + needed(candidate)
            val total = costOf(next)
            if (total > budget) return false
            chosen.clear(); chosen += next
            spent = total
            return true
        }
        fun reset() { chosen.clear(); spent = 0 }
        fun dropLast() { if (chosen.isNotEmpty()) { chosen.removeAt(chosen.lastIndex); spent = costOf(chosen) } }
        fun costOfSingle(candidate: Candidate) = costOf(listOf(candidate))
    }
    private data class BoundedInput(val text: String, val truncated: Boolean)
    private data class RenderedText(val text: String, val complete: Boolean)
    private data class TableCell(
        val element: Element,
        val text: String,
        val isHeader: Boolean,
        val row: Int,
        val column: Int,
        val rowSpan: Int,
        val columnSpan: Int,
        val scope: String,
    )

    private class TableScan {
        val rows = ArrayList<Element>()
        var complete = true
        var nestedTable = false
        var rowLimitReached = false
        var stopped = false
    }

    private class ScanState(var partial: Boolean = false, var stopped: Boolean = false, val rowLimit: Int = Int.MAX_VALUE) {
        var candidateBytes = 0
        var lastParagraph: Element? = null
        var lastParagraphText: String = ""
        var lastParagraphCandidate: Candidate? = null
        var nextGroup = 0
        var nextTable = 0
    }

    private class TraversalBudget {
        private var visited = 0
        var exhausted = false
            private set

        fun take(): Boolean {
            if (visited >= MAX_VISITED_NODES) {
                exhausted = true
                return false
            }
            visited++
            return true
        }
    }

    private class TextBuilder(private val limitBytes: Int) {
        private val value = StringBuilder()
        private var bytes = 0

        val isNotEmpty: Boolean get() = value.isNotEmpty()

        fun append(text: String): Boolean {
            var offset = 0
            while (offset < text.length) {
                val original = text.codePointAt(offset)
                val codePoint = if (original in 0xD800..0xDFFF) 0xFFFD else original
                val size = utf8Bytes(codePoint)
                if (bytes + size > limitBytes) return false
                value.appendCodePoint(codePoint)
                bytes += size
                offset += Character.charCount(original)
            }
            return true
        }

        fun appendText(text: String): Boolean {
            var offset = 0
            var pendingSpace = false
            while (offset < text.length) {
                val original = text.codePointAt(offset)
                val codePoint = if (original in 0xD800..0xDFFF) 0xFFFD else original
                offset += Character.charCount(original)
                if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) continue
                if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                    pendingSpace = true
                    continue
                }
                if (pendingSpace && !append(" ")) return false
                pendingSpace = false
                if (!append(String(Character.toChars(codePoint)))) return false
            }
            return !pendingSpace || append(" ")
        }

        fun newline(): Boolean = if (value.isNotEmpty() && value.last() != '\n') append("\n") else true

        fun text(): String = value.toString()
    }
}
