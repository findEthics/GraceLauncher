# Alarm day/ambiguity, German-locale alarms, Health null aggregates, SMS fallback status

## Overview
Fix 5 audit findings, smallest change each:
1. `!alarm` drops the requested date (Clock intent carries only hour:minute) → alarm can ring on the wrong day.
2. Unqualified times are guessed inconsistently (`alarm 7`→19:00, `alarm 7:30`→07:30, `alarm at 7:00`→19:00).
3. On a German-locale phone, `!alarm …` always fails (Calendar parser has no German → `UNSUPPORTED_LANGUAGE`).
4. Health evidence renders missing aggregates as invented zeros (`steps avg 0 min 0 max 0` + zero-day flag).
5. Failed SMS fallback handoff shows "Opened a carrier SMS draft…" instead of the no-app error.

Paths: **G** = `app/src/main/java/com/galaxyrio/gracelauncher/`, **C** = `app/src/main/java/com/katoaapps/openminilaunch/features/calendar/`.

## Business & UX Decisions
- **Named day must equal the next occurrence.** Android `ACTION_SET_ALARM` cannot carry a date; Clock rings at the next time hour:minute comes round. So accept a named day (today/tomorrow/weekday/date) only when the Calendar-resolved date equals the date of that next occurrence. Otherwise refuse. Example at 13:00: `alarm tomorrow 7am` → OK (07:00 already passed today → next is tomorrow); `alarm tomorrow 19:30` → refused; `alarm today 7am` → refused. Matches old project rule "explicit tomorrow only after that time has passed today".
- **Unqualified 1–12 times → nearest upcoming.** User choice. A time with no am/pm/24h signal picks whichever of `h` or `h+12` (for 12: 00 or 12) comes next strictly after now. At 13:00 `alarm 7:30` → 19:30 today; at 05:00 `alarm 7` → 07:00 today; at 20:00 `alarm 7` → 07:00 tomorrow. Applies also to `half past seven`, `quarter to 8`, `at 7:00`.
- **Literal (not ambiguous) times:** contains am/pm (any form: `am`, `a.m.`, `7am`), `noon`, `midnight`; hour 0 or 13–23; two-digit leading-zero hour (`07:00`, `07:30`). If the phrase has a day-part word (`morning`, `afternoon`, `evening`, `tonight`) trust Calendar's hour as-is.
- **Exact-now is refused:** a literal time equal to now's hour:minute (`alarm 13:00` at 13:00) → `null` (generic invalid hint), matching the project's existing exact-now rejection. For ambiguous input, a candidate equal to now is simply skipped (the other candidate wins). `next()` is strictly after now.
- **Ambiguous + named day:** of the two candidates on the Calendar-resolved date, pick the earliest that equals the next occurrence; if none, refuse. At 20:00 `alarm tomorrow 7` → 07:00 tomorrow. At 06:00 `alarm tomorrow 7` → refused (both 07:00 and 19:00 are still today).
- **Specific refusal message** for wrong day (not the generic hint): `Clock can only set the next %1$02d:%2$02d (%3$s). Remove the day or pick another time.` where `%3$s` = "today"/"tomorrow". Reported time = the earliest upcoming candidate (for ambiguous input) or the literal time.
- **Preview shows the day:** `Alarm · 19:00 today` / `Alarm · 07:00 tomorrow`, so the nearest-upcoming guess is visible before Enter. When the parse is `WrongDay`, preview shows the refusal text.
- **German locale:** `!` is an English-only command and there is no German Calendar language, so alarms always parse in English regardless of device locale.
- **Health missing numbers:** if `count` is 0 or `avg` is null → render `<metric> no data` (same wording already used when the whole object is missing) and raise no quality flag. If `avg` exists but `min`/`max` null → omit those parts (never print 0) and skip flags that need them. Applies to steps, sleep nightly stats, resting HR. Keep sleep `rolling_avg` when present.
- **SMS fallback:** show `magic_sms_fallback` only when the draft actually opened; otherwise keep `magic_no_app` set by `handoff(false)`.
- New strings: English only in `res/values/strings.xml` (existing Magic strings have no German translations either).

