# Assistant Memory — Research (2026-11-20)

Status: design only, not implemented. Specs: see `CAPABILITY-MAP.md`.

## Problem
Long-term memory for the Grace Launcher offline assistant (Gemma4 E2B / Qwen 1.5–1.7B on device). Must:
- Remember facts, times, dates, places, preferences. Tasks → Google Tasks module (deferred).
- Grow, **retain everything**, answer with the **newest active** value.
- Personal, single user, lightweight, near-deterministic, **not RAG**.
- Model-agnostic: 1–2B model must not be trusted to curate memory.
- Same memory reachable from other setups (desktop agents, other models) the same way.

## Constraints
- Offline first: works with no network and no `hc_` token.
- Health-dashboard repos (GitHub `findEthics/health-dashboard`, HF Space) untouched: GitHub main → `sync-to-hf.yml` force-pushes Space → would erase HF-only MCP server.
- No new hosted service; Supabase PostgREST RPC only.
- Privacy: memory never reaches third-party-bound model passes (Tavily/Wikipedia queries).

## Sources
| Source | Takeaway | Used for |
|---|---|---|
| Karpathy LLM Wiki gist — https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f | raw sources / wiki / schema layers; ingest/query/lint; `index.md` + `log.md`; works at ~hundreds of pages without embeddings; errors compound, needs human review | **Derived deterministic view only** (bridge export). Small model would corrupt self-maintained pages |
| MCP Knowledge Graph Memory Server — https://github.com/modelcontextprotocol/servers/tree/main/src/memory | entities/relations/observations | Rejected: heavy ceremony for personal facts |
| mem0 — https://github.com/mem0ai/mem0 | LLM extraction on every add; hybrid semantic+BM25+entity | Rejected: needs LLM + embedder, model-dependent |
| Letta/MemGPT — https://docs.letta.com/concepts/memgpt | core vs archival memory; model self-edits | Rejected: model-dependent writes |
| personal-agent.org memory docs — https://personal-agent.org/docs/features/memory/ | agent read-only; curator proposes; deterministic committer; push-prefetch "Known World State" block, no model call; supersede not overwrite; bitemporal | Write path (user-confirmed), prefetch block, supersede chain |
| m3-memory `MEMORY_MODEL.md` — https://github.com/skynetcmd/m3-memory/blob/main/docs/MEMORY_MODEL.md; SodaMem arXiv 2608.08055 — https://arxiv.org/html/2608.08055; DCPM arXiv 2606.09483 — https://arxiv.org/html/2606.09483; graph personalized memory survey arXiv 2609.08599 — https://arxiv.org/html/2609.08599v1; companion memory (zenn.dev) — https://zenn.dev/haru0416/articles/843c6c29c04c7c?locale=en | bitemporal validity, supersede chains; "deterministic freshness beats free-form judgment" | `valid_from/valid_to/status/supersedes` schema |
| Local-first tools: mnemo — https://github.com/arttttt/mnemo (deterministic writes, SQLite+FTS5); Amneshia — https://github.com/sabilmurti/amneshia (markdown-as-truth, zero-LLM engine); all41n14lla — https://github.com/bludragon-ai/all41n14lla; minimem — https://github.com/alexngai/minimem; recall — https://github.com/j0yen/recall; sqlite-memory — https://github.com/sqliteai/sqlite-memory/ | lexical FTS + deterministic writes are enough at personal scale | Local mirror (Room FTS4), lexical recall |
| Supabase pgvector docs — https://supabase.com/docs/guides/ai/vector-columns | available | Rejected V1: non-deterministic ranking; deferred V2, server-side only |

## Option comparison
Scores 1 (poor) – 5 (best).

| Criterion | Typed ledger + supersede (chosen) | LLM Wiki pages | Vector / free-text | Knowledge graph |
|---|---|---|---|---|
| Determinism | 5 | 2 | 2 | 4 |
| Model-agnostic | 5 | 1 | 3 | 3 |
| Recency correctness | 5 (supersede chain) | 2 (model rewrites) | 1 (no currency) | 3 |
| History retention | 5 (append-only) | 2 (overwritten) | 4 | 3 |
| Small-model fit (1–2B) | 5 | 1 | 3 | 2 |
| Cross-client access | 5 (SQL RPC + MCP) | 3 (files) | 3 | 3 |
| Effort | 3 | 3 | 2 | 1 |

