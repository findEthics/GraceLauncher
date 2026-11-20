# Memory Phase 3 — memory-chat Android wiring

## Overview
Wire the Phase 2a pure-Kotlin memory core into Grace Launcher. This phase adds:
- its own Room mirror database
- a Supabase RPC client
- sync triggers
- Tools-popup memory chips
- app-rendered cards
- an Advanced status line
- memory-block injection at exact `AssistantController` call sites

Authoritative spec: `docs/memory/SPEC-memory-chat.md` (Revision 2). Implement it exactly, including every Success Criterion, with the deviations below. Also read: `AGENTS.md` (repo rules), `assets/plans/2026-11-20-memory-p2a-chat-core.md` (core design + deviations), `docs/memory/SPEC-memory-ledger.md` (RPC contract), and decision record `assets/plans/2026-11-20-assistant-memory-research-design.md`.

## Business & UX Decisions
Approved deviations (user decisions 2026-11-20, they win over the spec):
1. **Privacy gate, generalized:** memory never enters a pass that has tools registered. In Web/Wikipedia, any tool-enabled pass that ends without a tool call while the block is non-empty is handled like this:
   - The draft is discarded (never shown or stored).
   - One extra tool-free pass runs with the evidence so far plus the memory block.
   - Empty block → keep the draft, no extra pass.
   - Max 2 searches per reply is unchanged.
2. **Wikipedia:** a turn with no tool call and no sources or valid citations keeps the existing "unable" outcome (no extra pass).
3. **Source-N follow-ups** (`inferSavedText`) get the memory block.
4. **Dates are English-only** (CalendarPhraseEngine has no German support).
- The Advanced status line reads: local only / synced at … / N pending.
- Chips are memory-save, memory-delete and memory-purge. They are one-shot, live in the Tools popup, and are kept separate from the `ChatSource` enum.
- Confirm, pick, recall and history results render as app cards (not model text).

## Technical Decisions
- **Mirror DB:** a new Room database, `memory_mirror.db`, in its own class (e.g. `assistant/memory/MemoryMirrorDatabase.kt`).
  - Its own version (1) and `exportSchema=true`. The existing Room plugin `room { schemaDirectory("$projectDir/schemas") }` exports it; include the generated schema JSON.
  - Tables: `entries` (+ `@Fts4` content table over subject+text), `outbox`, `sync_state`.
  - Never touch `LauncherDatabase` / `grace_launcher.db`.
  - Exclude `memory_mirror.db` (+ `-wal`, `-shm`, `-journal`) in both `app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml` (cloud-backup + device-transfer). Both files are currently templates with no active exclusions; preserve their other content.
- **RPC client:** `HttpURLConnection` on the IO dispatcher, matching `assistant/HealthMcpClient.kt:133-135`. Do not add OkHttp.
  - POST `{SUPABASE_URL}/rest/v1/rpc/<fn>`.
  - Headers: `apikey`, `x-grace-token`, `Content-Profile: grace_memory`, and `Accept-Profile: grace_memory` for reads.
  - The token comes from `HealthVault.token()` (`assistant/HealthVault.kt:70-71`, synchronous; call it off the main thread). The token never goes in the body, URL or logs.
  - Implement the Phase 2a RPC interface.
