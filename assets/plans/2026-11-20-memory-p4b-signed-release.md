# Memory Phase 4b — Signed release 1.2.12 R8 build + 5558 release smoke

## Overview
Build the signed release of Grace Launcher **1.2.12 / versionCode 20**, which includes the new assistant memory feature, and verify the artifact. Then install it on emulator **5558** (release cert) and run a functional release smoke. This matters because Phase 3 moved the Web/Wikipedia tool loop into `assistant/WebTurn.kt`, and R8 has previously broken LiteRT callbacks and the `WebSearchTools` tool schema (see `AGENTS.md`). Gate D is approved for 5558 under the rules below. Prerequisite: Phase 4a is done (version already bumped; see `assets/plans/2026-11-20-memory-p4a-device-quality.md`). Read `AGENTS.md` (signing, R8 keep rules, 5558 rules) and `tasks.md` first.

## Business & UX Decisions
- The version is bumped so this artifact doesn't share 1.2.11's identity with the prior `939d5c56…` artifact.
- 5558 gets the release through update-install only. Its data, models and Health token must be preserved.
- Live writes are allowed for exactly one memory test entry, which is purged afterwards.

## Technical Decisions
- **Build:** JDK 21, `bash ./gradlew :app:assembleRelease`. The output is unsigned and there's no release signingConfig. Don't add one.
  - Record the source commit (`git rev-parse HEAD`) and note that the working tree is dirty with uncommitted memory changes. The user has not asked for a commit; do NOT commit.
- **Align + sign:** Build Tools 36.1.0.
  - Align with `zipalign -P 16 -f 4`.
  - Sign with `apksigner sign --ks ~/.local/share/grace-launcher/signing/grace-launcher-current.p12 --ks-key-alias grace-launcher --ks-pass file:~/.local/share/grace-launcher/signing/grace-launcher-current.password`. Omit `--key-pass`, because store and key passwords are equal and reusing the file causes EOF.
  - Never print or log passwords.
  - Never use the older `edf4f9…` key.
- **Verify** and save logs next to the APK in `app/build/reports/memory/release-1.2.12/`:
  - `apksigner verify -v --print-certs` (v2/v3, `--min-sdk-version 24` as before): cert SHA-256 must be `7c928de3824f4c6d91056d5e5615c5cb5414fece82643c0930c0ca600f583a0b`.
  - `zipalign -c -P 16 -v 4`.
  - `aapt2 dump badging`: package, 1.2.12/20, not debuggable.
  - SHA-256 and size.
  - DEX keep check (e.g. `apkanalyzer dex packages` or a dexdump grep):
    - LiteRT JNI callbacks / `onMessage`
    - `MobileActionTools`
    - `WebSearchTools.search_web`
    - Wikipedia tool methods
    - the memory classes the app reaches by reflection, if any
  - Also check that `proguard-rules.pro` still covers the tool classes after the `WebTurn.kt` extraction. If a keep rule is missing, STOP and report; do not edit rules without approval.
- **5558 install:** `adb -s emulator-5558 install -r <signed apk>` only.
  - First check the installed package's cert matches `7c928de3…` (`dumpsys package` / pulled base.apk). On mismatch, STOP.
  - Never uninstall, `pm clear`, or wipe.
  - If 5558 isn't running, start AVD `Notes_Automation_Phone_API36` with a visible window and no wipe.
  - Verify the installed base.apk SHA-256 matches the signed APK.