## Decision rationale
- **Typed append-only ledger + supersede + provenance** gives a deterministic answer to "which value is current". Logs/vectors leave currency to the model.
- **Deterministic prefetch, no model call**: push a bounded "known" block into tool-free passes. Small models can't be trusted to call a memory tool well.
- **LLM Wiki as derived view**: deterministic markdown export (index/pages/log) from the ledger. LLM-synthesised pages deferred to V2 (desktop, large model, read-only from ledger).
- **Model never writes or picks delete targets.** Explicit slash/chip + user confirm card. Model only proposes a ≤6-word subject (validated; fallback rules).
- **Lexical search (Postgres FTS `simple`, Room FTS4)** handles EN/DE mix, deterministic, no embeddings.
- **Auth via existing `hc_` token** (user choice): one token covers Health + memory. Sent as `x-grace-token` header (not an RPC argument) and resolved by a private `_caller_user_id()` helper; schema exposed to PostgREST with profile headers.
- **Sync cursor = monotonic `change_seq`**, not time (`now()` is transaction-start; time cursors skip late commits). Overlap re-pull + idempotent upsert self-heals gaps.
- **Privacy gate incl. no-tool-call Web turns:** if the query-writing pass answers directly and memory is non-empty, discard the draft and run one extra tool-free pass with memory.

## Existing backend facts (reference only, never modify)
- Dashboard `user_memories`: free text + pgvector(384, MiniLM), auto-extracted. Left untouched, not imported in V1.
- `public.mcp_tokens`: SHA-256 token hash, `user_id`, `revoked_at`. Read-only dependency for memory auth.
- Migrations 015–017, 022; `app/api/routers/mcp_coach.py`; `.github/workflows/sync-to-hf.yml` (force-push risk).
- Launcher already has app-driven fetch → tool-free inference pattern (`HealthTurn`, `HealthMcpClient`, `HealthVault`).

## Launcher findings
- `AssistantController` budgets: evidence budgets in estimated tokens (`TokenEstimate`); memory block taken from the history share, evidence budgets unchanged.
- `ChatSource` enum: WEB/WIKIPEDIA/NONE/HEALTH. Memory is **not** a source (stacks with them); chips for memory are one-shot modes, separate from the enum.
- `MagicAlarmCommand` discards the parsed date → memory must keep the full resolved date-time from `CalendarPhraseEngine` / `CalendarTemporalResolver`.
- `grace_launcher.db` is Room v18, no destructive fallback → memory uses its own DB.
- Room has no FTS5 → `@Fts4`.

## Google Tasks findings (module deferred)
Sources: Tasks resource https://developers.google.com/workspace/tasks/reference/rest/v1/tasks · Workspace MCP servers https://developers.google.com/workspace/guides/configure-mcp-servers · `gws` CLI https://github.com/googleworkspace/cli · Tasks in Calendar help https://support.google.com/calendar/answer/9901136 · Calendar event types https://developers.google.com/workspace/calendar/api/guides/event-types
- Calendar tasks and Google Tasks app are the same data; Calendar API has no task event type.
- Tasks API `due` is date-only: time not readable/writable, no recurring creation.
- Official Workspace remote MCP servers (developer preview) cover Calendar, not Tasks.
- `gws` CLI (googleworkspace/cli, unofficial) reaches Tasks API; its built-in MCP removed in v0.8.0; third-party curated wrapper exists; desktop-only, not runnable in launcher; not installed on this Mac.
- OAuth: needs own client (tasks scope only); Testing-mode refresh tokens expire after 7 days unless published; sensitive scope → "unverified app" warning for personal use.
- Decisions so far: see `CAPABILITY-MAP.md` → google-tasks (Deferred).
