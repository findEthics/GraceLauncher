# memory-bridge

Desktop stdio MCP server over the Grace memory ledger RPCs (`grace_memory` schema via Supabase PostgREST). Spec: `docs/memory/SPEC-memory-bridge.md`.

## Config (environment only; never commit values)
- `SUPABASE_URL`
- `SUPABASE_ANON_KEY`
- `GRACE_MEMORY_TOKEN` (an `hc_` token; sent only as the `x-grace-token` header)

## Run / test
```
uv sync
uv run python -m memory_bridge   # stdio
uv run pytest -q
```

## Tools
`memory_remember`, `memory_recall`, `memory_forget`, `memory_purge` (needs `confirm="PURGE"`), `memory_history`, `memory_changes`, `memory_export` (deterministic markdown bundle: `index.md`, `subjects/*.md`, `log.md`).

Registering the server in an agent is a manual user step. The token has no scopes: any holder can read, write and purge.
