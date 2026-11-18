package com.galaxyrio.gracelauncher.assistant

import com.galaxyrio.gracelauncher.BuildConfig
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.json.JSONObject
import org.json.JSONTokener

/** Discovery-only schema for the first inference; no section capability exists yet. */
internal class WikipediaQueryTools : ToolSet {
    @Tool(description = "Look up Wikipedia first. Discover an article, introduction and section IDs. Headings and alternatives are navigation, not evidence.")
    fun query_wikipedia(
        @ToolParam(description = "Primary topic extracted from the question and relevant context, not the whole question") topic: String,
        @ToolParam(description = "Lowercase two-letter Wikipedia language edition matching the user's query, e.g. en or de. Required; never default to English.") lang: String,
        @ToolParam(description = "Local-only keywords for the exact requested facts, used to rank headings; not sent to Wikipedia.") focus: String,
    ): String = error("Wikipedia requires manual execution")
}

internal class WikipediaTools : ToolSet {
    @Tool(description = "Discover a Wikipedia article, introduction and section IDs. Headings and alternatives are navigation, not evidence.")
    fun query_wikipedia(
        @ToolParam(description = "Primary topic extracted from the question and relevant context, not the whole question") topic: String,
        @ToolParam(description = "Lowercase two-letter Wikipedia language edition matching the user's query, e.g. en or de. Required; never default to English.") lang: String,
        @ToolParam(description = "Local-only keywords for the exact requested facts, used to rank headings; not sent to Wikipedia.") focus: String,
    ): String = error("Wikipedia requires manual execution")

    @Tool(description = "Read one or two section IDs from this turn's discovered article; 0 is the introduction and infobox (key facts such as population, dates, office holders). No URL, page or revision arguments.")
    fun read_wikipedia_sections(
        @ToolParam(description = "One or two exact discovered section IDs, as a string array.") sections: List<String>,
        @ToolParam(description = "Local-only keywords identifying requested facts, table rows or list items.") focus: String,
    ): String = error("Wikipedia requires manual execution")
}

internal object WikipediaSearch {
    fun tools(firstLookup: Boolean): ToolSet = if (firstLookup) WikipediaQueryTools() else WikipediaTools()

    const val LICENSE_URL = "https://creativecommons.org/licenses/by-sa/4.0/"
    const val INABILITY = "I could not verify this answer from Wikipedia evidence retrieved for this reply."
    const val WARNING = "Wikipedia could not verify this request. No offline answer was substituted."
    private val numericCitation = Regex("""\[([0-9]+)\]""")

    fun citationsResolve(response: String, currentSourceCount: Int): Boolean {
        val maxDigits = currentSourceCount.coerceAtLeast(0).toString().length
        return numericCitation.findAll(response).all { match ->
            val digits = match.groupValues[1]
            digits.length <= maxDigits && digits.toIntOrNull()?.let { it in 1..currentSourceCount } == true
        }
    }

    /** Same fail-closed rule for turns that re-display earlier cards under their original numbers. */
    fun citationsResolve(response: String, displayed: Set<Int>): Boolean = numericCitation.findAll(response).all { match ->
        match.groupValues[1].length <= 3 && match.groupValues[1].toIntOrNull()?.let { it in displayed } == true
    }

    fun validate(topic: String, lang: String) {
        TavilySearch.validateQuery(topic)
        if (!lang.matches(Regex("^[a-z]{2}$"))) throw WebSearchFailure(WebSearchFailure.Reason.REQUEST)
    }

    fun safeArticleUrl(value: String, lang: String): Boolean = runCatching {
        validate("topic", lang)
        val uri = URI(value)
        value.length <= 2048 && value.none(Char::isISOControl) && uri.scheme == "https" &&
            uri.host == "$lang.wikipedia.org" && uri.rawUserInfo == null && uri.port == -1 &&
            uri.rawPath.startsWith("/wiki/") && uri.rawPath.length > 6 && uri.rawQuery == null &&
            uri.rawFragment == null && uri.path.none { it.isISOControl() || it == '\\' } &&
            uri.path.split('/').none { it == "." || it == ".." }
    }.getOrDefault(false)

