# Offline mobile action voice assistant — plan and feasibility

Status: planning only; no assistant implementation or APK. User confirms Gemma-4-E2B-it and MobileActions-270M work offline in AI Edge Gallery on the target device, including voice input for Mobile Actions; no benchmark supplied.

## PLAN

### Target and workspace

- Target: **Unihertz Titan 2 Elite, Android 16, MediaTek Dimensity 7400**, as specified by the user. Manufacturer lists **12 GB RAM / 256 GB storage**; verify the actual unit before performance testing.
- Project home: `/home/ss/projects/GraceLauncher/`. User explicitly requires this work outside life-book; keep code and planning documents in this source repository.
- Source checkout: `/home/ss/projects/GraceLauncher`, fork `findEthics/GraceLauncher`.
- Feature branch: `feat/offline-voice-actions`, already created locally from `refinements` at `31efed13ccca9397317f8ec1e7cf4bd2e5aa6a62`; not pushed.
- Detailed integration plan: `/home/ss/projects/GraceLauncher/.hermes/plans/2026-10-09_235446-offline-voice-actions.md`. This repository-local note owns target-device assumptions and feasibility. Do not place this project in the life-book vault.

### Smallest end-to-end implementation

1. **Reuse the proven device path, when implementation is authorized.** User confirms both Gemma-4-E2B-it and FunctionGemma/MobileActions-270M offline operation in Gallery, including Mobile Actions voice input. No separate model feasibility app is needed. Verify the launcher's guaranteed-local recognition API and tested language, then measure action accuracy, engine initialization, warm command latency, peak memory, and repeated-use heat. Exact artifact/backend remain unspecified; the assistant has not directly tested the phone.
2. **Add Advanced settings.** Enable assistant, select one of the two requested models, download/progress/cancel/retry/delete. FunctionGemma is the default. Download on explicit request, verify pinned LFS SHA-256, then atomically store under app-private `noBackupFilesDir`. Keep only one engine resident. A missing/corrupt model must not trigger cloud inference or an automatic replacement.
3. **Add Home hold-to-talk.** Separate circular bottom-right microphone button, using Grace styling and safe insets. Hold starts recording; release stops and processes; interruption cancels. Include accessibility start/stop actions. Hide outside normal Home. Native `createOnDeviceSpeechRecognizer` supplies text to either model; fail closed if unavailable. Do not use ordinary recognition plus `EXTRA_PREFER_OFFLINE` as a privacy guarantee.
4. **Dispatch validated mobile actions.** Use Gallery's exact FunctionGemma tool schema: flashlight on/off, contact editor, email draft, map lookup, Wi-Fi settings, and calendar editor. Allowlist names and validate arguments. Unknown/malformed output executes nothing. No arbitrary intent/shell/accessibility execution; no automatic email sending. External email/maps applications may independently need internet; assistant recognition/inference remains local.
5. **Verify before release.** Both models must work with network blocked after restart. Check token/download failures, permissions, holding/cancelling, interrupted capture, switching, native-handler failures, and responsive launcher behavior. Leave one focused runnable action-validation check; use existing JUnit/Compose tooling. Build/test/lint, then verify signed release/native alignment only during authorized implementation.

### Models and authentication

- `litert-community/functiongemma-270m-ft-mobile-actions`: generic `mobile_actions_q8_ekv1024.litertlm`, approximately **289 MB**, CPU first. Text-only, so local transcription is necessary.
- `litert-community/gemma-4-E2B-it-litert-lm`: generic `gemma-4-E2B-it.litertlm`, approximately **2.59 GB**, CPU baseline; try a compatible Mali GPU artifact/backend only if needed and verified. Do not use Tensor/Qualcomm-specific weights on Dimensity.
- Prior exact-artifact checks: FunctionGemma anonymous download returned 401 and Bitwarden-authorized access returned 200; Gemma 4 anonymous access returned 200. Therefore add a masked, download-session-only token input for FunctionGemma, not Gemma 4. Never package the developer's token or persist/log audio, transcripts, or credentials.

### Likely code changes

Under `/home/ss/projects/GraceLauncher/`:

