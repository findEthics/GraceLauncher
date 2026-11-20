## Objective
- Connect Grace Launcher's on-device Gemma4 chat assistant to the existing Supabase-backed health-dashboard MCP service, so the user can ask health questions ("How were my steps last week?", "Summarize my recent sleep.", "Help me ease back into weight training.") and get grounded, privacy-safe answers. Inference stays local; only read-only health data comes over the network.

## Important Details
- **Workflow:** Mastermind orchestration.
  - The orchestrator interviews one question at a time, gets a greenlight, writes the plan, then delegates to `mastermind-implementer-*` and reviews the diff without rerunning tests.
  - The user chose **Claude Sonnet 5.5** (`mastermind-implementer-sonnet-5-5`) as implementer.
  - Spec-driven-development was applied inside the plan (capability map, success criteria, deferred list).
- **User preferences:**
  - concise
  - doesn't want to be involved in verification ("figure it out yourself")
  - no pushes; commit only when asked
  - Ponytail mode: smallest change that works
- **Backend source of truth:** HF Space repo `https://huggingface.co/spaces/findethics/health-dashboard`, main `41b642a0c02c25f5200ee57c0f1247f4a2734ab2` (deployed, RUNNING).
  - It is NOT GitHub `findEthics/health-dashboard`, whose main `f366ac25` is 6 commits behind and has no MCP.
  - The user pointed at `app/api/routers/mcp_coach.py` and `scripts/issue_mcp_token.py`, which exist only in the HF repo.
- **Critical hazard:** GitHub `.github/workflows/sync-to-hf.yml` force-pushes GitHub main → HF. Any push to GitHub main would erase the MCP server. Never push either repo.
- **MCP facts:**
  - Endpoint and server: `https://findethics-health-dashboard.hf.space/api/mcp/` (trailing slash required; `/api/mcp` returns 404), served by FastMCP `mcp==1.29.1`.
  - Stateful Streamable HTTP: initialize returns `Mcp-Session-Id`, responses are SSE, and a request without an Origin header is accepted.
  - Exactly two tools: `get_connection_status()` and `get_coach_context(mode, timezone?)`, with modes chat_reply / morning_brief / nightly_brief / weekly_coach.
  - Tool results are TextContent JSON strings.
- **Auth:** opaque `hc_` + `token_urlsafe(32)` bearer token, looked up by SHA-256 hash in `mcp_tokens`. Revocation is supported, and the token must belong to the single designated active owner. No expiry or scopes. Bearer auth is confined to the MCP mount; dashboard routes are cookie-only.
- **Data limitation:** the timezone argument only shifts window boundaries; daily rows are provider/UTC-bucketed. Accepted and stated by the app, not fixed in the backend.
- **Approved design decisions:**
  - no backend runtime change or deploy; backend gets tests only
  - minimal hand-rolled JSON-RPC client, no new Gradle dependencies
  - Health chip with app-driven fetch: status → chat_reply, plus weekly_coach on week/trend/plan/training keywords; tool-free local inference
  - Keystore AES-GCM token storage + 7-day encrypted cache in noBackupFilesDir, fingerprint-isolated, wiped on remove/replace/401
  - token entered by pasting into Advanced; fixed HTTPS endpoint
  - app-rendered Live/Cached/Unavailable card
  - deterministic data-quality flags and an urgent-symptom guard
- **Approved production DB writes, all done:** token A (`grace-launcher-5558`) issued and kept, token B (`smoke-revoked`) issued then revoked. These used the service-role env from `/Users/susuresh/genie/health_app/claude-version/health-dashboard/.env` in a subshell only; it was never printed or copied.
- **Token sharing:** the user chose to reuse token A on the physical Titan 2 Elite too. One shared token; revoking it disables both devices.
- **Release signing:** existing key `~/.local/share/grace-launcher/signing/grace-launcher-current.p12`, cert `7c928de3824f4c6d91056d5e5615c5cb5414fece82643c0930c0ca600f583a0b`. Use `--ks-pass file:` and omit `--key-pass`.
- **Devices:**
  - emulator-5584 (`facts-temp-api36`) and its AVD storage are gone; ask before recreating.
  - 5558 (12 GB, release cert) was used with user approval.
  - Never uninstall or clear the launcher.
- **Security note for the user:** the stale local GitHub checkout `/Users/susuresh/genie/health_app/claude-version/health-dashboard` has an embedded HF credential in its git remote config and should be rotated. That checkout also has uncommitted changes; leave it untouched.

## Work State
### Completed
- Plan/spec written: `assets/plans/2026-11-18-health-mcp-chat.md`.
- **Android implementation:** `HealthMcpClient.kt`, `HealthVault.kt`, `HealthEvidence.kt`, `HealthTurn.kt`, plus wiring in `AssistantController.kt`, `AssistantChatState.kt` (`ChatSource.HEALTH`), `AssistantUi.kt` and `values/strings.xml`.
- **Tests:** `HealthVaultTest`, `HealthMcpClientTest`, `HealthEvidenceTest`, `HealthTurnTest`, `HealthFixtures`. JVM 345/345 pass on JDK 21, mocked as documented.
- **Backend:** local clone `~/genie/health_app/hf-health-dashboard`, branch `test/mcp-protocol-checks`, push URL `DISABLED`. The new untracked files are deliberately left unstaged and uncommitted:
  - `tests/test_mcp_protocol.py` (12 tests)
  - `scripts/mcp_live_smoke.py`
