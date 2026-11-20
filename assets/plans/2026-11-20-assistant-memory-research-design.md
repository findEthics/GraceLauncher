# Assistant Memory — Research & Design Docs (docs-only pass)

## Overview
User wants long-term memory for Grace Launcher's offline assistant. This pass is **investigation/research only**: the implementer writes a research + spec document set under `docs/memory/` (spec-driven-development format) and one pointer line in `tasks.md`. **No app code, no backend code, no migrations, no pushes.**

Goals the design must serve (from user):
- Personal use, single user. Lightweight. Close to deterministic, **not RAG**, model-intelligence agnostic (works with Gemma4 E2B / Qwen 1.5–1.7B on device and any future model/setup).
- Remember facts, times, dates, places, preferences. (Tasks → separate Google Tasks module, deferred.)
- Grows over time, **retains everything**, but is accurate about recency (newest active value wins).
- Other setups outside the launcher (desktop agents, other models) must access the same memory the same way.

## Business & UX Decisions
Each with reasoning; all must appear in the specs.

1. **Deliverable = docs only.** User asked for research pass. Implementation happens in later approved passes, module by module.
2. **Core unit = typed append-only ledger with supersede** (not LLM Wiki pages, not free-text vectors, not KG). Research consensus (personal-agent memory docs, m3-memory, SodaMem, bitemporal companion-memory writeups): typed facts + valid_from/valid_to + supersede chain + provenance give deterministic "which value is current"; append-only logs/vectors leave currency to the model. LLM Wiki is kept only as a **derived deterministic view** because a 1–2B on-device model would corrupt self-maintained pages.
3. **Types (V1):** `fact`, `preference`, `event` (has `occurred_at`/when), `place`, `note` (verbatim fallback). Tasks were removed from memory: user wants tasks in Google Tasks (deterministic, existing app).
4. **Write path = explicit + model-assisted parse, never automatic extraction.** Model never writes or chooses delete targets.
5. **Triggers (chat only, no Home Magic prefix):**
   - Add: `/remember <text>`, `/save <text>`
   - Soft delete: `/delete <words|n>`, `/forget <words|n>` → status=invalidated, valid_to=now, row kept, excluded from recall.
   - Hard delete: `/erase <words|n>`, `/purge <words|n>` → hard-delete the entry **and its whole supersede chain/history**, after a second explicit confirm (privacy right-to-forget).
   - Browse: `/recall <words>` (numbered app-rendered cards of ACTIVE matches, newest first, with type + date; `/recall` alone → 10 most recent), `/history <n>` (supersede chain of card n).
   - Slash commands are English-only tokens; content parsing cues support EN + DE.
6. **Chips:** new tools-popup chips `memory-save`, `memory-delete`, `memory-purge`. They are **one-shot action modes**, mutually exclusive with each other, separate from the existing source enum (WEB/WIKIPEDIA/HEALTH/NONE). Apply to the next submit only, then auto-clear to prior source. While a mode is active no Web/Wikipedia/Health fetch runs. A slash prefix in text overrides the chip.
7. **Confirmation:** save shows an editable card (type · subject · when · text) with Save / Edit / Cancel; nothing persists until Save. If an active entry with the same key exists, card shows a **"Replaces: …" toggle** — default ON for fact/preference/place, OFF for event/note (two dentist visits coexist).
   - **Review fix #3:** subject comes from a small model and may vary, so exact-key match alone misses duplicates. Replace candidates = exact `type+subject_key` match **plus** up to 3 same-type active entries matched lexically (FTS on subject+body of the new entry). Card lists them; default-ON rule applies only to an exact-key match; lexical-only candidates default OFF; user picks at most one. Deterministic, model-free. Delete/erase/purge: app lexically matches active entries, shows ≤5 numbered cards, user picks by tap or `n`; erase/purge require a second confirm.
