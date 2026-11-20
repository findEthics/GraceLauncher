## Objective
- Grace Launcher offline assistant: the selectable-model feature shipped earlier (1.2.11 / 19). This session tested all 4 models test-first, using JVM tests plus a real device matrix. It fixed what it could, added an engine-cache sweep on model switch, labelled Qwen2.5 experimental, and rebuilt the signed 1.2.11 release APK. The next step is your smoke test of the signed release.

## Important Details
- **Workflow:** Mastermind planner/orchestrator.
  - The planner never implements; it interviews one question at a time via `question`.
  - Facts come from the `mastermind-explorer` subagent.
  - Implementation goes to **`mastermind-implementer-gpt-6-1-sol`**, the model the user chose this session (the earlier session used sonnet-5-5).
  - After each implementer report: re-read the plan's Acceptance Criteria/Guardrails, review `git diff`, no test reruns, relay the report plus a review note.
  - A real new decision means another interview round, then greenlight, then an append to the plan, then a fresh `subagent` call passing only the plan path.
- **User preferences:** spec-driven + test-driven development (tests first; Prove-It for bugs). Be concise.
- **Plan file (untracked):** `assets/plans/2026-11-19-four-model-device-matrix-tdd.md`. It has the main plan (Revision 2, new AVD), **Follow-up 1** (cache sweep + Qwen2.5 label) and **Follow-up 2** (signed release). Edit it in place or append further follow-ups.
- **Prior plan (untracked):** `assets/plans/2026-11-19-model-selection-settings-sound-indicator.md` (the original model-selection feature).
- **Model catalog:** `Gemma4` (E2B, default; the enum name and filename must never change because storage is `<enum>-<filename>`), `Gemma4E4B`, `Qwen3_1_7B` (`enable_thinking=false`), `Qwen2_5_1_5B`. All pinned, all ungated.
- **Device decisions:**
  - 5558 (`Notes_Automation_Phone_API36`) couldn't fit E4B: 6 GiB qcow2 userdata, metadata-encrypted ext4, Play Store image.
  - Growing its partition in place failed; it was restored from a backup and is now **out of scope; don't touch it**. It is still on the signed 1.2.11 `458eb851…` build.
  - Backups: `/private/var/folders/d4/g73rwlh56w3b712vq80zl2y40000gn/T/opencode/avd-backup-5558-20261119/` and `.../avd-failed-resize-5558-20261119/`.
- **New retained AVD:** `grace-model-matrix-api36` on **emulator-5590**.
  - Hand-created (no avdmanager): google_apis API 36 arm64, 12 GB RAM, 20 GiB data, `hw.keyboard=yes`, visible window.
  - Has the debug build (cert `f1849f41…`), Gemma4 selected, and the Health token provisioned through Advanced.
  - Build with **`ANDROID_USER_HOME=/Users/susuresh/.android`**, or XDG picks the wrong debug key.
  - Never run `connectedDebugAndroidTest` (it would wipe the downloaded models). Use `install -r` / `install -t -r` plus `adb -s emulator-5590 shell am instrument -w -e modelMatrix true [-e models X,Y] -e class com.galaxyrio.gracelauncher.assistant.ModelMatrixTest com.galaxyrio.gracelauncher.test/androidx.test.runner.AndroidJUnitRunner`.
- **Matrix design:**
  - Model switches use full UI taps with a real in-app HF download. Order: Qwen3 → Qwen2.5 → E4B → Gemma4.
  - 6 prompts per model through the chat UI, all hard checks: 12×7→84; Grace name recall; Web 2024 Nobel Literature → Han Kang; Wikipedia capital of Australia → Canberra; "source 1"; Health sleep → Live/Cached card.
  - Every reply is also checked for no `<think>`, no `<tool_call>` and no `hc_` leaks.
  - Fixes are app-side only, max 3 cycles per model. No sampler/context/SDK/catalog changes.
- **User decisions this session:**
  - Qwen2.5: keep it, labelled experimental.
  - Sweep stale engine caches on a confirmed switch.
  - Release keeps **1.2.11 / 19** (no bump).
  - "Commit locally": the user said ignore anything already committed.
  - No release install was requested.
- **Repo rules:**
  - Commits use the repo identity `findEthics`, no tool attribution, never pushed.
  - Never print or commit `.env`, signing passwords, the `hc_` token or health data.
  - Release signing: `~/.local/share/grace-launcher/signing/grace-launcher-current.p12`, alias `grace-launcher`, `--ks-pass file:` with no `--key-pass`, cert `7c928de3824f4c6d91056d5e5615c5cb5414fece82643c0930c0ca600f583a0b`.
  - Health token file `~/.local/share/grace-launcher/health-mcp/valid.token` is shared by 5558, Titan and 5590; never revoke or print it.
- **Tooling:** context-mode `ctx_search` fails with "disk I/O error". Workaround: redirect shell output to a file under `/private/var/folders/d4/g73rwlh56w3b712vq80zl2y40000gn/T/opencode/` and `read` it.