    fun requestUrl(topic: String, lang: String): String {
        validate(topic, lang) // Before constructing any host or opening a connection.
        return "https://$lang.wikipedia.org/w/api.php?action=query&generator=search&gsrlimit=1" +
            "&prop=extracts%7Cinfo&inprop=url&exintro=1&explaintext=1&format=json&formatversion=2&gsrsearch=" +
            URLEncoder.encode(topic, "UTF-8")
    }

    fun recoveredCall(text: String): com.google.ai.edge.litertlm.ToolCall? = runCatching {
        val name = listOf("query_wikipedia", "read_wikipedia_sections").singleOrNull {
            text.startsWith("<|tool_call>call:$it>")
        } ?: return null
        val body = text.removePrefix("<|tool_call>call:$name>")
        if (!body.startsWith("{") || !body.endsWith("}")) return null
        val tokener = JSONTokener(body)
        val json = tokener.nextValue() as? JSONObject ?: return null
        if (tokener.nextClean() != '\u0000') return null
        val args = json.keys().asSequence().associateWith { key ->
            val value = json.get(key)
            if (value is org.json.JSONArray) (0 until value.length()).map { value.get(it) } else value
        }
        validateCall(name, args)
        com.google.ai.edge.litertlm.ToolCall(name, args)
    }.getOrNull()

    fun validateCall(name: String, args: Map<String, Any?>) {
        fun reject(): Nothing = throw WebSearchFailure(WebSearchFailure.Reason.REQUEST)
        val focus = args["focus"] as? String ?: reject()
        if (focus.toByteArray(Charsets.UTF_8).size > 512 || focus.any(Char::isISOControl)) reject()
        when (name) {
            "query_wikipedia" -> {
                if (args.keys != setOf("topic", "lang", "focus")) reject()
                validate(args["topic"] as? String ?: reject(), args["lang"] as? String ?: reject())
            }
            "read_wikipedia_sections" -> {
                if (args.keys != setOf("sections", "focus")) reject()
                val ids = args["sections"] as? List<*> ?: reject()
                if (ids.size !in 1..2 || ids.distinct().size != ids.size || ids.any { it !is String || !it.matches(Regex("[0-9]+")) }) reject()
            }
            else -> reject()
        }
    }

    internal fun parse(body: String, lang: String): List<WebSource> {
        validate("topic", lang)
        if (body.toByteArray(Charsets.UTF_8).size > 65_536) throw WebSearchFailure(WebSearchFailure.Reason.RESULTS)
        val root = JSONObject(body)
        if (root.has("error") || root.has("warnings")) throw WebSearchFailure(WebSearchFailure.Reason.RESULTS)
        val pages = root.optJSONObject("query")?.optJSONArray("pages")
            ?: throw WebSearchFailure(WebSearchFailure.Reason.RESULTS)
        val page = pages.optJSONObject(0) ?: throw WebSearchFailure(WebSearchFailure.Reason.RESULTS)
        val title = (page.opt("title") as? String).orEmpty()
            .filterNot { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }.trim()
        val content = (page.opt("extract") as? String).orEmpty().trim()
        val url = (page.opt("fullurl") as? String).orEmpty()
        if (page.has("missing") || page.optInt("ns", -1) != 0 || title.isBlank() || content.isBlank() || !safeArticleUrl(url, lang))
            throw WebSearchFailure(WebSearchFailure.Reason.RESULTS)
        return listOf(WebSource(AssistantController.evidencePrefix(title, 160), url,
            AssistantController.evidencePrefix(content, 6000), provider = ChatSource.WIKIPEDIA))
    }

