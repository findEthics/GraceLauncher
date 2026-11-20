# Four-model device matrix (TDD) on a new dedicated AVD (emulator-5590)

> **Revision 2 (2026-11-19):** first pass was INCOMPLETE because growing 5558's `/data` in place failed. 5558's userdata is a 6 GiB qcow2 holding metadata-encrypted ext4 on a Play Store image, and the emulator ignored `disk.dataPartition.size`. It was restored from backup. The user then chose a **new dedicated AVD**. 5558 is now **fully out of scope**: don't touch it. Evidence from the failed attempt: `app/build/reports/model-matrix/resize-blocked-20261119/summary.md`.

## Overview
The offline assistant has 4 selectable models (`AssistantModel` in `assistant/ModelFiles.kt`). Only Gemma4 E2B has been exercised for real; the others are unverified for chat, Web, Wikipedia, "source N" and Health. Build an opt-in instrumentation suite. It does real UI-driven model switches and in-app downloads for all 4 models, then runs a fixed, hard-asserted prompt set through the chat UI on a new AVD `grace-model-matrix-api36` (emulator-5590). Any failure is fixed test-first: write a JVM repro from the captured real output, then make the app-side fix. Cap at 3 fix cycles per model.

## Business & UX Decisions
- **Scope = JVM + device.** The user explicitly lifted the earlier "no agent device runs" rule for this task, on the new AVD only.
- **Goal = test + fix.** Run the matrix; every real failure goes through Prove-It: a failing JVM test from the captured output, then the fix, then a rerun.
- **Device = a new AVD `grace-model-matrix-api36` on port 5590**, created for this task and **kept afterwards** (not disposable). Why: 5558 couldn't fit E4B, and 5560 has only 2.5 GB RAM. 5558, 5560, 5580, 5582, 5584 and every other emulator stay untouched.
- **Build = plain debug APK + androidTest APK**, signed with the normal debug keystore (`~/.android/debug.keystore`, cert SHA-256 `f1849f419e633e9f078b53a0095d6e963683da46fe439b25e705e9b267665ce2`). There's no re-signing, no Gradle signing change, and R8 is not exercised (accepted).
- **Disk:** the 20 GiB data partition is set at AVD creation, which is enough for any one model plus its engine cache.
- **Health token:** provisioned by the agent from `~/.local/share/grace-launcher/health-mcp/valid.token`. The token is never printed, logged, put in reports or committed. This token is shared with 5558 and Titan; don't revoke it, and never use `revoked.token` except where a test deliberately needs it (none here).
- **Model install = real in-app switch for all 4**, driven by **full UI taps**: Settings → Advanced → Offline assistant → model picker → radio → confirm dialog → app's own HF download. Rationale: the user wants the real switch path covered (space check, cancel, chat clear, engine close, sweep, download, verify).
- **Switch order:** Qwen3_1_7B → Qwen2_5_1_5B → Gemma4E4B → Gemma4. That is 4 real switches, and the run ends on the default model.
- **Prompts go through the chat UI**: open chat on Home, set chips, type, press Enter, wait for streaming to finish, then read the reply and cards from semantics.
- **Every check is HARD**, including model-behaviour checks such as "did it search" and "fact correct". A model miss counts as a bug to fix.
- **Fix bounds:** app-side only. Allowed:
  - per-model-family system-prompt tweaks
  - Qwen-style `<tool_call>{json}</tool_call>` text recovery, with the same strict schema validation as the existing Gemma envelope recovery
  - output-cleaning fixes

  Max **3 fix→rerun cycles per model**. If it still fails, stop and report that model as **FAILING** with evidence. No catalog removals and no sampler/context/SDK changes.
- **End state:** the new AVD is kept, with the **debug build installed** and **Gemma4 selected**. It may stay running. No version bump, no release build. Commit code/test changes locally (repo identity, no tool attribution). **Never push.**

## Technical Decisions

### 1. Create the dedicated AVD (before anything else)
The SDK is at `~/Library/Android/sdk`; emulator 37.1.11.0. There are **no cmdline-tools**, so no `avdmanager`: create the AVD by hand.
1. Use system image `~/Library/Android/sdk/system-images/android-36/google_apis/arm64-v8a/` (non-Play, so root is available if needed). Confirm it exists. If it doesn't, stop and report; don't download images without asking.
2. Write `~/.android/avd/grace-model-matrix-api36.ini`:
   ```
   avd.ini.encoding=UTF-8
   path=/Users/susuresh/.android/avd/grace-model-matrix-api36.avd
   path.rel=avd/grace-model-matrix-api36.avd
   target=android-36
   ```
