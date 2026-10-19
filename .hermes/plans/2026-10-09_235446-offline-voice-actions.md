# Offline Mobile Action Voice Assistant Implementation Plan

## PLAN

**Goal:** Add hold-to-talk mobile actions to Grace Launcher, with both requested models selectable and all recognition/inference performed locally.

**Architecture:** One LiteRT-LM engine, one active model, one allowlisted Android action dispatcher. Native on-device speech recognition supplies text to either model. Downloading weights is the only assistant network operation; no hosted inference, cloud speech fallback, telemetry, or conversation persistence.

**Tech stack:** Existing Kotlin/Compose/ViewModel/SharedPreferences, Android SpeechRecognizer and intents, Java HTTPS/file APIs, and `com.google.ai.edge.litertlm:litertlm-android:0.18.0`. No Gallery application dependency, DI layer, model-provider abstraction, or chat framework.

### Starting point

- Target device: **Unihertz Titan 2 Elite, Android 16, Dimensity 7400** (user-specified). Manufacturer lists 12 GB RAM / 256 GB storage; MediaTek specifies Mali-G615 MC2. Android version satisfies the on-device recognizer API requirement, not recognition service/language availability. CPU first; GPU requires actual-device validation; no assumed Dimensity 7400 NPU support.
- Project home: `/home/ss/projects/GraceLauncher/`. Current target-device feasibility: `.hermes/plans/offline-mobile-assistant-plan.md`. User explicitly requires code and plans outside life-book. Documentation only; implementation has not started.

- Repository: `/home/ss/projects/GraceLauncher`, fork `findEthics/GraceLauncher`.
- Checked out local `feat/offline-voice-actions` from `origin/refinements`, both at `31efed13ccca9397317f8ec1e7cf4bd2e5aa6a62`. Branch is not pushed. No implementation changes have been made.
- Follow repository `AGENTS.md`. Existing Home gesture handling, managed profiles, private space, settings persistence, and launcher startup must remain intact.
- Advanced currently contains a placeholder in `ui/settings/LauncherSettingsScreen.kt`.
- Existing Home edit-mode Done button in `ui/LauncherScreen.kt` supplies circular styling and bottom inset placement. Add a separate assistant button; do not repurpose edit-mode controls or restore obsolete Grace-button customization.
- Keep launcher minimum API 28. Voice feature requires native on-device recognition API 31+ and installed language support; unsupported devices must fail closed rather than use network recognition.

### 1. Prove the shortest offline path on the target phone

Before UI implementation, exercise LiteRT-LM with each exact model file on the physical target device. Verify local speech availability and the intended language in airplane mode. Validate the Gallery Mobile Actions tool schema on both models; FunctionGemma is specifically trained for that schema, whereas Gemma 4 may require its own prompt template.

- FunctionGemma: `mobile_actions_q8_ekv1024.litertlm`, CPU backend.
- Gemma 4: `gemma-4-E2B-it.litertlm`, generic CPU-capable artifact; evaluate GPU only if necessary and actually supported. Do not select Tensor/Qualcomm-specific files for unmatched hardware.
- Record cold load, warm command latency, memory pressure, unsupported-command behavior, and background/foreground lifecycle behavior. Vendor benchmarks are not device acceptance evidence.
- If native offline ASR is unavailable, voice is blocked on that device until an explicitly local speech recognizer is integrated. Do not substitute ordinary SpeechRecognizer plus `EXTRA_PREFER_OFFLINE`, which is only a preference. A separate downloadable ASR model is the revival trigger for that extra dependency, not speculative initial scope.

### 2. Add the two-model settings and downloader

Replace the Advanced placeholder with enablement, selected model, download/progress/cancel/retry, and delete controls. Keep exactly these model choices:

- `litert-community/functiongemma-270m-ft-mobile-actions` — default for mobile actions.
- `litert-community/gemma-4-E2B-it-litert-lm` — optional larger model.