    /** No redirects, retries, error-body logging, credentials, or transcript uploads. */
    suspend fun search(topic: String, lang: String): List<WebSource> {
        val url = requestUrl(topic, lang)
        try {
            return withTimeout(20_000) {
                withContext(Dispatchers.IO) {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    coroutineScope {
                        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                            try { awaitCancellation() } finally { connection.disconnect() }
                        }
                        try {
                            connection.apply {
                                requestMethod = "GET"; instanceFollowRedirects = false
                                connectTimeout = 10_000; readTimeout = 10_000
                                setRequestProperty("User-Agent", "GraceLauncher/${BuildConfig.VERSION_NAME} (Wikipedia grounded answers; https://github.com/Galaxy-rio/GraceLauncher/blob/master/README.md)")
                                setRequestProperty("Accept", "application/json")
                            }
                            if (connection.responseCode != 200) throw WebSearchFailure(WebSearchFailure.Reason.NETWORK)
                            val bytes = connection.inputStream.use { input ->
                                val buffer = ByteArray(65_537)
                                var size = 0
                                while (size < buffer.size) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer, size, buffer.size - size)
                                    if (count < 0) break
                                    size += count
                                }
                                buffer.copyOf(size)
                            }
                            if (bytes.size > 65_536) throw WebSearchFailure(WebSearchFailure.Reason.RESULTS)
                            parse(String(bytes, Charsets.UTF_8), lang)
                        } finally { cancellation.cancel(); connection.disconnect() }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw WebSearchFailure(WebSearchFailure.Reason.NETWORK)
        } catch (e: CancellationException) { throw e }
        catch (e: WebSearchFailure) { throw e }
        catch (_: Exception) { throw WebSearchFailure(WebSearchFailure.Reason.NETWORK) }
    }

    internal suspend fun fetch(url: String, maxDecodedBytes: Int): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        coroutineScope {
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { connection.disconnect() }
            }
            try {
                connection.apply {
                    requestMethod = "GET"; instanceFollowRedirects = false
                    connectTimeout = 10_000; readTimeout = 10_000
                    setRequestProperty("User-Agent", "GraceLauncher/${BuildConfig.VERSION_NAME} (Wikipedia grounded answers; https://github.com/Galaxy-rio/GraceLauncher/blob/master/README.md)")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Accept-Encoding", "identity")
                }
                if (connection.responseCode != 200) throw WebSearchFailure(WebSearchFailure.Reason.NETWORK)
                val input = when (connection.contentEncoding?.lowercase(Locale.ROOT)) {
                    null, "identity" -> connection.inputStream
                    "gzip" -> GZIPInputStream(connection.inputStream)
                    else -> throw WebSearchFailure(WebSearchFailure.Reason.RESULTS)
                }
                val bytes = input.use {
                    val buffer = ByteArray(maxDecodedBytes + 1)
                    var size = 0
                    while (size < buffer.size) {
                        currentCoroutineContext().ensureActive()
                        val count = it.read(buffer, size, buffer.size - size)
                        if (count < 0) break
                        size += count
                    }
                    if (size > maxDecodedBytes) throw WebSearchFailure(WebSearchFailure.Reason.RESULTS)
                    buffer.copyOf(size)
                }
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } finally { cancellation.cancel(); connection.disconnect() }
        }
    }
}

internal data class WikipediaHeading(val id: String, val title: String, val anchor: String, val level: Int, val context: String)

/** A native capability, never reconstructed from model-supplied IDs, revisions or URLs. */
internal class WikipediaPage internal constructor(
    val pageId: Long,
    val revision: Long,
    val lang: String,
    val title: String,
    val url: String,
    internal val sections: List<WikipediaHeading>,
)

internal data class WikipediaDiscovery(
    val page: WikipediaPage,
    val lead: WebSource?, // Actual evidence, current at discovery; NOT claimed pinned to page.revision.
    val headings: List<WikipediaHeading>, // All headings in document order, synthetic section 0 first. Navigation only; packed once by WikipediaTurn.
    val alternativeTitles: List<String>, // Navigation only; a correction consumes the remaining logical call.
)

