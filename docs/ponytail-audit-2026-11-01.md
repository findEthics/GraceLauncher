# Ponytail audit — 2026-11-01 (partial)

What this repo does: Grace is an Android Home launcher with app/widget layout, notification previews/replies, and offline assistant chat plus explicitly authorized native actions. Assumed load: one phone, hundreds of apps, separate Home/settings ViewModels, asynchronous system callbacks and background downloads—not server traffic.

## Scope and status

Read-only audit after the notification fixes. **Incomplete; not a clean repo-wide verdict.** Two reviewers inspected assistant and notification flows. The persistence/UI/build reviewer failed before returning evidence; no automatic restart was attempted. Weather/private-space/manifest boundaries and most database/widget internals remain unchecked.

The notification reviewer found no defect in the new Home bulk-dismiss path. Its per-item counterpart has the older inconsistency below. Audit findings were not implemented.

## Should fix

1. **Popup dismissal can clear unseen messages** (`app/src/main/java/com/galaxyrio/gracelauncher/data/notifications/AppNotificationStore.kt:115–122,184–192`)
   - **What this is:** Group summaries are shown when no eligible child notification is displayed. Popup swipes and auto-cancel opening still use single-item `dismiss()`.
   - **Problem:** If the only child is secret or ranking-hidden, the summary is visible; cancelling that summary can also clear its hidden child. The new Home bulk path checks raw children, but this existing path does not. Code-traced; Android group cancellation was not reproduced on a device.
   - **Fix:** Block direct summary cancellation while raw children remain. Keep a private guarded cancellation path for bulk dismissal, which already verifies that every child was selected and accepted.
   - **If we skip it:** A popup swipe/open can remove a message the launcher never displayed.

2. **Deleting a model leaves Home's engine loaded** (`app/src/main/java/com/galaxyrio/gracelauncher/assistant/AssistantController.kt:43–46,71–79,210–218`)
   - **What this is:** Home and settings own separate assistant controllers and native inference engines.
   - **Problem:** Delete unloads only the settings controller. An already-loaded Home engine receives no deletion invalidation and remains resident; after re-download, the same model enum also permits reuse of that old engine. The readiness check still prevents inference while model files are missing. Retained-engine behavior is code-traced; retained native memory/disk usage was not measured.
   - **Fix:** Publish model deletion through shared observable state and invalidate/unload that model in every controller, using their existing engine locks.
   - **If we skip it:** “Model removed” does not necessarily release Home's native resources, and replacement files need not recreate its engine.

## Nice to have

3. **A fully downloaded partial file can force a full re-download** (`app/src/main/java/com/galaxyrio/gracelauncher/assistant/ModelFiles.kt:90–105,137–144,169–171`)
   - **What this is:** Interrupted model downloads resume with an HTTP byte-range request.
   - **Problem:** A crash after the last file sync but before publication leaves a complete partial file. If the server answers the resulting end-of-file range with HTTP 416, the code reports failure and deletes the complete download. The crash/server combination was not reproduced.
   - **Fix:** When partial length equals expected length, verify its hash and publish it without another request. Add one recovery test.
   - **If we skip it:** A rare interruption can waste a complete multi-gigabyte download and require another user retry.

## Evidence and limits

- Notification store, listener and Home action wiring inspected. Raw membership protects filtered children; revision/profile/ranking checks and callback-owned removal remain intact. Existing popup child-only semantics remain unchanged.
- Assistant controller/session/chat state, request policy, native action executor/tools, alarm skill and model-file flows inspected. Validation/manual dispatch guards and download host/hash boundaries were traced. Most assistant tests, build/config files and provider boundaries were not reviewed.
- Parent verification: debug APK/test APK assembly, lint, and 94/94 JVM tests passed. API 36 temporary emulator storage: 64 passed, one managed-profile inventory assumption skip, zero failures across notification store/UI/profile classes. These tests are not a full repo or live WhatsApp test.
- Rounded reply field visually checked in instrumentation screenshot; it matches the assistant's 28dp corner radius.
- No audit-triggered code changes, signing, release build, commit or push.

Verdict: Fix the older single-summary cancellation guard first. Complete the missing audit areas before treating this as repo-wide coverage.

Not checked: live WhatsApp/system-listener grouped removal, real managed-profile PendingIntents, physical Titan/GPU, release inference, download crash recovery, most persistence/migrations/widget internals, weather/private-space trust and manifest boundaries. Further audit/relaunch requires approval after the incomplete/failed reviewer results.
