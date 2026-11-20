# Memory Phase 4a — Version bump, 5590 device smoke, Gemma4E4B memory quality

## Overview
The memory feature (ledger, bridge, Android wiring) is implemented and green locally, and the ledger is live on Supabase (Gate A smoke PASS: `app/build/reports/memory/live-smoke-20261120/summary.md`). This phase:
- bumps the version;
- runs the memory androidTests and a live-sync device smoke on emulator **5590** (debug cert);
- adds and runs an opt-in, report-only memory answer-quality test with **Gemma4E4B**.

Gate D is approved for 5590 under the rules below. Read `AGENTS.md` (model-matrix state, signing, emulator rules), `tasks.md`, `assets/plans/2026-11-20-memory-p3-android-wiring.md` and `docs/memory/SPEC-memory-chat.md` first.

## Business & UX Decisions
- **Targets:** 5590 (`grace-model-matrix-api36`, API 36, debug cert `f1849f41…`) gets the debug smoke and the quality run. The signed release goes to 5558 in Phase 4b, not here.
- **Model:** switch 5590 to **Gemma4E4B** (the bigger Gemma 4) through the app's own Advanced → Offline assistant picker flow. The user accepted losing E2B files on 5590. **Leave it on E4B** at the end.
- **Quality eval:** report-only, with no pass threshold. Synthetic fixtures only, no personal data.
- **Live writes:** allowed for exactly one test entry, which must be purged afterwards. Only the content-free tombstone may remain.

## Technical Decisions
- **Version bump first:** `1.2.11/19` → **`1.2.12` / versionCode `20`** in `app/build.gradle.kts`. Phase 4b builds the signed release from this.
- **Build env:** JDK 21 (`/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`) and `ANDROID_USER_HOME=/Users/susuresh/.android`, so the debug key `f1849f41…` is used.
  - `.env` now holds `SUPABASE_URL`, already fixed by the user to the bare `https://<project>.supabase.co`, and `SUPABASE_ANON_KEY`. If the build-time URL check fails, STOP and report; do not edit `.env`.
  - Never print `.env` values, the token, or the Supabase host.
- **Install:** verify the debug and androidTest APK certs (`apksigner verify --print-certs`) = `f1849f41…` before installing. Then `adb -s emulator-5590 install -r <debug apk>` and `adb -s emulator-5590 install -t -r <androidTest apk>`.
  - **NEVER** run `connectedDebugAndroidTest`, uninstall, or `pm clear`.
  - If 5590 isn't running, start AVD `grace-model-matrix-api36` with a visible window (no `-no-window`) and no wipe.
  - A cert mismatch means STOP.
- **Filtered instrumentation:** `adb -s emulator-5590 shell am instrument -w -e class com.galaxyrio.gracelauncher.<pkg>.MemoryMirrorDaoTest,…MemoryBackupRulesTest <test-pkg>/androidx.test.runner.AndroidJUnitRunner`. Use the actual FQCNs and runner from the androidTest sources and manifest.
- **Live sync device smoke** (5590 already holds a provisioned Health `hc_` token via Advanced; memory uses the same `HealthVault` token):
  1. Open chat. Through adb UI automation (`input text`/`keyevent`, `uiautomator dump`), submit `/remember` with a unique smoke fact, e.g. `grace memory device smoke <short-uuid> is a test fact`, and confirm the save card.
  2. Wait for sync. The Advanced status line should show "synced at …" with 0 pending.
  3. From the desktop, run the bridge (`tools/memory-bridge`, `uv run`, loading `.env` + `~/.local/share/grace-launcher/health-mcp/valid.token` inside a scratch script under `/private/var/folders/d4/g73rwlh56w3b712vq80zl2y40000gn/T/opencode`). `memory_recall` must find the entry; record its id.
  4. On the device, `/purge` that entry through the pick/confirm flow (second confirm). Then sync.
  5. The bridge's `memory_changes` must show the tombstone for that id, and `memory_recall` must no longer find it.
  6. Also run `/recall` on the device before the purge and check that the entry is listed (uiautomator text).
  - The shared token is used by 5558 and the Titan: never revoke or alter it.
- **Model switch:** drive the existing Advanced picker to Gemma4E4B (confirm dialog → download, pinned revision). Wait for ready. This deletes the other model files, which is expected. Free space is about 15 GiB.
  - If the existing model-matrix tooling (`ModelMatrixTest` with `modelMatrix=true`, `models=Gemma4E4B`) provides a cleaner selection/ready path, you may reuse its helpers, but do not run the full matrix.