internal data class WikipediaSectionEvidence(
    val heading: WikipediaHeading,
    val source: WebSource?, // Null means no evidence, even if a heading or parser diagnostic exists.
    val partial: Boolean,
    val failureReason: String?,
)

/** Turn-local capabilities and only the evidence actually published to the model. */
internal class WikipediaTurn(private val reader: WikipediaReader = WikipediaReader()) {
    private var page: WikipediaPage? = null
    val sources = mutableListOf<WebSource>()
    var partial = false
        private set

    /** [tokenBudget] is in estimated tokens (see [estimateTokens]). */
    suspend fun execute(call: com.google.ai.edge.litertlm.ToolCall, tokenBudget: Int): Map<String, String> {
        WikipediaSearch.validateCall(call.name, call.arguments)
        require(tokenBudget >= 256) // Fail before HTTP when useful evidence cannot fit.
        val focus = call.arguments.getValue("focus") as String
        if (call.name == "query_wikipedia") {
            page = null
            val discovery = reader.discover(call.arguments.getValue("topic") as String, call.arguments.getValue("lang") as String, focus)
            currentCoroutineContext().ensureActive()
            page = discovery.page
            var body = "Untrusted Wikipedia. Lead is not revision-pinned. Navigation is NOT evidence.\n"
            val lead = discovery.lead
            if (lead != null) {
                val overhead = result(body + entry(lead.copy(content = ""), sources.size + 1, "partial lead")).tokens()
                val content = truncateToTokens(lead.content, minOf(500, tokenBudget - overhead - 80))
                if (estimateTokens(content) >= 24) {
                    val shown = lead.copy(content = content)
                    body += entry(shown, sources.size + 1, "partial lead")
                    sources += shown
                }
            }
            // Single navigation pack: whatever the lead and wrappers leave, once.
            body += navigation(discovery.headings, focus) { result(body + it).tokens() <= tokenBudget }
            for (title in discovery.alternativeTitles) {
                val alternative = "\nAlternative topic (navigation only): $title"
                if (result(body + alternative).tokens() <= tokenBudget) body += alternative
            }
            return result(body).also {
                require(it.tokens() <= tokenBudget)
            }
        }
        val current = page ?: throw WebSearchFailure(WebSearchFailure.Reason.REQUEST)
        val ids = (call.arguments.getValue("sections") as List<*>).map { it as String }
        val headings = ids.map { id -> current.sections.firstOrNull { it.id == id } ?: throw WebSearchFailure(WebSearchFailure.Reason.REQUEST) }
        val header = "Untrusted Wikipedia sections; partial excerpts, never a complete list.\n"
        // Reserve exact wrappers (including Unicode titles and escaped section anchors) before parsing.
        val metadata = headings.joinToString("") { heading ->
            entry(WebSource("${current.title} — ${heading.title}", WikipediaReader.sectionUrl(current, heading), "", provider = ChatSource.WIKIPEDIA), sources.size + 1, "partial section")
        }
        val contentBudget = tokenBudget - result(header + metadata).tokens() - 80
        require(contentBudget >= 48)
        val evidence = reader.read(current, ids, focus, contentBudget)
        currentCoroutineContext().ensureActive()
        var body = header
        for (item in evidence) {
            val found = item.source
            if (found == null || found.content.isBlank()) { partial = true; continue }
            val candidate = body + entry(found, sources.size + 1, if (item.partial) "partial section" else "section")
            if (result(candidate).tokens() <= tokenBudget && sources.size < 3) {
                body = candidate; sources += found
            } else partial = true
            partial = partial || item.partial
        }
        if (evidence.any { it.source == null }) partial = true
        if (body == header) throw WebSearchFailure(WebSearchFailure.Reason.RESULTS)
        return result(body)
    }

