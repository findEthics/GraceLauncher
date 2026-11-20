# SPEC memory-ledger — V1

Status: design only, not implemented. Module id: `memory-ledger`. See `CAPABILITY-MAP.md`, `RESEARCH.md`.

## Objective
Server-side canonical store for assistant memory: typed, append-only ledger with supersede chains, in the **existing Supabase project**, new schema `grace_memory`. All logic in Postgres functions; clients (launcher, bridge) call PostgREST RPC with the public anon key + the user's existing `hc_` token. No new hosted service.

Why: typed facts + `valid_from/valid_to` + supersede chain + provenance make "which value is current" deterministic. Single user, retain everything, newest active value wins.

## Tech Stack
- Supabase Postgres + PostgREST RPC (`POST {SUPABASE_URL}/rest/v1/rpc/<fn>`, headers `apikey: <anon>`, `x-grace-token: <hc_…>`, `Content-Profile: grace_memory`).
- Postgres FTS, config `simple` (EN/DE mix). No pgvector (deferred V2, server-side only).
- `pgcrypto` (`extensions.digest(...)`, enabled on Supabase) for token hash check; exact expression fixed by the hash-compat pre-check (see Auth).
- SQL migrations stored in launcher repo (proposed): `server/supabase/grace_memory/NNN_*.sql`. This pass writes no SQL files.

## Commands
(Proposed; run at implementation time, with explicit approval.)
- Apply migration: `psql`/Supabase CLI against the shared project — **needs explicit approval** (shared DB).
- SQL smoke tests: run in a scratch/branch DB, not prod.
- Verify grants: `select has_table_privilege('anon','grace_memory.memory_entries','select')` must be false; `has_function_privilege('anon','grace_memory.memory_recall(text,int)','execute')` true; `has_function_privilege('anon','grace_memory._caller_user_id()','execute')` false.

## Project Structure
```
server/supabase/grace_memory/
  001_schema.sql        # schema, table, indexes, purged_ids
  002_auth.sql          # owner role, grants, token check helper
  003_functions.sql     # RPC functions
  tests/*.sql           # pgTAP-style or plain assert scripts
```
Path is a proposal.

