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
- [x] Show eligible work-profile app notifications and route replies without mixing them with personal copies.
  - Root cause: `AppNotificationStore.parse()` discarded every notification whose user was not the personal user; the UI map was keyed only by package and selected notifications only for personal apps.
  - Preserve the personal package key; work notifications use the existing profile serial, and group-summary suppression and row selection are profile-qualified. Only related managed profiles that are present, storage-unlocked, and not quiet are eligible; open/dismiss/reply recheck that policy.
  - A remaining personal-only guard in `LauncherAppRow` also hid home/drawer previews after profile-aware selection. The row now accepts an already-matched work notification; package/profile mismatches, private apps, shortcuts, and unclassified profile rows remain hidden.
  - Work-profile PendingIntents remain the notification's original targets. Authentication-required work replies are left to Android's notification UI: personal unlock is not proof of a separate work challenge being unlocked. Android/DPC policy may still prevent listener delivery; no listener or cross-profile security bypass is added.
- [ ] Battery display has a bug; it does not show at all

## Validation and release status

Earlier testing was stopped at the user's request before full device validation. This follow-up passed 49 JVM tests and 41 focused instrumentation tests, with no skips, on the Android 16/API 36 emulator. The focused classes cover the shortcut repository, home gestures, notification UI/store, real notification-listener updates/dismissal, and accessibility-service metadata.

Debug assembly and lint passed (553 warnings, 1 hint, no errors). Full instrumentation and the affected physical device were not revalidated. Double-tap recognition/action dispatch was tested, but the real system lock action was not exercised.

At the earlier shortcut-validation checkpoint, the fixed debug APK was installed on the emulator with Grace as default HOME and notification access enabled. The then-current signed release was built from commit `710382f`, before the work-profile fix, and was superseded by the release below.

APK: `app/build/outputs/apk/release/GraceLauncher-1.1.4-release.apk` (version `1.1.4`, code `6`; 9,091,059 bytes).
SHA-256: `09485fadbb0d76122a221134291ee4982db7655da85c053835875f78d0566e01`.
Built from source commit: `c252dc922f61a514a5221f82abb4fc6c2d4f28fa` (includes the work-profile store/selection/reply changes and the inline row-preview correction).

Release assembly passed; APK v3 signature, 16 KB alignment, package/version/min/target SDK, and non-debuggable metadata passed verification. The APK reuses the existing local release signing identity, verified against the previous APK. It was not installed or device-tested, and no live managed-profile/DPC test is claimed. Android/DPC policy may still block listener delivery; authentication-required work replies remain in Android's notification UI. The APK cannot update an installation signed with a different key. The keystore and its password file remain outside the repository under `~/.local/share/grace-launcher/signing/`; no new key or version bump was made.

Calendar agenda follow-up:
- Verified JDK 21.0.7 and the installed Android SDK using command-local `JAVA_HOME`/`ANDROID_HOME`; no SDK/JDK installation, `local.properties`, persistent environment configuration, default-HOME setting, or wallpaper change was made.
- `:app:testDebugUnitTest` passed 53 tests (including 9 `ScheduleTimingTest` cases); `:app:assembleDebug` passed; `:app:lintDebug` passed with 0 errors, 553 warnings, and 1 hint (the existing lint warning count).
- Focused `HomeAgendaHeaderTest` instrumentation ran 9 tests: 8 passed, including all changed agenda tests; `dateAndAgendaMatchAppLabelSizeAndClockUsesMediumWeight` failed because the custom-drawn `home_clock` node has no `GetTextLayoutResult` action. No clock-rendering change was made for this unrelated test failure.
- CalendarRepository’s blank-title provider mapping has no existing mock-provider test setup; its localized fallback compiled and resource XML validation passed, but no provider-backed mapping test was added.
- `git diff --check` passed.