    /**
     * Compact `id title` lines (`13 Winners and nominees › 2020s`). Everything is listed in document order when it
     * fits; otherwise section 0, top-level headings, then the best focus-overlap children, kept in document order.
     */
    private fun navigation(headings: List<WikipediaHeading>, focus: String, fits: (String) -> Boolean): String {
        fun line(heading: WikipediaHeading) = "${heading.id} ${heading.context.replace(" / ", " › ")}"
        fun render(shown: List<WikipediaHeading>, omitted: Int) = "\nNavigation only" +
            (if (omitted > 0) " ($omitted headings omitted)" else "") + ":\n" + shown.joinToString("\n", transform = ::line)
        if (fits(render(headings, 0) + "\nMore navigation may be omitted.")) return render(headings, 0)
        val topLevel = headings.filter { it.id != "0" }.minOfOrNull { it.level } ?: 1
        val ranked = WikipediaReader.rankHeadings(headings, focus)
        val priority = headings.filter { it.id == "0" || it.level == topLevel } + ranked.filter { it.id != "0" && it.level != topLevel }
        val shown = mutableListOf<WikipediaHeading>()
        for (heading in priority) {
            val candidate = (shown + heading).sortedBy { headings.indexOf(it) }
            if (fits(render(candidate, headings.size - candidate.size) + "\nMore navigation may be omitted.")) shown += heading
        }
        val ordered = shown.sortedBy { headings.indexOf(it) }
        return render(ordered, headings.size - ordered.size)
    }
    private fun entry(source: WebSource, number: Int, kind: String) = "[$number] $kind: ${source.title}\n${source.url}\n${source.content}\n"
    private fun result(text: String) = mapOf("untrusted_sources" to text)
    private fun Map<String, String>.tokens() = estimateTokens(toString())
}