Use a small fixed two-entry model list containing immutable revision, filename, size, and SHA-256. Persist enablement and selected ID using existing local preferences; no Room schema change solely for these scalar values. Ensure separate Home/settings ViewModels observe updates rather than retaining stale snapshots.

Download only after an explicit user action, off the main thread. Check available storage, stream into a `.part` file under app-private `noBackupFilesDir/assistant/models`, compare the pinned Hugging Face LFS SHA-256, and atomically publish the completed file. Incomplete or corrupt files never become selectable. Report download, storage, authorization, and load errors plainly. Keep switching local: a missing model must request a download, never silently download or invoke another provider. Close the old engine before loading the next; only one is resident.

Add a masked Hugging Face token field **only for FunctionGemma**, plus a license-acceptance link. FunctionGemma download is gated; Gemma 4 is public. Keep the token in memory for the explicit download session instead of persisting it. Do not embed the developer's Bitwarden token in the APK or configuration. Use authorization only for trusted Hugging Face requests; never forward the bearer token to arbitrary redirect hosts. Clear it after completion/cancellation and never log it.

### 3. Wire hold-to-talk and local inference

Add a new circular microphone button at Home's bottom right, using existing theme, 54dp circle, and safe bottom inset. Hide it in settings, drawer, search, edit mode, and blocking overlays. Use a vector icon and accessibility label; provide accessible start/stop recording actions rather than relying solely on a held pointer.

On sustained press, check permission/model readiness and start `SpeechRecognizer.createOnDeviceSpeechRecognizer`; on release stop capture and run inference. Cancel on drag-away, interrupted gesture, backgrounding, or activity disposal. First permission request must not start unintended recording after the original hold ends. Check device and language support; use native model download APIs only if an offline language pack is missing and user explicitly requests setup. No ordinary recognizer fallback.

Show small transient listening/working/result/error feedback. No continuous microphone, wake word, history, audio files, or spoken-response engine. Do not preload a heavy engine during normal launcher startup. Supply current device date/time/timezone dynamically for calendar requests; no hardcoded dates or user identity.

### 4. Execute only supported, validated actions

Initial scope is Gallery's existing seven tool calls: flashlight on/off, contact creation, email composition, map lookup, Wi-Fi settings, and calendar event creation. Preserve exact FunctionGemma tool names and parameter descriptions instead of assuming an arbitrary launcher tool set will work with its fine-tune.

Validate tool name and parameter types/lengths, date parsing, and intent data before dispatch. Unknown/malformed output executes nothing. Allow one action per request initially; no autonomous multi-step agent loop. Use native Android settings/intent handlers and CameraManager; request microphone/camera permission only when needed. Catch absent handlers, unavailable hardware, permission denial, and model errors.

Contact/calendar creation opens the native confirmation editor; email opens a draft, never sends automatically. Avoid broad write permissions, shell execution, arbitrary URLs/intents, and accessibility automation. Work/private-profile data remains protected by existing eligibility rules; do not feed account/contact/notification contents into the model merely to populate prompts. Email/maps may independently need a network connection in the selected external app; assistant speech and inference must not.

### Likely files

Paths below are relative to the repository; Kotlin paths are under `app/src/main/java/com/galaxyrio/gracelauncher/`.

