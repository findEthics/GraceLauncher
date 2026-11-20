# Memory chips, slash-command popup, manual sync & memory instructions

## Overview
This is a follow-up to the assistant-memory work (1.2.12/20, uncommitted tree). It covers six user requests:
1. Rename the confusing memory chips (Delete → Forget, Erase → Purge).
2. Add a Recall chip.
3. Add concise memory usage instructions plus manual Supabase delete SQL in Advanced, and add a repo SQL file.
4. Add a "Sync memory now" option in Advanced → Offline assistant → Memory.
5. Add a coding-agent-style `/` command popup in the chat composer.
6. Include /web, /wikipedia and /health in that popup, so a physical keyboard can select sources.

Use **test-driven development** (skill `test-driven-development`): write a failing JVM/pytest test first for each logic slice, then implement. Record RED→GREEN per slice in `tasks.md`.

## Business & UX Decisions
- **Chip renames.**
  - The two chips are not redundant. "Delete" is a soft delete (status INVALIDATED; history kept; server `memory_forget`). "Erase" is a hard purge (whole supersession chain physically removed plus a tombstone; needs confirmation).
  - The labels were confusing, so the names now match the commands:
    - `memory_chip_delete` → EN **"Forget from memory"**, DE **"Aus Gedächtnis vergessen"**.
    - `memory_chip_purge` → EN **"Purge from memory"**, DE **"Endgültig aus Gedächtnis löschen"**.
  - Behaviour is unchanged. Renaming resource keys is optional; changing the values is enough.
- **Recall chip (new).**
  - EN **"Recall from memory"**, DE **"Aus Gedächtnis abrufen"**.
  - It is a one-shot memory action like Save/Forget/Purge: independent of the Web/Wikipedia/Health source, and cleared on submit.
  - Submitting text runs exactly what `/recall TEXT` does. Submitting an **empty** composer with the Recall chip selected must be allowed and runs `/recall` (10 newest).
  - Output: the existing numbered "Saved notes:" cards.
- **`/` popup trigger.**
  - It opens only while the **entire** composer text matches `^/[A-Za-z]*$` (no space or newline yet).
  - It closes once a space is typed, so `/remember milk` keeps working as today.
  - If nothing matches, it hides and Enter submits normally.
- **Rows** (fixed order; each row shows the command plus a one-line localized description):
  `/web`, `/wikipedia`, `/health` (only when `healthAvailable`), `/remember`, `/recall`, `/forget`, `/purge`, `/history`.
- **Matching.**
  - Case-insensitive prefix match on the canonical name **or** a hidden alias. Aliases: `/save`→remember, `/delete`→forget, `/erase`→purge, `/wiki`→wikipedia. Aliases never get their own rows.
  - A lone `/` shows all rows. Order always follows the fixed order above.
- **Keys** (mirror the Home `@` panel):
  - First row highlighted by default.
  - Up/Down move with clamping. Tab/Shift+Tab cycle with wrapping and keep composer focus.
  - **Enter, NumpadEnter or a row tap applies the highlighted row.** Shift+Enter still inserts a newline.
  - **Escape** closes only the popup; the text stays, and the popup reopens on the next text edit.
  - Back keeps its current close-chat behaviour.
  - The popup renders directly above the composer, inside the chat overlay.
- **Applying a row turns it into the chip.**
  - The `/…` text is cleared and the matching chip is selected, exactly as if chosen from the `+` menu:
    - `/web` → Web, `/wikipedia` → Wikipedia, `/health` → Health. The source persists for the session, same as the chip; the sources are mutually exclusive and replace each other.
    - `/remember` → Save, `/recall` → Recall, `/forget` → Forget, `/purge` → Purge. These are one-shot and replace any selected memory chip.
  - `/history` has no chip: it inserts the text `"/history "`, cursor at the end, and the popup closes because of the space.
