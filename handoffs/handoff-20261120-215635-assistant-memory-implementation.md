## Objective
- Implement Grace Launcher assistant memory (modules memory-ledger, memory-chat, memory-bridge) from the approved Revision 2 specs, test-first, then device, release and quality smokes. google-tasks is DEFERRED; do not build it.

## Important Details
- **Repo:** `/Users/susuresh/genie/grace-launcher/GraceLauncher`.
  - JDK 21 is `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`; the default `java` is 17.
  - Builds need `ANDROID_USER_HOME=/Users/susuresh/.android` so the debug key cert is `f1849f41…`.
- **Roles:** this session ran as the Mastermind coordinator. It plans and delegates only to `mastermind-explorer` (facts, read-only review) and `mastermind-implementer-sonnet-5-5` (the user's chosen model). The initial implementer prompt contains only the plan path. Follow-up tweaks get fresh subagent calls with self-contained briefs. After each implementer report, run a lightweight review (git diff vs plan) and give the user options on any discrepancy.
- **Source of truth:**
  - `docs/memory/CAPABILITY-MAP.md`, `SPEC-memory-ledger.md`, `SPEC-memory-chat.md`, `SPEC-memory-bridge.md`, `RESEARCH.md`
  - decision record `assets/plans/2026-11-20-assistant-memory-research-design.md`
  - phase plans `assets/plans/2026-11-20-memory-p1-ledger.md`, `-p2a-chat-core.md`, `-p2b-bridge.md`, `-p3-android-wiring.md`, `-p4a-device-quality.md`, `-p4b-signed-release.md`
- **Approved deviations from the specs** (user decisions 2026-11-20):
  - **Gate H hash:** `encode(extensions.digest(convert_to(btrim(x-grace-token),'UTF8'),'sha256'),'hex')`. This is lowercase hex SHA-256 of the full token including `hc_`, with no pepper; the dashboard also trims.
  - **Active-owner check:** `_caller_user_id` joins `public.personal_owner(singleton,user_id)` and `public.users(id,is_active)`. This amends the spec criterion "only mcp_tokens read".
  - **RLS:** the owner role `grace_memory_owner` is NOLOGIN BYPASSRLS, with column-only grants. The dashboard tables FORCE RLS; no policies were added to them.
  - **Privacy gate (generalized):** memory never enters a tool-enabled pass. If a tool-enabled Web/Wikipedia pass ends without a tool call and the memory block is non-empty, the draft is discarded and one extra tool-free pass runs with evidence + memory. An empty block keeps the draft.
  - **Wikipedia:** no tool call and no sources → keep "unable" (no extra pass).
  - **Source-N follow-ups** (`inferSavedText`) get the memory block.
  - **Dates:** English-only date → event detection (CalendarPhraseEngine has no German).
  - **subject_key:** server SQL `grace_memory._subject_key` and the Kotlin normalizer share one explicit fold table plus ß→ss. Shared vectors live in `app/src/test/resources/memory/subject_key_vectors.json`.
  - **Gate D targets:** 5590 for debug, 5558 for release. 5590 was left on Gemma4E4B. Version bumped to 1.2.12/20.
- **Hard rules still in force:**
  - No commits/pushes unless the user asks.
  - Never use the service-role key. Never update `mcp_tokens.last_used_at`. Never touch `user_memories` or the dashboard repos (`~/genie/health_app/hf-health-dashboard` is read-only).
  - Never log or commit tokens, `.env` values or the Supabase host. Never edit `grace_launcher.db` (memory uses `memory_mirror.db`).
  - Never run `connectedDebugAndroidTest` on 5590. Never uninstall/clear/wipe emulators.
  - The shared hc_ token is `~/.local/share/grace-launcher/health-mcp/valid.token`, used by 5558, 5590 and the Titan; never revoke it.
  - Max 2 bounded repairs per emulator failure, then stop.
- **Gates:** H done, A done (the user applied the migrations manually and fixed `.env`), D done for 5590 and 5558 only. Any other device (e.g. Titan) needs new approval.
- **`.env`:** has `SUPABASE_URL` (the user fixed it to the bare project URL) and `SUPABASE_ANON_KEY`. The APK embeds the URL and the anon key by design.

## Work State
### Completed
- **Phase 0 recon (explorer):** call sites, BuildConfig flow, test conventions, Gate H facts, owner/RLS facts.
- **Phase 1 ledger:** `server/supabase/grace_memory/001_schema.sql`, `002_auth.sql`, `003_functions.sql`, `APPLY-CHECKLIST.md`, and pytest suite `tests/` (uv venv, docker postgres:16). Final result 160/160.
  - Includes the subject_key helper.
  - Includes the security-review P2 fixes: self-supersede rejected (`:71`), cycle-safe CTEs (`UNION`), and the cross-user tombstone guard (`:152`) plus a remember-side reject.
  - Independent review (explorer, 7/12 calls): no P0/P1.
- **Phase 2a chat core:** `app/src/main/java/com/galaxyrio/gracelauncher/assistant/memory/` (MemoryTypes, Command, Parse, Select, Delete, Recall, Gate, Rpc, Sync) plus JVM tests.
- **Phase 2b bridge:** `tools/memory-bridge/` (uv, mcp==1.29.1, httpx, respx, golden export). 35/35.
- **Phase 3 Android wiring:**
  - Room `memory_mirror.db` with FTS4, excluded in both backup XMLs.
  - `MemoryHttp` over HttpURLConnection; BuildConfig `SUPABASE_*`; `ACCESS_NETWORK_STATE`; connectivity callback.
  - Chips/cards/status line; EN/DE strings.
  - Web/Wikipedia loop extracted into the new `assistant/WebTurn.kt` (deviation, behaviour kept).
  - JVM 471/471; lintDebug, assembleDebug and assembleDebugAndroidTest PASS.
- **Live smoke (Gate A) via bridge:** all steps PASS, including negative checks (400 `invalid_token`, 401 `42501`). Evidence: `app/build/reports/memory/live-smoke-20261120/summary.md`.
- **Phase 4a (5590):**
  - Version bumped to 1.2.12/20. Debug APK SHA `bad46164…6857` installed; cert verified.
  - `MemoryMirrorDaoTest` + `MemoryBackupRulesTest` PASS (5 tests).
  - Live device sync smoke PASS.
  - Switched to Gemma4E4B.
  - Opt-in `MemoryQualityTest` (`-e memoryQuality true`): subject JSON 12/12 valid, 0% fallback, type 11/12, grounded 5/5, no fabrication; median 1.1 s remember / 1.7 s chat. Noted: German input got an English subject; the long noisy input was typed as place.
  - Evidence: `app/build/reports/memory/device-smoke-5590-20261120/summary.md`, `app/build/reports/memory/quality-e4b-20261120/`.
- **Phase 4b (5558):**
  - Signed release `app/build/reports/memory/release-1.2.12/GraceLauncher-1.2.12-signed.apk`, 59,724,829 B, SHA-256 `650ca6ad4a000cab534d046b40e2dbbec3aace25e398fb27b206374273828f88`, cert `7c928de3…`. v2/v3, P16 alignment, badging and DEX keeps PASS. Built from HEAD `8f89baba` with a dirty tree.
  - Installed on 5558 with `install -r` (data, model and Health token kept).
  - Smoke PASS: Home, chat, Web, Wikipedia, Health Live, memory round trip. The memory-aware reply was weak (paraphrased). Logcat clean, but the buffer was cleared twice, so coverage is partial.
- `tasks.md` and the `AGENTS.md` "Current state" (Phase 4a/4b bullets) are updated by the implementers.

### Active
- (none). Last coordinator message offered: commit, then optionally rebuild the release from that commit.

### Blocked
- **Unverified:**
  - Titan install and smoke (needs approval)
  - native ELF 16KB alignment
  - visual QA (the user's job)
  - German replies
  - Health reply content
  - full-run logcat coverage
- **TDD evidence gaps:** Phase 1 slices 3–11 had a shared RED; Phase 3 per-slice RED was not captured; Phase 2b per-slice counts were not captured.
- **Uncommitted tree:** 36+ paths uncommitted. The release artifact is not tied to a clean commit.
- **Pre-existing unrelated changes (not from this session):** `README.zh-CN.md` deleted, `handoffs/handoff-20261118-152227-wikipedia-tdd-cleanup.md` modified, plus untracked older plans/handoffs. Do not include these in any commit without asking.
- **Session-log leak:** the Supabase host appeared once in subagent tool output (session log only, not in any files).

## Next Move
1. Ask the user whether to commit the memory work (memory files only; exclude the pre-existing unrelated changes unless told otherwise). If yes, optionally rebuild and re-sign the 1.2.12 release from that commit and record the new SHA in `tasks.md`/`AGENTS.md`.
2. **Pending, needs approval:**
   - Titan 2 Elite install and signed-release smoke
   - native ELF 16KB check
   - improving memory-aware reply fidelity and German subject language (would need a new plan)

## Relevant Files
- `docs/memory/*.md`: approved specs (Revision 2).
- `assets/plans/2026-11-20-memory-p1-ledger.md` … `-p4b-signed-release.md`: phase plans with deviations and guardrails.
- `server/supabase/grace_memory/`: live-applied migrations, `APPLY-CHECKLIST.md` (rollback), pytest suite.
- `tools/memory-bridge/`: desktop stdio MCP bridge and export.
- `app/src/main/java/com/galaxyrio/gracelauncher/assistant/memory/`: memory core plus Android adapters.
- `app/src/main/java/com/galaxyrio/gracelauncher/assistant/AssistantController.kt`, `WebTurn.kt`, `AssistantUi.kt`, `AssistantChatState.kt`: wiring and privacy gate.
- `app/src/androidTest/java/com/galaxyrio/gracelauncher/assistant/memory/`: DAO, backup and opt-in quality tests.
- `app/src/test/resources/memory/subject_key_vectors.json`: shared SQL/Kotlin vectors.
- `app/build.gradle.kts`: version 1.2.12/20, SUPABASE BuildConfig.
- `app/build/reports/memory/`: live-smoke, device-smoke-5590, quality-e4b, release-1.2.12 evidence (git-ignored).
- `tasks.md`, `AGENTS.md`: current state and open items.