- Modify `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, and existing `app/src/main/res/values/strings.xml`.
- Modify `data/LauncherPreferences.kt`, `ui/LauncherViewModel.kt`, `ui/LauncherScreen.kt`, and `ui/settings/LauncherSettingsScreen.kt`.
- Create only compact concrete files as needed: `data/AssistantModels.kt` (fixed catalog/downloads), `platform/OfflineVoiceAssistant.kt` (speech/engine/lifecycle), and `platform/MobileActions.kt` (validated tool dispatch). No provider interfaces or factories.
- Add `app/src/test/java/com/galaxyrio/gracelauncher/MobileActionsTest.kt` and one focused Android integration test for settings/button/lifecycle wiring. Use existing JUnit/Compose tooling.
- Update `tasks.md` and `AGENTS.md` with verified behavior and actual-device caveats during implementation, not during this planning turn.

### Verification before shipping

- Run `bash ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` using repository-required JDK 21 and SDK 37/Build Tools 36.1.0.
- Leave a small runnable test for allowlist/argument validation: supported command dispatches; malformed, unknown, oversized, or cancelled requests dispatch nothing. Cover corrupt downloads and missing models without executing live external actions.
- Verify both selectors, token visibility, permission denial, hold/release/cancel, interrupted capture, model switching, unavailable recognizer, no speech, storage exhaustion, and failed loads.
- After explicit downloads, block network/enable airplane mode and run spoken commands with both models after process restart. Inspect traffic and logs to verify no assistant network use or sensitive content leakage. Only relying on airplane mode failure is not enough to establish privacy.
- Measure responsiveness and memory on the physical phone. Emulator checks establish UI/lifecycle wiring, not ARM/GPU performance.
- Build release, check native 16KB page-size compatibility, R8 tool/reflection behavior, sign using the existing key, and verify APK/certificate/alignment/package/version/hash following `AGENTS.md`. No APK is produced by this plan.

## FEASIBILITY

**Verdict:** Technically feasible with a single shared local runtime. Physical-device usability and offline speech support remain unverified; this is not a claim of working implementation.

### Verified upstream facts

- Google AI Edge Gallery's current version catalog uses LiteRT-LM **0.18.0**. Google Maven publishes that version. Its actual AAR manifest declares minimum API **24** and ships **arm64-v8a** and **x86_64** native libraries, so its declared minimum is compatible with this launcher's API 28. Dependency resolution, native loading, release shrinking, and 16KB alignment still require a real build.
- FunctionGemma generic mobile-actions file is **288,964,608 bytes** (~289 MB). Its card reports ~510 MB peak RSS and 0.24s first-token time on S25 Ultra for the published benchmark; those are not predictions for the user's phone.
- Gemma 4 generic file is **2,588,147,712 bytes** (~2.59 GB). Its GPU-specific file is **2,008,432,640 bytes**, but backend/model choice must follow actual compatibility tests. Generic-file disk size is not peak resident RAM.
- FunctionGemma repository is gated (`gated: auto`). Anonymous artifact HEAD returned **401**; an authenticated request using the existing Bitwarden `FINDETHICS_HF_TOKEN` returned **200** for the generic file. Consequently the conditional user token option is justified. License/access acceptance is still required for other users.
- Gemma 4 repository is public (`gated: false`), and anonymous artifact HEAD returned **200**. No token UI is needed for that selection.
- Both repositories publish `.litertlm` artifacts and LFS SHA-256 checksums. No conversion, training, cloud provider, or custom inference runtime is required for the initial integration.
- Android's guaranteed on-device recognizer factory is available from API **31**, but installation/language/device support is conditional. This is the main voice feasibility gate for FunctionGemma, which is text-input only. Gemma 4's native audio capabilities do not automatically solve speech input for the smaller interchangeable model.

### Deferred, with revival triggers

- Separate local ASR dependency/model: only if the target phone lacks usable native offline recognition.
- App-launch tools/new action families or fine-tuning: only if requested; first demonstrate both supplied models on the existing Gallery schema.
- GPU-specific artifacts/tuning: only if measured CPU latency fails practical usability.
- Wake words, background service, chat history, TTS, dynamic model marketplace, and framework abstractions: not required for hold-to-talk mobile actions.

### Sources

- Gallery integration reference: https://github.com/google-ai-edge/gallery
- Gallery tool definitions: https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/mobileactions/MobileActionsTools.kt
- Grace parent reference: https://github.com/Galaxy-rio/GraceLauncher
- FunctionGemma model and card: https://huggingface.co/litert-community/functiongemma-270m-ft-mobile-actions
- Gemma 4 model and card: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
- Android offline recognizer: https://developer.android.com/reference/android/speech/SpeechRecognizer#createOnDeviceSpeechRecognizer(android.content.Context)
- Maven runtime metadata: https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml
