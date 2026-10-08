# Tasks

- [x] Make work-profile apps available, with profile-aware identities, launches, and work badges.
- [x] Add reply functionality to the home-screen notification viewer.
  - Use the notification's supported text-reply action.
  - Keep the reply UI consistent with the launcher theme.
- [x] Add opt-in double-tap to sleep/lock on the home background using Android's accessibility lock action.
- [x] Remove the normal bottom-right G button; swipe up on eligible home background to open the existing app search screen.
  - Retain the edit-only Done control.
- [x] Create a signed release APK with a new local release key.
- [x] Resolve the all-app shortcut popup failure reported with notification/gesture issues.
  - Fixed a shared cache-miss error that prevented shortcut queries, retry, and prefetch for every uncached app.
  - Added a real repository regression: reproduced the failure before the fix; verified cold queries, cache reuse, and multi-app prefetch afterward.
  - Verified native Phone and Chrome right-swipe popups and background swipe-up search on the emulator. Notification and home-gesture tests passed without changing their implementation.
- [x] Show every Calendar Provider occurrence overlapping the next 24 elapsed hours in the home agenda.
  - Include ongoing entries, zero-duration events at `now` and later within the window, recurring instances, and all-day UTC-date boundaries converted to local time; exclude ended and out-of-window entries.
  - An empty agenda uses the same header layout as `calendarAgenda=false` without changing the preference. Weather and clock remain independent; blank titles use a localized “Untitled event” fallback.
  - Calendar Provider entries only; no standalone task-app integration (source app unknown).

## Validation and release status

Earlier testing was stopped at the user's request before full device validation. This follow-up passed 49 JVM tests and 41 focused instrumentation tests, with no skips, on the Android 16/API 36 emulator. The focused classes cover the shortcut repository, home gestures, notification UI/store, real notification-listener updates/dismissal, and accessibility-service metadata.

Debug assembly and lint passed (553 warnings, 1 hint, no errors). Full instrumentation and the affected physical device were not revalidated. Double-tap recognition/action dispatch was tested, but the real system lock action was not exercised.

The fixed debug APK is installed on the emulator with Grace as default HOME and notification access enabled. The signed release below is unchanged and does not include this fix; its assembly, signature, alignment, and package/version metadata were verified at the earlier release checkpoint.

APK: `app/build/outputs/apk/release/GraceLauncher-1.1.4-release.apk` (version `1.1.4`, code `6`).

The APK uses a new local signing identity and cannot update an installation signed with a different key. The keystore and its password file are retained outside the repository under `~/.local/share/grace-launcher/signing/`.

Calendar agenda follow-up:
- Verified JDK 21.0.7 and the installed Android SDK using command-local `JAVA_HOME`/`ANDROID_HOME`; no SDK/JDK installation, `local.properties`, persistent environment configuration, default-HOME setting, or wallpaper change was made.
- `:app:testDebugUnitTest` passed 53 tests (including 9 `ScheduleTimingTest` cases); `:app:assembleDebug` passed; `:app:lintDebug` passed with 0 errors, 553 warnings, and 1 hint (the existing lint warning count).
- Focused `HomeAgendaHeaderTest` instrumentation ran 9 tests: 8 passed, including all changed agenda tests; `dateAndAgendaMatchAppLabelSizeAndClockUsesMediumWeight` failed because the custom-drawn `home_clock` node has no `GetTextLayoutResult` action. No clock-rendering change was made for this unrelated test failure.
- CalendarRepository’s blank-title provider mapping has no existing mock-provider test setup; its localized fallback compiled and resource XML validation passed, but no provider-backed mapping test was added.
- `git diff --check` passed.
