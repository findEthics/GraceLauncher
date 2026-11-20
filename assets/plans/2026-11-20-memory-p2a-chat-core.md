# Memory Phase 2a — memory-chat core (pure Kotlin, JVM tests)

## Overview
Pure-Kotlin core logic for assistant memory in Grace Launcher, with no Android dependencies, tested on the JVM, test-first. Android wiring (Room, HTTP, UI, AssistantController edits) is Phase 3, NOT this phase. Authoritative spec: `docs/memory/SPEC-memory-chat.md` (Revision 2) — implement its core-logic parts exactly, including the related Success Criteria, except for the approved deviations below. Background: `docs/memory/CAPABILITY-MAP.md`, `docs/memory/SPEC-memory-ledger.md` (RPC contract the sync engine talks to; finished in Phase 1 under `server/supabase/grace_memory/`), decision record `assets/plans/2026-11-20-assistant-memory-research-design.md`.

## Business & UX Decisions
Approved deviations from the spec (user decisions 2026-11-20, these win where they conflict):
1. **Privacy gate, generalized fix #1:** memory never enters any pass that has tools registered. With Web or Wikipedia, any tool-enabled pass that ends without a tool call while the memory block is non-empty has its draft discarded. That covers the first query-writing pass and also passes after evidence was retrieved. One extra tool-free pass then runs with the evidence gathered so far plus the memory block. The discarded draft is never shown or stored. If the memory block is empty, keep the draft and skip the extra pass. The existing max of 2 searches per reply is unchanged.
2. **Wikipedia exception:** if a Wikipedia turn ends with no tool call and has no current sources or valid citations, keep the existing "unable" outcome. No extra memory pass in that case. If Wikipedia has evidence, rule 1 applies.
3. **Source-N follow-ups** (tool-free `inferSavedText` path) also receive the memory block.
4. **Dates: English only.** `CalendarPhraseEngine` has no German support. Date → `event` detection runs with English only. The EN/DE cue words for preference/place still apply. A German date phrase becomes `fact` or whatever its cue words give. This is accepted, not a bug.