## Technical Decisions
### Alarm parser (G`magic/MagicAlarmCommand.kt`, 50 lines)
- Remove the `language` parameter of `parse`; always pass `"en"` to `CalendarParseRequest`. One-line comment: `// ! commands are English-only; no German Calendar language exists.` Update callers in G`magic/MagicCommandWindow.kt:341-351` (remove `val language` if it becomes unused) and tests.
- Add result type:
  ```kotlin
  data class Alarm(val hour: Int, val minute: Int, val message: String? = null, val tomorrow: Boolean = false) : MagicAlarmCommand
  data class WrongDay(val hour: Int, val minute: Int, val tomorrow: Boolean) : MagicAlarmCommand
  ```
  Default `tomorrow=false` keeps `MagicAlarmIntentTest` (`Alarm(7,30,"Breakfast")`) compiling. Intent dispatch (G`magic/MagicActions.kt:132-143`) unchanged — it ignores `tomorrow`.
- Core helper (pure, in the same file):
  ```kotlin
  // next occurrence of t strictly after now
  fun next(t: LocalTime, now: ZonedDateTime): ZonedDateTime
  ```
  Use `now.toLocalDate()`/`atStartOfDay`-style `ZonedDateTime.of(date, t, zone)`; if not after now, plus one day. `tomorrow` = next date != now date.
- Flow after Calendar `Success` (and the existing fallbacks: `at $rest` retry, `parseSpokenClockTime`, fraction path):
  1. Get Calendar's resolved `ZonedDateTime` (`draft.startMillis`) → date D and hour H, minute M.
  2. Decide `ambiguous` from the text `rest`: no meridian token (`\b[ap]\.?m\.?\b` or digit+`am`/`pm`), no `noon`/`midnight`, no day-part word (`morning|afternoon|evening|tonight`), and the typed hour is 1–12 without a leading zero (bare `7`, `7:30`, `seven`, `half past seven`). If the typed hour is ≥13, 0, or written `0X:`, it is literal. Implementer: derive the typed hour from the text (numbers may be words — reuse `ActionInputValidation.normalizeNumbers`), not from H, because Calendar already applied a PM heuristic (C`language/en/EnglishCalendarLanguage.kt:266-268`).
  3. Candidates: ambiguous → `{LocalTime.of(H % 12, M), LocalTime.of(H % 12 + 12, M)}`; else `{LocalTime.of(H, M)}`.
  4. Named day present? Use Calendar's draft to tell whether the input named a date (e.g. compare D against what a dayless parse would give, or check for day tokens — pick the simplest reliable signal the Calendar draft/engine already exposes; do not add a new parser). 
     - No named day: pick candidate with earliest `next(c, now)` → `Alarm(c, message, tomorrow)`.
     - Named day D: valid = candidates where `next(c, now).toLocalDate() == D`; pick earliest → `Alarm`; if none → `WrongDay(earliest-upcoming candidate, its tomorrow flag)`.
  5. Keep all existing rejections (allDay → null, multiple times, control chars, >1000 chars, `alarm tomorrow` with no time → null).
- The strict `parseSpokenClockTime` fallback (line 41) and fraction path (lines 42-47) must also go through the same candidate/next-occurrence step (they have no day, so "No named day" branch). `parseSpokenClockTime` returns null for `7:00`, so it won't steal ambiguous cases — fine.
- Existing helper `ActionInputValidation.alarmOccurrence` (G`assistant/ActionInputValidation.kt:87-98`) is legacy-validator logic for today/tomorrow only; reuse it only if it fits cleanly — do not change its behavior (legacy `ActionRequestPolicyTest` depends on it).

