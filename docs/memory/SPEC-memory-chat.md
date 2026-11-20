# SPEC memory-chat — V1

Status: design only, not implemented. Module id: `memory-chat`. Depends on `memory-ledger` for sync only; local works standalone. See `CAPABILITY-MAP.md`, `RESEARCH.md`.

## Objective
Give the offline assistant long-term memory (facts, preferences, events, places, notes) that is near-deterministic, not RAG, and model-agnostic (Gemma4 E2B / Qwen 1.5–1.7B / future models).
- **Write**: explicit commands/chips + model-assisted parse, user-confirmed. Never automatic extraction. Model never writes or picks delete targets.
- **Read**: deterministic prefetch block at answer time, no model call. Newest active value wins.
- **Offline**: full local function; syncs to ledger when an `hc_` token exists.

## Tech Stack
- Kotlin, Room (`@Fts4`, Room has no FTS5), separate DB `memory_mirror.db`.
- Existing: `CalendarPhraseEngine`, `CalendarTemporalResolver` (`com/katoaapps/openminilaunch/features/calendar/engine/`), `TokenEstimate`, `HealthVault` (token), `AssistantController`, `AssistantChatState`, `AssistantUi`.
- Network: Supabase REST RPC (`POST {SUPABASE_URL}/rest/v1/rpc/<fn>`), headers `apikey: <anon>`, `x-grace-token: <hc_…>` (read from `HealthVault`, never an argument/URL, never logged), `Content-Profile: grace_memory`; plain HTTPS client like `HealthMcpClient`.
- Supabase URL + anon key: build-time from ignored `.env` (like the Tavily key); anon key is public-safe but not committed; never logged.

## Commands
(Existing; unchanged.)
- JVM tests: `bash ./gradlew :app:testDebugUnitTest`
- One class: `bash ./gradlew :app:testDebugUnitTest --tests '…MemoryCommandParserTest'`
- Lint: `bash ./gradlew :app:lintDebug`; debug APK: `bash ./gradlew :app:assembleDebug`
- Set `ANDROID_USER_HOME=/Users/susuresh/.android` for builds in this environment.

## Project Structure
Proposal; under `app/src/main/java/com/galaxyrio/gracelauncher/assistant/memory/`:
```
MemoryCommand.kt        # slash/chip parsing, pre-model
MemoryParse.kt          # rules + tiny model call + validation
MemoryStore.kt          # Room DAO/DB: entries, outbox, sync_state (memory_mirror.db)
MemoryRecall.kt         # prefetch block builder
MemorySync.kt           # outbox push + changes pull
MemoryClient.kt         # Supabase RPC client
MemoryCards.kt / UI     # confirm/pick/recall cards, chips
```
Wiring: `AssistantController` (turn flow), `AssistantChatState` (mode state), `AssistantUi` (tools popup chips :168–205), Advanced settings (status line). Do not touch `LauncherDatabase` (Room v18).

## Command surface (chat only; no Home Magic prefix)
Slash commands are English-only tokens. Content parsing cues support EN + DE. Unknown `/xyz` → existing behavior (ordinary text), explicitly.

| Trigger | Effect |
|---|---|
| `/remember <text>`, `/save <text>` | Add (confirm card) |
| `/delete <words\|n>`, `/forget <words\|n>` | Soft delete → `status=invalidated`, `valid_to=now`, row kept, excluded from recall |
| `/erase <words\|n>`, `/purge <words\|n>` | Hard delete entry **and whole chain**, after second explicit confirm (right-to-forget) |
| `/recall <words>` | Numbered app-rendered cards of ACTIVE matches, newest first, with type + date. `/recall` alone → 10 most recent |
| `/history <n>` | Supersede chain of card n |

Rules: parsed before any model call; no model call for browse/delete.

## Chips
New tools-popup chips `memory-save`, `memory-delete`, `memory-purge`.
- One-shot action modes, mutually exclusive with each other, **separate from** the source enum (WEB/WIKIPEDIA/HEALTH/NONE).
- Apply to next submit only, then auto-clear back to prior source.
- While a mode is active, no Web/Wikipedia/Health fetch runs.
- Slash prefix in text overrides the chip.

## Types (V1)
`fact`, `preference`, `event` (has `occurred_at`), `place`, `note` (verbatim fallback). Tasks excluded → Google Tasks (deferred).

## Parse pipeline (`/remember`)
1. Rules: `CalendarPhraseEngine` finds a when → `event`; EN/DE cues ("I like/prefer/hate/love", "ich mag/bevorzuge") → `preference`; ("is at/parked/located/lives in", "liegt/steht/parkt") → `place`; else `fact`.
2. Local model, tool-free, greedy, one short call, asked ONLY for `{"subject":"≤6 words"}` (plus `type` only if rules returned `fact`).
3. Strict JSON/enum/length validation; invalid/timeout → subject = first 4 content words, type from rules.
- Body stored verbatim always. Card lets user edit everything.
- Known Magic limitation: `MagicAlarmCommand` discards the parsed date; memory keeps the full resolved date-time from `CalendarPhraseEngine`/`CalendarTemporalResolver`.