## Technical Decisions
- Package: `app/src/main/java/com/galaxyrio/gracelauncher/assistant/memory/` (small files, direct construction, no DI, matching the surrounding code). Tests in `app/src/test/java/com/galaxyrio/gracelauncher/assistant/memory/` (JUnit4 4.13.2, `runBlocking`, hand-written fakes; no new test deps unless unavoidable. If you need one, STOP and ask).
- Inference and RPC sit behind small interfaces. Tests use fakes. No Android imports anywhere in this package's core files (Phase 3 adds the Android adapters).
- Components (follow the spec for exact behavior, formats and caps):
  - **Slash-command parser:** `/remember /save /delete /forget /erase /purge /recall /history`, English-only tokens. Unknown `/xyz` is ordinary text. Chip modes (memory-save/delete/purge) are one-shot, and a slash prefix overrides the chip.
  - **/remember classification:** if `CalendarPhraseEngine.parse(CalendarParseRequest(...))` (`com.katoaapps.openminilaunch.features.calendar.engine`, internal object in the same module, English language tag) finds a when → `event`, keeping the full resolved date-time (epoch millis / ZonedDateTime). Do NOT reuse `MagicAlarmCommand`, which reduces the date to a `tomorrow` flag. EN/DE cue words → `preference` or `place`, else `fact`.
  - **Model subject JSON:** validator for `{"subject":"≤6 words"}` (plus `type` only when the rules gave `fact`). On invalid output or timeout, fall back to the first 4 content words. Inference goes behind an interface and tests use a fake.
  - **subject_key normalizer** (per spec).
  - **Replace-candidate selector:** exact key match, default ON only for fact/preference/place, plus up to 3 lexical same-type matches, all default OFF (#3).
  - **Numbered-pick resolver** (≤5) and **confirm-state machine**. Erase and purge need a second confirm.
  - **Prefetch builder:** ≤400 estimated tokens using the existing `estimateTokens`/`truncateToTokens` (`assistant/TokenEstimate.kt`). Preferences capped at 8. Events from now−2d to now+14d, soonest first. Lexical matches capped at 8 (EN+DE stopwords removed). Chain heads only. Drop order is section 3, then 2, then 1. Use the spec's exact line format.
  - **Privacy-gate decision function** (pure). Inputs are roughly: chat source (NONE/WEB/WIKIPEDIA/HEALTH/SAVED_SOURCES), whether the pass had tools, whether the model returned a tool call, whether evidence/sources exist, and whether the memory block is empty. Outputs are roughly: include block in this pass (yes/no), and the next action (KEEP_DRAFT / DISCARD_AND_RUN_TOOL_FREE_WITH_MEMORY / WIKIPEDIA_UNABLE / CONTINUE_TOOL_LOOP). It must encode deviations 1–3. A tool-enabled pass never gets the block. The block is never written into chat history (#7); expose it so Phase 3 can verify that.
  - **Sync engine** over a fake RPC client and a fake local store:
    - Outbox push is idempotent by UUID.
    - Pull calls `memory_changes(since_seq − 50)` (floor 0), upserts by id, and stores `max_seq`.
    - Tombstones delete locally. Conflict pairs (two active heads of one key, or `conflict:true`) are flagged.
    - With no token, status is local-only and nothing is sent. When the token is removed, the outbox is kept.
    - Status values feed the later Advanced line: local only / synced at … / N pending.
- RPC client interface names, arguments and JSON shapes must match the Phase 1 SQL functions in `server/supabase/grace_memory/003_functions.sql` (read it). The 7 RPCs are `memory_remember(p_entry jsonb, p_supersede_id uuid)`, `memory_forget(p_id)`, `memory_purge(p_id, p_confirm)`, `memory_recall(p_query, p_limit)`, `memory_history(p_id)`, `memory_changes(p_since_seq)`, `memory_export()`.

## Relevant Files
- `docs/memory/SPEC-memory-chat.md`: authoritative behavior, formats, caps and success criteria.
- `app/src/main/java/com/galaxyrio/gracelauncher/assistant/TokenEstimate.kt`: `estimateTokens(text)`, `tokenPrefix(text,max)`, `truncateToTokens(text,max)`.
- `app/src/main/java/com/katoaapps/openminilaunch/features/calendar/engine/CalendarPhraseEngine.kt`, `model/CalendarParseRequest.kt`, `model/CalendarDraft.kt`.
- `app/src/main/java/com/katoaapps/openminilaunch/features/calendar/magic/MagicAlarmCommand.kt:64-95`: the date-dropping pattern NOT to copy.
- `app/src/test/java/com/galaxyrio/gracelauncher/assistant/HealthTurnTest.kt`: test style reference.
- `server/supabase/grace_memory/003_functions.sql`: RPC contract.

## Acceptance Criteria
- Every core-logic Success Criterion in `SPEC-memory-chat.md` is covered by a JVM test and passes, as amended by deviations 1–4.
- Privacy-gate tests cover:
  - no block in any tool-enabled pass;
  - Web, first pass, no tool call: non-empty block → discard plus extra pass; empty block → keep draft;
  - Web after evidence, tool-enabled pass ends without a tool call: non-empty block → discard plus tool-free pass with evidence and block;
  - Wikipedia, no tool call, no sources → unable, no extra pass;
  - Wikipedia with evidence → same as Web;
  - Health, plain chat and source-N passes include the block;
  - the block is never stored in history.
- Prefetch builder output is ≤400 estimated tokens in every test, follows the drop order, and uses the exact line format.
- Sync engine tests cover: idempotent push, since_seq−50 overlap, max_seq stored, tombstone delete, conflict flag, no-token local-only, outbox kept after token removal.
- No Android framework imports in the core package. No raw user text or tokens in any log call.

## Tests to Add/Run
- Focused, during each RED/GREEN cycle: `ANDROID_USER_HOME=/Users/susuresh/.android bash ./gradlew :app:testDebugUnitTest --tests '*memory*'` (JDK 21).
- Final: the full `ANDROID_USER_HOME=/Users/susuresh/.android bash ./gradlew :app:testDebugUnitTest` stays green (prior baseline 362/362 plus new tests).
- Report RED evidence (failing test and reason) and GREEN evidence per component, with the exact commands and pass counts.

## Scope Guardrails
- Do NOT edit `AssistantController`, `AssistantUi`, `AssistantChatState`, Room databases, gradle files, the manifest, or `strings.xml` (all Phase 3).
- Do NOT touch `server/` SQL or `tools/memory-bridge/` (other phases; Phase 2b runs in parallel in `tools/memory-bridge/`).
- No network, no emulator/device (Gate D), and no `connectedDebugAndroidTest`.
- No commits/pushes. No delegation or spawning agents.
- If the spec is ambiguous or contradicts the code, STOP and report. Do not decide silently.

## Open Questions & Risks
- Exact EN/DE stopword lists (spec open item): pick small, explicit lists in code and report them.
- Small-model subject quality is unverified (fake inference only).

## Implementation Task List
- [ ] Read the spec and the Phase 1 RPC signatures.
- [ ] Slash parser + chip override (RED→GREEN).
- [ ] /remember classification with date preservation (RED→GREEN).
- [ ] Subject JSON validator + fallback (RED→GREEN).
- [ ] subject_key normalizer (RED→GREEN).
- [ ] Replace-candidate selector (RED→GREEN).
- [ ] Pick resolver + confirm-state machine (RED→GREEN).
- [ ] Prefetch builder (RED→GREEN).
- [ ] Privacy-gate decision function (RED→GREEN).
- [ ] Sync engine with fakes (RED→GREEN).
- [ ] Refactor; run the full JVM suite; report.
