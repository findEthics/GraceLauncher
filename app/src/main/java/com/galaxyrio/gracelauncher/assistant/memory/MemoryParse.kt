package com.galaxyrio.gracelauncher.assistant.memory

import com.katoaapps.openminilaunch.features.calendar.engine.CalendarPhraseEngine
import com.katoaapps.openminilaunch.features.calendar.model.CalendarParseRequest
import com.katoaapps.openminilaunch.features.calendar.model.CalendarParseResult
import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal data class RuleResult(val type: MemoryType, val occurredAt: Long?)

private val PREFERENCE_CUES = Regex(
    "\\b(?:i\\s+(?:like|prefer|hate|love|dislike)|ich\\s+(?:mag|bevorzuge|liebe|hasse))\\b",
    RegexOption.IGNORE_CASE,
)
private val PLACE_CUES = Regex(
    "\\b(?:is\\s+at|parked|located|lives?\\s+in|liegt|liegen|steht|stehen|parkt|parken)\\b",
    RegexOption.IGNORE_CASE,
)

/** Rules only. A "when" from CalendarPhraseEngine (English only, approved) → event with the full resolved instant. */
internal fun classifyMemory(text: String, now: ZonedDateTime): RuleResult {
    val parsed = CalendarPhraseEngine.parse(
        CalendarParseRequest(text = text, defaultTitle = text, languageTag = "en-US", now = now),
    )
    val start = (parsed as? CalendarParseResult.Success)?.draft?.startMillis
    if (start != null) return RuleResult(MemoryType.EVENT, start)
    return when {
        PREFERENCE_CUES.containsMatchIn(text) -> RuleResult(MemoryType.PREFERENCE, null)
        PLACE_CUES.containsMatchIn(text) -> RuleResult(MemoryType.PLACE, null)
        else -> RuleResult(MemoryType.FACT, null)
    }
}

internal data class SubjectJson(val subject: String, val type: MemoryType?)

private val MODEL_TYPES = setOf(MemoryType.FACT, MemoryType.PREFERENCE, MemoryType.PLACE, MemoryType.NOTE)

/** Strict: only `subject` (≤6 words, ≤200 chars, no control chars) and, for rule-type FACT, `type`. Nothing else. */
internal fun validateSubjectJson(raw: String, rulesType: MemoryType): SubjectJson? {
    val map = parseFlatStringJson(raw) ?: return null
    if (!setOf("subject", "type").containsAll(map.keys)) return null
    val subject = map["subject"]?.trim() ?: return null
    if (subject.isEmpty() || subject.length > 200 || subject.any { it < ' ' }) return null
    if (subject.split(Regex("\\s+")).size > 6) return null
    val type = if (rulesType == MemoryType.FACT && "type" in map) {
        MemoryType.fromWire(map["type"])?.takeIf { it in MODEL_TYPES } ?: return null
    } else null
    return SubjectJson(subject, type)
}

/** First 4 content words (EN+DE stopwords removed); never blank. */
internal fun fallbackSubject(text: String): String =
    contentWords(text).take(4).joinToString(" ").ifEmpty { "note" }

internal fun subjectPrompt(text: String, askType: Boolean): String =
    "Give a short subject (at most 6 words) for this note. " +
        (if (askType) "Also give its type: fact, preference or place. " else "") +
        "Reply with only JSON: " + (if (askType) """{"subject":"...","type":"..."}""" else """{"subject":"..."}""") +
        "\nNote: $text"

/** Body stays verbatim. [infer] is the tool-free, greedy local-model call; null/invalid/timeout/throw → fallback subject. */
internal suspend fun parseRemember(
    text: String,
    now: ZonedDateTime,
    infer: suspend (String) -> String?,
    timeoutMs: Long = 8_000,
): MemoryDraft {
    val rules = classifyMemory(text, now)
    val reply = try {
        withTimeoutOrNull(timeoutMs) { infer(subjectPrompt(text, rules.type == MemoryType.FACT)) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    val valid = reply?.let { validateSubjectJson(it, rules.type) }
    return MemoryDraft(valid?.type ?: rules.type, valid?.subject ?: fallbackSubject(text), text, rules.occurredAt)
}

/** Flat `{"k":"v",...}` of strings only; any deviation (trailing text, non-string, dup key) → null. */
private fun parseFlatStringJson(raw: String): Map<String, String>? {
    var i = 0
    fun ws() { while (i < raw.length && raw[i] in " \t\r\n") i++ }
    fun str(): String? {
        if (raw.getOrNull(i) != '"') return null
        i++
        val sb = StringBuilder()
        while (i < raw.length) {
            val c = raw[i++]
            when {
                c == '"' -> return sb.toString()
                c < ' ' -> return null
                c != '\\' -> sb.append(c)
                else -> when (val e = raw.getOrNull(i++)) {
                    '"', '\\', '/' -> sb.append(e)
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                    'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                    'u' -> {
                        val hex = raw.substring(i, minOf(i + 4, raw.length))
                        if (hex.length < 4) return null
                        sb.append((hex.toIntOrNull(16) ?: return null).toChar()); i += 4
                    }
                    else -> return null
                }
            }
        }
        return null
    }
    ws()
    if (raw.getOrNull(i) != '{') return null
    i++
    val out = LinkedHashMap<String, String>()
    ws()
    if (raw.getOrNull(i) == '}') i++ else while (true) {
        ws(); val k = str() ?: return null
        ws(); if (raw.getOrNull(i) != ':') return null
        i++; ws(); val v = str() ?: return null
        if (out.put(k, v) != null) return null
        ws()
        val c = raw.getOrNull(i++)
        if (c == '}') break
        if (c != ',') return null
    }
    ws()
    return if (i == raw.length) out else null
}
