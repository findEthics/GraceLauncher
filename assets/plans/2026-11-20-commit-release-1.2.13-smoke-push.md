# Commit all work, signed release 1.2.13/21, 5558 smoke, push

## Overview
The tree has two batches of uncommitted work, both verified: assistant memory (1.2.12/20) and the follow-ups in `assets/plans/2026-11-20-memory-chips-slash-commands-sync.md`, including Follow-up 1. Three steps:
1. Commit everything in 3 logical commits.
2. Bump to 1.2.13/21, build and sign the release APK from the clean commit, and smoke-test it on emulator-5558.
3. If the smoke passes, commit the evidence and push the branch.

For the release/verification procedure, mirror `assets/plans/2026-11-20-memory-p4b-signed-release.md` (read it). Its evidence is in `app/build/reports/memory/release-1.2.12/summary.md`.

## Business & UX Decisions
- **Commit literally everything, user decision.** This includes pre-existing unrelated changes:
  - the `README.zh-CN.md` deletion;
  - the modified `handoffs/handoff-20261118-152227-wikipedia-tdd-cleanup.md`;
  - all untracked older plans and handoffs.
  - Coordinator pre-scan: no real hc_ token, Supabase anon key or Supabase host appears in any file to be committed. The only hits are `x.supabase.co` test fixtures, `.supabase.co` generic text and supabase.com doc links.
- **3 logical commits plus 1 evidence commit:**
  1. **Assistant memory (base, 1.2.12/20).**
     - Files: `server/supabase/grace_memory/` (migrations 001–003, `APPLY-CHECKLIST.md`, `tests/`), `tools/memory-bridge/`, `docs/memory/`, `app/src/main/java/.../assistant/memory/` core plus Android wiring, `WebTurn.kt`, the controller/UI/state wiring, Room schema under `app/schemas/`, backup XMLs, manifest, `app/build.gradle.kts` (SUPABASE BuildConfig, 1.2.12/20), memory strings, and the memory JVM/androidTests and fixtures.
     - The memory work and the follow-ups overlap in the same files (`AssistantUi.kt`, `strings.xml`, `MemorySync.kt`, …). Splitting hunks between commits 1 and 2 is **not required** if it's error-prone. Acceptable fallback: commit 1 holds all code (memory plus follow-ups), and commit 2 holds only the version bump. Note it in the commit body.
  2. **Memory chips, slash commands, sync, collapsible help** plus the **version bump to 1.2.13 / versionCode 21**: `SlashCommands.kt`, `MemorySql.kt`, `manual-delete.sql`, `test_07_manual_delete.py`, `HelpSection`, the `MagicCommandWindow.kt` help placement, strings, and tests.
  3. **Docs/housekeeping:** `assets/plans/*`, `handoffs/*`, the `README.zh-CN.md` deletion, `tasks.md`, `AGENTS.md` (state before the release).
  4. **Release evidence commit**, after the smoke: `tasks.md` + `AGENTS.md` current-state bullet with the 1.2.13 artifact facts.
  - Message style: short imperative subject, an optional body, **no tool/AI attribution**, and no mention of agent settings.
  - Author: the repo's local config is already set (`finds_ethics.0j@icloud.com`). Don't change git config.
- **Version 1.2.13 / 21.** 1.2.12/20 was already signed from a dirty tree and is installed on 5558. A new version keeps the artifacts distinct.
- **Smoke target: emulator-5558 only** (release cert `7c928de3…`, non-debuggable install). Use `install -r`, keeping data, model and Health token. Titan, 5590 and other emulators are not touched.
- **Push only after the smoke succeeds.** Run `git push origin work/from-7587164`; no force and no other branches. Remote: `git@github-personal:findEthics/GraceLauncher.git`. The branch is already 13 commits ahead of `origin/work/from-7587164`, and those get pushed too, as the user intends. If the smoke fails after the bounded repairs, **don't push**. Report and stop.