- **Backend test results:**
  - Targeted MCP/coach tests: 37 pass.
  - Full suite: 200 pass, but only with dummy Settings env vars. Without them, 5 existing modules fail at collection; this happens on a clean checkout too.
- **Live smoke against the deployed Space:** 12/12 PASS (protocol flow, 401s, revoked token, MCP token rejected on dashboard read/write routes, 404 without trailing slash).
- **Device E2E on 5558** (1.2.9/17 Health build `98c4e8aa…`, text read via uiautomator only): steps 1–6 PASS.
  - Covered: live answers whose numbers match the data, cached card offline, unavailable state with no cache, revoked token rejected, token A restored.
  - Partial: the training reply didn't ask how the user feels (model compliance; not enforced in code).
- **Commit** `7c9a8011ad6bacbe9d64da86006bc4f1bdfb1bff`, "Add Health dashboard chat source via read-only MCP (1.2.10)": 17 files, including the version bump to 1.2.10 / versionCode 18. Not pushed.
  - Excluded from the commit: `handoffs/handoff-20261118-152227-wikipedia-tdd-cleanup.md` (modified) and `Screenshot 2026-11-12 at 18.34.23.png` (untracked).
- **Signed release APK:** `app/build/reports/health-mcp/release-1.2.10/GraceLauncher-1.2.10-signed.apk`, 59,585,453 B, SHA-256 `f49fd8273b6c85cf900f522e3e271df50d8b6384fb1e2755a639b8b10744f1b5`.
  - Checks passed: cert, P16 alignment, non-debuggable, R8 keep rules, no secrets in the APK.
  - Not installed on any device.
- Titan 2 test steps were given to the user (13 steps in the conversation; not saved in the repo).
- `AGENTS.md` condensed and refreshed: a single current-state block, stale facts fixed, Health wiring bullet added. It is **uncommitted**.

### Active
- (none) Waiting on the user.

### Blocked
- **Not verified:**
  - visual layout and accessibility (user)
  - physical Titan install and test (user)
  - urgent guard and German phrasing on a device
  - prompt injection against real profile data (mocked only, by design)
  - true token expiry (the server has none)
  - native ELF 16 KB alignment
  - physical GPU
- **Real data state:** about 2 days behind; Google connection shows `google_health=error` / reauthorization may be required. The user must reconnect in the web dashboard.
- **Deferred (needs backend deploy and approval):**
  - `day_basis` field / local-day rebucketing
  - token expiry and scopes
  - readOnlyHint annotations
  - per-day series
  - fixing the GitHub ↔ HF divergence
- **Unknown:** whether the user wants the `AGENTS.md` update committed.

## Next Move
1. Await the user. Likely options:
   - commit the uncommitted `AGENTS.md` update (only if the user asks)
   - act on the Titan 2 test results
   - the optional follow-up already offered: an app-enforced "how do you feel today?" check-in for training replies
2. Any new design decision needs a Mastermind interview round and approval before the implementer is invoked again.

## Relevant Files
- `/Users/susuresh/genie/grace-launcher/GraceLauncher/assets/plans/2026-11-18-health-mcp-chat.md`: approved plan/spec (acceptance criteria, guardrails).
- `/Users/susuresh/genie/grace-launcher/GraceLauncher/app/src/main/java/com/galaxyrio/gracelauncher/assistant/Health{McpClient,Vault,Evidence,Turn}.kt`: Health implementation.
- `/Users/susuresh/genie/grace-launcher/GraceLauncher/app/src/main/java/com/galaxyrio/gracelauncher/assistant/{AssistantController,AssistantChatState,AssistantUi}.kt`: wiring.
- `/Users/susuresh/genie/grace-launcher/GraceLauncher/app/build/reports/health-mcp/report.md`: evidence report (mocked / live / device / blocked sections).
- `/Users/susuresh/genie/grace-launcher/GraceLauncher/app/build/reports/health-mcp/release-1.2.10/GraceLauncher-1.2.10-signed.apk`: release APK for the Titan.
- `/Users/susuresh/genie/grace-launcher/GraceLauncher/app/build/reports/health-mcp/prebuild-backup/` and `.../release-1.2.10/prebuild-backup/`: backups of previous APKs.
- `/Users/susuresh/genie/grace-launcher/GraceLauncher/AGENTS.md`: refreshed repo notes (uncommitted).
- `/Users/susuresh/genie/grace-launcher/GraceLauncher/tasks.md`: pending items and evidence (updated by the implementer).
- `~/genie/health_app/hf-health-dashboard`: backend HF clone (branch `test/mcp-protocol-checks`, push disabled; `tests/test_mcp_protocol.py`, `scripts/mcp_live_smoke.py`).
- `~/.local/share/grace-launcher/health-mcp/{valid,revoked}.token`: 0600 token files. The valid one is shared by 5558 and the Titan. Never print it.
- `/Users/susuresh/genie/health_app/claude-version/health-dashboard/.env`: service-role env source (subshell use only; never copy or print).