- **Typed source commands on submit.**
  - A submitted message whose first token is `/web`, `/wikipedia`, `/wiki` or `/health` followed by whitespace and text does three things:
    1. selects that source for the session;
    2. strips the token;
    3. sends the remaining text through the normal path.
  - `/health …` without a saved token stays ordinary text.
  - A bare `/web` with no remaining text just selects the chip and sends nothing; the popup normally handles this case anyway.
  - Typed memory commands (`/remember milk`, etc.) keep working unchanged.
- **Sync memory now** (Advanced → Offline assistant → Memory, **outside** the instructions block). A button "Sync memory now" / DE "Gedächtnis jetzt synchronisieren". When tapped:
  1. It re-queues orphans: every mirror row with `changeSeq == 0` (never confirmed by the server) and no outbox op for its id. Re-push is safe because the server ignores a repeated id for the same owner.
  2. It calls the existing sync (push, then pull).
  3. It shows a one-line result: synced / failed (offline or server) / local only (no token saved), and refreshes the status line.
  - The button is disabled while running.
  - Also: **saving a Health token triggers a memory sync**. Today it doesn't, so notes queued before a token existed sit unsynced.
- **Instructions.**
  - A collapsible row "How to use memory" (DE "So nutzt du das Gedächtnis") under the Memory heading, **collapsed by default**. The text must be **concise bullet points**, EN and DE. Content:
    - The chips and their `/` equivalents. One bullet each: remember/save, recall, forget (soft, history kept), purge (permanent, confirm), history N.
    - Type `/` in chat to open the command list. /web, /wikipedia and /health select sources.
    - Local only vs synced: needs a Health token; use "Sync memory now".
    - Privacy: memory is never sent into Web/Wikipedia tool calls.
    - A sub-part "Delete directly in Supabase" with a one-line warning: sync the phone first so nothing is still queued, and a queued save of the same note can recreate it. Then the 4 SQL queries in monospace, long-press-selectable text (`SelectionContainer`). The SQL is identical in EN and DE.
  - The existing `assistant_chat_hint` string also gets a short mention of `/` commands and Health.
- **Manual delete SQL.**
  - It must be tombstone-safe. A plain DELETE leaves phones with stale mirror copies forever, and an outstanding REMEMBER can recreate the row.
  - The `memory_purge` RPC cannot run in the SQL editor because `_caller_user_id()` requires the `x-grace-token` header. The queries therefore replicate purge using the owner from `public.personal_owner`.

## Technical Decisions
- **Slash command model (pure Kotlin, JVM-testable).** Suggested new file `assistant/SlashCommands.kt`:
  ```kotlin
  enum class SlashCommand(val token: String, val aliases: List<String>) {
      WEB("web", emptyList()), WIKIPEDIA("wikipedia", listOf("wiki")), HEALTH("health", emptyList()),
      REMEMBER("remember", listOf("save")), RECALL("recall", emptyList()),
      FORGET("forget", listOf("delete")), PURGE("purge", listOf("erase")), HISTORY("history", emptyList())
  }
  /** Rows for the popup, or empty when the popup must be hidden. */
  fun slashSuggestions(input: String, healthAvailable: Boolean): List<SlashCommand>
  /** Leading /web|/wikipedia|/wiki|/health + whitespace + rest → (source, rest) else null. */
  fun parseSourcePrefix(input: String, healthAvailable: Boolean): Pair<ChatSource, String>?
  ```
  - `slashSuggestions` returns an empty list unless `input` matches `^/[A-Za-z]*$`.
  - Keep descriptions in string resources and map them in the UI.
- **Chip mapping.** Add `Recall` to the memory chip enum in `assistant/memory/MemoryCommand.kt`, next to Save/Delete/Erase. Wherever the chip plus composer text is converted into a `MemoryCommand`, Recall plus text maps to `MemoryCommand.Recall(text)`, and Recall with blank text maps to the blank recall.
  - The submit guard that blocks empty input must allow blank input **only** when the Recall chip is selected.
  - Keep Enter, IME Send and the busy-guard semantics.
