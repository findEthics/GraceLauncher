# Memory Phase 1 — memory-ledger (SQL + local tests)

## Overview
Build the server-side memory ledger as SQL migrations for schema `grace_memory` in the existing Supabase project, test-first against a LOCAL disposable Postgres. Nothing is applied to the live project in this phase (Gate A). Authoritative spec: `docs/memory/SPEC-memory-ledger.md` (Revision 2) — implement it exactly, including every Success Criterion, except the approved deviations below. Background: `docs/memory/CAPABILITY-MAP.md`, `docs/memory/RESEARCH.md`, decision record `assets/plans/2026-11-20-assistant-memory-research-design.md`.

## Business & UX Decisions
- One `hc_` token covers Health + memory (spec Decision 14). Same token the dashboard issues.
- Server is canonical; clients mirror. No hosted service; all logic in Postgres functions reached through PostgREST RPC with the anon key + `x-grace-token` header.

## Technical Decisions
Approved deviations from the spec (user decisions 2026-11-20, supersede the spec where they conflict):
1. **Gate H hash (verified read-only against the dashboard):** dashboard `token_hash` = lowercase hex SHA-256 of the full token UTF-8 bytes, `hc_` prefix included, no pepper/salt; its HTTP auth trims whitespace before hashing. Use exactly:
   `encode(extensions.digest(convert_to(btrim(<header value>), 'UTF8'), 'sha256'), 'hex')`
   where header value = `current_setting('request.headers', true)::json ->> 'x-grace-token'`. pgcrypto in schema `extensions` (as on Supabase).
2. **Active-owner check (amends criterion "no dashboard table other than mcp_tokens read"):** `_caller_user_id()` returns `user_id` only if token hash matches, `revoked_at is null`, AND the user is the dashboard's active owner:
   ```sql
   select t.user_id
     from public.mcp_tokens t
     join public.personal_owner p on p.user_id = t.user_id and p.singleton is true
     join public.users u on u.id = p.user_id and u.is_active is true
    where t.token_hash = <hash expr> and t.revoked_at is null
   ```
   Column-level SELECT grants for the owner role only: `public.mcp_tokens(token_hash, user_id, revoked_at)`, `public.personal_owner(singleton, user_id)`, `public.users(id, is_active)`. Nothing else in the dashboard schema is read. Any failure (no row, null/empty header, malformed headers JSON) → the same `raise exception 'invalid_token'` with no detail; never echo the token.
3. **RLS:** dashboard tables have ENABLE + FORCE RLS with no policies for other roles. The dedicated function owner role (e.g. `grace_memory_owner`, NOLOGIN) is created `WITH BYPASSRLS`; its privileges remain limited by the column grants above. Do NOT add policies to dashboard tables. The Gate A checklist must include a pre-check that the live `postgres` role can create a BYPASSRLS role (`select rolbypassrls from pg_roles where rolname = current_user`) and say: if not → stop and ask the user (do not fall back silently).
4. `last_used_at` is never written by memory functions (dashboard behavior differs; memory must not touch it).

Other technical rules (from spec + coordinator brief):
- Files: `server/supabase/grace_memory/001_schema.sql`, `002_auth.sql`, `003_functions.sql` (+ more `NNN_*.sql` only if needed, e.g. change_seq). A separate test-only stub `server/supabase/grace_memory/tests/stub_dashboard.sql` creates local `anon` role (if absent), `extensions` schema + pgcrypto, and stub `public.users`, `public.personal_owner`, `public.mcp_tokens` with the real column types (see below), owned by a separate stub role, with ENABLE + FORCE RLS and no policies. The stub is never part of the live migration set.
  - Dashboard DDL to mirror: `mcp_tokens(id uuid pk default gen_random_uuid(), user_id uuid not null references users(id), token_hash text not null unique, label text not null default 'unnamed-client', created_at timestamptz not null default now(), last_used_at timestamptz null, revoked_at timestamptz null)`; `personal_owner(singleton boolean primary key default true check(singleton), user_id uuid not null unique references users(id), created_at timestamptz not null default now())`; `users(id uuid pk, email text, is_active boolean, created_at timestamptz, updated_at timestamptz)` (subset is fine).
- Functions `SECURITY DEFINER`, owned by the owner role, fixed `search_path` (e.g. `pg_catalog, grace_memory, extensions` — qualify `public.*`). `REVOKE ALL ON FUNCTION ... FROM PUBLIC` for every function; `GRANT EXECUTE` to `anon` only on the 7 RPCs: `memory_remember`, `memory_forget`, `memory_purge`, `memory_recall`, `memory_history`, `memory_changes`, `memory_export`. `_caller_user_id()` and any other helper: no EXECUTE for anon/PUBLIC. `anon` gets USAGE on schema `grace_memory` (needed for PostgREST) but zero table/sequence privileges. Explicitly revoke table privileges from `anon`, `authenticated`, `PUBLIC` in case of default privileges.
- PostgREST simulation in tests: in a transaction, `select set_config('request.headers', '{"x-grace-token":"..."}', true); set local role anon;` then call RPCs.
- Errors: stable short codes `invalid_token`, `bad_entry`, `confirm_required` (spec).
- Test harness: pytest + psycopg (v3) in an isolated uv venv under `server/supabase/grace_memory/tests/` (`pyproject.toml` or `requirements.txt`; do not install into the global python). Disposable `docker run --rm -d postgres:16` on a random free localhost port with a unique container name; fixture applies stub + migrations to a fresh database per test module (or per test via template DB), and stops/removes the container at session end. Tokens in tests are generated fixtures (`hc_test_...`), never real tokens.
- Prerequisite: Docker daemon must be running (user is starting Docker Desktop). If `docker info` fails, STOP and report — do not install Postgres, do not touch live.