## Data model
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
  source        text not null,                -- 'launcher:<model>', 'bridge:<client>'
  created_at    timestamptz not null default now(),   -- transaction time
  updated_at    timestamptz not null default now(),   -- set by trigger (clock_timestamp())
  change_seq    bigint not null,                      -- set by trigger from grace_memory.change_seq
  fts           tsvector generated always as (to_tsvector('simple', subject || ' ' || body)) stored
);
create sequence grace_memory.change_seq;               -- one shared sequence, entries + tombstones
create table grace_memory.purged_ids (
  id uuid primary key, user_id uuid not null, purged_at timestamptz not null default now(),
  change_seq bigint not null                            -- same trigger/sequence
);  -- tombstones only; no content kept
-- BEFORE INSERT OR UPDATE trigger on both tables: new.change_seq := nextval('grace_memory.change_seq');
-- on memory_entries also: new.updated_at := clock_timestamp();
```
Spec to refine at implementation: indexes (`(user_id,status,type)`, GIN on `fts`, `(user_id,subject_key)`, `(user_id,change_seq)`), trigger, body/subject length caps, unique active head per `(user_id,type,subject_key)` for fact/preference/place only if superseded flag used (events/notes may coexist).

Bitemporal-ish: `valid_from/valid_to` = real-world validity; `created_at` = transaction time.

## Auth & grants
- Dedicated owner role for functions. Functions `SECURITY DEFINER`, fixed `search_path`.
- `anon` role: **EXECUTE on RPC functions only**, no table/schema-table privileges. Revoke default `PUBLIC` execute.
- Owner role gets read-only `SELECT (token_hash, user_id, revoked_at)` on `public.mcp_tokens` — column-level, nothing else.
- Caller identity (review fix #4): clients send header `x-grace-token: <hc_…>`. Private helper `grace_memory._caller_user_id()` reads it via `current_setting('request.headers', true)::json ->> 'x-grace-token'`, hashes it, matches `token_hash`, requires `revoked_at is null`, returns `user_id`. Helper **not executable by anon**. No function takes a token argument. Invalid/revoked/unknown/missing header → reject (same error, no detail); errors never echo the token.
- **Hash-compat pre-check (fix #5, precondition):** before implementation, a read-only look at the dashboard's token hashing (`scripts/issue_mcp_token.py`, auth in `mcp_coach.py`) must confirm encoding (hex vs base64, pepper/prefix handling). The spec'd SQL expression (using `extensions.digest(...)`) is then written to match exactly. Mismatch → fail closed. Do not implement the helper until confirmed.
- **Never** update `public.mcp_tokens.last_used_at`. Never touch `user_memories` or any other dashboard table.
- Service-role key never used by launcher/bridge.
- Fail closed if the dashboard schema changes (missing columns → function errors; mirror keeps working on clients).
- **PostgREST exposure (fix #6, decided):** add `grace_memory` to the project's API "Exposed schemas"; clients send `Content-Profile: grace_memory` (writes/RPC) / `Accept-Profile: grace_memory` (reads) headers. No objects created in `public`. Exposed-schema change is a shared-project setting → explicit approval at implementation time.

## Functions
All active-only unless stated. All resolve `user_id` via `_caller_user_id()`; no token parameter anywhere.
| Function | Behavior |
|---|---|
| `memory_remember(p_entry jsonb, p_supersede_id uuid)` | Insert; idempotent on `id`. If `p_supersede_id` set and target active + same user: close it (`valid_to = new.valid_from`, `status='superseded'`, `superseded_by=new.id`), set `new.supersedes`. **Fix #8a:** if target no longer active (superseded/invalidated/purged) → insert new as active, close nothing, return `{conflict: true, head_id: <current head id>}`; otherwise `{conflict: false}`. Two active heads of one key surface in `/recall` as a flagged conflict pair; user resolves via new `/remember` with Replaces. |
| `memory_forget(p_id)` | Soft invalidate: `status='invalidated'`, `valid_to=now()`. Row kept, excluded from recall. |
| `memory_purge(p_id, p_confirm text)` | Requires `p_confirm = 'PURGE'`. **Fix #8b:** collect whole chain ids (recursive CTE, both directions) and hard-delete them in a **single `DELETE`** (FKs on `supersedes`/`superseded_by` stay default `NO ACTION`, checked at end of statement), then insert tombstones into `purged_ids`. Partial-chain purge not offered. |
| `memory_recall(p_query text, p_limit int)` | Active only. `websearch_to_tsquery('simple', q)`, rank then `valid_from desc`. Empty query → most recent. Cap limit. |
| `memory_history(p_id)` | Full chain ordered by `valid_from`. |
| `memory_changes(p_since_seq bigint)` | **Fix #2:** rows + purge tombstones (ids only) with `change_seq > p_since_seq`, ordered by `change_seq`, plus `max_seq`. Cursor is the monotonic sequence, not time (`now()` is transaction-start, so a time cursor can skip late commits). Sequence values can still commit out of order under concurrent writers; acceptable for a single user — client re-pulls with overlap (`since_seq − 50`) and upserts idempotently by id, so gaps self-heal. |
| `memory_export()` | All rows incl. history as JSON (bridge). |

## Code Style
- SQL: lowercase keywords, snake_case, schema-qualified names, explicit `set search_path = ''`-style fixed path in each `SECURITY DEFINER`.
- Functions pure/deterministic where possible; errors via `raise exception` with stable short codes (`invalid_token`, `bad_entry`, `confirm_required`).
- Comments only for the why.

## Testing Strategy
- SQL tests in scratch DB: each function × (valid token, revoked, unknown, other user's id).
- Idempotency: same `id` twice → one row.
- Supersede: active target closes; non-active target → new entry inserted active, target untouched, `conflict: true` + head id returned.
- Purge: whole chain (multi-version, both directions) removed in one statement without FK error, tombstones present, no content in `purged_ids`; wrong `p_confirm` rejects.
- Grants: anon has no table privileges; anon can execute RPC; anon cannot execute `_caller_user_id()`/internal helpers.
- Auth: token only via `x-grace-token` header; missing/invalid/revoked header rejects; no function has a token parameter; error text never contains the token.
- Verify `last_used_at` unchanged after calls.
- `memory_changes(p_since_seq)` returns updated rows + tombstones with `change_seq` above the cursor, ordered, with `max_seq`; overlap re-pull is idempotent.

## Boundaries
**Always**
- Take `user_id` from token row, never client input.
- Keep functions `SECURITY DEFINER` with fixed `search_path`.
- Keep `anon` EXECUTE-only.
- Retain history (append-only) except explicit purge.

**Ask first**
- Any DB change in the shared Supabase project (create schema, role, column-level grant on `public.mcp_tokens`, adding `grace_memory` to PostgREST exposed schemas).
- Token-hash expression for `_caller_user_id()` — only after the hash-compat pre-check (precondition; see Auth).
- Adding scopes/own token table.
- Adding pgvector.
- Importing dashboard `user_memories`.

**Never**
- Modify, push to, or read-modify the dashboard repos: GitHub `findEthics/health-dashboard` or HF Space repo (`sync-to-hf.yml` force-push would erase HF-only MCP server).
- Update `public.mcp_tokens.last_used_at`, or write any dashboard table.
- Use the service-role key from clients (launcher/bridge).
- Grant `anon` any table privilege.
- Store/log real tokens, anon keys or project refs in repo docs.

## Success Criteria
- [ ] `anon` has zero table privileges in `grace_memory`; EXECUTE on the 7 RPCs only (not `_caller_user_id()`).
- [ ] Invalid/revoked/missing `x-grace-token` → every function rejects, no rows leaked; no function has a token parameter; errors never echo the token (fix #4).
- [ ] Hash-compat pre-check done and its exact expression recorded before `_caller_user_id()` is written; mismatch fails closed (fix #5).
- [ ] `grace_memory` exposed in PostgREST, calls work with `Content-Profile`/`Accept-Profile: grace_memory`; nothing created in `public`; approval recorded (fix #6).
- [ ] `memory_remember` idempotent on `id`; supersede closes old head (`valid_to`, `status`, `superseded_by`).
- [ ] Superseding a non-active target inserts new as active, closes nothing, returns `conflict: true` + head id (fix #8a).
- [ ] `memory_recall` returns only `status='active'`, ranked then newest; empty query → most recent N.
- [ ] `memory_forget` keeps row, excludes from recall.
- [ ] `memory_purge` removes whole multi-version chain in a single `DELETE` without FK error, requires `'PURGE'`, leaves id-only tombstones (fix #8b).
- [ ] `change_seq` set by trigger from one shared sequence on entries + tombstones; `memory_changes(p_since_seq)` returns rows + tombstones with `change_seq > cursor`, ordered, plus `max_seq`; overlap re-pull (`since_seq − 50`) idempotent (fix #2).
- [ ] `memory_export` includes history.
- [ ] `public.mcp_tokens.last_used_at` never changes.
- [ ] No dashboard table other than `mcp_tokens(token_hash,user_id,revoked_at)` read.

## Out of Scope (Deferred)
- pgvector / semantic recall (V2, optional, server-side only).
- Import of dashboard `user_memories`.
- Per-client tokens/scopes.
- LLM-written synthesis (V2, desktop, read-only from ledger).
- Rate limiting.
- google-tasks.

## Open Questions
- **`hc_` token has no scopes:** any holder (emulator 5558 and Titan share it) can read, write and purge memory; revoking it disables Health and memory together. Future: per-client tokens or scopes column — would need a dashboard schema change (ruled out by user); alternative is a `grace_memory`-owned token table later.
- **Coupling to dashboard schema:** depends on `public.mcp_tokens(token_hash, user_id, revoked_at)` staying stable; a dashboard migration could break memory auth. Failure mode: fail closed on server; client mirrors keep working.
- **Shared-DB changes need approval at implementation:** new schema `grace_memory`, new role, column-level `SELECT` on `public.mcp_tokens`, adding `grace_memory` to PostgREST exposed schemas — not dashboard *code* changes but still need explicit approval.
- **Token-hash compatibility** unverified until the read-only pre-check of `scripts/issue_mcp_token.py` / `mcp_coach.py` (encoding, pepper/prefix).
- **Out-of-order `change_seq` commits** under concurrent writers: tolerated for single user via overlap re-pull.
- **Brute force / rate limit:** PostgREST has no per-function rate limit; tokens are 256-bit, brute force impractical.
- Anon key + `hc_` token live on device (see `SPEC-memory-chat.md`).
- Dashboard `user_memories` import deferred.
- pgvector semantic recall deferred.
- Unique-head enforcement for fact/preference/place: DB constraint vs function-level only.
- Subject normalization (diacritics folding) location: SQL vs client; must match across launcher and bridge.
