# SPEC memory-bridge — V1

Status: design only, not implemented. Module id: `memory-bridge`. Depends on `memory-ledger`. See `CAPABILITY-MAP.md`, `RESEARCH.md`.

## Objective
Let desktop agents / other models / other setups access the **same** memory the same way the launcher does: a small local stdio MCP server over the ledger's RPC functions, plus a deterministic markdown export (Karpathy-LLM-Wiki-compatible view, no LLM).

## Tech Stack
- Python, stdio MCP server using `mcp` SDK (dashboard uses `mcp==1.29.1`), `httpx` → Supabase PostgREST RPC.
- No hosted service; no service-role key.

## Commands
(Proposed.)
- Run: `python -m memory_bridge` (stdio) from `tools/memory-bridge/`.
- Tests: `pytest tools/memory-bridge`.
- Env (all required): `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `GRACE_MEMORY_TOKEN` (an `hc_` token). Never committed; never logged.
- Every RPC call (`POST {SUPABASE_URL}/rest/v1/rpc/<fn>`) sends headers `apikey: <anon>`, `x-grace-token: <GRACE_MEMORY_TOKEN>`, `Content-Profile: grace_memory` (`Accept-Profile` for reads). Tools and RPC bodies carry no token argument.
- Installing/registering the MCP server in an agent is a user step, not done in this design.

## Project Structure
```
tools/memory-bridge/
  pyproject.toml
  memory_bridge/
    server.py     # MCP tools
    client.py     # httpx RPC calls
    export.py     # deterministic markdown rendering
  tests/
```
Path is a proposal. Lives in the launcher repo only.

## MCP tools
Mirror the ledger functions 1:1:
| Tool | Maps to | Notes |
|---|---|---|
| `memory_remember` | `memory_remember` | Client generates uuid; `type`, `subject`, `body`, optional `occurred_at`, optional `supersede_id`; `source='bridge:<client>'`. Caller (agent) supplies fields; bridge validates enums/lengths. Surfaces server `conflict`/`head_id` result to the agent |
| `memory_recall` | `memory_recall` | Active only |
| `memory_forget` | `memory_forget` | Soft invalidate |
| `memory_purge` | `memory_purge` | Requires explicit `confirm` arg (`"PURGE"`) |
| `memory_history` | `memory_history` | Chain |
| `memory_export` | `memory_export` | Renders markdown (below) |

Write tools require the calling agent to pass explicit user intent; tool descriptions state that memory must only be written/deleted on user request.

## `memory_export` — deterministic markdown, no LLM
Same input → same bytes (stable ordering, UTC dates).
- `index.md`: one line per subject: type, current value, last date.
- One page per subject: current value + dated history table (all versions, incl. superseded/invalidated).
- `log.md`: chronological, `## [YYYY-MM-DD] save|supersede|forget|purge | subject`.
- Purged content absent (tombstones only → no log content beyond id-free "purge" lines if derivable; otherwise omitted).
- LLM-written synthesis pages: deferred V2 (desktop, large model, read-only from ledger).

## Code Style
Python 3.11+, type hints, small modules, no global state beyond config; errors → MCP tool errors with short stable codes; no token/secret in logs or exceptions.

## Testing Strategy
- Unit: client with mocked httpx (request shape: `apikey`, `x-grace-token`, `Content-Profile`/`Accept-Profile: grace_memory` headers; token never in body/URL/args).
- Export: golden-file tests (same ledger JSON → identical markdown); supersede/forget/purge rendering; ordering stability.
- Tools: enum/length validation; purge refuses without `confirm`.
- Live test against Supabase only with explicit approval after ledger exists.

## Boundaries
**Always**
- Read config from env only; use anon key + `hc_` token.
- Keep export deterministic and LLM-free.

**Ask first**
- Adding tools beyond the six.
- Any LLM-written synthesis.
- Registering the server in agent configs; installing packages.

**Never**
- Use service-role key.
- Modify dashboard repos or tables; update `mcp_tokens.last_used_at`.
- Log/commit tokens, keys, project refs.
- Purge without explicit `confirm`.

## Success Criteria
- [ ] Six tools present, schema matches ledger RPCs (no token parameter on any tool).
- [ ] Every RPC request carries `x-grace-token` and `Content-Profile: grace_memory` headers; token absent from URL, body and logs.
- [ ] A fact saved by launcher is recallable via bridge and vice versa (same ledger).
- [ ] `memory_purge` without confirm rejected.
- [ ] Export is byte-identical across two runs on same data.
- [ ] Export index shows one current value per subject; history table lists superseded values.
- [ ] No secrets in logs/output.

## Out of Scope (Deferred)
- LLM-written synthesis/wiki pages (V2).
- Semantic/vector recall.
- Local mirror/offline queue in bridge (online only).
- Dashboard `user_memories` import.
- google-tasks / `gws`.

## Open Questions
- **`hc_` token has no scopes**: any agent holding it can read/write/purge; revoking it disables Health and memory together. Use caution when handing it to agents.
- **Coupling to dashboard `public.mcp_tokens`**: auth fails closed if the dashboard schema changes.
- Anon key + token in desktop env/config files: storage convention (keychain vs env) to decide.
- No per-function rate limit on PostgREST (256-bit tokens).
- Client-side subject-key normalization must match launcher/ledger.
- Export size for large ledgers (single JSON dump) — pagination later if needed.
- Dashboard `user_memories` import and pgvector deferred.