## Technical Decisions
- **Pre-commit:** `git status --porcelain -uall`. Confirm that `.env`, `*.apk`, `app/build/`, keystores/`.p12`, token files and `memory_mirror.db` are not staged (git-ignored). Re-run the content scan on the staged diff:
  - `git diff --cached | grep -E 'hc_[A-Za-z0-9]{16,}|eyJhbGciOi'` returns nothing;
  - a fixed-string grep of the staged diff for the real host and anon key, both read from `.env` into variables, returns nothing. **Never print them.**
- **Version bump** in `app/build.gradle.kts`: `versionCode = 21`, `versionName = "1.2.13"`. Update any test or fixture that asserts the version (grep `1.2.12` and `versionCode` in `app/src`) in the same commit.
- **Checks before release:** `bash ./gradlew :app:testDebugUnitTest :app:lintDebug` on the committed tree (JDK 21: `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`; `ANDROID_USER_HOME=/Users/susuresh/.android`). The tree must be clean (`git status --porcelain` empty apart from ignored files) before `assembleRelease`.
- **Release** (the established procedure from AGENTS.md "Task tracking and release signing" and the p4b plan):
  1. `bash ./gradlew :app:assembleRelease` (unsigned, R8).
  2. `zipalign -P 16 -f 4` (16 KB pages).
  3. `apksigner sign` with `~/.local/share/grace-launcher/signing/grace-launcher-current.p12`, alias `grace-launcher`, `--ks-pass file:…grace-launcher-current.password`. **Omit `--key-pass`**, and never print the password.
  4. Verify: `apksigner verify -v --min-sdk-version 24` (v2/v3) and the cert SHA-256 `7c928de3824f4c6d91056d5e5615c5cb5414fece82643c0930c0ca600f583a0b`; `zipalign -c -P 16 -v 4`; `aapt2 dump badging` (package, 1.2.13/21, not debuggable); the DEX keeps (LiteRT JNI `onMessage`, `MobileActionTools`, `WebSearchTools.search_web`, Wikipedia tool methods); SHA-256.
  - Output: `app/build/reports/release-1.2.13/GraceLauncher-1.2.13-signed.apk` plus logs (`verify.txt`, `zipalign.txt`, `badging.txt`, `dex-keep.txt`, `sha256.txt`, `summary.md`).
- **5558 install:** check that 5558 is running (`adb devices`). If it isn't, start AVD `Notes_Automation_Phone_API36` with a visible window; if that can't be done, stop and ask. Compare the installed cert to `7c928de3…` before installing, then `adb -s emulator-5558 install -r <apk>` and confirm that `dumpsys package` shows versionName 1.2.13.
- **Smoke** (drive the UI via adb input / uiautomator dump as in Phase 4b; no screenshots for visual judgement). Clear logcat once at the start and capture it for the whole run in one file, with no mid-run clears. For each item, record PASS/FAIL with a one-line observation:
  1. Home loads; chat opens with Space.
  2. Plain chat reply.
  3. Web reply with sources.
  4. Wikipedia reply with sources.
  5. Health Live card.
  6. Memory: `/remember …` → Save, sync status shows synced, `/recall` lists it, Forget chip soft-deletes, Purge chip purges with confirm, and the tombstone syncs. Use a disposable test note and leave no test notes behind.
  7. New features:
     - typing `/` shows the popup;
     - Enter on `/web` selects the Web chip;
     - typed `/wiki <q>` selects Wikipedia;
     - Recall chip with empty input lists notes;
     - Advanced → "Sync memory now" shows a result;
     - each of the 3 help toggles (assistant, memory, keyboard) expands.
  8. Logcat contains no `FATAL EXCEPTION`, `NoSuchMethodError` or SIGABRT for the package.
  - Raw Health replies and memory text stay in ignored reports, never in commits.
