# Memory Phase 2b — memory-bridge (desktop stdio MCP)

## Overview
Desktop Python stdio MCP server that exposes the Phase 1 ledger RPCs to desktop agents and produces a deterministic markdown export, built test-first with mocked HTTP. Authoritative spec: `docs/memory/SPEC-memory-bridge.md` (Revision 2). Implement it exactly, including every Success Criterion. Background: `docs/memory/CAPABILITY-MAP.md`, `docs/memory/SPEC-memory-ledger.md`, decision record `assets/plans/2026-11-20-assistant-memory-research-design.md`.

## Business & UX Decisions
- Same `hc_` token as Health. The anon key + token reach Supabase PostgREST RPC directly; there is no other service.
- `purge` is destructive, so it requires an explicit confirm argument. A supersede conflict is surfaced to the caller, never hidden.
- LLM-written synthesis pages are deferred to V2 (spec). Export is deterministic only.

## Technical Decisions
- Location: `tools/memory-bridge/` (new), with its own `pyproject.toml` managed by `uv`. Pin `mcp==1.29.1` (the version the dashboard uses), plus `httpx`, and dev deps `pytest` and `respx`. Never install into the global python. The global python has mcp 1.26; ignore it.
- HTTP: `httpx` POST to `{SUPABASE_URL}/rest/v1/rpc/<fn>` with headers `apikey: <anon>`, `x-grace-token: <hc_…>`, `Content-Profile: grace_memory` (RPC/writes) and `Accept-Profile: grace_memory` (reads). The token never appears in the body, URL, query string, tool arguments, tool results, exception messages or logs.
- Config comes from the environment (names in the spec, e.g. `SUPABASE_URL`, `SUPABASE_ANON_KEY`, token env/file). If config is missing, fail with a clear message that contains no values.
- Tools mirror the 7 RPCs, with signatures from `server/supabase/grace_memory/003_functions.sql` (Phase 1 output; read it):
  - `memory_remember(p_entry jsonb, p_supersede_id uuid)` returns `{conflict, head_id}`. Surface the conflict.
  - `memory_forget(p_id)`
  - `memory_purge(p_id, p_confirm)`. The tool requires an explicit confirm arg and sends `'PURGE'` only when it is given.
  - `memory_recall(p_query, p_limit)`
  - `memory_history(p_id)`
  - `memory_changes(p_since_seq)`
  - `memory_export()`
  - Map the stable error codes `invalid_token`, `bad_entry` and `confirm_required` to clear tool errors.
- Deterministic markdown export from `memory_export()` JSON:
  - `index.md`;
  - one page per subject;
  - `log.md`, with lines in the form `## [YYYY-MM-DD] save|supersede|forget|purge | subject`.
  Ordering, filenames and slugging must be stable, so the same input always gives byte-identical output (UTC dates, LF line endings). Check it with golden-file tests in `tools/memory-bridge/tests/golden/`.

## Relevant Files
- `docs/memory/SPEC-memory-bridge.md`: authoritative.
- `server/supabase/grace_memory/003_functions.sql`: RPC contract (read-only for this phase).

## Acceptance Criteria
- Every Success Criterion in `SPEC-memory-bridge.md` is covered by a test and passes.
- respx tests prove the following:
  - Each tool calls the right RPC path with all four headers.
  - The token string never appears in the request URL or body, the tool args or result, or error text.
  - Purge without confirm makes no HTTP call.
  - A conflict response is surfaced.
  - Error codes are mapped.
  - Missing config gives an error without leaking any values.
- Golden-file export tests pass, and a rerun produces byte-identical output.
- The MCP server starts over stdio and lists the tools. Show this with an in-process test or a short scripted stdio handshake; no network.

## Tests to Add/Run
- `cd tools/memory-bridge && uv sync && uv run pytest -q`
- Report RED→GREEN evidence per slice (headers/token hygiene, each tool, purge confirm, conflict, errors, export/golden, stdio listing), with exact commands and pass counts.

## Scope Guardrails
- NO live Supabase calls. Use mocked HTTP only (Gate A). No real tokens or `.env` values in files, fixtures or logs. Never use the service-role key.
- Do not touch `server/` SQL, the Android `app/`, `tasks.md`, or the dashboard repos. Phase 2a runs in parallel in `app/`.
- No commits/pushes. No delegation or spawning agents.
- If the spec is ambiguous or contradicts the Phase 1 SQL, STOP and report. Do not decide silently.

## Open Questions & Risks
- The live behavior of the PostgREST profile headers is unverified until the Gate A live smoke.

## Implementation Task List
- [ ] Scaffold the uv project with pinned deps.
- [ ] HTTP client with headers and token hygiene (RED→GREEN).
- [ ] Tools for the 7 RPCs, with purge confirm, conflict surfacing and error mapping (RED→GREEN).
- [ ] Deterministic export plus golden tests (RED→GREEN).
- [ ] stdio server tool listing test.
- [ ] Short README in `tools/memory-bridge/` (config names only, no values).
- [ ] Full test run; report.