### Window (G`magic/MagicCommandWindow.kt`)
- Preview (~line 513): `is Alarm -> stringResource(R.string.magic_alarm_preview, h, m, dayString)`; `is WrongDay -> stringResource(R.string.magic_alarm_wrong_day, h, m, dayString)`; timer unchanged.
- Submit (~lines 350-356): `WrongDay` → `s.status = magic_alarm_wrong_day…`, do not dispatch, do not close; `Alarm` → unchanged dispatch; null → existing `magic_alarm_status_invalid`.
- SMS (lines 220-223):
  ```kotlin
  SmsOutcome.NoSim, SmsOutcome.Unavailable -> {
      val opened = actions.messaging.openPreferredMessageDraft(draft.recipient, draft.body, null) != PreferredMessageDraftResult.FAILED
      handoff(opened)
      if (opened) s.status = resources.getString(R.string.magic_sms_fallback)
  }
  ```

### Strings (`app/src/main/res/values/strings.xml`, near line 636-638)
- Change `magic_alarm_preview` to `Alarm · %1$02d:%2$02d %3$s`.
- Add `magic_alarm_today` = `today`, `magic_alarm_tomorrow` = `tomorrow`, `magic_alarm_wrong_day` = `Clock can only set the next %1$02d:%2$02d (%3$s). Remove the day or pick another time.`
- If `values-de/strings.xml` has `magic_alarm_preview`, update its format args to match (3 args) — lint will flag mismatches.

### Health (G`assistant/HealthEvidence.kt:101-123`)
- Per metric (steps `windows.*.steps`, sleep `sleep_hours.nightly_stats`, resting HR `resting_hr`): if object missing, `optInt("count") == 0`, or `num("avg") == null` → existing "no data" line, no flag. Else render avg; append min/max only when non-null; compute each flag only from non-null values (steps zero flag needs non-null `min`; sleep `<1`/`>16` and HR `<30`/`>200` need their values). Remove the `?: 0.0` defaults.
- Keep sleep `rolling_avg` suffix when present, even when nightly stats are "no data" only if that already happens today; otherwise leave as is — don't redesign.

## Relevant Files
- G`magic/MagicAlarmCommand.kt` — parser (main change).
- G`magic/MagicCommandWindow.kt` — `language` (341-351), submit (350-356), preview (~513), `handoff` (193-196), `sendDirect` (214-226).
- G`magic/MagicActions.kt:96,132-143` — alarm intent (no change expected).
- G`assistant/ActionInputValidation.kt` — `parseSpokenClockTime` (185-251), `normalizeNumbers`, `alarmOccurrence` (87-98).
- C`engine/CalendarPhraseEngine.kt`, C`engine/CalendarTemporalResolver.kt`, C`language/en/EnglishCalendarLanguage.kt:217-268` — read-only reference.
- G`assistant/HealthEvidence.kt:101-131`; fixtures `app/src/test/java/com/galaxyrio/gracelauncher/assistant/HealthFixtures.kt:19-30`.
- Tests: `app/src/test/java/com/galaxyrio/gracelauncher/magic/MagicAlarmCommandTest.kt` (fixed now = Berlin 2026-11-13 13:00), `app/src/test/.../assistant/HealthEvidenceTest.kt`, `app/src/androidTest/.../magic/MagicAlarmIntentTest.kt` (must still compile).
- `res/values/strings.xml:636-648`; `tasks.md` (Pending section).

## Acceptance Criteria
- `MagicAlarmCommand.parse` has no `language` parameter; a device locale of `de-DE` no longer affects alarm parsing.
- At fixed now 13:00 Berlin: `alarm 7`→19:00 today; `alarm 7:30`→19:30 today; `alarm at 7:00`→19:00 today; `alarm at 8:15`→20:15 today; `alarm quarter to 8`→19:45 today; `alarm half past seven`→19:30; `alarm 7am`→07:00 tomorrow; `alarm 07:30`→07:30 tomorrow; `alarm 19:30`→19:30 today; `alarm Breakfast 7:30am`→07:30 tomorrow, message Breakfast.
- At 05:00: `alarm 7`→07:00 today. At 20:00: `alarm 7`→07:00 tomorrow. At exactly 13:00: `alarm 13:00`→null.
- Named day at 13:00: `alarm tomorrow 7am`→Alarm 07:00 tomorrow; `alarm tomorrow 19:30`→`WrongDay(19,30,tomorrow=false)`; `alarm today 7am`→`WrongDay(7,0,tomorrow=true)`. At 22:00 `alarm tomorrow 7am`→Alarm 07:00 tomorrow. At 20:00 `alarm tomorrow 7`→07:00 tomorrow. At 06:00 `alarm tomorrow 7`→`WrongDay`. A weekday case: weekday = tomorrow and time already passed today → Alarm; weekday 3+ days ahead → `WrongDay`.
- All existing null cases in `MagicAlarmCommandTest` still return null; all timer cases unchanged.
- Preview shows `Alarm · HH:MM today|tomorrow`; `WrongDay` shows the refusal in preview and as status on submit, with no intent fired.
- Health: `steps:{avg:null,min:null,max:null,count:0}` renders "no data" with no zero flag; same for sleep nightly stats and resting HR; avg present + min/max null renders avg only, no flag, no `0`.
- SMS: when `openPreferredMessageDraft` returns FAILED after NoSim/Unavailable, status remains `magic_no_app`; when it opens, status is `magic_sms_fallback` (unchanged).
- No changes to intent extras, timer parsing, legacy `ActionRequestPolicy`/`alarmOccurrence` behavior, SMS send rules.

