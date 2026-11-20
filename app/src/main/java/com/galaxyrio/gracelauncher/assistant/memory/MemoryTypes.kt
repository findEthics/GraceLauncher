package com.galaxyrio.gracelauncher.assistant.memory

import java.text.Normalizer
import java.util.Locale

internal enum class MemoryType {
    FACT, PREFERENCE, EVENT, PLACE, NOTE;

    val wire: String get() = name.lowercase(Locale.ROOT)

    companion object {
        fun fromWire(s: String?): MemoryType? = entries.firstOrNull { it.wire == s }
    }
}

internal enum class MemoryStatus {
    ACTIVE, SUPERSEDED, INVALIDATED;

    val wire: String get() = name.lowercase(Locale.ROOT)

    companion object {
        fun fromWire(s: String?): MemoryStatus? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * Mirrors `grace_memory.memory_entries` (server columns, millis for timestamps).
 * `serverConflict`: the server answered `conflict:true` for this entry's save. `conflict`: derived flag shown in /recall
 * (serverConflict, or another active head shares this single-valued type+subject_key). Neither is sent to the server.
 */
internal data class MemoryEntry(
    val id: String,
    val type: MemoryType,
    val subject: String,
    val subjectKey: String,
    val body: String,
    val occurredAt: Long? = null,
    val validFrom: Long,
    val validTo: Long? = null,
    val status: MemoryStatus = MemoryStatus.ACTIVE,
    val supersedes: String? = null,
    val supersededBy: String? = null,
    val source: String = "launcher",
    val createdAt: Long = validFrom,
    val changeSeq: Long = 0,
    val conflict: Boolean = false,
    val serverConflict: Boolean = false,
)

/** What the confirm card shows before Save; nothing persists until the user confirms. */
internal data class MemoryDraft(val type: MemoryType, val subject: String, val body: String, val occurredAt: Long?)

// MUST stay byte-identical to SQL `grace_memory._subject_key` (server/supabase/grace_memory/003_functions.sql): same table,
// same order. Both are checked against test resource memory/subject_key_vectors.json. No java.text.Normalizer on purpose.
private const val KEY_FROM = "àáâãäåāăąçćĉċčðďđèéêëēĕėęěĝğġģĥħìíîïĩīĭįıĵķĺļľŀłñńņňòóôõöøōŏőŕŗřśŝşšșţťŧțùúûüũūŭůűųŵýÿŷźżžÀÁÂÃÄÅĀĂĄÇĆĈĊČÐĎĐÈÉÊËĒĔĖĘĚĜĞĠĢĤĦÌÍÎÏĨĪĬĮĴĶĹĻĽĿŁÑŃŅŇÒÓÔÕÖØŌŎŐŔŖŘŚŜŞŠȘŢŤŦȚÙÚÛÜŨŪŬŮŰŲŴÝŸŶŹŻŽİÆŒÞ"
private const val KEY_TO = "aaaaaaaaacccccdddeeeeeeeeegggghhiiiiiiiiijklllllnnnnooooooooorrrsssssttttuuuuuuuuuuwyyyzzzaaaaaaaaacccccdddeeeeeeeeegggghhiiiiiiiijklllllnnnnooooooooorrrsssssttttuuuuuuuuuuwyyyzzziæœþ"
private val KEY_MAP: Map<Char, Char> = KEY_FROM.zip(KEY_TO).toMap()
private val KEY_WS = Regex("[ \t\n\u000B\u000C\r]+")

/** ß/ẞ→ss, fold listed diacritics, lower per code point (like SQL lower), collapse ASCII whitespace, trim spaces. Type scoping is the caller's. */
internal fun subjectKey(subject: String): String {
    val folded = subject.replace("ß", "ss").replace("ẞ", "ss").map { KEY_MAP[it] ?: it }.joinToString("")
    val lowered = buildString { folded.codePoints().forEach { appendCodePoint(Character.toLowerCase(it)) } }
    return lowered.replace(KEY_WS, " ").trim(' ')
}

internal fun foldText(s: String): String {
    val d = Normalizer.normalize(s.replace("ß", "ss").replace("ẞ", "SS"), Normalizer.Form.NFD)
    return d.replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
}

// Small explicit lists (spec open item). Compared after foldText, so umlauts are matched folded.
private val EN_STOPWORDS = """
a an and are as at be been being but by can could do does for from had has have how i if in into is it its me my
no not of on or our so than that the their them then there these they this those to us was we were what when where
which who will with would you your about am
""".trim().split(Regex("\\s+"))

private val DE_STOPWORDS = """
aber auch auf aus bei bin bist dann das dass dem den der des die dich dir du ein eine einem einen einer eines er es
für haben hat hatte ich ihr im in ist kann kein keine mein meine meinem meinen meiner mich mir mit nicht noch nur oder
sein sind sie uns und von war waren was wann wer werden wie wird wir wo zu zum zur an am
""".trim().split(Regex("\\s+"))

private val STOPWORDS: Set<String> = (EN_STOPWORDS + DE_STOPWORDS).map(::foldText).toSet()

private val WORD = Regex("[\\p{L}\\p{N}]+")

/** Original-case words with EN+DE stopwords and 1-letter tokens removed. */
internal fun contentWords(text: String): List<String> = WORD.findAll(text).map { it.value }.filter {
    val folded = foldText(it)
    folded !in STOPWORDS && !(folded.length == 1 && folded[0].isLetter())
}.toList()

internal fun contentTerms(text: String): Set<String> = contentWords(text).map(::foldText).toSet()