## Work State
### Completed
- **Commits** (local, not pushed), on top of `09f25a6`:
  - `6ec1936`: `ModelMatrixTest` + `assistant/ModelPrompts.kt` family prompts (Qwen2.5 retrieval/year instructions; Gemma "repeat exact headline" for source-N) + `ModelPromptRegressionTest` + public-fact fixtures under `app/src/test/resources/model-matrix/`
  - `b1602be`: docs
  - `f65643b`: `staleEngineCaches()` in `ModelFiles.kt`, called in `sweepExcept`, best-effort delete; Qwen2.5 note "Small, multilingual · experimental, may misstate facts"; `EngineCacheSweepTest`; matrix cache assertion
  - `a2790c6`: release evidence docs
- **Full matrix run `1795123418393`: 23/24 PASS.**
  - Qwen3, E4B and Gemma4 pass all 6 cases.
  - **Qwen2.5 is FAILING after 3 cycles** on the Web case: it named Anne Carson despite real sources. Its other 5 cases pass.
  - Evidence: `app/build/reports/model-matrix/avd-5590-20261119/summary.md`.
- **Follow-up 1 rerun `1795125667867`** (Qwen3 → Gemma4): switch and cache assertions pass, 12/12 cases, Health Live. `/data` free went from 12.6 GB to 15.6 GB. Evidence: `app/build/reports/model-matrix/followup1-20261119/summary.md`.
- **Tests and build:** JVM **362/362**; `lintDebug`, `assembleDebug` and `assembleDebugAndroidTest` pass.
- **Signed release APK** (from `f65643b`, still 1.2.11/19): `app/build/reports/model-matrix/release-1.2.11-f65643b/GraceLauncher-1.2.11-signed.apk`.
  - 59,606,045 B, SHA-256 `939d5c563d5bc4515903ca26489202453bb03af2f256a556c77188785c877d86`, cert `7c928de3…`.
  - v2/v3 verified, P16 OK, non-debuggable, R8 keep checks OK.
  - Planner confirmed the hash, the version and that the new Qwen2.5 string is in the APK.
  - Supersedes `458eb851…` at the same version. **Not installed anywhere.**
- Planner diff reviews of all implementer passes: consistent with the plan, no scope violations.

### Active
- Nothing in progress; waiting on the user.
- Uncommitted on purpose (the user said to leave them):
  - a `tasks.md` hunk (the 1.2.11 install line for 5558)
  - the `README.zh-CN.md` deletion (pre-existing, intent unknown)
  - the modified `handoffs/handoff-20261118-152227-wikipedia-tdd-cleanup.md`
  - untracked `.claude/`, both plan files, and the `handoffs/handoff-20261118-213911-*` and this handoff file

### Blocked
- Nothing blocked.
- **Unverified:**
  - signed-release runtime smoke (assistant/Web/Wikipedia/Health)
  - native ELF 16KB alignment
  - physical-device GPU, including E4B performance on the Titan
  - visual QA; German prompts
  - Qwen2.5/E4B not rerun after the cache sweep
- **Known risks:**
  - Qwen2.5 factual grounding is unreliable.
  - The Qwen2.5 year prompt is narrowly worded (it mirrors the test question).
  - A leaked `<tool_call>` may flash during streaming before the final fail-closed check.
  - A failed download after a switch leaves no model until retry.

## Next Move
1. Collect the user's results from the signed-release smoke test (5558 via `adb -s emulator-5558 install -r` only, same cert; or physical Titan 2 Elite). Install only if the user asks.
2. For each bug or tweak: run an interview round if it's a new decision, append a follow-up to the plan file, then re-invoke `mastermind-implementer-gpt-6-1-sol` (tests first) and review the diff.
3. Optional, awaiting the user: commit the plan/handoff files and the leftover `tasks.md` hunk; decide on the `README.zh-CN.md` deletion; more Qwen2.5 fix cycles need fresh approval.

## Relevant Files
- `assets/plans/2026-11-19-four-model-device-matrix-tdd.md`: this session's plan, with Follow-ups 1–2.
- `assets/plans/2026-11-19-model-selection-settings-sound-indicator.md`: the original model-selection plan.
- `app/src/androidTest/java/com/galaxyrio/gracelauncher/assistant/ModelMatrixTest.kt`: the opt-in 4-model device matrix.
- `app/src/main/java/com/galaxyrio/gracelauncher/assistant/ModelPrompts.kt`: per-family prompt tweaks.
- `app/src/main/java/com/galaxyrio/gracelauncher/assistant/ModelFiles.kt`: catalog, `sweepTargets`, `staleEngineCaches`, `sweepExcept`, `hasSpaceForSwitch`.
- `app/src/main/java/com/galaxyrio/gracelauncher/assistant/AssistantController.kt`: engine/cache dir, inference, prompt wiring.
- `app/src/test/java/com/galaxyrio/gracelauncher/assistant/{ModelPromptRegressionTest,EngineCacheSweepTest,ModelCatalogTest}.kt`: JVM tests.
- `app/src/main/res/values/strings.xml`: `assistant_model_note_qwen25`.
- `app/build/reports/model-matrix/`: matrix evidence (`avd-5590-20261119/`, `followup1-20261119/`, `resize-blocked-20261119/`) and the release (`release-1.2.11-f65643b/`). Contains real Health replies; never commit.
- `~/.android/avd/grace-model-matrix-api36.{ini,avd/}`: retained matrix AVD.
- `tasks.md`, `AGENTS.md`: evidence and current-state notes (AGENTS current state is at `f65643b` / `939d5c56…`).