/** Create once per turn. Both failed and successful calls consume this instance's budgets. */
internal class WikipediaReader(
    private val request: suspend (url: String, maxDecodedBytes: Int) -> String = WikipediaSearch::fetch,
) {
    private var currentPage: WikipediaPage? = null
    private var busy = false
    internal var logicalCalls: Int = 0
        private set
    internal var httpRequests: Int = 0
        private set

    suspend fun discover(topic: String, lang: String, focus: String): WikipediaDiscovery = logicalCall {
        currentPage = null
        WikipediaSearch.validate(topic, lang)
        val query = json(load(discoveryRequestUrl(topic, lang), 65_536))
        val pages = query.optJSONObject("query")?.optJSONArray("pages") ?: resultsFailure()
        if (pages.length() !in 1..3) resultsFailure()
        val candidates = (0 until pages.length()).map { i ->
            val page = pages.optJSONObject(i) ?: resultsFailure()
            val id = positiveNumber(page, "pageid")
            val index = positiveNumber(page, "index")
            val namespace = page.opt("ns")
            if ((namespace !is Int && namespace !is Long) || (namespace as Number).toLong() != 0L || page.has("missing")) resultsFailure()
            if (page.has("pageprops") && page.optJSONObject("pageprops") == null) resultsFailure()
            val title = clean(requiredString(page, "title"))
            val extract = requiredString(page, "extract").trim()
            val url = requiredString(page, "fullurl")
            if (title.isBlank() || !WikipediaSearch.safeArticleUrl(url, lang)) resultsFailure()
            Candidate(id, index, title, extract, url, page.optJSONObject("pageprops")?.has("disambiguation") == true)
        }.sortedBy { it.index }
        if (candidates.map { it.index }.distinct().size != candidates.size || candidates.map { it.id }.distinct().size != candidates.size) resultsFailure()
        val selected = candidates.firstOrNull { !it.disambiguation } ?: resultsFailure()
        val parsed = json(load(api(lang, "action=parse&pageid=${selected.id}&prop=sections%7Crevid"), 65_536), sectionIndex = true)
            .optJSONObject("parse") ?: resultsFailure()
        if (parsed.has("pageid") && positiveNumber(parsed, "pageid") != selected.id) resultsFailure()
        val revision = positiveNumber(parsed, "revid")
        val sections = parsed.optJSONArray("sections") ?: resultsFailure()
        val parents = sortedMapOf<Int, String>()
        val headings = (0 until sections.length()).map { i ->
            val section = sections.optJSONObject(i) ?: resultsFailure()
            val id = requiredString(section, "index")
            val anchor = requiredString(section, "anchor")
            val title = clean(Jsoup.parseBodyFragment(requiredString(section, "line")).text())
            val level = requiredString(section, "level").toIntOrNull() ?: resultsFailure()
            if (!id.matches(Regex("[0-9]+")) || anchor.isBlank() || anchor.length > 512 || anchor.any(Char::isISOControl) || title.isBlank() || level !in 1..6) resultsFailure()
            parents.keys.filter { it >= level }.toList().forEach(parents::remove)
            val context = (parents.values + title).joinToString(" / ")
            parents[level] = title
            WikipediaHeading(id, title, anchor, level, context)
        }
        if (headings.map { it.id }.distinct().size != headings.size) resultsFailure()
        // Section 0 (introduction and infobox) is not in prop=sections; expose it as a synthetic first heading.
        val all = listOf(WikipediaHeading("0", "Introduction and infobox", "", 1, "Introduction and infobox")) + headings
        val page = WikipediaPage(selected.id, revision, lang, selected.title, selected.url, all)
        currentPage = page
        WikipediaDiscovery(page, selected.extract.takeIf(String::isNotBlank)?.let {
            WebSource(selected.title, selected.url, truncateToTokens(it, 500), provider = ChatSource.WIKIPEDIA)
        }, all, candidates.filter { it.id != selected.id }.map { it.title })
    }

    suspend fun read(page: WikipediaPage, sectionIds: List<String>, focus: String, maxTokens: Int): List<WikipediaSectionEvidence> = logicalCall {
        if (page !== currentPage || sectionIds.size !in 1..2 || sectionIds.distinct().size != sectionIds.size || maxTokens <= 0) requestFailure()
        val allowed = sectionIds.map { id -> page.sections.firstOrNull { it.id == id } ?: requestFailure() }
        var remaining = maxTokens
        allowed.map { heading ->
            val parsed = json(load(api(page.lang, "action=parse&oldid=${page.revision}&section=${encode(heading.id)}&prop=text&disableeditsection=1&disabletoc=1&disablelimitreport=1"), 1_048_576))
                .optJSONObject("parse") ?: resultsFailure()
            if (parsed.has("revid") && positiveNumber(parsed, "revid") != page.revision) resultsFailure()
            if (parsed.has("pageid") && positiveNumber(parsed, "pageid") != page.pageId) resultsFailure()
            val html = requiredString(parsed, "text")
            val content = WikipediaContent.extract(html, focus, remaining)
            val source = content.text.takeIf { it.isNotBlank() && content.failureReason == null }?.let {
                remaining -= estimateTokens(it)
                WebSource("${page.title} — ${heading.title}", sectionUrl(page, heading), it, provider = ChatSource.WIKIPEDIA)
            }
            WikipediaSectionEvidence(heading, source, content.partial, content.failureReason)
        }
    }

    private suspend fun load(url: String, limit: Int): String {
        currentCoroutineContext().ensureActive()
        if (httpRequests >= 4) requestFailure()
        httpRequests++
        val body = request(url, limit)
        currentCoroutineContext().ensureActive()
        if (utf8(body) > limit) resultsFailure() // Complete JSON only; never salvage truncated input.
        return body
    }

    private suspend fun <T> logicalCall(block: suspend () -> T): T {
        synchronized(this) {
            if (busy || logicalCalls >= 2) requestFailure()
            logicalCalls++; busy = true
        }
        try {
            return withTimeout(20_000) { block() }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw WebSearchFailure(WebSearchFailure.Reason.NETWORK)
        } catch (e: CancellationException) {
            throw e
        }
        catch (e: WebSearchFailure) { throw e }
        catch (e: IOException) { throw WebSearchFailure(WebSearchFailure.Reason.NETWORK) }
        catch (_: Exception) { resultsFailure() }
        finally { synchronized(this) { busy = false } }
    }

    private data class Candidate(val id: Long, val index: Long, val title: String, val extract: String, val url: String, val disambiguation: Boolean)

    companion object {
        internal fun discoveryRequestUrl(topic: String, lang: String): String {
            WikipediaSearch.validate(topic, lang)
            return api(lang, "action=query&generator=search&gsrsearch=${encode(topic)}&gsrlimit=3&prop=extracts%7Cinfo%7Cpageprops&exintro=1&explaintext=1&exchars=700&exlimit=3&inprop=url&ppprop=disambiguation")
        }

        internal fun sectionUrl(page: WikipediaPage, heading: WikipediaHeading): String =
            if (heading.anchor.isEmpty()) page.url else "${page.url}#${URI(null, null, null, heading.anchor).rawFragment}"

        internal fun rankHeadings(headings: List<WikipediaHeading>, focus: String): List<WikipediaHeading> {
            fun tokens(value: String) = Regex("[\\p{L}\\p{M}\\p{N}]+").findAll(
                Normalizer.normalize(value.take(4_096), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            ).map { it.value }.toSet()
            val terms = tokens(focus)
            return headings.sortedByDescending { heading -> tokens(heading.context).count { it in terms } }
        }

        private fun api(lang: String, parameters: String) = "https://$lang.wikipedia.org/w/api.php?$parameters&format=json&formatversion=2"
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun utf8(value: String) = value.toByteArray(Charsets.UTF_8).size
        private fun clean(value: String) = value.filterNot { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }.trim()
        private fun requiredString(json: JSONObject, name: String) = json.opt(name) as? String ?: resultsFailure()
        private fun positiveNumber(json: JSONObject, name: String): Long {
            val value = json.opt(name)
            if (value !is Int && value !is Long) resultsFailure()
            return (value as Number).toLong().takeIf { it > 0 } ?: resultsFailure()
        }

        private fun json(body: String, sectionIndex: Boolean = false): JSONObject {
            val tokener = JSONTokener(body)
            val root = tokener.nextValue() as? JSONObject ?: resultsFailure()
            if (tokener.nextClean() != '\u0000' || root.has("error")) resultsFailure()
            if (root.has("warnings")) {
                val warnings = root.optJSONObject("warnings") ?: resultsFailure()
                if (!sectionIndex || warnings.length() == 0) resultsFailure()
                for (module in warnings.keys()) {
                    val warning = warnings.optJSONObject(module) ?: resultsFailure()
                    if (warning.keys().asSequence().toSet() !in setOf(setOf("warnings"), setOf("*"), setOf("*", "code"), setOf("warnings", "code"))) resultsFailure()
                    val text = requiredString(warning, if (warning.has("warnings")) "warnings" else "*")
                    val known = when (module) {
                        "parse" -> text in setOf(
                            "\"prop=sections\" has been deprecated. Please use \"prop=tocdata\" instead.",
                            "The \"sections\" value has been deprecated. Use \"tocdata\" instead.",
                        )
                        "main" -> text == "Subscribe to the mediawiki-api-announce mailing list at <https://lists.wikimedia.org/postorius/lists/mediawiki-api-announce.lists.wikimedia.org/> for notice of API deprecations and breaking changes. Use [[Special:ApiFeatureUsage]] to see usage of deprecated features by your application."
                        else -> false
                    }
                    if (!known || (warning.has("code") && requiredString(warning, "code") !in setOf("deprecation", "deprecation-help"))) resultsFailure()
                }
            }
            return root
        }

        private fun requestFailure(): Nothing = throw WebSearchFailure(WebSearchFailure.Reason.REQUEST)
        private fun resultsFailure(): Nothing = throw WebSearchFailure(WebSearchFailure.Reason.RESULTS)
    }
}