## Relevant Files
- `docs/memory/SPEC-memory-ledger.md` — data model, functions table, success criteria (authoritative).
- `server/supabase/grace_memory/` — new (does not exist yet).
- Dashboard (READ-ONLY reference, never modify/commit/push): `~/genie/health_app/hf-health-dashboard/migrations/022_create_mcp_tokens.sql`, `024_single_owner.sql`, `app/api/routers/mcp_coach.py:51-75`.

## Acceptance Criteria
- Every Success Criterion in `SPEC-memory-ledger.md` holds and is covered by a test, with the criterion "no dashboard table other than mcp_tokens read" replaced by "only the listed columns of mcp_tokens, personal_owner, users are read".
- TDD slices each RED→GREEN, in this order:
  1. Grants: anon has no table privileges in grace_memory; EXECUTE on exactly the 7 RPCs; cannot execute `_caller_user_id()`; PUBLIC has no EXECUTE.
  2. `_caller_user_id`: valid token of active owner → user_id; revoked, unknown, missing header, empty/whitespace header, malformed headers, valid token of a non-owner/inactive user → identical `invalid_token`; error text never contains the token; whitespace-padded valid token accepted (btrim). Works with FORCE RLS stubs (proves BYPASSRLS path).
  3. `mcp_tokens.last_used_at` never changes across all RPC calls.
  4. remember: idempotent on `id` (same id twice → one row).
  5. supersede via `memory_remember(p_entry, p_supersede_id)`: active target closed (`valid_to`, `status='superseded'`, `superseded_by`, new `supersedes`) and `{conflict:false}`; non-active target → new row active, nothing closed, `{conflict:true, head_id}` (#8a). Other user's target never touched.
  6. forget: soft invalidate, row kept, excluded from recall.
  7. purge: `p_confirm <> 'PURGE'` → `confirm_required`; whole chain (both directions) removed in one DELETE; tombstones in `purged_ids` (#8b).
  8. recall: FTS `simple`, `websearch_to_tsquery`, active only, rank then `valid_from desc`; empty query → most recent; limit capped.
  9. history: full chain ordered by `valid_from`.
  10. change_seq trigger + `memory_changes(p_since_seq)`: rows + tombstones with seq > since, ordered, plus `max_seq` (#2).
  11. export: all caller rows incl. history as JSON.
- Every function × (valid token, revoked, unknown, other user's id) per spec Testing Strategy; no row of another user ever leaks.
- No function has a token parameter.
- `server/supabase/grace_memory/APPLY-CHECKLIST.md` (one page): prerequisites (BYPASSRLS pre-check, pgcrypto in `extensions`), apply order, post-apply verification queries (spec "Verify grants" commands plus column-grant checks), Exposed-schemas step (add `grace_memory`, keep existing ones), rollback (remove from exposed schemas, drop schema cascade, revoke column grants, drop role), and an explicit "requires user approval (Gate A)" header.

## Tests to Add/Run
- `server/supabase/grace_memory/tests/test_*.py` (pytest+psycopg), run via the uv venv, e.g. `cd server/supabase/grace_memory/tests && uv run pytest -q`.
- Report for each slice: the RED run (failing test + reason) and GREEN run, with exact commands and pass counts. Final full run must be green.

## Scope Guardrails
- NEVER connect to, apply to, or query the live Supabase project (Gate A). No network DB connections other than the local docker container.
- Never modify, commit or push the dashboard repo; never touch `user_memories` or any dashboard table definition; no policies on dashboard tables.
- No service-role key anywhere. No real tokens or `.env` values in files/logs.
- Do not touch Android app code, `grace_launcher.db`, or `tasks.md` in this phase.
- No commits/pushes. No delegation or spawning agents.
- If the spec is ambiguous or contradicts what you find, STOP and report the question — do not decide silently. If Docker is unavailable, STOP.

## Open Questions & Risks
- Live Supabase `postgres` may lack BYPASSRLS grant rights → checked at Gate A; user decides then.
- Unique-head enforcement for fact/preference/place (spec open question): keep function-level only unless the spec says otherwise; report what you did.
- Coupling to dashboard schema: column/table rename breaks memory auth → fails closed (accepted).

## Implementation Task List
- [ ] Confirm `docker info` succeeds; else stop.
- [ ] Create uv test venv + pytest fixtures (container lifecycle, fresh DB, stub apply, migration apply, PostgREST-simulation helper, token fixture insert using the Gate H hash).
- [ ] Slices 1–11 strictly RED→GREEN, recording evidence.
- [ ] Refactor pass with suite green.
- [ ] Write APPLY-CHECKLIST.md.
- [ ] Final full run; report files, per-slice RED/GREEN evidence, commands, results, unverified items.