3. Write `~/.android/avd/grace-model-matrix-api36.avd/config.ini`, modelled on `~/.android/avd/Notes_Automation_Phone_API36.avd/config.ini` (read it, don't modify it). Changes from that model:
   - `image.sysdir.1=system-images/android-36/google_apis/arm64-v8a/`
   - `tag.id=google_apis`, `tag.display=Google APIs`, `PlayStore.enabled=false`
   - `hw.ramSize=12288`, `disk.dataPartition.size=21474836480`, `hw.keyboard=yes`
   - `hw.cpu.ncore=4` (or the model's value if higher)
   - `fastboot.forceColdBoot=yes`
   - `avd.ini.displayname=grace-model-matrix-api36`

   Don't copy any userdata, snapshots or qcow2 files.
4. Boot with a **visible window** in the background: `~/Library/Android/sdk/emulator/emulator -avd grace-model-matrix-api36 -port 5590 -no-snapshot-load`. Wait for `sys.boot_completed=1`.
5. Verify:
   - `adb -s emulator-5590 shell df -h /data` shows ~20G total with ≥ 15 GB free.
   - `getprop ro.build.version.sdk` = 36, ABI arm64-v8a, `MemTotal` ≈ 12 GB.
   - The emulator has internet: e.g. `adb -s emulator-5590 shell ping -c1 huggingface.co`, or rely on the first download.
6. If the size or RAM didn't apply, or the boot fails: stop that emulator and report **INCOMPLETE** with evidence. Never touch other AVDs.
7. Basic launcher setup is done by the test or via adb on 5590 only, e.g. `cmd role add-role-holder android.app.role.HOME com.galaxyrio.gracelauncher` if Home must be the launcher for the chat/Space path.

### 2. Build, install, provision
- `bash ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`.
- Verify both APK certs (`apksigner verify --print-certs`). They must be `f1849f419e633e9f…` (the debug keystore). Never regenerate or replace the debug keystore.
- `adb -s emulator-5590 install -r <app>`, then `adb -s emulator-5590 install -t -r <test>`.
  - **Never** use `connectedDebugAndroidTest`: its teardown can uninstall, which would delete the downloaded models.
  - Use `adb -s emulator-5590` explicitly on every command.
- Run tests with `adb -s emulator-5590 shell am instrument -w -e modelMatrix true [-e models Qwen3_1_7B,...] -e class com.galaxyrio.gracelauncher.assistant.ModelMatrixTest com.galaxyrio.gracelauncher.test/androidx.test.runner.AndroidJUnitRunner`. Confirm the test package/runner name from the built test APK.
- The debug build must carry the Tavily key (embedded from the ignored `.env`, as today). If Web is unavailable in debug, stop and report; do not hand-copy `.env` values anywhere.
- **Health token provisioning** (once, before the matrix):
  1. `adb -s emulator-5590 push ~/.local/share/grace-launcher/health-mcp/valid.token /data/local/tmp/grace-health.token`.
  2. A test step reads that file and enters the token through the app's normal Advanced → Health token field and Save. That path stores it Keystore-encrypted in `noBackupFilesDir/health-mcp/`.
     - This can be a separate opt-in test method, e.g. `provisionHealthToken`, gated by `-e provisionHealth true`. It may also be the first step of the matrix when the Health chip is absent and the file exists.
  3. Delete the tmp file right away: `adb -s emulator-5590 shell rm -f /data/local/tmp/grace-health.token`. The test should also delete it, best-effort.
  4. The token must **never** appear in stdout, logcat, test failure messages, reports or fixtures. Mask any assertion message. Never `cat` the file.
  5. After this, the Health chip must be visible. If it isn't, that's a hard precondition failure for case 6.
  - Never print or log anything matching `hc_`.

### 3. `ModelMatrixTest` (new, androidTest, `assistant/` package)
- **Opt-in:** skip the whole class unless the instrumentation arg `modelMatrix=true` is set, following the existing `modelDownload=true` pattern in `ModelDownloadTest`. Optional `models=` comma-separated enum names, for reruns of a single model during fix cycles.
  - On a filtered rerun, if the target model is already selected and ready, the switch step may be skipped.
  - A full run must perform all 4 real switches.
- **Per model, in order Qwen3_1_7B, Qwen2_5_1_5B, Gemma4E4B, Gemma4:**
  1. **Switch via UI.** Launch `SettingsActivity` → Advanced → Offline assistant → picker → select radio → confirm. Await the download plus verification, with a timeout of ~30 min.
  2. **Assert after the switch:**
     - the picker shows the new model as selected/ready
     - `assistant-models/` contains exactly `<enum>-<filename>` + `.sha256` for that model and nothing else (read via the instrumentation's target context)
  3. **Prompt suite** through the Home chat UI (Space or chat button → composer → type → Enter → wait for streaming end; reply timeout ~10 min):

     | # | Chips | Prompt | HARD asserts |
     |---|---|---|---|
     | 1 | none (remove Web) | `What is 12 times 7? Reply with just the number.` | reply contains `84` |
     | 2 | none | `My name is Grace.` then `What is my name?` | 2nd reply contains `Grace` |
     | 3 | Web | `Who won the 2024 Nobel Prize in Literature?` | a Tavily search ran; ≥1 source card; reply contains `Han Kang` |
     | 4 | Wikipedia | `What is the capital of Australia?` | Wikipedia lookup ran; ≥1 source card; reply contains `Canberra` |
     | 5 | (after #4, same chat) | `source 1` | resolved by the app's source-N path (tool-free, no network); reply mentions source 1's title |
     | 6 | Health | `How did I sleep last night?` | Health card shows Live or Cached; non-empty reply |

     - Clear chat between cases (except 2a→2b and 4→5).
     - Matching is case-insensitive substring.
     - **Every reply also asserts:** non-empty; no `<think`; no `<tool_call`, `<|tool_call`, or other raw tool markers; no `hc_`; no crash/ANR (the test process survives).
  4. **Capture evidence** (always, pass or fail):
     - per case: model, prompt id, raw final reply text, card titles/hosts, Health card state, latency (ms), pass/fail + reason
     - written as JSON to the test APK's/target's files dir and pulled with `adb` into `app/build/reports/model-matrix/<run-timestamp>/` (git-ignored build dir)
     - Health reply text is captured **only in the build report**, never committed.
- Use soft-collect-then-fail: run all cases for a model, record each result, then fail at the end with a summary. One miss shouldn't hide the rest.
- Reuse existing test tags/semantics from `AssistantUiTest`, `AssistantHomeKeyboardTest` and `LauncherSettingsIntegrationTest`. Add test tags in production UI only where none exist (minimal, no behaviour change).
- How "Tavily ran" / "Wikipedia ran" is detected: prefer what the UI exposes (source cards with hosts; a wikipedia.org host for #4). Do not add production logging of raw input/output.

### 4. Fix loop (per failing hard check)
1. Take the captured real output from the report.
2. Write a **failing JVM test** in `app/src/test/.../assistant/` that reproduces it. Fixtures go in `app/src/test/resources/model-matrix/<model>/` and must have health data removed (Health: replace with synthetic values).
3. Make the minimal app-side fix. Candidates:
   - Qwen `<tool_call>{"name":"search_web","arguments":{...}}</tool_call>` text recovery feeding the same validators as `WebToolText.query()` / `WikipediaSearch.recoveredCall()`
   - per-family system-prompt tweak
   - `ModelText.cleanModelText` fix
4. `testDebugUnitTest` green → rebuild + `install -r` / `install -t -r` on 5590 → rerun that model with `-e models <enum>`.
5. Max 3 cycles per model. After that, mark the model FAILING in the report and move on.
- Gemma behaviour must not regress. Any parsing change must keep the existing `WebToolTextTest` and Wikipedia recovery tests green.

## Relevant Files
- `app/src/main/java/com/galaxyrio/gracelauncher/assistant/ModelFiles.kt`: catalog, `file/ready` (`<enum>-<filename>` + `.sha256`), sweep/space helpers.
- `app/src/main/java/com/galaxyrio/gracelauncher/assistant/AssistantController.kt`:
  - `withEngine` (~246), greedy sampler (~281/310/352/407), `inferChat` (~304–324), `inferSavedText` (~347), `inferWebChat` (~398–505; `automaticToolCalling=false`, manual tool loop, recovery ~429–443), context 4096 (~560)
  - `switchModel`
- `assistant/ModelText.kt` (`stripThinking`, `cleanModelText`); `assistant/WebSearch.kt` (`WebToolText.query` ~36); `assistant/WikipediaSearch.kt` (`recoveredCall` ~90); `assistant/SourceReference.kt`; `assistant/HealthTurn.kt`; `assistant/AssistantUi.kt` (picker, confirm dialog, chips, cards).
- androidTest patterns:
  - `app/src/androidTest/java/com/galaxyrio/gracelauncher/assistant/ModelDownloadTest.kt` (opt-in arg pattern, download + SHA)
  - `AssistantOfflineTest.kt` (`actionCase` filter arg), `AssistantUiTest.kt`, `AssistantHomeKeyboardTest.kt`
  - `app/src/androidTest/.../LauncherSettingsIntegrationTest.kt` (Advanced navigation, model row)
- JVM: `app/src/test/java/com/galaxyrio/gracelauncher/assistant/ModelCatalogTest.kt`, `WebToolTextTest.kt`, `WikipediaTurnTest.kt`, `SourceFollowUpTest.kt`.
- AVD: new `~/.android/avd/grace-model-matrix-api36.{ini,avd/config.ini}`; read-only template `~/.android/avd/Notes_Automation_Phone_API36.avd/config.ini` (do not modify). System image `~/Library/Android/sdk/system-images/android-36/google_apis/arm64-v8a/`.
- Debug keystore: `~/.android/debug.keystore` (alias `AndroidDebugKey`, cert `f1849f41…`). Health token file: `~/.local/share/grace-launcher/health-mcp/valid.token` (never print it).
- `tasks.md`, `AGENTS.md` (current-state notes).

## Acceptance Criteria
1. AVD `grace-model-matrix-api36` exists. It's a google_apis API 36 arm64 image with 12 GB RAM, `disk.dataPartition.size=21474836480` and `hw.keyboard=yes`. It boots as emulator-5590 with a visible window, and `/data` is ~20G. 5558's AVD files and install are unchanged.
2. Debug app + androidTest APKs (debug cert `f1849f41…`, verified) are installed on 5590 via `install -r` / `install -t -r`. The Health token was provisioned via the Advanced field, and the tmp file is deleted. No other emulator was touched.
3. `ModelMatrixTest` exists:
   - it is skipped unless `modelMatrix=true`, and supports `models=`
   - it performs all 4 switches via UI taps in the stated order
   - it asserts exactly one model's files after each switch
   - it runs all 6 cases per model with the hard asserts in the table plus the universal leak/empty checks
4. A full matrix run was executed on 5590. The report JSON for every model and case is in `app/build/reports/model-matrix/<timestamp>/`, with a short `summary.md` (per model × case pass/fail, latency, fix cycles used).
5. Every fix has a JVM test that failed before the fix and passes after. Fixtures contain no real health data and no token.
6. Final per-model status is either PASS or FAILING (after ≤3 cycles) with evidence. FAILING models are listed in the summary and `tasks.md`.
7. `bash ./gradlew :app:testDebugUnitTest` and `:app:lintDebug` pass on the final tree.
8. End state: the debug build is installed on 5590 with Gemma4 selected, and the AVD is kept. No push. Changes are committed locally with the repo identity and no tool attribution. `tasks.md` and the `AGENTS.md` current state record:
   - the new AVD `grace-model-matrix-api36` on 5590 as the model-matrix target (debug cert)
   - that 5558 is unchanged on 1.2.11
   - the matrix results
9. No `.env` values, signing passwords, `hc_` tokens or health data are committed or printed.

## Tests to Add/Run
- **Add:** `app/src/androidTest/java/com/galaxyrio/gracelauncher/assistant/ModelMatrixTest.kt` (above).
- **Add, as needed:** JVM repro tests per fix, e.g. `QwenToolCallRecoveryTest`, plus `ModelText` cases from real Qwen/E4B output.
- **Run:**
  - `bash ./gradlew :app:testDebugUnitTest` (full suite, after each fix)
  - `bash ./gradlew :app:lintDebug` (final)
  - `bash ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`
  - full matrix via `adb -s emulator-5590 shell am instrument -w -e modelMatrix true -e class ...ModelMatrixTest ...`
  - per-model reruns with `-e models <enum>`
- Report actual pass/fail for each. No screenshot/visual QA; the user does visual checks.

## Scope Guardrails
- **Do not touch emulator-5558 at all**: no install, no restart, no AVD file edits. The earlier backup dirs under `<TMP>/avd-backup-5558-20261119/` and `<TMP>/avd-failed-resize-5558-20261119/` stay as they are. Never touch emulator-5560/5580/5582/5584 or their AVDs. Never uninstall or clear the launcher, `-wipe-data`, or use `connectedDebugAndroidTest`. Only the new AVD on 5590 may be created and booted.
- No Gradle signing config, no release build, no version bump, no push.
- No catalog changes. Do not rename `Gemma4` or its filename. No sampler/context/SDK changes, and no LiteRT upgrade.
- Don't touch the legacy `AssistantController.infer()` action path or the `!` Magic path.
- No production logging of raw prompts or model output. Evidence capture lives in test code only.
- Don't change the Health token, revoke tokens, or push to the HF Space or GitHub `health-dashboard`.
- Leave the pre-existing uncommitted `README.zh-CN.md` deletion, the handoff edits and `.claude/` alone. Stage only your own files.
- Gemma behaviour must not regress: the existing `WebToolTextTest` and Wikipedia recovery tests stay green.

## Open Questions & Risks
- A hand-written AVD config may not apply RAM or partition size, or may fail to boot. Verify, and report INCOMPLETE if hit; never fall back to another AVD.
- E4B CPU inference on the emulator may be very slow and could hit the ~10 min timeout. That counts as a hard failure → it gets the fix loop, but no sampler/context changes are allowed. It may end FAILING.
- "Everything hard" means live-data flakiness can cause false failures: Tavily results, Wikipedia, and Health data being ~2 days behind or Google reauth being flagged. Record the evidence; don't "fix" network flakiness in app code.
- Small models (Qwen2.5 1.5B) may simply not emit tool calls. Expect FAILING after 3 cycles to be a legitimate outcome.
- ~8.8 GB of downloads plus the Gemma4 re-download over emulator networking; long wall-clock.
- Host RAM: a 12 GB guest plus any other running emulators (5558, 5560) may pressure the Mac. Don't stop other emulators to make room; report if the boot fails.
- The shared Health token is used by 5558, Titan and now 5590. Just reading it is fine. Never print it.

## Implementation Task List
- [ ] Hand-create AVD `grace-model-matrix-api36` (google_apis API 36 arm64, 12 GB RAM, 20 GiB data, keyboard on); boot visible on port 5590; verify df/RAM/SDK/network.
- [ ] Inspect existing UI test tags/semantics for the Advanced picker/confirm, chat composer, chips, source cards and Health card; add minimal test tags only where missing.
- [ ] Write `ModelMatrixTest`: opt-in args, UI switch helper, one-model-on-disk assert, 6 cases, universal leak checks, JSON evidence capture, soft-collect-then-fail.
- [ ] Build debug + androidTest, verify the debug cert, `install -r` / `install -t -r` on 5590, set the launcher as HOME if needed, provision the Health token (push → Advanced field → delete tmp).
- [ ] Run the full matrix; pull the report into `app/build/reports/model-matrix/<ts>/`; write `summary.md`.
- [ ] For each failure: JVM repro from the captured output (with health data removed) → red → minimal app-side fix → full JVM suite green → rebuild/reinstall on 5590 → rerun the model (≤3 cycles).
- [ ] Final: full JVM suite + `lintDebug`; confirm Gemma4 is selected on 5590 with the debug build installed.
- [ ] Update `tasks.md` (results, FAILING models, evidence paths) and the `AGENTS.md` current state; commit your own files locally; no push.

---

# Follow-up 1 (2026-11-19): Qwen2.5 experimental label + engine-cache sweep on switch

Context: the matrix run `1795123418393` ended 23/24. Qwen2.5 is FAILING: it named the wrong 2024 Nobel Literature winner after 3 cycles. The run also showed that old models' LiteRT engine caches pile up in `noBackupFilesDir`. On 5590, after the 4 switches:

| Cache file | Size |
|---|---|
| Gemma4 | 788 MB |
| Gemma4E4B | 2.21 GB |
| Qwen2.5 | 1.55 GB |
| Qwen3 | 974 MB |

Only Gemma4's model file remains. The user approved both changes below. TDD: tests first.

## Decisions
- **Qwen2.5 stays selectable, labelled experimental.** Change only `assistant_model_note_qwen25` in `app/src/main/res/values/strings.xml` (currently `Small, multilingual`) to `Small, multilingual · experimental, may misstate facts`. There are no localized overrides; don't add any. Don't touch the catalog, the prompts or `ModelPrompts.kt`.
- **Engine-cache sweep on a confirmed switch, on all devices.** After the existing `sweepExcept(target)` model-file sweep, delete stale engine caches in `context.noBackupFilesDir` (the shared cache dir passed to LiteRT in `AssistantController.startEngine`, ~line 510). Rule:
  - Only **top-level regular files** (non-recursive; never directories).
  - The name ends with `.xnnpack_cache` or `_mldrift_weight_cache.bin` (the same suffixes `resolveEngineCacheDir` recognises, `AssistantController.kt` ~546; reuse those constants if they exist).
  - The name does **not** start with `"${target.name}-${target.filename}_"`. That is the target's own cache, observed format `<enum>-<filename>_<n>_<bytes>.xnnpack_cache`.
  - This covers other catalog models and legacy (e.g. FunctionGemma) caches. `Gemma4-gemma-4-E2B-it.litertlm_…` vs `Gemma4E4B-gemma-4-E4B-it.litertlm_…` must not collide; the full `<enum>-<filename>_` prefix guarantees this.
  - Never touch `assistant-models/`, `health-mcp/`, `private-apps.json` (+ AtomicFile companions), or any other file.
- **Placement:** put the pure selection helper next to `sweepTargets` in `assistant/ModelFiles.kt` (e.g. `internal fun staleEngineCaches(dir: File, target: AssistantModel): List<File>`). Call it in the same switch flow and under the same IO/mutex as `sweepExcept`, after the engine is closed.
- **Deletion failures:** a cache file that fails to delete must not fail or abort the switch. Caches are disposable; at most a best-effort, non-sensitive log without file contents.
- Don't change `resolveEngineCacheDir`'s emulator cleanup/low-disk policy.

## Acceptance Criteria (follow-up)
- F1. The picker option for Qwen2.5 shows the new note text. No other string changes.
- F2. After a confirmed switch, `noBackupFilesDir` top level has no `.xnnpack_cache` / `_mldrift_weight_cache.bin` files except ones starting with the target's `<enum>-<filename>_`. Directories and other files are untouched.
- F3. JVM tests were written first (RED shown), then GREEN. They cover:
  - target cache kept; other catalog models' caches selected; legacy cache selected
  - `Gemma4` vs `Gemma4E4B` prefix non-collision, both directions
  - directories (incl. a directory with a cache-like name), `health-mcp/`, `assistant-models/`, `private-apps.json` and unrelated files not selected
  - missing/unlistable dir → empty list
- F4. `ModelMatrixTest` asserts F2 after every switch. A filtered rerun on emulator-5590 with `-e models Qwen3_1_7B,Gemma4` passes the switch and cache assertions. That rerun's prompt cases are reported as-is; Qwen2.5/E4B are not rerun. It ends with Gemma4 selected, only Gemma4's model + `.sha256` in `assistant-models/`, and at most Gemma4's own cache at top level. Report `/data` free space before and after.
- F5. Full `bash ./gradlew :app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug :app:assembleDebugAndroidTest` pass. Use `ANDROID_USER_HOME=/Users/susuresh/.android` so the debug cert is `f1849f41…`, and verify it before `install -r` / `install -t -r` on 5590.
- F6. `tasks.md` + the `AGENTS.md` model-matrix/current-state notes are updated:
  - Qwen2.5 labelled experimental
  - cache sweep added, and the "old-model engine caches not swept" risk removed/marked fixed
  - the rerun evidence path

  Commit locally (repo identity, no tool attribution), stage only your own files, no push.

## Guardrails (follow-up)
- Same as the main plan's Scope Guardrails. In particular:
  - 5590 only; never touch 5558/5560/others
  - never `connectedDebugAndroidTest`; never uninstall/clear
  - no catalog/prompt/sampler/context/SDK changes; no release build/version bump
  - never print the Health token (already provisioned on 5590; don't re-provision unless the Health chip is missing)
- Don't commit the pre-existing uncommitted `tasks.md` hunk from before this task unless it's already part of your edits; leave `README.zh-CN.md`, the handoffs and `.claude/` alone.

## Task List (follow-up)
- [ ] RED: JVM tests for `staleEngineCaches` in `ModelCatalogTest` or a new `EngineCacheSweepTest`.
- [ ] GREEN: helper in `ModelFiles.kt`, plus a call in the confirmed-switch flow after `sweepExcept`, best-effort deletes.
- [ ] Update the `assistant_model_note_qwen25` string.
- [ ] Add the post-switch cache assertion to `ModelMatrixTest`.
- [ ] Full JVM suite, lint, debug builds; verify cert; install on 5590.
- [ ] Filtered rerun `models=Qwen3_1_7B,Gemma4`; capture the report under `app/build/reports/model-matrix/`; note df before/after.
- [ ] Update `tasks.md`/`AGENTS.md`; commit locally.

---

# Follow-up 2 (2026-11-19): Signed release APK from `f65643b`, still 1.2.11 / 19

## Decisions
- **No version bump.** The user explicitly chose to keep `versionName 1.2.11` / `versionCode 19` (`app/build.gradle.kts` ~24–25). This new APK supersedes the earlier 1.2.11 artifact (`458eb851…`, built from `e80e9ef`), and both share the same version.
- **Source = HEAD `f65643b`.** Code is already committed. Don't add other uncommitted files: `README.zh-CN.md` deletion, handoffs, plans and `.claude/` stay as they are. The only uncommitted `tasks.md` hunk that predates this (a 5558 install line) also stays uncommitted unless your docs edit touches that file. In that case stage only your own hunk (`git add -p`).
- **No install** on any device or emulator. No push.

## Steps
1. Make sure the worktree's tracked source equals `f65643b` for `app/` (no uncommitted `app/` changes). Use JDK 21.
2. `bash ./gradlew :app:assembleRelease` (unsigned, R8).
3. 16KB alignment: `zipalign -P 16 -f 4 app-release-unsigned.apk aligned.apk`, using build-tools 36.1.0 (or the repo's established path).
4. Sign:
   ```
   apksigner sign --ks ~/.local/share/grace-launcher/signing/grace-launcher-current.p12 \
     --ks-key-alias grace-launcher \
     --ks-pass file:$HOME/.local/share/grace-launcher/signing/grace-launcher-current.password \
     --out GraceLauncher-1.2.11-signed.apk aligned.apk
   ```
   Do **not** pass `--key-pass`, and never print passwords.
5. Verify, saving logs next to the APK:
   - `apksigner verify -v --print-certs`: v2/v3 OK, cert SHA-256 `7c928de3824f4c6d91056d5e5615c5cb5414fece82643c0930c0ca600f583a0b`
   - `zipalign -c -P 16 -v 4`: OK
   - `aapt2 dump badging`: package `com.galaxyrio.gracelauncher`, 1.2.11 / 19, not debuggable
   - R8 keep check: DEX still contains `com.google.ai.edge.litertlm.*` callback classes, `MobileActionTools`, `WebSearchTools` (with `search_web`) and `WikipediaTools` tool methods, per `app/proguard-rules.pro`. Use `apkanalyzer dex packages` or `dexdump` with grep, whichever exists.
   - SHA-256 and byte size.
6. Output dir: `app/build/reports/model-matrix/release-1.2.11-f65643b/`, holding the signed APK plus `verify.txt`, `zipalign.txt`, `badging.txt`, `dex-keep.txt` and `sha256.txt`.
7. Docs: add the artifact evidence (source commit, size, SHA-256, cert, checks) to `tasks.md` and the `AGENTS.md` current-state note. State that it supersedes `458eb851…` at the same 1.2.11/19, and that it is **not installed on any device**; signed-release runtime assistant/Web/Wikipedia/Health smoke is still pending (user). Commit the docs locally (repo identity, no tool attribution), no push.

## Acceptance Criteria (follow-up 2)
- R1. The signed APK exists in the output dir. It verifies with cert `7c928de3…`, P16 alignment OK, 1.2.11/19, non-debuggable.
- R2. The DEX keep check confirms the LiteRT/tool classes and methods are present. If any is missing, stop and report; don't edit ProGuard rules without approval.
- R3. No device was touched, no version change, no push, no secrets in logs or commits.
- R4. Docs are committed with the evidence.

## Guardrails (follow-up 2)
- No source or ProGuard changes. If the release build fails, stop and report.
- Don't touch any emulator or device.