8. **Recall at answer time = deterministic prefetch block, no model call.** ≤400 estimated tokens, taken from the history share (evidence budgets unchanged). Order and caps: (1) active preferences, cap 8; (2) events dated now−2d…now+14d, soonest first; (3) lexical FTS matches of the user message (EN+DE stopwords removed) on subject+text, BM25 then newest, cap 8. Only the head of each supersede chain. Line format `type · subject: text (when; saved YYYY-MM-DD)`. Over budget → drop from (3), then (2), then (1). Block labelled as reference; the user's live message wins. Not a chat source, so it stacks with Web/Wikipedia/Health.
9. **Privacy gate:** memory block is injected only into passes that cannot reach the network: plain chat, Health's tool-free inference, and the final tool-free synthesis pass of Web/Wikipedia. The pass where the model writes Tavily/Wikipedia queries **never** sees memory → deterministic no-leak to third parties.
   - **Review fix #1 (user decision):** Web is the default chip, so many turns are Web turns where the model answers directly without a tool call (no separate final pass exists). Rule: on a Web/Wikipedia turn, if the query-writing pass returns **no tool call** and the memory block is non-empty, the app **discards that draft answer and runs one extra tool-free pass with the memory block**. If the memory block is empty, keep the first answer (no extra pass). Accepted cost: ~2× latency on those turns. Discarded draft is never shown or stored.
   - **Review fix #7:** the memory block is built fresh per turn and **never stored in chat history/context** (same as Health evidence); history keeps only user text + reply.