## Tests to Add/Run
- Rewrite/extend `MagicAlarmCommandTest` with every case in Acceptance Criteria (pass `nowMillis` + Berlin `zoneId`). Add a test asserting parse result is the same regardless of locale (now that language is gone, a simple `alarm 7am` case suffices; delete any `language` args).
- Extend `HealthEvidenceTest`: null/empty-object aggregates for steps, sleep, resting HR; avg-only case.
- Run with `ANDROID_USER_HOME=/Users/susuresh/.android` and JDK 21:
  - `bash ./gradlew :app:testDebugUnitTest`
  - `bash ./gradlew :app:lintDebug`
  - `bash ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`
- Report pass/fail counts. No emulator/device/adb runs; no `connectedDebugAndroidTest`.

## Scope Guardrails
- Do not touch Calendar language/engine files under C (read-only reference), `ActionInputValidation.alarmOccurrence`/`parseSpokenClockTime` behavior, `ActionRequestPolicy`, legacy `AssistantController.infer()`/`AlarmSkill`.
- Do not change `MagicActions` intent extras or timer behavior.
- Do not change SMS send/role/permission/confirmation logic — only the status line after a failed fallback.
- Health: only rendering of the three aggregate metrics; no changes to HealthMcpClient, vault, token budget, weight rendering.
- No version bump, no signing, no release build, no commit/push, no emulator/device commands.
- No new dependencies, no new files except tests if needed.

## Open Questions & Risks
- Determining "named day present" from Calendar's draft: the implementer must pick the simplest reliable signal; if the Calendar draft exposes nothing, a minimal day-token check (`today|tomorrow|tonight|monday..sunday|date patterns`) on `rest` is acceptable. Report which was used.
- DST gap/overlap days: `next()` via `ZonedDateTime.of` may shift a nonexistent local time; acceptable, not tested.
- Day-part words (`tonight`, `morning`) depend on what Calendar supports; we only promise to trust Calendar's hour when present.
- Device behavior (Clock app actually ringing, preview layout) is user-verified, not here.

## Implementation Task List
- [ ] Read `MagicAlarmCommand.kt`, its test, `MagicCommandWindow.kt` alarm/SMS parts, `HealthEvidence.kt:95-131`, `HealthEvidenceTest`.
- [ ] Write the new/changed `MagicAlarmCommandTest` cases first; see them fail.
- [ ] Remove `language` param, force `"en"`; add `tomorrow`, `WrongDay`, `next()`, ambiguity detection, named-day check.
- [ ] Update window preview/submit for `WrongDay` and day string; drop unused `language` val.
- [ ] Update/add strings.
- [ ] SMS fallback status fix.
- [ ] Health: write null/avg-only tests first, then fix rendering and flags.
- [ ] Run JVM tests, lint, debug + androidTest assemble; fix until green.
- [ ] Add a short dated entry to `tasks.md` (implemented vs verified; device check pending by user).
- [ ] Report: files changed, test counts, named-day signal chosen, anything deviating from the plan.
