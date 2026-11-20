# Configurable assistant model + Advanced reorders + Home sound-mode indicator (1.2.11 / 19)

## Overview
Assistant is hard-wired to Gemma 4 E2B. Make the model user-selectable (Settings → Advanced → Offline assistant) from a vetted shortlist of LiteRT-LM 0.18-compatible `.litertlm` bundles, strictly one model on disk at a time. Plus three unrelated small tasks: reorder the Keyboard shortcuts page, reorder the Offline assistant page (controls first, drop stale `!` hint), and add a Home sound-mode (ringer) indicator next to the weather. Work spec-driven + TDD: write failing JVM tests first for every non-trivial logic unit, then implement.

## Business & UX Decisions
1. **Shortlist (user-approved, all ungated Apache-2.0, native LiteRT-LM tool calling, ≤4k context fits our 4,096 engine):**
   - Gemma 4 E2B (default, existing) — balanced.
   - Gemma 4 E4B — smarter, slower, bigger.
   - Qwen3 1.7B INT4 (`dynamic_wi4b32` file — the one tested on SDK 0.18 GPU; the other `Qwen3_1.7B.litertlm` needs 0.19 for GPU, do NOT use it) — small/fast, multilingual.
   - Qwen2.5 1.5B INT8 — small, multilingual.
   Rejected: SmolLM3 (tools unverified), gated Gemma 3n / Gemma3 1B (would need HF token UI), Phi-4-mini/DeepSeek/Llama (heavy, unverified, or base models), Qwen3-4B-Instruct-2507 (broken template on ≥0.18).
