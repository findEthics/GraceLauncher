# Repository notes

## Commands and prerequisites

- Work from the directory containing `settings.gradle.kts` (this file's directory), not its parent workspace. `:app` is the only Gradle module.
- Install JDK **21**, Android SDK Platform **37**, and Build Tools **36.1.0**. The Gradle daemon requires 21 despite Java source compatibility being 11; automatic JDK provisioning is disabled.

| Check | Command |
| --- | --- |
| Debug APK | `bash ./gradlew :app:assembleDebug` |
| JVM tests | `bash ./gradlew :app:testDebugUnitTest` |
| One JVM test class | `bash ./gradlew :app:testDebugUnitTest --tests 'com.galaxyrio.gracelauncher.ClockSpacingTest'` |
| Android lint | `bash ./gradlew :app:lintDebug` |
| Device/emulator tests | `bash ./gradlew :app:connectedDebugAndroidTest` |
| Bundled font integrity | `python3 third_party/fonts/verify_fonts.py` |

- Filter device tests with `-Pandroid.testInstrumentationRunnerArguments.class=com.galaxyrio.gracelauncher.LauncherFontFallbackTest`; that class requires API 31+.
- Integration tests may skip missing prerequisites: weather integration needs Breezy Weather 6.1+ and its permission; system notification integration needs listener access. Synthetic notification store/UI tests do not need that access. Revoking Breezy permission during instrumentation can kill the test process.
- `connectedDebugAndroidTest` teardown can remove installed test packages. To preserve an existing emulator installation, use `adb -s SERIAL install -r` for the debug APK and `adb -s SERIAL install -t -r` for the test APK, then run filtered `adb -s SERIAL shell am instrument` directly.
- Store screenshot tests are opt-in (`storeScreenshots=true`) and change real wallpaper. `scripts/capture-store-screenshots.ps1` is Windows-specific and updates images under `fastlane/metadata/android/`.

## Task tracking and release signing

- Current assistant: offline text chat plus Gemma4-only native actions, `1.2.6` / versionCode `14`, LiteRT-LM 0.18.0, verified pinned downloads in `noBackupFilesDir`. Voice/setup/diagnostics and microphone declarations were removed; speech error 7 is superseded, not fixed. No cloud fallback. Historical implementation plans remain under `.hermes/plans/`; current approved behavior and evidence are recorded in `tasks.md`.
- Ordinary messages use tool-free chat. Only a literal leading `!command` / `! command` authorizes an immediate, validated standalone English action from the latest body, never history; actions are single-line and at most 1,000 characters. Invalid proposals must not fall back to chat or retry dispatch. Preserve manual SDK execution (`automaticToolCalling=false`), generation/claim/commit and process-wide dispatch guards, permission discard/repeat, provider read-back, uncertain-write no-retry, and email draft-only behavior. Dispatch is at most once per command generation, not durable exactly-once across process death or repeated submissions.
- Alarm times must be explicit. With no day stated, a future time uses today and a passed time rolls to tomorrow; explicit today must still be ahead, and explicit tomorrow is accepted only after that time passes today. Keep the existing exact-now rejection. `7:00` alone is ambiguous, whereas `7am`/`7:00am`/`07:00` identify 07:00. Show fixed owned request-validator reasons without exposing SDK exceptions/model output. The user manually confirmed `!wake me up at 7am tomorrow` created an alarm; automatic rollover/ringing remains unverified until recorded in `tasks.md`. Do not treat intent launch as confirmed alarm creation.
- Home chat opens a full-screen overlay without a title or Close/Send buttons; Enter/IME Send submits, Shift+Enter inserts a newline, and Back closes/clears. On idle Home, produced Unicode `@` or plain Space opens/focuses chat without inserting text or changing a retained draft; `!` retains its action-prefix behavior. Request the keyboard, preserve rapid following typing, and consume opening-key repeats/release; do not hijack settings/search/editable widgets. Inside chat both characters are ordinary input. Draft/transcript/context are RAM-only: temporary switches retain, HOME minimizes, process death clears. Roll oldest complete conversational context turns with a notice within the 4,096-token engine context.
- Advanced's “Show chat button on Home” defaults on and uses the assistant's existing observable preferences. Hiding it affects only the Home button; keep assistant enablement separate and retain both keyboard shortcuts, even with no visible button. Do not add a database migration or gate shortcut eligibility on button visibility.
- Usage instructions belong in Advanced, not chat. Keep the composer visually unlabelled but accessible, rounded message/composer shapes, and existing theme colors/fonts. Do not reintroduce ordinary-reply captions or transient/completed reply footers; ordinary-chat status stays blank while streaming. Preserve actual action results, failures, permission/download notices, action progress/Stop controls, the reply bubble's initial processing text, and context-roll notices.
- Gemma4 uses `Backend.GPU()` with a one-shot `Backend.CPU()` inference-only fallback covering engine init and first inference, never tool dispatch or application validation failures. Existing FunctionGemma-270M files remain preserved, not used for the current action path. GPU speedup requires physical Titan validation (emulator lacks Mali); emulator evidence proves fallback, not physical GPU performance. Earlier voice-era emulator tests verified both models, not full coverage of the current chat build.
- LiteRT defaults camelCase tool schemas to snake_case; explicitly disable that flag before engine creation to preserve MobileActions model signatures. Offline instrumentation must assert readiness (not Assume/skip); seeded filenames are `<enum name>-<catalog filename>` plus `.sha256` matching the verified digest. UI tests run before seeding if testing download-token visibility.
- New signing key: alias `grace-launcher`, RSA 3072; certificate SHA-256 `7c928de3824f4c6d91056d5e5615c5cb5414fece82643c0930c0ca600f583a0b`. Key and password backups were read back and verified in Bitwarden (`GRACE_SIGNING_KEYSTORE_BASE64`, `GRACE_SIGNING_STORE_PASSWORD`, `GRACE_SIGNING_KEY_PASSWORD`). Existing differently signed installs cannot be updated with this key.
- Store/key passwords are equal: use `apksigner --ks-pass file:...` and omit `--key-pass`. Reusing the same password file for both options consumes its only line and fails with EOF.

- `tasks.md` (see its "Pending" section first) tracks pending tasks, completed implementation, and validation caveats. Keep it updated; distinguish implemented changes from verified behavior.
- The local release signing key and password file are saved under `~/.local/share/grace-launcher/signing/`. Back up both securely, keep them outside version control, and reuse the same key for compatible updates.
- Current Mac signing files: `~/.local/share/grace-launcher/signing/grace-launcher-current.p12` and `grace-launcher-current.password` (0600), restored from the user-provided `.env` and verified against the certificate fingerprint above. Never copy `.env` values into tracked files, log/commit `.env` or signing secrets, or substitute the retained older `edf4f9…` key. The current `1.2.6`/14 candidate passed artifact verification and an ordinary-chat signed-release smoke on API 36 `emulator-5580`; this does not verify the action matrix or physical GPU path. See `tasks.md` for evidence.
- Release builds (`bash ./gradlew :app:assembleRelease`) are unsigned; there is no release `signingConfig`. Apply 16KB alignment before signing with the existing key, then verify the signature/certificate, alignment, package/version, and SHA-256. Never log signing passwords.
- Record the built source commit and artifact evidence in `tasks.md`; later documentation-only commits do not change the APK's source or require a rebuild.

## Wiring and persistence

Kotlin sources below are relative to `app/src/main/java/com/galaxyrio/gracelauncher/`.

- `MainActivity` handles HOME (`singleTask`); the app icon opens `SettingsActivity`. Both use `ui/LauncherScreen.kt`'s `LauncherRoute`, with `settingsOnly=true` for settings. Repeated HOME intents reset the UI through `onNewIntent`, not activity recreation.
- `ui/LauncherViewModel.kt` constructs repositories directly. HOME and settings have separate ViewModels. Preserve the startup wait for both settings and launcher-item snapshots before refreshing apps; it prevents cold-start icon flashes.
- Persistence is split: Room owns new launcher data; legacy favorites, renames, categories, and appearance preferences remain in their existing stores. Use `data/LauncherSettingsRepository.kt`'s transactional `mutateSettings` for read-modify-write changes; UI snapshots can be stale across activities.
- Database changes need a version bump and a registered migration in `data/LauncherDatabase.kt`; there is no destructive fallback. Room/KSP exports **tracked** schemas to `app/schemas/`—include the generated schema with database changes.
- Folder members live in `app_popups` under `folder:<id>`, not the removed `folder_apps` table. Membership edits must preserve embedded widgets and their positions.
- Private Space has separate system-unlocked and launcher-visible states. Discovery needs API 35+ and default-HOME status. With lock-on-exit enabled, defer system locking until return from a launched private app; immediate locking stops it (`platform/PrivateSpaceController.kt`).
- `data/notifications/AppNotificationStore.kt` separates notifications by `(packageName, userSerial)`. Preserve live profile eligibility/authentication checks, original PendingIntents, and revision-guarded actions. Personal unlock cannot satisfy work-profile reply authentication.
- `LauncherAppRow` must receive the complete eligible notification list and preserve individual body lines. Suppress only redundant inline sender headings; keep popup notification data and actions unchanged.
- Home uses `data/ScheduleEvent.kt`'s `visibleScheduleEvents` rolling 24-elapsed-hour window; the date popup spans 14 dates. Empty agenda must not change the preference or suppress enabled battery/weather information (`ui/home/HomeScreen.kt`).

## Editing conventions

- Keep one `strings.xml` per language (`values/` and `values-<locale>/`); organize with section comments, not feature-specific string files. See `README.md`'s Contributing section.
- Fonts are bundled/offline. Asset changes must match `third_party/fonts` provenance records and pass the verifier above; `.ttf` assets are intentionally uncompressed. Imported app and clock fonts share the compatibility-sensitive `filesDir/clock_fonts` storage.
- Weather comes from Breezy's permission-protected content provider. The exported update receiver accepts only invalidation signals; preserve provider ownership/protocol validation and reread the trusted provider (`data/weather/`, `platform/BreezyWeatherUpdates.kt`).

- Release (R8) builds must keep `com.google.ai.edge.litertlm.**` (`app/proguard-rules.pro`): LiteRT-LM's native JNI calls Java callbacks by name; obfuscation caused `NoSuchMethodError onMessage` → SIGABRT on first inference (found on Titan, 1.2.5). Always smoke-test the SIGNED RELEASE apk's assistant path, not just debug.
- Historical voice-era findings (Titan 2, 1.2.6; not the current chat path): OS speech worked; FunctionGemma 270M needed greedy decoding (`SamplerConfig` topK=1, temp 0) to avoid sampled tool/free-text flips. Even greedy, "Torch on"/"Draft email" were unreliable; "Flashlight on" worked. Never log raw assistant input or model output in committed code (temporary `AsstDbg` and speech diagnostics were removed).
- Download token errors: a wrong HF token shows as "requires a valid token"; the status line is now at the top of Advanced so it is visible. Downloads outlive the screen and resume.
- Pending work is tracked in `tasks.md` under "Pending". Fine-tune dataset/generator from the user live in `docs/finetune/` (train/eval JSONL are for a *different* launcher command schema — see tasks.md before reuse).
- Historical signed release 1.2.6 (versionCode 14, before the chat pivot): SHA-256 bf48091bfc9c210d0bf33f24e1df6a1f5029e9a057a07dd249a6b31eea672612; installed on Titan 2; stock-270M phrase coverage was weak. Current chat source `ad2a0e3` has a separately verified signed APK at the same user-requested version, SHA-256 `c98505762c3907481136ae0e3f2bc60b76df6c886e740204ebc78df80058cdfa`; signed-release inference is still unverified. Evidence/limits are in `tasks.md`.
- User manually confirmed the chat app works on the emulator. The six requested UI refinements, `@` shortcut, and transient-footer/validation-feedback follow-up are implemented; focused UI/Home keyboard, alarm-stage, and synthetic screenshot evidence is in `tasks.md`. Automated `createContact` inference still fails closed before dispatch and blocks full matrix coverage; physical Titan/GPU and full action validation remain open. Do not infer a full action/release pass or resume speculative model fixes without renewed approval.