- **Typed source prefix.** Apply `parseSourcePrefix` in the submit path **before** memory-command parsing and source routing. Set `chat.source`, then continue with the stripped text. `!` handling is a Home path, not a chat path; don't touch it.
- **Popup UI.** It lives inside `AssistantChatWindow` (`assistant/AssistantUi.kt`), above the `OutlinedTextField`.
  - State: highlighted index, plus a `dismissed` flag that resets on any text change.
  - Extend the existing `onPreviewKeyEvent`. Up/Down/Tab/Shift+Tab/Enter/NumpadEnter/Escape are consumed only while the popup is visible; consume down and up like the existing Enter handling and ignore repeats. Otherwise behaviour is unchanged.
  - Copy the row and highlight styling pattern from `magic/MagicCommandWindow.kt:437–466`; don't refactor that file.
  - Rows need accessible content descriptions/semantics.
  - Add stable test tags such as `assistant_slash_popup` and `assistant_slash_row_<token>`.
- **Orphan re-queue** (`assistant/memory/MemorySync.kt` plus the store):
  - Add a function, e.g. `requeueUnconfirmed(): Int`. It finds mirror rows with `changeSeq == 0` whose id has no outbox op and enqueues the same REMEMBER op the original save would have produced. Reuse the existing payload builder; don't hand-build JSON. It returns the count.
  - The runtime exposes e.g. `MemoryRuntime.syncLocalNow(): Result<SyncOutcome>`, which runs re-queue then `sync.sync()` under the existing mutex/IO context.
  - `changeSeq == 0` rows that are INVALIDATED (soft-deleted before ever syncing) also need a FORGET op after the REMEMBER, so the server ends up invalidated too. If the existing op model makes that awkward, enqueue REMEMBER only and record it as a known limitation in `tasks.md`.
  - Rows rejected permanently by the server will be dropped again; that is acceptable (see Risks).
- **Token-save trigger.** After a successful Health token save in `HealthSettings`, call the memory runtime sync, fire-and-forget on the existing scope. Fix the inaccurate comment at `MemoryRuntime.kt:38`.
- **Status refresh.** The Advanced status is read once today (`LaunchedEffect(Unit)`). Re-read it after the sync button completes, e.g. by keying the effect on a counter. No new Flow is needed.
- **SQL file.** Create `server/supabase/grace_memory/manual-delete.sql`. It is not numbered, so the migration tooling and the checklist must not treat it as a migration. It holds 4 commented queries:
  1. List notes: `select id, type, subject, body, status, occurred_at, valid_to, created_at from grace_memory.memory_entries where user_id = (select user_id from public.personal_owner where singleton is true) order by created_at desc;`. Confirm the real column names from `001_schema.sql`.
  2. Purge one chain by id: a replica of the `memory_purge` recursive CTE in `003_functions.sql:140–154`. Use `v_uid` = the owner subquery and `p_id` = a `'<NOTE-ID>'::uuid` placeholder: one DELETE plus a `purged_ids` insert in a single statement. Keep `union` for cycle safety.
  3. Purge all for the owner: `with del as (delete … where user_id = owner returning id) insert into grace_memory.purged_ids(id,user_id,change_seq) select id, owner, 0 from del on conflict (id) do update set purged_at = now() where grace_memory.purged_ids.user_id = excluded.user_id;`. Deleting all rows in one statement satisfies the NO ACTION self-FKs.
  4. List tombstones.
  - Header comment: run in the Supabase SQL editor as the project owner; sync phones first; never touch `public.user_memories`; this does not update `mcp_tokens`.
  - The `change_seq` BEFORE INSERT trigger assigns the real sequence, so `0` is fine.
  - Reference it from `APPLY-CHECKLIST.md` with one line.
  - The in-app SQL text must match the file (minus the long comments). A JVM test reads the file and checks that each in-app query string is a substring, after whitespace normalization, so they can't drift.