2. **Selected model drives all assistant paths:** ordinary chat, Web search, Wikipedia, Health, saved-source follow-ups. Reason: single model on disk; LiteRT-LM parses tool calls per model natively.
3. **`!` actions untouched:** verified that model-driven `!` dispatch was already replaced by deterministic idle-Home `!` Magic alarm/timer (commit `597a2a5`, `tasks.md:233–236`). Legacy `AssistantController.infer()` action code remains only for instrumentation tests — do not touch, do not expose. (`AGENTS.md` text describing active `!command` dispatch is stale; refresh it.)
4. **Strict mutual exclusivity, delete-then-download:** on switch, delete old first, then download new. Peak disk = one model. If the new download fails/cancels, no model is installed until retry (`.part` kept for resume). Reason: matches user's wording, minimal disk.
5. **Picker UX:** a row in Advanced showing current model name; tap → radio-list dialog (label, size in GB, 1-line note). Choosing a different model → confirm dialog "Delete <old> and download <new> (X GB)?" (if nothing installed: "Download <new> (X GB)?"). On confirm the download starts immediately using the existing progress/Cancel UI. Choosing the same model = dismiss, no-op.
6. **Conflicts:** picker disabled while any download is running (user must Cancel first). Confirmed switch: stop streaming reply, close engine, clear RAM chat transcript/context (same effect as chat clear), then delete + download. Reason: avoid mixing templates/context across models.
7. **Leftovers:** remove the unused FunctionGemma entry from the catalog. On every confirmed switch, delete ALL files in `noBackupFilesDir/assistant-models/` except those of the new selection (model, `.sha256`, `.part`). No startup sweep (existing FG files go on first switch).
8. **Free-space guard (stated in greenlight):** before deleting, if `usableSpace + bytes of files to be deleted < new.bytes + 200 MB margin` → show an error status and change nothing (selection, files untouched).
9. **Delete button** keeps current behavior (deletes selected model's files; selection unchanged; Download re-downloads selected).
10. **Offline assistant page order (user-approved):** heading → Enable toggle → Show chat button toggle → model picker row → size / "Verified and ready" → Download/Delete → progress/Cancel → status line → divider → Health block (unchanged internally, incl. its own hint) → divider → info texts: privacy, show-button hint, chat hint, direct-actions note.
11. **Stale `!` text:** `assistant_show_home_button_hint` says "use Space or ! …" — no such behavior exists. Change to "When hidden, use Space on the Home keyboard to open chat. The assistant must remain enabled." (English only exists; check locales, update any override).
12. **Keyboard shortcuts page order:** "Messaging preferences" button first, then "Keyboard commands" heading, then help text.
13. **Sound-mode indicator:** icon only, from `AudioManager.ringerMode`; Normal → nothing shown; Vibrate → vibration icon; Silent → muted-bell icon. Live updates via `AudioManager.RINGER_MODE_CHANGED_ACTION` broadcast (no permission). Placed right after weather temperature (or after battery % when weather hidden; alone if both hidden). Same tint/size (1.15em)/shadow as weather icon. Not tappable. Has content description. No settings toggle, no new preference. DND not reflected.
14. **Verification scope:** JVM tests + lint + assembleDebug + signed release APK. No emulator/device runs (emulator-5584 gone); user does device QA on Titan/5558. No live multi-GB downloads.
15. **Commit** source (no push) before building the signed APK so evidence records the commit.

## Technical Decisions

### Catalog (`assistant/ModelFiles.kt`)
Extend enum `AssistantModel` (fields today: `label, repository, revision, filename, bytes, sha256, tokenRequired`) with `extraContext: Map<String, Any>` (default empty) and a note string resource id (or plain note — prefer a `@StringRes` in `values/strings.xml`). Keep enum constant name **`Gemma4`** unchanged (storage filename is `<enum name>-<filename>`; renaming forces re-download). Remove `FunctionGemma`.

| enum | label | repository | filename | bytes | sha256 |
|---|---|---|---|---:|---|
| `Gemma4` | Gemma 4 E2B | `litert-community/gemma-4-E2B-it-litert-lm` @ `b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1` (existing) | `gemma-4-E2B-it.litertlm` | 2588147712 | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| `Gemma4E4B` | Gemma 4 E4B | `litert-community/gemma-4-E4B-it-litert-lm` | `gemma-4-E4B-it.litertlm` | 3659530240 | `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0` |
| `Qwen3_1_7B` | Qwen3 1.7B | `litert-community/Qwen3-1.7B` | `Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm` | 977184032 | `2a72330b504a6596d53a2c00923c57173b10f1b83e6a541213ffec08d1fc5de8` |
| `Qwen2_5_1_5B` | Qwen2.5 1.5B | `litert-community/Qwen2.5-1.5B-Instruct` | `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm` | 1597922856 | `c4a9b7289365ab80fe599b844b77ad253eb3c53fb0e5d963d699146a6f01255e` |

All `tokenRequired = false`. Qwen3: `extraContext = mapOf("enable_thinking" to false)`.
**Revisions:** resolve the current commit SHA for each new repo via `https://huggingface.co/api/models/<repo>` (`sha`) and confirm via `https://huggingface.co/api/models/<repo>/revision/<sha>?blobs=true` that the sibling's `lfs.sha256` and `size` equal the table values. If mismatch, stop and report (don't guess). Use `ctx_execute` fetch, not curl.
Notes (English): E2B "Default · balanced"; E4B "Smarter, slower"; Qwen3 "Small and fast"; Qwen2.5 "Small, multilingual".

### Selection persistence (`assistant/AssistantController.kt`)
- Replace `val selected = AssistantModel.Gemma4` with observable state (Compose `mutableStateOf`, like `enabled`/`show_home_button`) backed by SharedPreferences `"offline_assistant"` key `"model"` (enum name). Pure helper, unit-tested:
  ```kotlin
  fun parseSelectedModel(stored: String?): AssistantModel =
      AssistantModel.entries.firstOrNull { it.name == stored } ?: AssistantModel.Gemma4
  ```
  Stop overwriting the pref with Gemma4 at init.
- `withEngine`: replace `require(model == AssistantModel.Gemma4)` with "model == selected && files.ready(model)". Engine reuse must be keyed by model (close & recreate if selected changed). Keep backend policy (physical GPU + one-shot CPU fallback; emulator CPU), `resolveEngineCacheDir`, 4096 tokens, camelCase→snake_case flag disabled, samplers.
- Pass `model.extraContext` into every `ConversationConfig(... extraContext = ...)` (chat, Web/Wikipedia, source follow-ups, Health via chat). API: `ConversationConfig(extraContext = mapOf("enable_thinking" to false))` (LiteRT-LM 0.18 `Config.kt:240`). Keep `channels` default (null → metadata channels, so thoughts go to `channels["thought"]`, never displayed).
- Download now uses `selected` (remove the "reject any other model" check, replace with "must be selected").
- Legacy `infer(model, request)` actions: leave as-is semantically; it will run only if `model` is ready (tests pass `Gemma4`).