Work-profile notification fix follow-up:
- Before production edits: `adb shell am instrument -w -r -e class 'com.galaxyrio.gracelauncher.AppNotificationStoreTest#managedWorkNotificationKeepsItsReplyPendingIntent' com.galaxyrio.gracelauncher.test/androidx.test.runner.AndroidJUnitRunner` failed because the parser dropped the synthetic work-user `StatusBarNotification` (`notifications().single()` threw `NoSuchElementException`).
- After the work-authentication guard correction: the direct runner passed 37/37 tests (`AppNotificationStoreTest` 18, `WorkProfileIdentityTest` 6, `AppNotificationUiTest` 13). The expanded store regression exercises profile-specific buckets, same-package/same-group isolation, matching work-row selection, and a real mutable `RemoteInput` receiver using a personal test-context PendingIntent—not a live work-user PendingIntent. JVM tests passed 53/53; debug/test assembly and lint passed.
- Review found that work storage availability does not prove a separate work challenge is unlocked. A new regression failed before the guard (`canReply` was true) and passed afterward: authentication-required work actions cannot use personal unlock, while ordinary work replies and authenticated personal replies retain their existing behavior. No credential-locked-profile content guarantee is inferred from `UserManager.isUserUnlocked`.
- Direct test commands: `adb shell am instrument -w -r -e class com.galaxyrio.gracelauncher.AppNotificationStoreTest com.galaxyrio.gracelauncher.test/androidx.test.runner.AndroidJUnitRunner`; the same command with `WorkProfileIdentityTest` (6/6) and `AppNotificationUiTest` (13/13). Debug/test APKs were updated with `adb install -r app/build/outputs/apk/debug/app-debug.apk` and `adb install -t -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`. `connectedDebugAndroidTest` was not used because its teardown uninstalls packages. The abandoned fixture-only changes from the earlier timeout were removed after backup; the original `SystemNotificationIntegrationTest.kt` is unchanged. No real managed-profile system-listener test is claimed.
- Regression classification is synthetic: the test injects the same serial policy while exercising store parsing and a real Android `RemoteInput` PendingIntent, because no managed work profile/DPC device was available. No users/profiles were created. Actual managed-profile listener delivery and enterprise policy remain unverified; Android/DPC may suppress delivery even when the shade shows a notification.
- Independent review accepted the work-profile fix after the authentication guard correction. The earlier unvalidated test manifest/service and system-fixture delta are backed up under `/tmp/grace-work-notification-baseline-y5mkg3h4` and are not included in the final changes.
- Emulator state was restored: Grace notification-listener access remains absent as before; no test-posting permission is granted; no synthetic fixture notifications remain. Debug/test APKs are installed as permitted; no uninstall, data clear, default-HOME, or wallpaper change was performed.
- At this earlier checkpoint, the signed APK was built from source commit `017761522a689055ac46bebd47016254e0aa267f` and included the initial store/selection/reply changes but not the row-preview correction; it was later superseded by the release built from `c252dc922f61a514a5221f82abb4fc6c2d4f28fa` above. No release install or live managed-profile test was performed.

Work-profile row-preview follow-up:
- User reported that work-profile inline previews were still missing in the signed release and manually confirmed that long-press/right-swipe notification popups worked in the preceding build. A read-only subagent compared Prompt Pad (`479b7f3`) with Grace (`331d28d`); Prompt Pad was left unchanged. Prompt Pad shows listener messages in a generic list without joining profile-aware app rows; this is reference source evidence, not proof of live work-notification delivery.
- Confirmed another source blocker: `LauncherAppRow` suppressed every notification on an app with a user/user serial, despite `notificationsFor(app)` now selecting eligible work entries. Fixed this shared row guard for home and drawer without weakening listener/profile/privacy/authentication policies.
- The work-home preview regression failed before the guard change (notification title not displayed), then passed afterward through preview arrow, popup, and reply UI dispatch. Added drawer preview/popup and private/shortcut/unclassified/mismatched-profile row checks. Final direct instrumentation passed 40/40 tests (UI 16, store 18, identity 6); JVM tests passed 53/53. Debug/test assembly, lint, and `git diff --check` passed. An initial drawer assertion matched both retained home and drawer previews; scoping it to the drawer fixed the test ambiguity.
- This verifies rendering from synthetic, already-delivered notifications only. The user's popup confirmation is manual; live managed-profile listener delivery, the live resolver, and actual work-user PendingIntent execution remain unverified on the affected device. The remaining reported symptom motivating this change was inline app-row previews.
- The signed release above was rebuilt from source commit `c252dc922f61a514a5221f82abb4fc6c2d4f28fa` and includes this row-preview correction. It was not installed or device-tested; no live managed-profile listener or actual work-user PendingIntent test is claimed. Only approved debug/test APK updates were made on the emulator; no notification-access, profile, data, default-HOME, wallpaper, or reference-repository changes were made.