## Confirmation UX
- **Save**: editable card (type · subject · when · text) with Save / Edit / Cancel; nothing persists until Save. If an active entry with same key exists → **"Replaces: …" toggle**, default ON for fact/preference/place, OFF for event/note (two dentist visits coexist).
- **Replace candidates (fix #3):** subject comes from a small model and may vary, so exact-key match alone misses duplicates. Candidates = exact `type+subject_key` match **plus** up to 3 same-type active entries matched lexically (mirror FTS on subject+body of the new entry). Card lists them; default-ON rule applies **only to an exact-key match**; lexical-only candidates default OFF; user picks at most one. Deterministic, model-free.
- **Delete/erase/purge**: app lexically matches active entries, shows ≤5 numbered cards; pick by tap or `n`; erase/purge require a second confirm.
- Save/forget commit to mirror immediately ("Saved · will sync").

## Recall at answer time (prefetch, no model call)
- Block ≤400 estimated tokens (`TokenEstimate`), taken from the history share; evidence budgets unchanged.
- Order and caps: (1) active preferences, cap 8; (2) events dated now−2d…now+14d, soonest first; (3) lexical FTS matches of user message (EN+DE stopwords removed) on subject+text, BM25 then newest, cap 8.
- Only the head of each supersede chain.
- Line format: `type · subject: text (when; saved YYYY-MM-DD)`.
- Over budget → drop from (3), then (2), then (1).
- Block labelled as reference; the user's live message wins.
- Not a chat source; stacks with Web/Wikipedia/Health.
- **Fix #7:** block is built fresh per turn and **never stored in chat history/context** (same as Health evidence); history keeps only user text + reply.

## Privacy gate
Memory block injected only into passes that cannot reach the network: plain chat, Health's tool-free inference, final tool-free synthesis pass of Web/Wikipedia. The pass where the model writes Tavily/Wikipedia queries **never** sees memory → deterministic no-leak to third parties. Implement by adding the block only at tool-free inference call sites (cf. `AssistantController` ~:393–406, :455–503, budgets ~:184–192, :510–514, :560–573; `AssistantChatState` context rolling :69–83).

**No-tool-call Web/Wikipedia turns (fix #1, user decision):** Web is the default chip, so many turns end with the model answering directly, no tool call and no separate final pass. Rule: if the query-writing pass returns **no tool call** and the memory block is non-empty, the app **discards that draft answer and runs one extra tool-free pass with the memory block**. Memory block empty → keep first answer, no extra pass. Discarded draft is never shown or stored. Accepted cost: ~2× latency on those turns.

## Local mirror, outbox, sync
- `memory_mirror.db`: own version/migrations, **not** `grace_launcher.db`; `@Fts4` index; excluded from Android backup; app sandbox; not field-encrypted (would break FTS). Tables: `entries` (same columns as server incl. `change_seq`), `outbox` (op, payload, client uuid, attempts), `sync_state` (cursor = last `max_seq`). Fully rebuildable from server.
- Sync: push outbox (idempotent by client UUID), then pull `memory_changes(p_since_seq = cursor − 50)` (overlap, fix #2), upsert rows idempotently by id, apply tombstones, store returned `max_seq` as cursor (monotonic sequence, **not** a time cursor). Server canonical; append-only → conflicts trivial (same-subject concurrent saves both survive; supersede pointer wins only if target still active, else server returns `conflict: true` + head id and the entry is shown as a flagged conflict pair in `/recall`, fix #8a).
- Triggers: chat open, after each write, connectivity regained. No periodic WorkManager.
- Token read from existing `HealthVault`, never logged/duplicated.
- Erase/purge remove from mirror immediately and queue server hard-delete (`'PURGE'`), retried until confirmed; tombstones from pull delete locally.
- **No token**: memory works fully locally; Advanced shows "Memory: local only, not synced" until a token exists, then syncs. Removing/replacing the token keeps local memory; outbox waits.

## Advanced settings
Single memory status line: local only / synced at … / N pending. No other V1 settings.

## Code Style
Match surrounding Kotlin: direct construction (no DI), small files, `strings.xml` per language with section comments (EN + DE), no raw user text or tokens in logs, no abstractions beyond need.

## Testing Strategy
JVM tests (TDD, RED→GREEN), real fixtures:
- Command parser: all triggers, chip override rules, unknown `/xyz` passthrough.
- Parse pipeline: rules per type EN/DE; model JSON validation, invalid/timeout fallback; date preserved.
- Recall builder: ordering, caps, budget dropping, chain heads only, stopwords EN+DE.
- Privacy gate: block absent in query-writing pass; present in tool-free passes; no-tool-call Web turn with non-empty block → extra pass runs and draft discarded; empty block → no extra pass.
- Recall/history: block never persisted into chat history/context.
- Replace candidates: exact-key default ON (fact/preference/place), lexical-only default OFF, ≤3 lexical, one pick max.
- Store: supersede, soft delete, purge chain, outbox idempotency, tombstone apply.
- Sync: fake client; cursor = `max_seq` with `−50` overlap, idempotent re-apply; headers (`x-grace-token`, `Content-Profile`) sent, no token in URL/body; no-token path; token removed/replaced; conflict pair flagged.
- Emulator/device checks are user-run; not claimed otherwise.

## Boundaries
**Always**
- Parse commands before any model call; require user confirm before persisting.
- Keep body verbatim; keep newest active value in recall.
- Keep memory out of network-reaching passes.
- Keep memory local-working with no token.

**Ask first**
- Any automatic (unprompted) extraction.
- Adding settings beyond the status line.
- Field-level encryption or changing backup rules.
- Changing evidence budgets.

**Never**
- Let the model write memory or choose delete targets.
- Touch `grace_launcher.db`/v18 migrations for memory.
- Log/commit tokens, anon key, raw memory text.
- Push anything to dashboard repos; use service-role key.
- Gate local memory on token or network.

## Success Criteria
- [ ] Each trigger and chip works; slash overrides chip; unknown `/xyz` is ordinary text.
- [ ] Nothing persists before Save; Cancel persists nothing.
- [ ] Same-key active entry → Replaces toggle default ON (fact/preference/place), OFF (event/note).
- [ ] Differently-worded subject of same type: up to 3 lexical candidates listed, default OFF, max one chosen (fix #3).
- [ ] Soft delete excluded from recall but kept; purge removes chain and queues server delete.
- [ ] Prefetch block ≤400 est. tokens, correct order/caps/drop order, chain heads only.
- [ ] Block absent from the Web/Wikipedia query-writing pass; present in tool-free passes.
- [ ] Web/Wikipedia turn with no tool call + non-empty block → draft discarded (not shown/stored), one extra tool-free pass with block; empty block → first answer kept (fix #1).
- [ ] Memory block never present in stored chat history/context after a turn (fix #7).
- [ ] Sync cursor is `change_seq`-based `max_seq` (from `memory_changes`), pulled with `since_seq − 50` overlap; no time-based cursor; re-pull idempotent (fix #2).
- [ ] All RPC calls send `x-grace-token` + `Content-Profile: grace_memory` headers; token never in args/URL/logs (fixes #4, #6).
- [ ] Server `conflict: true` → entry shown as flagged conflict pair in `/recall` (fix #8a).
- [ ] Works with no `hc_` token; status line shows local-only; syncs after token saved.
- [ ] Mirror in separate DB, excluded from backup.
- [ ] Newest active value wins in answers.
- [ ] Parse fallback works when model output is invalid/slow.

## Out of Scope (Deferred)
- Automatic extraction; model-chosen deletes.
- Home Magic prefix commands.
- google-tasks (`/task`, `/task-list`, `/task-done`).
- Dashboard `user_memories` import.
- Vector/semantic recall.
- Periodic background sync.
- Field-level encryption.

## Open Questions
- **Anon key + `hc_` token on device**; PostgREST has no per-function rate limit (256-bit tokens, brute force impractical).
- **Local mirror not field-encrypted** (FTS needs plaintext); relies on Android app sandbox + backup exclusion.
- **Supabase URL/anon key config** for launcher builds (ignored `.env` like Tavily) to be finalized.
- **Small-model subject extraction quality unverified**; fallback keeps it working.
- **`hc_` token has no scopes**: emulator 5558 and Titan share it; any holder can read/write/purge; revoking disables Health and memory together.
- **Coupling to dashboard schema** (`public.mcp_tokens`): server auth fails closed; mirror keeps working.
- Token-hash/exposed-schema/approval items: see `SPEC-memory-ledger.md`.
- Extra tool-free pass on no-tool-call Web turns doubles latency (accepted); revisit if too slow on device.
- Dashboard `user_memories` import deferred; pgvector deferred.
- Google Tasks needs own OAuth client; Testing-mode refresh tokens expire after 7 days; Tasks API time-of-day limitation (see `CAPABILITY-MAP.md`).
- Exact stopword lists and FTS4 tokenizer for DE (diacritics) to decide.
- Token budget of 400 vs 4,096-token engine context: validate with Qwen/Gemma on device.
