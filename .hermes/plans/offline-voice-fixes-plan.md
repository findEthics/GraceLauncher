# Offline voice assistant — investigation & plan (no code yet)

Branch: `feat/offline-voice-actions`. Scope: fix 4 observed gaps. Privacy unchanged — everything stays on-device, no cloud inference.

## Findings (what the code actually does today)

- **Transcription** is delegated to Android's `SpeechRecognizer.createOnDeviceSpeechRecognizer`
  (`AssistantController.startListening`, lines 76-114). That recognizer needs Google Speech
  Services + a downloaded on-device language pack. On a minimal/Titan device without those,
  `isOnDeviceRecognitionAvailable()` returns false → voice fails closed → user sees "offline
  transcribe is not present". **We ship no transcriber of our own.**
- **Actions** path (lines 145-172) mirrors Gallery correctly: LiteRT-LM `Engine` +
  `createConversation(tools=..., automaticToolCalling=false)`, stream, collect `toolCalls`,
  map to `MobileAction`. This is the same mechanism as Gallery's `MobileActionsViewModel` +
  `LlmChatModelHelper`. No change needed here.
- **Lag on Gemma 4**: `infer` hardcodes `backend = Backend.CPU()` (line 153). Gallery defaults
  to **GPU** (`LlmChatModelHelper`: `preferredBackend = model.currentAccelerator ?: GPU`;
  `Model.currentAccelerator` falls back to `backendSpec.defaultAccelerator`). Gemma 4 E2B (2.6 GB)
  on CPU is slow; on GPU (FLOAT16) it is smooth. **CPU hardcode is the lag.**

## Plan

### 1. Fix lag — use GPU backend for the big model (ladder: use the dep we already have)
- In `AssistantController.infer`, pick backend per model instead of hardcoding CPU:
  - Gemma 4 E2B → `Backend.GPU()` (Gallery default; FLOAT16 by default → the smoothness gap).
  - FunctionGemma 270M → `Backend.CPU()` (tiny, CPU is already instant; GPU setup overhead not worth it).
- Wrap `engine.initialize()` in try/catch → on GPU failure, retry once with `Backend.CPU()` and
  surface a "running slower on CPU" status. Fail-closed stays intact; GPU is still fully local.
- Remove the blanket "CPU-first, no Mali assumption" constraint from AGENTS.md — it was the cause,
  not a safety rule. GPU inference is on-device and private.
- Skipped: a user-facing accelerator picker (Gallery has one). YAGNI — single device, auto-select
  by model. Add when a second device actually needs CPU override.

### 2. Transcription — system recognizer is the ONLY path (confirmed)
We will NOT spin up Gemma 4 for transcription. The app already uses the OS on-device recognizer
(`createOnDeviceSpeechRecognizer`), and that stays the sole transcriber. No AudioRecord, no audio
modality, no bundled ASR. New dependency count: zero.

Consequence: on a device with no on-device recognizer + installed language pack
(`isOnDeviceRecognitionAvailable() == false`), voice cannot work offline. That is a device-setup
gap, not an app bug. Only change here is to make the failure actionable instead of a dead end:
- On unavailable/`onError`, keep failing closed (no cloud) but show a one-line, specific status:
  "Install an offline language pack in Settings > Speech recognition".
- Add a guarded "Open settings" action next to that status that fires
  `Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)`. Guard with `resolveActivity()`; if it doesn't
  resolve (OEM-dependent), fall back to `Settings.ACTION_SETTINGS`. It only opens the OS voice-input
  screen — it does not download anything; the user taps to install the pack there.
- That's the whole change. Existing wiring (lines 76-118) is correct; only the status strings and
  the one guarded settings-intent are added.

Skipped: Gemma 4 audio transcription, Whisper, any bundled recognizer. The user explicitly does not
want a 2.6 GB model loaded just to transcribe. Add only if the user reverses that.

### 3 & 4 are covered above (Gallery methodology = LiteRT-LM Engine/Conversation/tools; lag = CPU hardcode).

## Decisions (confirmed by user)
1. Transcription uses the **OS on-device recognizer only** — never spin up Gemma 4 for it. If the
   device lacks an on-device recognizer + language pack, voice fails closed with an actionable
   message. (Supersedes the earlier "force Gemma 4 for voice" idea — no model touches transcription.)
2. **Gemma 4 defaults to GPU** with one-time CPU fallback on init failure. "CPU-first / no Mali"
   rule dropped — GPU is fully on-device. (This only affects the action/inference step, not voice.)

## Verification when built
- Gemma 4 voice turn end-to-end on emulator with GPU backend; compare latency vs current CPU.
- GPU-init-failure → CPU fallback path exercised.
- AudioRecord→Gemma 4 transcription produces a valid tool call offline with network disabled.