10. **Offline:** memory works fully locally even with **no hc_ token saved** (mirror + outbox); Advanced shows "Memory: local only, not synced" until a token exists; syncs once saved. Removing/replacing the token keeps local memory and the outbox waits. Save/forget commit to mirror immediately ("Saved · will sync"). Erase/purge remove from mirror immediately and queue the server hard-delete, retried until confirmed. Sync triggers: chat open, after each write, connectivity regained. No periodic WorkManager.
11. **Google Tasks = separate module, DEFERRED** (user chose to defer when OAuth isolation surfaced). Record decisions made so far: commands `/task <text>` (add, confirm card), `/task-list` (open tasks, numbered cards, no model), `/task-done <n|text>` (numbered pick → complete); hyphenated names chosen to reduce typo-triggered actions; no delete from chat; default Google Tasks list; time handling = due date only + time prepended to notes (`⏰ 14:30`) with a card warning that the API can't set a time; online-only, fail closed ("Tasks needs a connection"), no queue (avoids duplicates); existing `-` Home to-do route untouched. Open for later: needs its own Google OAuth client (tasks scope only) — must not reuse the dashboard's encrypted tokens/keys.
12. **Nothing new goes to the health-dashboard repos** (neither GitHub `findEthics/health-dashboard` nor the HF Space repo). GitHub main → `sync-to-hf.yml` force-pushes the Space and would erase the HF-only MCP server. All new code (later passes) lives in the Grace Launcher repo.
13. **No new hosted service.** User's model: read/write Supabase directly. Launcher and desktop clients call Supabase PostgREST RPC with the public anon key; all logic lives in Postgres functions.
14. **Auth = reuse the exact existing `hc_` token** (user's explicit choice). Functions verify it against the dashboard's `public.mcp_tokens` (SHA-256 hash, not revoked) with a read-only grant. One token covers Health + memory.

## Technical Decisions
To be written into the specs as the target design (not implemented this pass).

### Capability map (CAPABILITY-MAP.md)
| Module id | Responsibility | Depends on |
|---|---|---|
| `memory-ledger` | `grace_memory` schema in existing Supabase project: table, indexes, RPC functions, token check | existing `public.mcp_tokens` (read-only) |
| `memory-chat` | Launcher: slash commands, chips, confirm/pick cards, parse pipeline, prefetch, `memory_mirror.db`, outbox sync | `memory-ledger` (for sync only; local works standalone) |
| `memory-bridge` | Local stdio MCP server for desktop agents + deterministic markdown export | `memory-ledger` |
| `google-tasks` | **Deferred** | own OAuth client (TBD) |

Build order: `memory-ledger` → `memory-chat`, `memory-bridge` (parallel). `google-tasks` independent, later. Module ids are stable; never rename.

### memory-ledger (SPEC-memory-ledger.md)
- Same Supabase project as the dashboard, **new schema `grace_memory`**; migrations stored in launcher repo (e.g. `server/supabase/grace_memory/NNN_*.sql`) — path is a spec proposal; this pass writes no SQL files except snippets inside the spec.
- Dedicated owner role for the functions; the `anon` role gets **EXECUTE on the RPC functions only**, no table privileges. The service-role key is never used by launcher/bridge. Functions are `SECURITY DEFINER` with fixed `search_path`.
- Read-only access to dashboard data: `SELECT (token_hash, user_id, revoked_at)` on `public.mcp_tokens` only. **Never update `last_used_at`**, never touch `user_memories` or any other dashboard table.
- Table sketch (spec should refine):
```sql
create table grace_memory.memory_entries (
  id            uuid primary key,             -- client-generated (idempotent sync)
  user_id       uuid not null,                -- from token row, never from client
  type          text not null check (type in ('fact','preference','event','place','note')),
  subject       text not null,                -- display form
  subject_key   text not null,                -- type-scoped normalized key (lower, trim, diacritics folded)
  body          text not null,                -- verbatim user text
  occurred_at   timestamptz,                  -- 'when' for events/dated facts
  valid_from    timestamptz not null,
  valid_to      timestamptz,                  -- null = active
  status        text not null default 'active' check (status in ('active','superseded','invalidated')),
  supersedes    uuid references grace_memory.memory_entries(id),
  superseded_by uuid references grace_memory.memory_entries(id),
  source        text not null,                -- e.g. 'launcher:<model>', 'bridge:<client>'
  created_at    timestamptz not null default now(),   -- transaction time
  updated_at    timestamptz not null default now(),
  fts           tsvector generated always as (to_tsvector('simple', subject || ' ' || body)) stored
);
```
- Functions (caller identity from the `x-grace-token` header via `_caller_user_id()` — see review fix #4; resolve `user_id`; reject invalid/revoked). Signatures below are pre-review; **drop `p_token`** and use `p_since_seq bigint` for `memory_changes`:
  - `memory_remember(p_token, p_entry jsonb, p_supersede_id uuid)` — insert; if `p_supersede_id` set and active+same user, close it (valid_to = new.valid_from, status=superseded, superseded_by). Idempotent on `id`.
  - `memory_forget(p_token, p_id)` — soft invalidate.
  - `memory_purge(p_token, p_id, p_confirm text)` — hard delete entry + whole chain; requires `p_confirm = 'PURGE'`.
  - `memory_recall(p_token, p_query text, p_limit int)` — active only, `websearch_to_tsquery('simple', …)`, rank then `valid_from desc`; empty query → most recent.
  - `memory_history(p_token, p_id)` — full chain ordered.
  - `memory_changes(p_token, p_since timestamptz)` — rows with `updated_at > since` plus purge tombstones (ids only) for mirror pull.
  - `memory_export(p_token)` — all rows (incl. history) as JSON for the bridge.
- Search is lexical (Postgres FTS `simple` config handles EN/DE mix), deterministic, no pgvector. Vectors explicitly deferred (V2, optional, server-side only).
- Purge tombstones: a minimal `grace_memory.purged_ids(id, user_id, purged_at, change_seq)` so other mirrors delete locally; no content kept.

**Review fixes for the ledger (must be in SPEC-memory-ledger.md):**
- **#2 Sync cursor = monotonic change sequence, not time.** `now()` is transaction-start time, so a cursor on `updated_at` can skip rows committed late. Add `change_seq bigint` to `memory_entries` and `purged_ids`, set from one shared sequence `grace_memory.change_seq` by a `BEFORE INSERT OR UPDATE` trigger (trigger also sets `updated_at = clock_timestamp()`). `memory_changes(p_since_seq bigint)` returns rows + tombstones with `change_seq > p_since_seq` ordered by `change_seq`, plus `max_seq`. Note in spec: sequence values can still commit out of order under concurrent writers; for a single user this is acceptable, and the client re-pulls with a small overlap (since_seq − 50) and upserts idempotently by id, which makes gaps self-heal.
- **#4 Token in header, not argument.** Clients send `x-grace-token: <hc_…>` header; functions read it via `current_setting('request.headers', true)::json ->> 'x-grace-token'` inside a private helper `grace_memory._caller_user_id()` (not executable by anon). Remove `p_token` from all function signatures. Errors never echo the token. Bridge sends the same header.
- **#5 Hash compatibility pre-check.** Before implementation, a read-only lookup of the dashboard's token hashing (`scripts/issue_mcp_token.py`, auth in `mcp_coach.py`) must confirm encoding (hex vs base64, any pepper/prefix handling); spec states the exact SQL expression to match, using `extensions.digest(...)` from `pgcrypto` (enabled on Supabase). Fail closed on mismatch.
- **#6 PostgREST exposure decided:** add `grace_memory` to the project's API "Exposed schemas"; clients send `Content-Profile: grace_memory` / `Accept-Profile: grace_memory` headers. No objects are created in `public`. Exposed-schema change is a shared-project setting → needs explicit approval at implementation time.
- **#8a Concurrent supersede:** if `p_supersede_id` target is no longer active (superseded/invalidated/purged), insert the new entry as active without closing anything and return `conflict: true` with the current head id; `/recall` shows two active heads of the same key as a flagged conflict pair so the user can resolve via a new `/remember` with Replaces.
- **#8b Purge as one statement:** FKs on `supersedes`/`superseded_by` stay default `NO ACTION` (checked at end of statement); `memory_purge` collects the whole chain ids (recursive CTE both directions) and deletes them in a **single `DELETE`**, then inserts tombstones; partial-chain purge is not offered.

### memory-chat (SPEC-memory-chat.md)
- Command parsing in app code before any model call; unknown `/xyz` → existing behavior (treated as ordinary text) — spec must say so explicitly.
- **/remember parse pipeline:** (1) rules: `CalendarPhraseEngine` finds a when → `event`; EN/DE cue words ("I like/prefer/hate/love", "ich mag/bevorzuge") → `preference`; ("is at/parked/located/lives in", "liegt/steht/parkt") → `place`; else `fact`. (2) Local model, tool-free, greedy, one short call, asked ONLY for `{"subject":"≤6 words"}` (plus `type` only if rules returned `fact`). (3) Strict JSON/enum/length validation; invalid/timeout → subject = first 4 content words, type from rules. Body stored verbatim always. Card lets user edit everything.
- Note the known Magic limitation found during research: `MagicAlarmCommand` discards the parsed date; memory must keep the full resolved date-time from `CalendarPhraseEngine`/`CalendarTemporalResolver`.
- Local mirror: separate Room DB `memory_mirror.db` (own version/migrations, **not** `grace_launcher.db` v18), Room `@Fts4` index (Room has no FTS5), excluded from Android backup, app-sandbox protected, not field-encrypted (would break FTS). Tables: `entries` (same columns as server), `outbox` (op, payload, client uuid, attempts), `sync_state` (cursor). Fully rebuildable from server.
- Sync: push outbox (idempotent by client UUID) then pull `memory_changes(since_seq − 50)` and store `max_seq` as cursor (review fix #2); server canonical; append-only makes conflicts trivial (same-subject concurrent saves both survive; supersede pointer wins only if target still active, else flagged conflict pair — fix #8a).
- Network: launcher calls Supabase REST `POST {SUPABASE_URL}/rest/v1/rpc/<fn>` with headers `apikey: <anon>`, `x-grace-token: <hc_…>`, `Content-Profile: grace_memory` (fixes #4, #6); the hc_ token is read from the existing `HealthVault` (never logged). Supabase URL + anon key: spec must state how they're configured (build-time from ignored `.env`, like the Tavily key) — anon key is public-safe but still not committed.
- Prefetch + privacy gate as in decisions 8–9; implement by adding memory block only at the tool-free inference call sites.
- Advanced settings: memory status line (local only / synced at … / N pending), no other settings in V1.

### memory-bridge (SPEC-memory-bridge.md)
- Small Python stdio MCP server in launcher repo (e.g. `tools/memory-bridge/`), `mcp` SDK (dashboard uses `mcp==1.29.1`), `httpx` to the same RPC functions. Config via env: `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `GRACE_MEMORY_TOKEN` (an hc_ token). Tools mirror the functions: `memory_remember`, `memory_recall`, `memory_forget`, `memory_purge` (requires explicit confirm arg), `memory_history`, `memory_export`.
- `memory_export` renders **deterministic markdown, no LLM** (Karpathy-compatible view): `index.md` (one line per subject: type, current value, last date), one page per subject (current value + dated history table), `log.md` (`## [YYYY-MM-DD] save|supersede|forget|purge | subject`). LLM-written synthesis pages deferred to V2 (desktop, large model, read-only from ledger).

### RESEARCH.md content
Sources gathered this session (cite with links):
- Karpathy LLM Wiki gist (https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f): raw sources / wiki / schema layers; ingest/query/lint; index.md + log.md; works at ~hundreds of pages without embeddings; risk: errors compound, needs human review → why derived-view only.
- MCP Knowledge Graph Memory Server (modelcontextprotocol/servers src/memory): entities/relations/observations; heavier ceremony.
- mem0 (github.com/mem0ai/mem0): LLM extraction on every add, hybrid semantic+BM25+entity; requires LLM + embedder → too model-dependent.
- Letta/MemGPT (docs.letta.com): core vs archival memory, self-editing by model → model-dependent.
- personal-agent.org memory docs: agent read-only, curator proposes, deterministic committer; push prefetch "Known World State" block, no model call; supersede not overwrite; bitemporal time-travel.
- m3-memory MEMORY_MODEL.md, SodaMem (arXiv 2608.08055), DCPM (arXiv 2606.09483), graph personalized memory survey (arXiv 2609.08599), companion memory (zenn.dev): bitemporal validity, supersede chains, "deterministic freshness beats free-form judgment".
- Local-first tools: mnemo (deterministic writes, SQLite+FTS5), Amneshia (markdown-as-truth, zero-LLM engine), all41n14lla, minimem, recall, sqlite-memory.
- Supabase pgvector docs: available but rejected for V1 (non-deterministic ranking).
- Existing backend facts: dashboard `user_memories` (free text + pgvector 384, MiniLM, auto-extracted) — left untouched, not imported in V1.
- Google Tasks findings: Calendar tasks and Google Tasks app are the same data; Calendar API has no task event type; Tasks API `due` is date-only (time not readable/writable, no recurring creation); official Workspace remote MCP servers (developer preview) cover Calendar but not Tasks; `gws` CLI (googleworkspace/cli, unofficial) reaches Tasks API, its built-in MCP was removed in v0.8.0, third-party curated wrapper exists; desktop-only, not runnable in the launcher; `gws` not installed on this Mac.
- Option comparison table: Typed ledger vs LLM Wiki vs vectors vs KG, scored on determinism, model-agnosticism, recency correctness, history retention, small-model fit, cross-client access, effort.

## Relevant Files
(read-only context for the docs; do not modify)
- `app/src/main/java/com/galaxyrio/gracelauncher/assistant/AssistantController.kt` — turn flow; budgets at ~:184–192, :510–514, :560–573; tool loop :393–406, :455–503.
- `assistant/AssistantChatState.kt` — RAM-only chat, `ChatSource` enum (WEB/WIKIPEDIA/NONE/HEALTH), context rolling :69–83.
- `assistant/AssistantUi.kt` — tools popup/chips :168–205.
- `assistant/HealthTurn.kt`, `HealthMcpClient.kt`, `HealthVault.kt` — app-driven fetch → tool-free inference pattern; hc_ token storage.
- `assistant/TokenEstimate.kt` — token estimation.
- `magic/MagicAlarmCommand.kt`, `com/katoaapps/openminilaunch/features/calendar/engine/CalendarPhraseEngine.kt`, `CalendarTemporalResolver.kt` — deterministic date parsing to reuse.
- `data/LauncherDatabase.kt` — Room v18 (memory must NOT use it).
- `platform/NotesCapture.kt` — existing `-` to-do (custom provider, untouched).
- Backend (reference only, never modify): `~/genie/health_app/hf-health-dashboard/migrations/015–017,022`, `app/api/routers/mcp_coach.py`, `.github/workflows/sync-to-hf.yml`.
- `tasks.md` — add one Pending pointer line.
- Output folder: `docs/memory/` (new).

## Acceptance Criteria
- `docs/memory/` contains exactly: `RESEARCH.md`, `CAPABILITY-MAP.md`, `SPEC-memory-ledger.md`, `SPEC-memory-chat.md`, `SPEC-memory-bridge.md`.
- Each SPEC follows the spec-driven template: title with `— V1`, Objective, Tech Stack, Commands, Project Structure, Code Style, Testing Strategy, Boundaries (Always / Ask first / Never), Success Criteria (testable), Out of Scope (Deferred), Open Questions.
- `CAPABILITY-MAP.md` has the module table, dependency direction, build order, and a `google-tasks` Deferred section recording decision 11 verbatim-in-substance.
- Every decision 1–14 above appears in the specs or RESEARCH with its reasoning.
- `RESEARCH.md` cites the listed sources with URLs and includes the option comparison table.
- `SPEC-memory-ledger.md` Boundaries state: never modify/push dashboard GitHub or HF Space repos; never update `public.mcp_tokens.last_used_at`; never use the service-role key from clients; anon role EXECUTE-only.
- Open Questions/Risks in specs include all items in "Open Questions & Risks" below.
- `tasks.md` Pending gains one line pointing to `docs/memory/CAPABILITY-MAP.md` marked "design only, not implemented".
- No changes outside `docs/memory/` and that one `tasks.md` line. No code, SQL files, Gradle or manifest edits. No commits/pushes.
- Docs are concise (user prefers concise writing; sacrifice grammar for concision).
- **Revision 2 (post-review):** docs reflect review fixes #1–#8 (decisions 7, 9 sub-bullets; ledger "Review fixes" block; memory-chat sync/network bullets). No spec still shows `p_token` arguments or a time-based sync cursor. Each fix appears in the relevant SPEC's design text **and** a testable Success Criterion.
- **Revision 2 URLs:** `RESEARCH.md` gives a working URL for every listed source, at minimum: personal-agent.org memory docs (https://personal-agent.org/docs/features/memory/), m3-memory (https://github.com/skynetcmd/m3-memory/blob/main/docs/MEMORY_MODEL.md), SodaMem (https://arxiv.org/html/2608.08055), DCPM (https://arxiv.org/html/2606.09483), graph memory survey (https://arxiv.org/html/2609.08599v1), zenn companion memory (https://zenn.dev/haru0416/articles/843c6c29c04c7c?locale=en), mnemo (https://github.com/arttttt/mnemo), Amneshia (https://github.com/sabilmurti/amneshia), all41n14lla (https://github.com/bludragon-ai/all41n14lla), minimem (https://github.com/alexngai/minimem), recall (https://github.com/j0yen/recall), sqlite-memory (https://github.com/sqliteai/sqlite-memory/), Supabase vector columns (https://supabase.com/docs/guides/ai/vector-columns), Letta MemGPT (https://docs.letta.com/concepts/memgpt), Google Tasks resource (https://developers.google.com/workspace/tasks/reference/rest/v1/tasks), Workspace MCP servers (https://developers.google.com/workspace/guides/configure-mcp-servers), gws CLI (https://github.com/googleworkspace/cli), Tasks in Calendar help (https://support.google.com/calendar/answer/9901136), Calendar event types (https://developers.google.com/workspace/calendar/api/guides/event-types). Use these exact URLs (gathered this session); do not invent others.

## Tests to Add/Run
- No code → no unit tests. Run these checks and report:
  - `git status --short` shows only new `docs/memory/*` files and modified `tasks.md`.
  - `git diff --stat tasks.md` shows a single added line.
  - A grep check that each SPEC contains all section headers (Objective, Tech Stack, Commands, Project Structure, Code Style, Testing Strategy, Boundaries, Success Criteria, Out of Scope (Deferred), Open Questions).
  - Grep that no file contains secrets (no `hc_` token values, no `eyJ` JWT strings, no Supabase URLs with real project refs).
- No visual/browser verification.

## Scope Guardrails
- Docs only. Do not touch `app/`, Gradle files, manifests, `app/schemas/`, or any existing doc besides one `tasks.md` line.
- Do not read, modify, commit or push anything in `~/genie/health_app/hf-health-dashboard` or any dashboard remote. Do not run Supabase/SQL against any live DB (no Databricks/Supabase calls).
- Do not install `gws` or any MCP server.
- No commits/pushes.
- Do not invent decisions beyond this plan; anything unresolved goes to Open Questions.
- Never copy `.env` values, tokens or keys into docs.

## Open Questions & Risks
- **hc_ token has no scopes:** any holder (emulator 5558 and Titan share it) can read, write and purge memory; revoking it disables Health and memory together. Future: per-client tokens or a scopes column — but that would require a dashboard schema change, which the user has ruled out; alternative is a grace_memory-owned token table later.
- **Coupling to dashboard schema:** ledger depends on `public.mcp_tokens(token_hash, user_id, revoked_at)` staying stable; a dashboard migration could break memory auth. Spec should name the exact columns and a failure mode (fail closed, mirror keeps working).
- Granting a new role SELECT on `public.mcp_tokens` and creating schema `grace_memory` are DB changes in the shared Supabase project — they are not dashboard *code* changes but still need explicit approval at implementation time; also the schema must be exposed to PostgREST (API settings "exposed schemas" or wrapper functions in `public`) — decide at ledger implementation.
- Anon key + hc_ token on device; PostgREST has no per-function rate limit (tokens are 256-bit, brute force impractical).
- Local mirror not field-encrypted (FTS needs plaintext); relies on Android app sandbox + backup exclusion.
- Supabase URL/anon key configuration for launcher builds (ignored `.env` like Tavily) to be finalized.
- Small-model subject extraction quality unverified; fallback keeps it working.
- google-tasks: needs its own Google OAuth client; Testing-mode refresh tokens expire after 7 days unless app published (sensitive scope → "unverified app" warning for personal use). Tasks API time-of-day limitation.
- Dashboard `user_memories` import deferred.
- pgvector semantic recall deferred (V2, optional).

## Implementation Task List
- [ ] Create `docs/memory/RESEARCH.md`: problem, constraints, sources with URLs, option comparison table, why typed ledger + deterministic prefetch, LLM Wiki as derived view, Google Tasks findings, existing backend facts.
- [ ] Create `docs/memory/CAPABILITY-MAP.md`: module table, build order, google-tasks Deferred section (decision 11), cross-cutting decisions 12–14.
- [ ] Create `docs/memory/SPEC-memory-ledger.md` (schema, functions, auth, grants, tombstones, boundaries, success criteria, deferred, open questions).
- [ ] Create `docs/memory/SPEC-memory-chat.md` (commands, chips, cards, parse pipeline, prefetch rules + budget, privacy gate, mirror/outbox/sync, offline/no-token behavior, Advanced status line).
- [ ] Create `docs/memory/SPEC-memory-bridge.md` (stdio MCP tools, env config, deterministic markdown export format).
- [ ] Add one Pending line to `tasks.md` pointing to `docs/memory/CAPABILITY-MAP.md` ("design only, not implemented").
- [ ] Run the checks in "Tests to Add/Run" and report results.

### Revision 2 (post-review) — docs already exist; edit them in place
- [ ] `SPEC-memory-chat.md`: add fix #1 (extra tool-free memory pass when a Web/Wikipedia turn returns no tool call and the block is non-empty; discarded draft never shown/stored), fix #3 (lexical replace candidates), fix #7 (block never stored in history), updated sync cursor + headers; add matching Success Criteria.
- [ ] `SPEC-memory-ledger.md`: add fixes #2 (change_seq + trigger + overlap re-pull), #4 (header token, `_caller_user_id()`, no `p_token`), #5 (hash-compat pre-check as an "Ask first"/precondition), #6 (exposed schema + profile headers; approval needed), #8a (conflict return), #8b (single-statement chain purge); update function signatures and Success Criteria.
- [ ] `SPEC-memory-bridge.md`: send `x-grace-token` + profile headers; no token args.
- [ ] `RESEARCH.md`: add the URLs listed under Acceptance Criteria → Revision 2 URLs.
- [ ] `CAPABILITY-MAP.md`: only if a cross-cutting statement now contradicts the fixes.
- [ ] Re-run the checks; additionally grep that no doc contains `p_token` and that `change_seq` appears in ledger and chat specs. `tasks.md` untouched in this revision.