- `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, existing `app/src/main/res/values/strings.xml`.
- Existing settings/preferences/ViewModel/Home wiring under `app/src/main/java/com/galaxyrio/gracelauncher/`; follow `AGENTS.md` for settings synchronization between separate Home/settings activities.
- At most small concrete model/download, speech/engine, and action-dispatch files plus focused tests. No provider interface, model marketplace, chat framework, wake word, TTS, or background service.
- Future verification: `bash ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`; physical-device checks additionally required. Repository requires JDK 21, SDK 37, Build Tools 36.1.0.

## FEASIBILITY

**Verdict: both requested model families run offline in Gallery on the target device, user-confirmed. Mobile Actions also works with offline voice input. Basic device feasibility is established; launcher integration, its strict local-only recognition path, and measured performance remain unverified.**

### User-reported device result

- User confirms Gemma-4-E2B-it works offline in the Android AI Edge Gallery app on the target Titan 2 Elite.
- Attached image `/home/ss/.hermes/cache/images/img_61bc0b72f3e4.jpg` identifies the Agent Skills recommendation as Gemma-4-E2B-it, 2.6 GB. The image is a selection screen, not an inference trace; the success evidence is the user's explicit report.
- User additionally confirms Mobile Actions works offline, including its voice input. Image `/home/ss/.hermes/cache/images/img_83fbd871826e.jpg` identifies MobileActions-270M, 289.0 MB, described as a fine-tuned Function Gemma 270M model. Again, the offline success evidence is the explicit user report, not the selection screen alone.
- Exact artifact revisions, CPU/GPU backend, tested language, individual action results, latency, and peak memory were not supplied. Offline voice success demonstrates a working offline path in Gallery, not a guarantee that ordinary recognition never uses cloud services when connected. The launcher must enforce exclusively local recognition independently.

- **Android/voice:** Android 16 clears the API 31 requirement for the on-device recognizer factory. Gallery voice input is now user-confirmed offline. Verify native on-device API/service and the tested language when wiring the launcher; do not add a separate ASR dependency unless this path fails.
- **Runtime:** Previously inspected LiteRT-LM 0.18.0 AAR declares minimum API 24 and includes ARM64. The OS/ABI fit is plausible; actual native loading, shrinking, 16 KB page alignment, and runtime behavior still require a build and phone test.
- **Chip:** MediaTek specifies **Mali-G615 MC2** for Dimensity 7400. Start with portable CPU execution. GPU performance/driver compatibility is unverified. Do not budget for NPU acceleration: no verified Dimensity 7400 runtime/delegate/model combination has been established. MediaTek support on another SoC is not evidence for this device.
- **FunctionGemma:** MobileActions-270M offline operation and voice input are user-confirmed. Its small weight file keeps it the planned lightweight default. No Titan latency or RAM measurements were supplied; upstream flagship benchmarks must not be reused as Titan estimates.
- **Gemma 4:** User-confirmed offline execution removes the basic model-runs-on-device uncertainty. Mobile Actions voice success is separate from a Gemma 4 voice/action test; launcher coexistence and sustained responsiveness remain unmeasured. Total RAM is not the application's usable budget and download size is not peak memory. Keep context/output bounded to a single action and one resident model; measure latency/memory/heat before promising performance.
- **Privacy:** Local weights and inference are feasible. Voice privacy additionally depends on exclusively local ASR; ordinary Android speech recognition can use servers even when offline is preferred. Downloads are explicit setup operations, never online inference.

### Deferred work and triggers

- Separate local ASR dependency/model: add only if the Titan lacks working native offline speech or it fails the privacy/accuracy check. This is a necessary fallback implementation, never a cloud fallback.
- GPU tuning: add only if measured CPU responsiveness is inadequate; validate the exact Mali/device/model combination.
- NPU integration: revisit only with confirmed Dimensity 7400 support and compatible artifacts; no speculative vendor SDK work.
- Additional actions/fine-tuning: only if requested and existing model/schema tests demonstrate the need.

### Sources

- Manufacturer/device specifications: https://www.unihertz.com/products/titan-2-elite
- Dimensity 7400 / Mali-G615 MC2: https://www.mediatek.com/products/smartphones/mediatek-dimensity-7400
- Gallery integration reference: https://github.com/google-ai-edge/gallery
- Grace parent: https://github.com/Galaxy-rio/GraceLauncher
- FunctionGemma: https://huggingface.co/litert-community/functiongemma-270m-ft-mobile-actions
- Gemma 4: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
- Android on-device recognizer: https://developer.android.com/reference/android/speech/SpeechRecognizer

Device specifications researched on 2026-10-10. User-provided target OS/chip are the planning inputs; no claim of direct inspection of the physical phone.