- **BuildConfig:** add `SUPABASE_URL` and `SUPABASE_ANON_KEY` the same way as `TAVILY_API_KEY` (`app/build.gradle.kts:10-13,28`): read the root ignored `.env`, trim and unquote, empty default, and do not fail the build. Add a simple format check like the Tavily one if trivial. Empty values or no token → local-only status, no network.
- **Sync triggers:** chat open, after a local write, and connectivity regained. Use a `ConnectivityManager.registerDefaultNetworkCallback`, registered for the controller's lifetime and unregistered on clear. Nothing like it exists yet. Add `ACCESS_NETWORK_STATE` to the manifest if it is absent. No WorkManager.
- **AssistantController injection** (call sites from read-only recon; re-verify the lines before editing):
  - Ordinary chat `inferChat` (`AssistantController.kt:303-326`, called at `:217`): add the block (system or user content per spec).
  - Health: `HealthTurn.kt:24-26,54-56` → `inferChat(evidence, …)` at `AssistantController.kt:198-202`: add the block.
  - Source-N `answerFromSavedSources`/`inferSavedText` (`:330-365`, `SourceReference.kt:109-111`): add the block (deviation 3).
  - Web/Wikipedia `inferWebChat` (`:373`, loop `:398-417`, tools registered `:403`, `allowSearch` `:400`, no-tool return `:443-453`, Web emits draft at `:451`, Wikipedia unable at `:445-449`, tool results `:466-505`):
    - Never add the block to any pass with tools registered.
    - Apply the Phase 2a privacy-gate decision function for deviations 1–2. The extra pass is tool-free (no tools registered) and includes the evidence messages so far plus the block.
    - Discarded drafts are never emitted via `onResponse` (including streaming partials: buffer or suppress UI updates for tool-enabled passes when the block is non-empty), and never stored.
  - **Budgets:** the block is ≤400 est. tokens and must fit the existing budget constants (`:560-573`, `:184-192`). Reduce the history/evidence allowance accordingly; never exceed input 3072.
  - The block is never stored in `AssistantChatState` turns/history (#7).
- **Slash commands / chips:** route via the Phase 2a parser before normal submit. Unknown `/xyz` stays ordinary text.
  - Do not route `!` (leave the Magic path untouched), and leave idle-Home `@ # + ?` routing alone.
  - Subject classification uses a tool-free greedy local inference call through the Phase 2a interface, with fallback on timeout.
- **UI:** cards and chips in `assistant/AssistantUi.kt` (popup options `:198-205`, source chip `:168-172`). Status line in `AssistantSettings` (`:343-382`, near the Health settings at `:255,291`). Use the existing theme colors/fonts and accessibility. Strings go in the existing single `values/strings.xml` and `values-de/strings.xml` (and other existing locales if the repo convention requires every key; follow `README.md` Contributing), with section comments.
- **Tests:**
  - JVM where possible: fake DAO, request-builder tests (headers, URL, body, no token in body/URL), controller-level privacy-gate tests with the existing fake inference hooks (see `HealthTurnTest.kt`), BuildConfig empty → local-only.
  - DAO/FTS and backup-exclusion runtime tests go under `app/src/androidTest/`: written and compiled, NOT run (Gate D).

## Relevant Files
- `app/src/main/java/com/galaxyrio/gracelauncher/assistant/AssistantController.kt`, `AssistantChatState.kt` (`ChatSource` at `:9`, turns `:27-35`, context `:69-83`), `AssistantUi.kt`, `HealthTurn.kt`, `HealthVault.kt`, `HealthMcpClient.kt`, `SourceReference.kt`, `TokenEstimate.kt`
- `app/src/main/java/com/galaxyrio/gracelauncher/assistant/memory/` (Phase 2a core)
- `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml:75-78`, `app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml`, `app/schemas/`
- `tasks.md`

## Acceptance Criteria
- Every Success Criterion in `SPEC-memory-chat.md` holds, amended by deviations 1–4. Each is covered by a JVM test, or listed as androidTest-written-not-run, or as manual/device pending.
- No tool-enabled pass ever receives the memory block. The JVM test asserts the system/user content of every tool-enabled pass.
- `memory_mirror.db` is a separate DB with an exported schema, excluded from both backup rule files. `LauncherDatabase` version, schema and migrations are unchanged.
- Builds pass with empty `SUPABASE_*`, and the status shows local only.
- Must pass, with `ANDROID_USER_HOME=/Users/susuresh/.android`, JDK 21:
  - `bash ./gradlew :app:testDebugUnitTest` (full)
  - `bash ./gradlew :app:lintDebug`
  - `bash ./gradlew :app:assembleDebug`
  - Also compile the androidTest sources: `bash ./gradlew :app:assembleDebugAndroidTest`
- `tasks.md` updated:
  - implemented vs verified for memory-ledger / memory-chat / memory-bridge
  - evidence paths
  - open items: Gate A live apply + live smoke, device smoke (Gate D), small-model subject quality, signed-release smoke, German dates unsupported, BYPASSRLS live pre-check

## Tests to Add/Run
- Focused, RED→GREEN per slice: `bash ./gradlew :app:testDebugUnitTest --tests '*memory*'` (plus specific controller test classes).
- Final: full `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest`. Report exact commands and pass counts, plus RED evidence per slice.

## Scope Guardrails
- No emulator/device use, no `adb`, and never `connectedDebugAndroidTest` (Gate D; 5584 unavailable, 5590 forbidden for this).
- No live Supabase calls (Gate A). Never use the service-role key. Never log or commit tokens or `.env` values. Never update `mcp_tokens.last_used_at`, never touch `user_memories` or the dashboard repos.
- Do not edit `LauncherDatabase` / `grace_launcher.db`. Do not change the `ChatSource` enum semantics or the Health/Web/Wikipedia behavior, except for the approved privacy-gate injection. Do not touch the `!` Magic path or the release signing config.
- Do not touch `server/` SQL or `tools/memory-bridge/`.
- No commits/pushes. No delegation or spawning agents.
- If the spec is ambiguous or the code contradicts these call sites, STOP and report. Do not decide silently.

## Open Questions & Risks
- Streaming drafts in tool-enabled passes could briefly reach the UI before the discard decision. They must be suppressed; this is a real leak/UX risk.
- About 2× latency on discarded-draft turns (accepted).
- German date phrases are not detected as events (accepted).

## Implementation Task List
- [ ] Re-verify the call-site lines in AssistantController / HealthTurn / SourceReference.
- [ ] BuildConfig fields + local-only status (RED→GREEN).
- [ ] RPC client request builder + error mapping (RED→GREEN).
- [ ] Room mirror DB + DAO + FTS4 + schema export; backup exclusions; androidTest DAO/FTS tests (written, compile only).
- [ ] Store adapter + sync triggers (chat open, after write, connectivity) (RED→GREEN with fakes).
- [ ] Slash/chip routing + confirm/pick/recall/history cards (RED→GREEN on state).
- [ ] AssistantController injection + privacy gate + draft suppression (RED→GREEN).
- [ ] Advanced status line + EN/DE strings.
- [ ] Full test, lint, assembleDebug, assembleDebugAndroidTest.
- [ ] Update `tasks.md`; report.