- **Release smoke** via adb UI automation (`input`, `uiautomator dump`, `logcat`). 5558 has Gemma4 E2B and the Health token provisioned. Run each of these:
  1. Launch; HOME works.
  2. Plain chat (remove the Web chip or use a plain prompt): get a non-empty reply.
  3. Web search with a current-events question: get a reply with sources, and logcat shows no plain-text `Search:` fallback.
  4. Wikipedia chip with a factual question: get a cited reply.
  5. Health chip, if shown: get the Live/Cached card + reply.
  6. Memory:
     - `/remember grace memory release smoke <short-uuid> is a test fact` → save card.
     - Sync shows "synced at …".
     - Desktop bridge `memory_recall` finds it (use `tools/memory-bridge` via `uv run` with `.env` + `~/.local/share/grace-launcher/health-mcp/valid.token` loaded inside a scratch script under `/private/var/folders/d4/g73rwlh56w3b712vq80zl2y40000gn/T/opencode`).
     - `/recall` on the device lists it.
     - `/purge` with second confirm → sync → bridge `memory_changes` shows the tombstone.
  7. A plain-chat question answerable from a memory saved during the smoke, asked before the purge, is answered using it. Record the reply.
  8. `logcat` throughout must show no `FATAL EXCEPTION`, `NoSuchMethodError`, `SIGABRT` or `ClassNotFoundException` for the app process.
  - Record status/error codes and reply keyword checks only. No secrets, and no Supabase host in files.
- Each emulator/infra failure gets at most **2 bounded repairs**, then STOP. If an app bug shows up, STOP and report it with evidence; don't fix it.

## Relevant Files
- `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/java/com/galaxyrio/gracelauncher/assistant/WebTurn.kt`
- Signing files under `~/.local/share/grace-launcher/signing/` (read-only use)
- `tools/memory-bridge/`, `tasks.md`, `AGENTS.md`

## Acceptance Criteria
- The signed APK in `app/build/reports/memory/release-1.2.12/` meets all of these:
  - cert `7c928de3…`;
  - v2/v3 verified;
  - P16 alignment OK;
  - 1.2.12/20, not debuggable;
  - DEX keeps present;
  - SHA-256 recorded.
- Its logs are saved there: `verify.txt`, `verify-v2-v3.txt`, `zipalign.txt`, `badging.txt`, `dex-keep.txt`, `sha256.txt`.
- On 5558, the installed base.apk SHA-256 equals the signed APK. Data and models are preserved, and the Health token is still present.
- Release smoke steps 1–8 are each recorded PASS/FAIL/SKIP with a reason. The memory test entry ends purged, with only its tombstone left.
- `tasks.md` gets the release artifact evidence (source commit + dirty-tree note, SHA-256, cert, smoke results) and the open items: Titan install, native ELF 16KB unverified, visual QA by the user.
- `AGENTS.md` "Current state" gets a concise dated bullet: the 1.2.12/20 signed APK path + SHA-256, 5558 now on 1.2.12, and the smoke result.
- Summary at `app/build/reports/memory/release-1.2.12/summary.md`.

## Tests to Add/Run
- No new tests. Run the build, sign and verify commands above, and the 5558 smoke.
- Report the exact commands (without secrets), results and evidence paths.
- No visual QA claims.

## Scope Guardrails
- Only emulator-5558 for the device steps. Do not touch 5590 (Phase 4a leaves it on E4B), 5580, 5582 or the Titan.
- Never uninstall, `pm clear`, wipe or recreate. Never regenerate or replace keys. Never add a release signingConfig. Never print passwords. Never commit or push.
- No production code or keep-rule changes; STOP and report if they're needed.
- Never use the service-role key, revoke the token, touch the dashboard repos or `user_memories`, or log `.env` values or the Supabase host.
- No delegation.
- On ambiguity, STOP and report.

## Open Questions & Risks
- The working tree is uncommitted, so the artifact's source isn't a clean commit. This is recorded as a caveat.
- adb UI automation brittleness. Bounded repairs.
- Model inference on 5558 is CPU-only (emulator), so it's slow. Allow generous waits.

## Implementation Task List
- [ ] Confirm the version is 1.2.12/20 and record the HEAD + dirty state.
- [ ] assembleRelease (R8).
- [ ] zipalign -P 16 + apksigner sign; verify cert, alignment, badging, DEX keeps, SHA-256; save the logs.
- [ ] Check 5558's running state and cert; install -r; verify the installed SHA-256.
- [ ] Release smoke steps 1–8; purge the memory test entry.
- [ ] Write the summary; update tasks.md + AGENTS.md current state; report.
