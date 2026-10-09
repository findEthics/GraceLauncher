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

- `tasks.md` tracks pending tasks, completed implementation, and validation caveats. Keep it updated; distinguish implemented changes from verified behavior.
- The local release signing key and password file are saved under `~/.local/share/grace-launcher/signing/`. Back up both securely, keep them outside version control, and reuse the same key for compatible updates.
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
