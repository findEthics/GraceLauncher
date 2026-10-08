# Repository notes

## Commands and prerequisites

- Work from the directory containing `settings.gradle.kts` (this file's directory), not its parent workspace. `:app` is the only Gradle module.
- Install JDK **21**, Android SDK Platform **37**, and Build Tools **36.1.0**. The Gradle daemon requires 21 despite Java source compatibility being 11; automatic JDK provisioning is disabled.

| Check | Command |
| --- | --- |
| Debug APK | `./gradlew :app:assembleDebug` |
| JVM tests | `./gradlew :app:testDebugUnitTest` |
| One JVM test class | `./gradlew :app:testDebugUnitTest --tests 'com.galaxyrio.gracelauncher.ClockSpacingTest'` |
| Android lint | `./gradlew :app:lintDebug` |
| Device/emulator tests | `./gradlew :app:connectedDebugAndroidTest` |
| Bundled font integrity | `python3 third_party/fonts/verify_fonts.py` |

- Filter device tests with `-Pandroid.testInstrumentationRunnerArguments.class=com.galaxyrio.gracelauncher.LauncherFontFallbackTest`; that class requires API 31+.
- Integration tests can pass with checks skipped: Breezy Weather 6.1+ and its permission must be configured before weather tests; notification tests require notification access. Revoking Breezy permission during instrumentation can kill the test process.
- Store screenshot tests are opt-in (`storeScreenshots=true`) and change real wallpaper. `scripts/capture-store-screenshots.ps1` is Windows-specific and updates images under `fastlane/metadata/android/`.

## Task tracking and release signing

- `tasks.md` tracks pending tasks, completed implementation, and validation caveats. Keep it updated; distinguish implemented changes from verified behavior.
- The local release signing key and password file are saved under `~/.local/share/grace-launcher/signing/`. Back up both securely, keep them outside version control, and reuse the same key for compatible updates.

## Wiring and persistence

Kotlin sources below are relative to `app/src/main/java/com/galaxyrio/gracelauncher/`.

- `MainActivity` handles HOME (`singleTask`); the app icon opens `SettingsActivity`. Both use `ui/LauncherScreen.kt`'s `LauncherRoute`, with `settingsOnly=true` for settings. Repeated HOME intents reset the UI through `onNewIntent`, not activity recreation.
- `ui/LauncherViewModel.kt` constructs repositories directly. HOME and settings have separate ViewModels. Preserve the startup wait for both settings and launcher-item snapshots before refreshing apps; it prevents cold-start icon flashes.
- Persistence is split: Room owns new launcher data; legacy favorites, renames, categories, and appearance preferences remain in their existing stores. Use `data/LauncherSettingsRepository.kt`'s transactional `mutateSettings` for read-modify-write changes; UI snapshots can be stale across activities.
- Database changes need a version bump and a registered migration in `data/LauncherDatabase.kt`; there is no destructive fallback. Room/KSP exports **tracked** schemas to `app/schemas/`—include the generated schema with database changes.
- Folder members live in `app_popups` under `folder:<id>`, not the removed `folder_apps` table. Membership edits must preserve embedded widgets and their positions.
- Private Space has separate system-unlocked and launcher-visible states. Discovery needs API 35+ and default-HOME status. With lock-on-exit enabled, defer system locking until return from a launched private app; immediate locking stops it (`platform/PrivateSpaceController.kt`).

## Editing conventions

- Keep one `strings.xml` per language (`values/` and `values-<locale>/`); organize with section comments, not feature-specific string files. See `README.md`'s Contributing section.
- Fonts are bundled/offline. Asset changes must match `third_party/fonts` provenance records and pass the verifier above; `.ttf` assets are intentionally uncompressed. Imported app and clock fonts share the compatibility-sensitive `filesDir/clock_fonts` storage.
- Weather comes from Breezy's permission-protected content provider. The exported update receiver accepts only invalidation signals; preserve provider ownership/protocol validation and reread the trusted provider (`data/weather/`, `platform/BreezyWeatherUpdates.kt`).
