package com.galaxyrio.gracelauncher.assistant.memory

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

internal enum class EntriesKind { RECALL, HISTORY }

/** Fixed outcomes; the UI maps each to a localized string. Never model text. */
internal enum class NoteKind { SAVED, CANCELLED, DELETED, ERASED, NO_MATCH, NOT_FOUND, USAGE_REMEMBER, USAGE_HISTORY, INVALID }

/** App-rendered card of a memory turn (RAM only, never part of model context). */
internal sealed interface MemoryCard {
    data class Save(
        val draft: MemoryDraft, val candidates: List<ReplaceCandidate>, val replaceId: String?, val invalid: Boolean = false,
    ) : MemoryCard
    data class Pick(val state: DeleteState.Picking) : MemoryCard
    data class Confirm(val state: DeleteState.Confirm) : MemoryCard
    data class Entries(val kind: EntriesKind, val entries: List<MemoryEntry>) : MemoryCard
    data class Note(val kind: NoteKind) : MemoryCard

    /** Numbered cards a later `/delete n`, `/erase n` or `/history n` refers to. */
    val shown: List<MemoryEntry> get() = when (this) {
        is Pick -> state.cards
        is Entries -> entries
        else -> emptyList()
    }
}

private const val SUBJECT_MAX = 200
private const val BODY_MAX = 8000

/**
 * The memory command flow over a local store + sync (all blocking store calls: run off the main thread).
 * Nothing persists before [save]/[pick]-commit; the model never writes memory or picks delete targets.
 */
internal class MemoryTurns(
    private val store: MemoryLocalStore,
    private val sync: MemorySync,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** [shown] = numbered cards from the latest list/pick card on screen. [infer]: tool-free greedy subject call, null on failure. */
    suspend fun start(command: MemoryCommand, shown: List<MemoryEntry>, infer: suspend (String) -> String?): MemoryCard {
        val all = store.entries()
        return when (command) {
            is MemoryCommand.Remember -> {
                if (command.text.isBlank()) return MemoryCard.Note(NoteKind.USAGE_REMEMBER)
                val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(clock()), zone)
                saveCard(parseRemember(command.text, now, infer), all)
            }
            is MemoryCommand.Delete -> deleteCard(startDelete(DeleteKind.SOFT, command.target, all, shown))
            is MemoryCommand.Erase -> deleteCard(startDelete(DeleteKind.ERASE, command.target, all, shown))
            is MemoryCommand.Recall -> recallCards(command.query, all).let {
                if (it.isEmpty()) MemoryCard.Note(NoteKind.NO_MATCH) else MemoryCard.Entries(EntriesKind.RECALL, it)
            }
            is MemoryCommand.History -> {
                val card = command.n?.let { shown.getOrNull(it - 1) } ?: return MemoryCard.Note(if (command.n == null) NoteKind.USAGE_HISTORY else NoteKind.NOT_FOUND)
                MemoryCard.Entries(EntriesKind.HISTORY, historyOf(all, card.id))
            }
        }
    }

    private fun saveCard(draft: MemoryDraft, all: List<MemoryEntry>): MemoryCard.Save {
        val candidates = replaceCandidates(draft, all)
        return MemoryCard.Save(draft, candidates, defaultReplaceId(candidates))
    }

    /** User edited type/subject/text/when: candidates are recomputed, the default toggle follows the new exact match. */
    fun edit(card: MemoryCard.Save, draft: MemoryDraft): MemoryCard.Save = saveCard(draft, store.entries())

    fun toggleReplace(card: MemoryCard.Save, id: String?): MemoryCard.Save = card.copy(replaceId = pickReplace(card.candidates, id))

    /** Persist (mirror first, then outbox) and queue sync. Invalid fields keep the card open. */
    fun save(card: MemoryCard.Save): MemoryCard {
        val d = card.draft
        val subject = d.subject.trim()
        if (subject.isEmpty() || subject.length > SUBJECT_MAX || subjectKey(subject).isEmpty() || d.body.isBlank() || d.body.length > BODY_MAX)
            return card.copy(invalid = true)
        val now = clock()
        val entry = MemoryEntry(
            id = newId(), type = d.type, subject = subject, subjectKey = subjectKey(subject), body = d.body,
            occurredAt = d.occurredAt.takeIf { d.type == MemoryType.EVENT }, validFrom = now, createdAt = now,
        )
        sync.record(saveLocally(store.entries(), entry, card.replaceId))
        return MemoryCard.Note(NoteKind.SAVED)
    }

    private fun deleteCard(s: DeleteState): MemoryCard = when (s) {
        is DeleteState.Picking -> MemoryCard.Pick(s)
        is DeleteState.Confirm -> MemoryCard.Confirm(s)
        is DeleteState.Commit -> commit(s)
        DeleteState.Cancelled -> MemoryCard.Note(NoteKind.CANCELLED)
        DeleteState.NoMatch -> MemoryCard.Note(NoteKind.NO_MATCH)
    }

    fun pick(card: MemoryCard.Pick, n: Int): MemoryCard = deleteCard(card.state.pick(n))

    fun confirm(card: MemoryCard.Confirm): MemoryCard = deleteCard(card.state.confirm())

    private fun commit(c: DeleteState.Commit): MemoryCard {
        val all = store.entries()
        val result = when (c.kind) {
            DeleteKind.SOFT -> softDeleteLocally(all, c.card.id, clock())
            DeleteKind.ERASE -> purgeLocally(all, c.card.id)
        } ?: return MemoryCard.Note(NoteKind.NOT_FOUND)
        sync.record(result)
        return MemoryCard.Note(if (c.kind == DeleteKind.SOFT) NoteKind.DELETED else NoteKind.ERASED)
    }

    /** Prefetch block for an answer pass (no model call). "" when nothing qualifies. */
    fun block(userMessage: String, maxTokens: Int = MEMORY_BLOCK_MAX_TOKENS): String =
        if (maxTokens <= 0) "" else buildMemoryBlock(userMessage, store.entries(), clock(), zone, maxTokens)
}

/** UI → controller callbacks for memory cards (turn id based). */
internal class MemoryActions(
    val edit: (Long, MemoryDraft) -> Unit, val toggleReplace: (Long, String?) -> Unit, val save: (Long, MemoryDraft) -> Unit,
    val pick: (Long, Int) -> Unit, val confirm: (Long) -> Unit, val cancel: (Long) -> Unit,
) {
    companion object {
        val None = MemoryActions({ _, _ -> }, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}, {})
    }
}