- **MemoryQualityTest** (new, `app/src/androidTest/...`): opt-in via instrumentation arg `memoryQuality=true`, otherwise it skips. Follow the `ModelMatrixTest` pattern: real engine, the selected model must be **asserted** ready (not Assume/skip when opted in).
  - Use the real local model through the same inference path the app uses for (1) the `/remember` subject JSON call and (2) tool-free chat with the memory prefetch block. Use a **fake** RPC and fake/in-memory store, so nothing is written to live.
  - Cases, about 12 `/remember` inputs:
    - EN and DE, covering fact / preference / place / English-dated event;
    - one German date phrase (expected: not an event);
    - one long or noisy input.
    - Record per case: rule type, model subject JSON validity, whether the fallback was used, the final subject, latency.
  - Cases, about 6 grounded questions: seed the fake store with synthetic entries, build the real prefetch block, ask questions answerable only from memory (EN and DE), plus one question whose answer is NOT in memory (expect no fabrication).
    - Record per case: reply text, a keyword hit or miss, latency.
  - Output a JSON + markdown report pulled from the device to `app/build/reports/memory/quality-e4b-20261120/`. Raw replies stay in ignored reports only, never in fixtures or commits.
  - The test never fails on quality; it fails only on infrastructure errors (model not ready, crash).
- Each emulator/infra failure gets at most **2 bounded repair attempts**, then STOP and report.

## Relevant Files
- `app/build.gradle.kts` (version)
- `app/src/androidTest/java/com/galaxyrio/gracelauncher/**` (`ModelMatrixTest`, memory androidTests, new `MemoryQualityTest`)
- `app/src/main/java/com/galaxyrio/gracelauncher/assistant/**` (`AssistantController`, memory package, `ModelFiles.kt` catalog)
- `tools/memory-bridge/`
- `tasks.md`, `AGENTS.md` (Current state section)

## Acceptance Criteria
- Version is 1.2.12/20. `:app:testDebugUnitTest` (full), `:app:lintDebug`, `:app:assembleDebug` and `:app:assembleDebugAndroidTest` PASS after the bump and the new test.
- The installed 5590 debug APK SHA-256 matches the built APK, and the cert is `f1849f41…`.
- `MemoryMirrorDaoTest` and `MemoryBackupRulesTest` were run on 5590 via filtered `am instrument`, with the result recorded.
- Live sync smoke steps 1–6 are recorded PASS/FAIL, with the entry id, status-line text, and bridge results (status/error codes only). The test entry ends purged, with only its tombstone left.
- 5590 ends with Gemma4E4B selected and ready, with only its model + sidecar (+ its own cache).
- `MemoryQualityTest` runs with `memoryQuality=true` on E4B.
  - The report contains the per-case table and summary: subject valid %, fallback %, grounded hits n/6, non-fabrication result, median latency.
  - `MemoryQualityTest` skips without the arg, and the JVM/lint builds are unaffected.
- `tasks.md` records evidence paths, implemented vs verified, and open items. `AGENTS.md` "Current state" gets a concise dated bullet: 1.2.12/20, 5590 now on E4B, the memory device-smoke and quality evidence paths.

## Tests to Add/Run
- `MemoryQualityTest` (new, opt-in androidTest). Run it via `am instrument -e memoryQuality true -e class <FQCN>` on 5590.
- `MemoryMirrorDaoTest`, `MemoryBackupRulesTest` via filtered `am instrument` on 5590.
- Gradle (JDK 21, ANDROID_USER_HOME set): `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest`.
- Report the exact commands (without secrets), pass/fail counts and evidence paths. Write a summary at `app/build/reports/memory/device-smoke-5590-20261120/summary.md`.
- No visual QA claims; the user does visual verification.

## Scope Guardrails
- Only emulator-5590. Do not touch 5558 (Phase 4b), 5580, 5582, the Titan, or any other device. Never `connectedDebugAndroidTest`, uninstall, `pm clear`, wipe, or AVD recreate.
- Never change the signing keys/config, `LauncherDatabase`, or the dashboard repos; never touch `user_memories`; never use the service-role key; never revoke the token. Never log/commit secrets or `.env` values; don't print the Supabase host.
- No production code changes, except the version bump and the new androidTest. If a real app bug is found, STOP and report it with evidence; do not fix it.
- No commits/pushes. No delegation.
- On ambiguity, STOP and report.

## Open Questions & Risks
- adb UI automation of the Compose chat can be brittle (IME, focus). Bounded 2 repairs, then STOP.
- E4B download time/size. If it fails, STOP and report.
- Sync timing: the triggers are chat open / after write / connectivity. Allow a bounded wait and reopen chat if needed.

## Implementation Task List
- [ ] Bump the version to 1.2.12/20.
- [ ] Write `MemoryQualityTest` (opt-in) and run the gradle checks.
- [ ] Build debug + androidTest; verify certs; check 5590 is running (start visible if not).
- [ ] install -r / install -t -r; verify the installed SHA-256.
- [ ] Filtered am instrument: MemoryMirrorDaoTest, MemoryBackupRulesTest.
- [ ] Live sync smoke steps 1–6 with the bridge; purge the test entry.
- [ ] Switch 5590 to Gemma4E4B; wait ready; verify the files on disk.
- [ ] Run MemoryQualityTest with memoryQuality=true; pull the report.
- [ ] Write the summary; update tasks.md + AGENTS.md current state; report.