## Relevant Files
- `app/src/main/java/com/galaxyrio/gracelauncher/assistant/AssistantUi.kt`: chips/`+` menu (~:81–230), composer key handling (:193–207), `AssistantSettings` (:479–526), Health settings (:391–427), memory status (:377–387).
- `assistant/AssistantChatState.kt`: `ChatSource` (WEB/WIKIPEDIA/HEALTH, exclusive), `memoryChip`.
- `assistant/AssistantController.kt`: submit/routing (:165–166, :273 chip clear, :290–292 sync after mutation), health availability (:50–54, :120).
- `assistant/memory/MemoryCommand.kt` (chip enum :5, parser :28–49), `MemoryTurns.kt`, `MemorySelect.kt`, `MemorySync.kt` (record :46–50, status :53–57, sync :60–111), `MemoryRuntime.kt` (:15–59), `RoomMemoryStore.kt`, `MemoryMirrorDatabase.kt` (outbox :51–63).
- `magic/MagicCommandWindow.kt` (:319–338, :437–466, :543–563): keyboard and row pattern to copy, not modify.
- `app/src/main/res/values/strings.xml` (:523–597 assistant, :679–684 memory), `values-de/strings.xml` (:430–432).
- `server/supabase/grace_memory/{001_schema,003_functions}.sql`, `APPLY-CHECKLIST.md`, `tests/` (pytest, docker postgres:16, `conftest.py`, `stub_dashboard.sql`).
- Tests: `app/src/test/java/com/galaxyrio/gracelauncher/assistant/memory/{MemoryParseTest,MemoryTurnsTest,MemorySyncTest}.kt`; `app/src/androidTest/java/com/galaxyrio/gracelauncher/assistant/AssistantUiTest.kt`.

## Acceptance Criteria
1. The memory chips read Save / Recall / Forget / Purge (EN) with the German equivalents above. Forget and Purge behaviour is unchanged (soft delete vs confirmed chain purge).
2. The Recall chip plus text produces the same cards as `/recall text`. The Recall chip plus empty input submits and lists up to 10 newest. The chip clears after submit. Empty submit is still blocked when no Recall chip is selected.
3. Typing `/` as the first character shows the popup with all rows in fixed order. `/health` appears only when a Health token is saved.
4. Prefix and alias matching: `/w` → web, wikipedia; `/wi` → wikipedia; `/del` → forget; `/er` → purge; `/sa` → remember; `/xyz` → no popup, and Enter submits normally.
5. A space or newline in the text, or text not starting with `/`, hides the popup.
6. Up/Down clamp, Tab/Shift+Tab wrap, Enter, NumpadEnter or a tap applies the highlighted row (the first by default), Escape hides the popup until the next edit, and Shift+Enter inserts a newline.
7. Applying a source row selects that exclusive source for the session and clears the text. Applying a memory row selects that one-shot memory chip and clears the text. `/history` inserts `"/history "`.
8. Submitting `/web tokyo weather` (likewise `/wikipedia`, `/wiki`, `/health` with a token) selects the source and sends `tokyo weather`. `/health x` without a token is sent as ordinary text. `/remember milk` is unchanged.
9. Advanced → Offline assistant → Memory has a "Sync memory now" button outside the instructions. It re-queues unconfirmed orphan rows, syncs, shows the outcome (synced / failed / local only), refreshes the status line, and is disabled while running.
10. A successful Health token save triggers a memory sync.
11. A collapsed-by-default "How to use memory" block with concise EN/DE bullets covers chips, `/` commands, local vs synced, the privacy note, and the Supabase SQL section with its warning and the 4 selectable monospace queries.
12. `server/supabase/grace_memory/manual-delete.sql` exists with the 4 queries, is referenced from `APPLY-CHECKLIST.md`, and its purge queries delete whole chains and write `purged_ids` tombstones.
13. JVM tests, `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest` and the pytest suite pass. The filtered `AssistantUiTest` passes on 5590.