### Switch orchestration
Add `AssistantController.switchModel(target: AssistantModel, scope)`:
1. no-op if `target == selected` or a download is running.
2. free-space check (decision 8) — compute deletable bytes = all files in models dir not belonging to `target`.
3. cancel streaming reply, clear chat session (reuse existing chat-clear path in `AssistantChatState`/controller), close/unload engine.
4. persist `model = target.name`, update state.
5. `files.sweepExcept(target)` — deletes every file in `assistant-models/` whose name doesn't start with `"${target.name}-${target.filename}"` (i.e. keep exact model file, `.sha256`, `.part`).
6. start `download(target)` via existing path.
Put pure decision logic (`sweep` file selection, space check) in small testable functions in `ModelFiles.kt` taking a `File` dir / numbers.

### Output guard (chat + Health)
One shared function (e.g. in `WebSearch.kt`'s `WebToolText` or a new tiny `ModelText.kt`):
```kotlin
fun cleanModelText(raw: String): String? {
    val t = raw.replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("(?s)^\\s*<think>.*$"), "").trim()
    return if (t.contains("<tool_call>") || t.contains("</tool_call>")) null else t // null = fail closed
}
```
Apply in `inferChat` result (`AssistantController.kt ~281–298`, also used by Health) — `null` → the existing generic failure handling for a bad reply. Also apply the think-strip before the existing Web/Wikipedia `suspicious()` check (`~404–427`). Gemma text-envelope recovery (`WebSearch.kt:36–55`, `WikipediaSearch.kt:90–105`) stays Gemma-only; Qwen relies on SDK-structured `toolCalls`. Streaming: if partial text is shown while streaming, hide content inside an open `<think>` (strip from `<think>` to end when no closing tag) — keep simple.

### Settings UI (`assistant/AssistantUi.kt` `AssistantSettings` ~340–376)
Reorder per decision 10. Model row: clickable row/button `testTag("assistant_model_picker")` showing `selected.label`; disabled while busy. Dialog: Material3 `AlertDialog` with radio rows `testTag("assistant_model_option_<enum name>")`, each "Label · X.X GB · note". Confirm dialog with confirm/cancel buttons. Size format: GB with one decimal (`bytes / 1e9`). Reuse existing theme components/colors/fonts used in this file. New strings in `values/strings.xml` assistant section (model picker title, confirm-switch text with placeholders, confirm-download text, not-enough-space error, notes, switch/cancel button labels). English only; other locales fall back.

### Keyboard shortcuts (`magic/MagicCommandWindow.kt:645–647`)
Move the `magic_settings` button above `magic_title` heading; help text after heading. Nothing else.

### Sound-mode indicator (`ui/home/HomeScreen.kt` ~441–455, `ui/weather/WeatherComponents.kt` pattern)
- Pure mapping (unit test): `fun ringerIcon(mode: Int): Int? = when (mode) { AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ms_vibration; AudioManager.RINGER_MODE_SILENT -> R.drawable.ms_notifications_off; else -> null }`.
- Composable state: `produceState`/`DisposableEffect` registering a `BroadcastReceiver` for `AudioManager.RINGER_MODE_CHANGED_ACTION` via `ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)` (protected system broadcasts are still delivered to NOT_EXPORTED receivers), initial value from `AudioManager.ringerMode`; unregister on dispose.
- Add vector drawables `ms_vibration.xml`, `ms_notifications_off.xml` from Material Symbols Outlined (same provenance comment style as `drawable/ms_sunny.xml:2`). Render `Icon` with same tint (`appearance.text`), 1.15em-equivalent size, shadow handling consistent with weather; baseline-aligned in the status row; spacer like the existing 10dp between battery and weather. Content descriptions: new strings "Vibrate mode" / "Silent mode".
- Row must still render when battery/weather are hidden but ringer is non-normal; and must not alter the existing empty-agenda/battery/weather gating logic.

### Version / docs
`versionName 1.2.11`, `versionCode 19` (`app/build.gradle.kts`). Update `tasks.md` (implemented vs verified, evidence) and `AGENTS.md` (current state, catalog of 4 models, selection behavior, remove stale active-`!command` dispatch claims, keep all other guidance).

## Relevant Files
- `app/src/main/java/com/galaxyrio/gracelauncher/assistant/ModelFiles.kt` — enum catalog (22–40), URL (76), storage naming (44–52), download/resume (57–160), delete (186–192).
- `assistant/AssistantController.kt` — `selected` (27), prefs (24–27, 52–62), download (65–78), delete (80–87), `withEngine` (224–245), chat (280–298), Web/Wiki (377–427), EngineConfig/cache (486–536), prompts (650–657).
- `assistant/AssistantUi.kt` — `AssistantSettings` (341–376), Health block (253–289).
- `assistant/AssistantChatState.kt` — chat clear/session.
- `assistant/WebSearch.kt` (36–55 recovery, 38–43 `suspicious`), `WikipediaSearch.kt` (90–105), `SourceReference.kt` (113), `HealthTurn.kt`.
- `magic/MagicCommandWindow.kt` (645–647).
- `ui/home/HomeScreen.kt` (243–245, 351, 370–385, 441–501), `ui/weather/WeatherComponents.kt` (64–87), `res/drawable/ms_*.xml`.
- `app/src/main/res/values/strings.xml` (523–582 assistant, 604–626 magic).
- Tests: `app/src/test/java/com/galaxyrio/gracelauncher/assistant/{ModelFilesTest,EngineBackendTest,EngineCacheDirTest}.kt`; androidTest `assistant/{ModelDownloadTest,AssistantOfflineTest,AssistantUiTest}.kt` (AssistantUiTest:226 references `assistant_model_FunctionGemma`).
- `app/build.gradle.kts`, `tasks.md`, `AGENTS.md`.

## Acceptance Criteria
1. Catalog has exactly 4 entries (`Gemma4`, `Gemma4E4B`, `Qwen3_1_7B`, `Qwen2_5_1_5B`) with pinned 40-hex revisions verified against HF API LFS sha256/size; FunctionGemma gone from main code.
2. Existing installed Gemma 4 E2B file (`Gemma4-gemma-4-E2B-it.litertlm` + `.sha256`) remains valid; default selection is Gemma 4 E2B; unknown/legacy stored values fall back to it.
3. Advanced → Offline assistant shows a model picker row; dialog lists 4 models with size + note; confirm dialog before switching; confirmed switch clears chat, closes engine, persists selection, deletes all other files in `assistant-models/`, and auto-starts download of the new model with existing progress/Cancel.
4. Picker disabled while downloading; selecting the current model is a no-op; insufficient space → error, nothing deleted/changed.
5. Chat, Web, Wikipedia, Health, source follow-ups run on the selected model; Qwen3 conversations pass `enable_thinking=false`; `<think>` blocks never shown; raw `<tool_call>` text in chat/Health fails closed.
6. Engine is recreated when the selected model changes; GPU/CPU policy, cache dir policy, 4096 context, samplers unchanged.
7. Offline assistant page order matches decision 10; show-button hint no longer mentions `!`.
8. Keyboard shortcuts page: Messaging preferences button above "Keyboard commands" heading.
9. Home shows vibration icon in Vibrate, muted-bell icon in Silent, nothing in Normal, updating live without app restart; placed after weather (or battery); has content description; no new permission, preference or DB migration.
10. Version 1.2.11/19; JVM tests, lint, assembleDebug pass; signed, 16KB-aligned release APK built and verified (cert `7c928de3…`, package/version, SHA-256) and recorded in `tasks.md` with source commit; `AGENTS.md` refreshed.
11. No `.env`/token/signing secrets logged or committed.

## Tests to Add/Run
TDD: write each test first, see it fail, then implement.
- `ModelCatalogTest` (JVM): 4 entries, unique names/filenames, `https://huggingface.co/<repo>/resolve/<40-hex>/<filename>` URLs, 64-hex sha256, bytes > 0, none tokenRequired, Qwen3 extraContext `enable_thinking=false`, others empty, `Gemma4` name+filename unchanged.
- `parseSelectedModel` tests: null, `"Gemma4"`, `"Gemma4E4B"`, `"FunctionGemma"` (→ Gemma4), garbage.
- Sweep test (temp dir): files for several models + `.part`/`.sha256` + FunctionGemma leftover → only target's three file kinds remain.
- Space-check test: boundary cases incl. deletable bytes counted, margin.
- `cleanModelText` tests: plain text unchanged; closed `<think>` stripped; unclosed leading `<think>` stripped; `<tool_call>` → null; empty think block + answer → answer.
- `ringerIcon` mapping test: normal → null, vibrate → vibration drawable, silent → notifications_off drawable.
- Update `ModelFilesTest`/other JVM tests broken by catalog change. Update androidTest sources that reference `FunctionGemma` (`AssistantUiTest:226` — assert none of removed model tags; keep compiling) — compile only (`:app:compileDebugAndroidTestKotlin` or `assembleDebugAndroidTest`), do not run on device.
- Run: `bash ./gradlew :app:testDebugUnitTest`, `bash ./gradlew :app:lintDebug`, `bash ./gradlew :app:assembleDebug`, `bash ./gradlew :app:assembleDebugAndroidTest`, `python3 third_party/fonts/verify_fonts.py` (sanity, fonts untouched), then `:app:assembleRelease` + zipalign `-P 16` + apksigner sign (`--ks-pass file:...`, no `--key-pass`) + `apksigner verify --print-certs` + aapt2 badging (package/version) + sha256. Report pass/fail for each.

## Scope Guardrails
- Don't touch legacy `infer()` action code/MobileActionTools/AlarmSkillTools, Magic `!` alarm/timer logic, `@ # + ?` routes.
- Don't change backend selection, cache-dir cleanup, sampler settings, context size, prompts' content (beyond none), Health MCP client/vault/token handling, Tavily/Wikipedia request logic.
- Don't rename the `Gemma4` enum constant or storage filename scheme; don't change download integrity/resume/redirect/credential logic.
- No HF token UI, no gated models, no DB migration, no new dependencies, no new permissions, no new settings toggles.
- No emulator/device installs or runs; no live model downloads; never uninstall/clear any app.
- Commit locally (work profile `suhas-suresh_zse`/`suhas.suresh@zalando.de`, clean message, no tool attribution); never push. Never commit/log `.env` or signing secrets.
- Home status row: don't regress battery/weather/agenda gating or the 220ms/1200ms pill animation.

## Open Questions & Risks
- Qwen/E4B quality (German, tool-call reliability for Web/Wikipedia) unverified on device; user QA.
- Qwen templates: documented empty-preface bug — avoid `prefillPrefaceOnInit=true` with tools but no initial messages; check current ConversationConfig usage and don't enable it.
- E4B on Titan GPU memory/perf unverified; CPU fallback exists.
- Delete-then-download leaves no model if download fails — accepted.
- Engine-cache files in `noBackupFilesDir` for the old model (GPU caches) may linger; `resolveEngineCacheDir` emulator cleanup handles some; physical caches not swept (out of scope; mention in tasks.md if observed).
- RINGER_MODE_CHANGED receiver flag semantics on API 34+: verify it receives updates (user QA).
- HF repo revisions may have moved; if LFS hash/size mismatch the table, stop and report.

## Implementation Task List
- [ ] Read `tasks.md` Pending + this plan; confirm repo state clean.
- [ ] Resolve + verify pinned revisions for 3 new repos via HF API.
- [ ] TDD catalog: write `ModelCatalogTest`, update enum (remove FG, add 3, extraContext, notes).
- [ ] TDD `parseSelectedModel`; observable `selected` in controller; stop overwriting pref.
- [ ] TDD sweep + space-check helpers in `ModelFiles.kt`.
- [ ] Generalize `withEngine` (model-keyed engine reuse) + pass `extraContext` to all ConversationConfigs; download uses selected.
- [ ] `switchModel` orchestration (cancel reply, clear chat, close engine, persist, sweep, download).
- [ ] TDD `cleanModelText`; wire into chat/Health and before Web/Wiki suspicious check.
- [ ] Settings UI: reorder Offline assistant page, picker row + radio dialog + confirm dialog, strings, fix `!` hint.
- [ ] Reorder Keyboard shortcuts page.
- [ ] TDD `ringerIcon`; add drawables, receiver state, render in Home status row with content descriptions.
- [ ] Fix androidTest compile references (FunctionGemma tag).
- [ ] Bump 1.2.11/19; run unit tests, lint, assembleDebug, assembleDebugAndroidTest, font verifier.
- [ ] Update `tasks.md` + `AGENTS.md`; commit locally (no push).
- [ ] Build release, 16KB-align, sign, verify cert/package/version/alignment/SHA-256; record evidence + commit in `tasks.md` (docs-only follow-up commit OK).
- [ ] Report each check's pass/fail and anything unverified.
