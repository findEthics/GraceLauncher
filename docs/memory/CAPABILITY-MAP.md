# Assistant Memory — Capability Map

Status: design only, not implemented. Module ids are stable; never rename.

## Modules
| Module id | Responsibility | Depends on |
|---|---|---|
| `memory-ledger` | `grace_memory` schema in existing Supabase project: table, indexes, RPC functions, token check | existing `public.mcp_tokens` (read-only) |
| `memory-chat` | Launcher: slash commands, chips, confirm/pick cards, parse pipeline, prefetch, `memory_mirror.db`, outbox sync | `memory-ledger` (sync only; local works standalone) |
| `memory-bridge` | Local stdio MCP server for desktop agents + deterministic markdown export | `memory-ledger` |
| `google-tasks` | **Deferred** | own OAuth client (TBD) |

Specs: `SPEC-memory-ledger.md`, `SPEC-memory-chat.md`, `SPEC-memory-bridge.md`. Research: `RESEARCH.md`.

## Dependency direction
`memory-chat` → `memory-ledger` ← `memory-bridge`. Ledger depends on nothing of ours (only reads `public.mcp_tokens`). Chat never depends on bridge or vice versa. `google-tasks` independent.

## Build order
1. `memory-ledger` (needs explicit approval for DB changes in shared Supabase project).
2. `memory-chat` and `memory-bridge` in parallel.
3. `google-tasks` later, independent.

Each pass is a separate approved implementation; module by module.

## Cross-cutting decisions
- **Deliverable now = docs only.** Implementation in later approved passes.
- **Core unit** = typed append-only ledger with supersede (not wiki pages / vectors / KG). Wiki = derived view only.
- **Write path** = explicit + model-assisted parse, never automatic extraction. Model never writes or chooses delete targets.
- **Decision 12 — nothing in health-dashboard repos.** Neither GitHub `findEthics/health-dashboard` nor HF Space repo. GitHub main → `sync-to-hf.yml` force-pushes Space → would erase HF-only MCP server. All new code lives in Grace Launcher repo.
- **Decision 13 — no new hosted service.** Clients read/write Supabase directly: PostgREST RPC with public anon key; all logic in Postgres functions.
- **Decision 14 — auth = exact existing `hc_` token.** Functions verify against `public.mcp_tokens` (SHA-256 hash, not revoked) with read-only grant. One token covers Health + memory. Token sent in `x-grace-token` header (never a function argument); `grace_memory` exposed to PostgREST via profile headers; sync cursor is a monotonic `change_seq` (see ledger/chat specs).
- **Offline**: memory works with no token (local mirror + outbox); syncs once a token exists.
- **Privacy gate**: memory only enters passes that cannot reach the network (incl. extra tool-free pass on no-tool-call Web/Wikipedia turns when memory is non-empty).

## google-tasks — DEFERRED
User deferred when OAuth isolation surfaced. Decisions made so far (not built):
- Commands: `/task <text>` (add, confirm card), `/task-list` (open tasks, numbered cards, no model), `/task-done <n|text>` (numbered pick → complete). Hyphenated names reduce typo-triggered actions.
- No delete from chat.
- Default Google Tasks list.
- Time handling: due date only + time prepended to notes (`⏰ 14:30`); card warns that API can't set a time.
- Online-only, fail closed ("Tasks needs a connection"), no queue (avoids duplicates).
- Existing `-` Home to-do route (`NotesCapture`) untouched.
- Tasks were removed from memory types: tasks live in Google Tasks (deterministic, existing app).
- Open for later: own Google OAuth client (tasks scope only) — must NOT reuse dashboard's encrypted tokens/keys. Testing-mode refresh tokens expire after 7 days unless published; "unverified app" warning for sensitive scope; Tasks API time-of-day limit.