## Tests to Add/Run
TDD: write each test RED first, then implement to GREEN.
- **JVM `SlashCommandsTest`** (new, `app/src/test/java/com/galaxyrio/gracelauncher/assistant/`): trigger regex, lone `/`, fixed order, aliases, health gating, case-insensitivity, space/newline hides, no-match empty; `parseSourcePrefix` cases from AC 8, including bare `/web`, `/web` without a space (null), `/health` without a token (null), `/webx foo` (null).
- **JVM memory chip mapping** (`MemoryParseTest` or `MemoryTurnsTest`): Recall chip plus text equals `/recall text`; Recall chip plus blank equals the blank recall; Forget/Purge chips still map to Delete/Erase commands.
- **JVM `MemorySyncTest`**:
  - `requeueUnconfirmed` enqueues only `changeSeq == 0` rows without an outbox op;
  - it is idempotent (a second call returns 0);
  - rows with `changeSeq > 0` are skipped;
  - `syncLocalNow` with no RPC returns LocalOnly and keeps the outbox;
  - INVALIDATED unconfirmed rows behave as designed (or the limitation is recorded).
- **JVM SQL drift test**: the in-app SQL string resources are contained in `manual-delete.sql` (whitespace-normalized). Read the file via a relative path from the module, the same way the existing tests load resources, or copy it into test resources only if necessary.
- **pytest** `server/supabase/grace_memory/tests/test_07_manual_delete.py`, using the existing conftest fixtures:
  - seed an owner plus a 3-row supersession chain plus an unrelated note plus another user's note;
  - run query 2 for the chain: all 3 rows deleted, 3 tombstones, unrelated rows intact, `memory_changes` reports the tombstones;
  - run query 3: all owner rows deleted and tombstoned, the other user's rows untouched;
  - extract the queries from the SQL file (split on marker comments) rather than duplicating them.
- **Compose `AssistantUiTest`** additions: popup shows on `/`; filtering; Enter applies the first row (source chip shown, text cleared); Down then Enter picks the second; Escape hides; a space hides; `/history` inserts text; the Recall chip appears in the `+` menu; empty submit with the Recall chip is allowed.
- **Run:**
  - `bash ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`, with JDK 21 at `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` and `ANDROID_USER_HOME=/Users/susuresh/.android`;
  - the pytest suite in `server/supabase/grace_memory/tests` (uv, docker postgres:16);
  - on **emulator-5590 only**: verify the APK cert is `f1849f41…`; `adb -s emulator-5590 install -r` the debug APK and `install -t -r` the test APK; then `adb -s emulator-5590 shell am instrument -w -e class com.galaxyrio.gracelauncher.assistant.AssistantUiTest <test-runner>`. Report pass/fail.
- No visual/screenshot verification; the user does visual QA.