- **Failures:** at most 2 bounded repairs per failure. A code fix becomes a new commit before the rebuild (keep the version unless the APK is already distributed; it isn't, so re-sign 1.2.13/21). If it still fails, stop: no evidence commit, no push. Report.

## Relevant Files
- `app/build.gradle.kts` (version), `tasks.md`, `AGENTS.md` (current-state bullets)
- `assets/plans/2026-11-20-memory-p4b-signed-release.md` (procedure to mirror), `app/build/reports/memory/release-1.2.12/summary.md` (evidence format)
- `app/proguard-rules.pro` (keeps to verify in DEX)
- Signing: `~/.local/share/grace-launcher/signing/grace-launcher-current.{p12,password}`
- Health token (already on 5558; do not reprovision unless missing): `~/.local/share/grace-launcher/health-mcp/valid.token`

## Acceptance Criteria
1. `git status --porcelain` is empty (ignored files aside) after commits 1–3. The README.zh-CN deletion and the old handoffs/plans are committed. No secrets are in any commit (scan passed).
2. Commits have clean messages with no tool attribution, from the existing local author.
3. The committed tree passes `testDebugUnitTest` and `lintDebug`. Version is 1.2.13/21.
4. The signed release was built from a clean HEAD (commit hash recorded). It passes v2/v3 verify with cert `7c928de3…`, 16 KB alignment, badging 1.2.13/21 non-debuggable, and the DEX keeps. SHA-256 recorded.
5. 5558 runs 1.2.13 via `install -r` with data, model and token kept. Every smoke item is PASS with a logcat capture of the full run.
6. The evidence commit updates `tasks.md`/`AGENTS.md` with the source commit, SHA-256, size and smoke results.
7. `git push origin work/from-7587164` succeeded (no force), and the upstream is now level with HEAD. This only happens if 5 passed.

## Tests to Add/Run
- No new tests. Run `:app:testDebugUnitTest` and `:app:lintDebug` on the committed tree, plus the release verification commands and the 5558 smoke above. Report pass/fail for each.
- No visual QA or screenshots for visual judgement; that's the user's job.

## Scope Guardrails
- No code changes except a smoke-failure repair (bounded) and the version bump. No force-push, rebase, history rewrite, other branches, or `master`. Don't change git config or the signing key, and don't use the debug key for the release.
- Never run `connectedDebugAndroidTest`. Never uninstall, clear or wipe any emulator. Don't touch 5590, Titan or other devices.
- Never print or commit `.env` values, tokens, the Supabase host or the signing password. Don't revoke the shared hc_ token. No live Supabase SQL except via the app's normal sync. Never touch `public.user_memories` or `mcp_tokens`.
- Don't push the health-dashboard repo or any other repo.

## Open Questions & Risks
- Splitting the hunks of shared files between commits 1 and 2 may be impractical; the fallback is allowed (see Decisions).
- The 13 already-unpushed older commits get pushed too, as the user intends.
- Memory-aware reply fidelity was weak in Phase 4b. A paraphrased answer is not a FAIL; FAIL only if memory is ignored or wrong.

## Implementation Task List
- [ ] Pre-commit secret/ignore scan (staged diff).
- [ ] Commit 1 (memory base), commit 2 (follow-ups + 1.2.13/21 bump), commit 3 (docs/housekeeping); `git status` clean.
- [ ] `testDebugUnitTest` + `lintDebug` on the committed tree.
- [ ] `assembleRelease` → zipalign P16 → sign → verify (v2/v3, cert, alignment, badging, DEX keeps, SHA-256) → evidence files.
- [ ] 5558: cert check, `install -r`, version check.
- [ ] Smoke items 1–8 with a full logcat capture; at most 2 repairs per failure, otherwise stop.
- [ ] Commit 4: evidence in `tasks.md`/`AGENTS.md`.
- [ ] `git push origin work/from-7587164` (only if the smoke passed); confirm the upstream is level.