## Scope Guardrails
- **Never** run `connectedDebugAndroidTest` (it would wipe 5590's retained models). Never uninstall, clear or wipe any emulator. Don't touch 5558, Titan or other emulators. No release build and no version bump (stay 1.2.12/20).
- No commits or pushes. Don't stage or alter the pre-existing unrelated changes (`README.zh-CN.md` deletion, older handoffs/plans).
- Don't change server migrations `001–003`, RPCs, grants or RLS. Don't run SQL against the live Supabase. Never use the service-role key or touch `public.user_memories` / `mcp_tokens`. Never log or commit tokens, `.env` values or the Supabase host (don't put the host in the instructions text).
- Don't change Forget/Purge semantics, the confirmation flow, the privacy gate (memory never in tool-enabled passes), the Home `@`/`#`/`+`/`?`/`!` routes, or `MagicCommandWindow.kt`.
- No German command aliases, no auto-sync toggle, and no new Flow/observable status layer.
- Keep one `strings.xml` per language with section comments.

## Open Questions & Risks
- Notes the server permanently rejects will be re-queued and dropped again on each button tap. The outcome may still say "synced" while they stay local. Acceptable; log it in `tasks.md`. Surfacing a rejected count is optional, only if `SyncOutcome` already makes it trivial.
- Manual SQL purge while a phone still has a queued REMEMBER for the same id can recreate the note: the server blocks only other owners' tombstones. Mitigated by the "sync first" warning, not fixed server-side (out of scope).
- `changeSeq == 0` is the only "unconfirmed" signal. A row the server accepted whose pull hasn't happened yet is also 0, but re-push is idempotent, so that's harmless.
- Long SQL in Advanced is awkward on a phone. That's why it sits collapsed and selectable.

## Implementation Task List
- [ ] RED/GREEN `SlashCommands.kt` plus `SlashCommandsTest` (suggestions, aliases, health gating, `parseSourcePrefix`).
- [ ] RED/GREEN Recall chip enum plus chip→command mapping plus blank-submit allowance; rename the Forget/Purge string values (EN/DE).
- [ ] Wire `parseSourcePrefix` into the submit path before memory parsing.
- [ ] Build the popup in `AssistantChatWindow`: state, key handling, row tap, chip application, `/history` insertion, test tags and semantics.
- [ ] Add the Recall chip to the `+` menu and selected-chip rendering.
- [ ] RED/GREEN `requeueUnconfirmed` plus `syncLocalNow` in `MemorySync`/`MemoryRuntime`; fix the `MemoryRuntime.kt:38` comment.
- [ ] Advanced: "Sync memory now" button plus result text plus status refresh; trigger sync after Health token save.
- [ ] RED/GREEN `manual-delete.sql` plus `test_07_manual_delete.py`; add one line to `APPLY-CHECKLIST.md`.
- [ ] Advanced: collapsible "How to use memory" bullets (EN/DE) plus SQL section (`SelectionContainer`, monospace); SQL drift JVM test; update `assistant_chat_hint`.
- [ ] Add the Compose tests to `AssistantUiTest`.
- [ ] Run JVM tests, lint, assembleDebug, assembleDebugAndroidTest and pytest; do the filtered `AssistantUiTest` run on 5590 (install -r / -t -r, verify the cert first).
- [ ] Update `tasks.md` (implemented vs verified, RED→GREEN evidence, limitations) and the `AGENTS.md` current-state bullet briefly.

---

# Follow-up 1 (2026-11-20): single-line slash rows + collapsible Advanced help

The main plan is implemented; its Scope Guardrails still apply. The user accepted the 3 pre-existing `AssistantUiTest` failures (Gemma4 selection, the scroll lookup, the 48dp touch-target height). Don't try to fix them.

## Business & UX Decisions
- **Slash popup rows on one line**, to save screen space. Show the command (current `titleSmall`), then the description (`bodySmall`, muted) on the same line, e.g. `/web  Search the web`. The description uses `maxLines = 1` with an ellipsis. Keep the ≥48dp row height, selection highlight, tags `assistant_slash_row_<token>` and the contentDescription `"/command, description"`.
- **Offline assistant help collapsible (new).**
  - Place a new toggle **below** "How to use memory" (after the existing divider): EN **"How to use the assistant"**, DE **"So nutzt du den Assistenten"**. Collapsed by default.
  - Move these four always-visible texts inside it: `assistant_privacy`, `assistant_show_home_button_hint`, `assistant_chat_hint`, `assistant_direct_actions`.
  - `assistant_health_hint` stays beside the Health token field (it's tied to that control).
- **Keyboard shortcuts collapsible.** In `MagicAdvancedSettings`, keep the Messaging preferences button and the "Keyboard commands" (`magic_title`) heading. The `magic_help` text goes under a collapsed toggle: EN **"How to use keyboard commands"**, DE **"So nutzt du Tastaturbefehle"**. `magic_automatic_hint` stays in the dialog.
- **Rewrite the moved texts as concise bullet points** (EN and DE), as in `memory_help_body`: `assistant_privacy`, `assistant_show_home_button_hint`, `assistant_chat_hint`, `assistant_direct_actions`, `magic_help`. **Keep every fact** (shortcuts @ # + ! ? -/Alt+T ,/Alt+N/Alt+A Space, examples, nav/edit keys, permission/provider behaviour, drafts, SMS safeguards, privacy/network statements). Shorten only the wording, and don't drop any facts. Use the same bullet character and format as `memory_help_body`.

## Technical Decisions
- Extract the memory toggle in `assistant/AssistantUi.kt` (`MemorySettings`, ~:465–492) into one small `internal` composable, e.g. `HelpSection(title: String, toggleTag: String, contentTag: String, content: @Composable ColumnScope.() -> Unit)`, put in `assistant/AssistantUi.kt` (or a tiny shared `ui/` file if `magic/` can't reach it, since it is `internal` in the same module). It keeps the full-width ≥48dp TextButton, ▾/▸ prefix, `stateDescription` expanded/collapsed, `remember { mutableStateOf(false) }`, and no animation.
- Reuse it for memory (its tags stay `assistant_memory_help_toggle` / `assistant_memory_help`), assistant (`assistant_help_toggle` / `assistant_help`) and keyboard (`magic_help_toggle` / `magic_help_section`).
- Only the help-text placement changes inside `magic/MagicCommandWindow.kt` `MagicAdvancedSettings` (~:644–653). The main plan's no-touch rule for that file is relaxed for this purpose only; keep all command logic untouched.

## Acceptance Criteria
1. Slash rows show the command and description on one line, with the description ellipsized; tags, semantics and keys are unchanged; the existing slash tests still pass.
2. Advanced → Offline assistant shows, in order: … Memory section (with "How to use memory") → divider → a collapsed "How to use the assistant" containing the four bullet-rewritten texts. No help text is always visible apart from the Health hint and the control labels.
3. Advanced → Keyboard shortcuts shows the Messaging preferences button, the "Keyboard commands" heading and a collapsed "How to use keyboard commands" containing the bullet-rewritten `magic_help`.
4. All three sections share one helper, start collapsed, and expand/collapse on tap with correct `stateDescription`.
5. EN and DE strings were updated, and the rewritten texts keep all facts.
6. JVM tests, lintDebug, assembleDebug and assembleDebugAndroidTest pass; the filtered 5590 runs pass apart from the 3 accepted pre-existing failures.

## Tests to Add/Run
- Update `AssistantUiTest.offlineAssistantExposesGemma4WithoutSpeechSetupOrKeyboardInstructions` to click `assistant_help_toggle` before asserting `assistant_chat_hint` (the Gemma4 selection failure is still accepted).
- Update `LauncherSettingsIntegrationTest.graceSettingsOpensFullScreenSettingsAndEachCategoryNavigatesBack`. On Keyboard shortcuts, `magic_help` is absent while collapsed and present after clicking `magic_help_toggle`. It stays absent on Offline assistant.
- Add a Compose test, e.g. `advancedHelpSectionsStartCollapsedAndExpand`, covering the assistant and memory toggles: collapsed means the content tag doesn't exist; a click makes it exist, with `stateDescription` "expanded".
- Run: `bash ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` (JDK 21, `ANDROID_USER_HOME=/Users/susuresh/.android`).
- **emulator-5590 only**: verify the cert is `f1849f41…`, then `install -r` / `install -t -r`, then filtered `am instrument` for `AssistantUiTest` and `LauncherSettingsIntegrationTest`. **Never** `connectedDebugAndroidTest`. Report pass/fail per test; the 3 accepted failures are expected.
- No visual or screenshot checks.

## Scope Guardrails
- Stay at version 1.2.12/20. No commits, release builds or other devices. Don't change command or keyboard behaviour in `MagicCommandWindow.kt`, the slash matching logic, or memory sync.
- Don't move `assistant_health_hint` or `magic_automatic_hint`.

## Implementation Task List
- [ ] Make the slash row single-line (Row plus ellipsized description).
- [ ] Extract the `HelpSection` helper and migrate the memory help to it (tags unchanged).
- [ ] Add the "How to use the assistant" section holding the 4 texts; add EN/DE titles.
- [ ] Add the Keyboard shortcuts "How to use keyboard commands" section around `magic_help`; add EN/DE titles.
- [ ] Rewrite the 5 texts as concise bullets in EN and DE (no lost facts).
- [ ] Update and add the androidTests; run the builds/tests and the filtered 5590 runs.
- [ ] Update `tasks.md` briefly (implemented vs verified).
