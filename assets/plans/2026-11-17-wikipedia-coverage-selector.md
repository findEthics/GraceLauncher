# Wikipedia evidence coverage selector + diagnostic cleanup + signed 1.2.9/17

## Overview
Grace Launcher's on-device Gemma4 assistant answers Wikipedia questions via two native tools: `query_wikipedia` (discovery: lead + heading index) then `read_wikipedia_sections` (1–2 revision-pinned sections). Section HTML is parsed by `WikipediaContent.extract(html, focus, maxUtf8Bytes)` into "candidate chunks" (paragraphs, list items, table rows with header/legend context, each prefixed with rendered `Section: …` context), then a subset is selected to fit a tight UTF-8 budget (~569 bytes of content per section in practice).

Runtime failure (emulator-5558, "Who won Best Actor and Best Actress at the 2026 Oscars?"): retrieval succeeded (4× HTTP 200, page 79369474 rev 1381253826) but the answer lacked both winners. Deterministic replay proved the cause: ranking by distinct focus-term overlap count selected a ~450 B intro matching 3 terms, exhausting the budget (512 of 569 B) before two ~100 B fact captions matching 2 terms each; the second section got only 57 B. A density-only fix (score/bytes, "F1", currently uncommitted in the tree) fixed that but failed two gates: tiny one-term noise and unrelated fragments sharing matching section headings displaced the facts.

This task replaces the ranking/selection with a body-only, IDF-weighted budgeted maximum-coverage selector, removes temporary diagnostic logging, and produces/installs a signed production 1.2.9/17.

## Business & UX Decisions
- **Coverage selector chosen (user-approved)** over density+heading fix or revert. Reason: handles all three failure modes (long multi-term intro vs short facts; one-term noise; heading-inherited relevance) and avoids redundant chunks crowding out complementary facts, without dependencies, embeddings, domain rules or cap increases.
- **No domain heuristics.** No awards/year/name/"winner" keywords. Must generalize across query types.
- **Impossible 400-byte heading fixture:** the heading-sharing case needs 403 B (106+108 facts + 2×36 context + 4 separators + 113 partial marker) — infeasible at 400. User chose: assert both facts at real budgets 569/700/1024; at 400 assert best effort (≥1 fact kept, zero unrelated/heading-only fragments, truthful partial marker). Other fixtures keep 400 where feasible.
- **Heading-only matches** (focus terms appear only in inherited headings/context, no body overlap anywhere) → treat as zero overlap and use the **existing zero-overlap fallback unchanged** (document-order chunks + partial marker). Keeps the model seeing actual section content.
- **Scope:** fix + remove temporary diagnostics + full JVM suite + signed release build + same-cert update-install on 5558 + docs. User performs visual checks (Oscars, photosynthesis, Web) themselves.
- Version stays **1.2.9 / versionCode 17** (never distributed; only diagnostic same-version installs on 5558).

## Technical Decisions

### Selector algorithm (replace F1 comparator + greedy loop in `WikipediaContent.kt` ~90–130)
Keep candidate construction, rendering, separator/marker accounting, `compose()` (document-order output), final UTF-8 checks and all parser guards. Change only what is scored and how candidates are chosen.

1. **Separate evidence from context.** When building `Candidate` (~280–311 and table path ~314+/436–469), retain body-only token info in addition to the rendered text: `evidenceTerms: Set<String>` (normalized tokens of the chunk's own body, using the existing tokenizer/normalizer used by `score()`), and `bodyLength: Int` (body token occurrences). Inherited `Section:` headings and copied table-context/legend lines contribute **zero** to evidenceTerms but their bytes still count in `utf8Bytes`. For table rows, the row's own cells (including its header→value mappings as rendered for that row) count as body; repeated table header/legend context lines do not.
2. **Cost** = existing complete rendered `utf8Bytes + 2` (separator), exactly as packing already charges.
3. **Weights** (article/section-local): `N` = candidate count; `df[t]` = #candidate bodies containing `t`; `w[t] = ln(1 + (N - df[t] + 0.5)/(df[t] + 0.5))` (always > 0). `avgLen` = mean bodyLength (guard 0 → 1).
4. **Support** (binary-TF BM25-style): `support(c,t) = if t ∈ c.evidenceTerms then 1.0 / (1.0 + 1.2 * (0.5 + 0.5 * c.bodyLength / avgLen)) else 0.0`.
5. **Feasible** = candidates that individually fit the existing exact accounting (remaining content budget incl. reserved partial marker).
6. **Gate**: `overlap(c)` = |Q ∩ c.evidenceTerms|. `gate = min(2, max overlap among feasible)`. If `gate == 0` → existing zero-overlap fallback path unchanged (this covers heading-only matches). Else `eligible = feasible with overlap ≥ gate`.
7. **Greedy coverage**: `bestSupport[t] = 0` for t in Q. Loop: among eligible unselected candidates that still fit remaining bytes, `gain(c) = Σ_t w[t] * max(0, support(c,t) - bestSupport[t])`; require `gain > 0`; choose max `gain/cost` (compare via cross-multiplication `gainA*costB` vs `gainB*costA` on Double is acceptable; ties → higher gain, then lower original document order). Add, update `bestSupport[t] = max(...)`, subtract cost. Stop when none qualifies.
8. **Best singleton**: `single` = eligible feasible candidate maximizing `F({c}) = Σ_t w[t]*support(c,t)` (ties: fewer bytes, then document order). Compute `F(S)` for the greedy set similarly (`Σ_t w[t]*max support`). Return whichever has greater F (tie → fewer bytes, then the greedy set).
9. Compose selected in document order with existing marker/partial semantics; preserve existing untruncated path (no marker when everything fits).
10. Deterministic; O(candidates × |Q| × iterations), bounded by existing 512-candidate cap.

Pseudocode (Kotlin-ish sketch, adapt to existing types):
```kotlin
val q = focusTokens // existing normalized distinct tokens
val n = cands.size
val df = q.associateWith { t -> cands.count { t in it.evidenceTerms } }
val w = q.associateWith { t -> ln(1.0 + (n - df[t]!! + 0.5) / (df[t]!! + 0.5)) }
val avg = cands.map { it.bodyLength }.average().takeIf { it > 0 } ?: 1.0
fun sup(c: Candidate, t: String) =
    if (t in c.evidenceTerms) 1.0 / (1.0 + 1.2 * (0.5 + 0.5 * c.bodyLength / avg)) else 0.0
```

### Diagnostic removal (must be complete)
Remove all temporary diagnostics, following `app/build/reports/wikipedia-1.2.9/diagnostic/report.md:86–94` and `diagnostic-network/report.md:80–89`:
- `AssistantController.kt`: temporary observer construction (~284) and all emissions (~298–408).
- `WikipediaSearch.kt`: `WikipediaDiagnostic` class/`emit()`/`Log.i("WikiStage", …)` (~248–261), all stage emissions (~188–208, 270, 296–380, 500–508), reader diagnostic property (~396), `ioStage`, observer-only try/finally/catch wrappers and diagnostic return wrappers. Restore original direct fetch response-code check, constructor default `WikipediaSearch::fetch`, direct `request(url, limit)`, original timeout/cancellation/IOException handling.
- Delete only the temporary exception-classification test in `WikipediaReaderTest.kt` (~8–21).
- Do **not** reset whole files. Final grep for `TEMP_WIKI_DIAGNOSTIC`, `WikiStage`, `WikipediaDiagnostic`, `ioStage` in `app/src` returns nothing.
- **Keep**: `WikipediaQueryTools`, `WikipediaSearch.tools(firstLookup)` (~22–32,48), first-step query-only selector + lookup-first instruction in controller (~306,574), `app/proguard-rules.pro:10` keep rule, SDK enumeration test `WikipediaTurnTest.kt:13–28`, Android fixtures `WikipediaSearchTest.kt`.

### Release
- Env: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`, `ANDROID_USER_HOME=/Users/susuresh/.android`. Use long foreground timeouts for Gradle.
- `bash ./gradlew :app:assembleRelease` (unsigned) → `zipalign -P 16 -f 4` (Build Tools 36.1.0) → `apksigner sign --ks ~/.local/share/grace-launcher/signing/grace-launcher-current.p12 --ks-key-alias grace-launcher --ks-pass file:~/.local/share/grace-launcher/signing/grace-launcher-current.password` (omit `--key-pass`). Never print passwords/.env.
- Verify: `apksigner verify --verbose --print-certs` cert SHA-256 `7c928de3824f4c6d91056d5e5615c5cb5414fece82643c0930c0ca600f583a0b`; `zipalign -c -P 16 4`; package `com.galaxyrio.gracelauncher` 1.2.9/17 non-debuggable; SHA-256 + size; DEX retains `WikipediaQueryTools.query_wikipedia`, `WikipediaTools.query_wikipedia(topic,lang,focus)` and `read_wikipedia_sections(sections,focus)`, `WebSearchTools.search_web`, `@Tool`/`@ToolParam` runtime annotations, MethodParameters, LiteRT `onMessage`; no `WikiStage` string in DEX.
- Outputs: `app/build/outputs/apk/release/app-release-signed.apk` and identical `app-release.apk`. Before overwriting, ensure the superseded `5713142e…` candidate copy already exists under `app/build/reports/wikipedia-1.2.9/*/original-candidate-preserved.apk` (it does per reports); don't delete it.
- Install: target **only** `adb -s emulator-5558`. Verify installed cert matches first; `adb -s emulator-5558 install -r <signed apk>`. Never uninstall, clear data, redownload model, wipe/restart AVD. Pull installed base APK and confirm hash matches. If 5558 offline, stop and report (don't substitute other emulators).

## Relevant Files
- `app/src/main/java/com/galaxyrio/gracelauncher/assistant/WikipediaContent.kt` — candidate construction (~280–311, table ~314/436–469), selection (~90–136), `score()`/tokenize (~799–805, 863), `compose()` (~787–796).
- `app/src/test/java/com/galaxyrio/gracelauncher/assistant/WikipediaContentTest.kt` — F1 tests at 8, 12, 17, 22, 46; fixtures 59–75; existing parser guard tests.
- `app/src/main/java/com/galaxyrio/gracelauncher/assistant/WikipediaSearch.kt`, `AssistantController.kt` — diagnostic removal only.
- `app/src/test/java/com/galaxyrio/gracelauncher/assistant/WikipediaReaderTest.kt`, `WikipediaTurnTest.kt`.
- `app/proguard-rules.pro`, `app/build.gradle.kts` (version 1.2.9/17, verify only).
- Reports for context: `app/build/reports/wikipedia-1.2.9/{section-evidence-diagnosis,evidence-ranking-repair,diagnostic,diagnostic-network,diagnostic-lookup-fix}/report.md`.
- `tasks.md`, `AGENTS.md` — candidate/installed entries.

## Acceptance Criteria
- Selector implements steps 1–10 above; inherited headings/table context give zero relevance; gate excludes one-term chunks when a feasible ≥2-term chunk exists; heading-only/zero-body-overlap uses the unchanged fallback.
- No change to byte caps, parser memory/node/table guards, hidden/explicit-headers table rejection, network/HTTP limits, tool schemas, controller loop, prompts, or Web search.
- All tests in `WikipediaContentTest`, `WikipediaReaderTest`, `WikipediaTurnTest` pass; full `:app:testDebugUnitTest` passes with zero failures.
- No `TEMP_WIKI_DIAGNOSTIC`/`WikiStage`/`WikipediaDiagnostic`/`ioStage` in `app/src`; first-lookup query-only fix intact.
- Signed APK 1.2.9/17, cert `7c928de3…`, P16-aligned, non-debuggable, DEX retention verified, no WikiStage in DEX; SHA-256/size recorded.
- 5558 updated in place via `install -r`; pulled installed hash equals new signed APK hash; app data/model untouched.
- `tasks.md` and `AGENTS.md` record the new candidate hash, tests run, diagnostics removed, and that runtime visual acceptance is pending the user (no pass claimed). Superseded `5713142e…` / diagnostic hashes noted as superseded.

## Tests to Add/Run
Add/replace in `WikipediaContentTest`:
- Replace `equalDensityPrefersHigherRawScoreThenDocumentOrder` with a deterministic-tie test for the new selector (equal gain/cost → higher gain → document order).
- Keep/adjust: compact facts vs 450 B 3-term intro — both facts at 400/569/700/1024.
- One-term short noise does not displace two 2-term facts at 400/569/700/1024.
- Unrelated fragments sharing matching headings: both facts at 569/700/1024; at 400: ≥1 fact, zero unrelated fragments, partial marker present.
- Pinned-derived caption structure: both facts at 569/700/1024 (and 400 if feasible per arithmetic; else best-effort as above).
- Duplicate candidate adds zero gain (not selected twice in effect / second duplicate not chosen over complementary fact).
- Complementary captions sharing template words both selected.
- Singleton-vs-greedy trap: one high-coverage chunk beats many cheap low-coverage chunks when F is higher.
- One-term focus works (gate = 1).
- Heading-only match → identical output to existing zero-overlap fallback.
- Unicode (ß, ς, CJK) byte bounds respected; table header/legend context retained with selected rows.
- All existing parser guard tests (many-header adversarial, hidden rows/groups, explicit headers) pass unchanged.

Run:
```sh
bash ./gradlew :app:testDebugUnitTest --tests 'com.galaxyrio.gracelauncher.assistant.WikipediaContentTest' --tests 'com.galaxyrio.gracelauncher.assistant.WikipediaReaderTest' --tests 'com.galaxyrio.gracelauncher.assistant.WikipediaTurnTest' --console=plain
bash ./gradlew :app:testDebugUnitTest --console=plain
bash ./gradlew :app:compileDebugAndroidTestKotlin :app:assembleRelease --console=plain
```
Optionally re-run the ignored host harness `app/build/reports/wikipedia-1.2.9/section-evidence-diagnosis/SectionEvidenceDiagnosis.java` against the new selector at 569 B and report whether both captions appear (informational).
Report exact pass/fail counts. No visual/screenshot verification — the user does that.

## Scope Guardrails
- Do not touch Web search (`WebSearch.kt` behavior/limits), native `!` actions, Space/Stop/browser-return bugs, DB, fonts, settings, UI strings.
- No new dependencies, embeddings, prompt tuning, F2 fair-share, F3 ancestor-skip, cap increases, domain keywords.
- Don't reset whole files when removing diagnostics; don't remove the query-only first-lookup fix.
- Devices: only emulator-5558; no uninstall/clear/download/wipe/restart/network config. Don't touch 5580/5582/5584.
- Signing: only the specified key; never regenerate keys, log passwords, or read/commit `.env`.
- No commits/pushes.
- If any gate test fails after the algorithm is implemented as specified, stop and report the fixture and numbers — don't invent extra heuristics.

## Open Questions & Risks
- Actual model focus/section choice at runtime is unlogged; the selector fixes the reproduced budget defect but can't guarantee the model picks the right section or uses the evidence.
- Lexical gate may drop valid one-term evidence when 2-term chunks exist; local IDF varies by article; greedy is not globally budget-monotone (don't assert monotonicity).
- Photosynthesis/other answers may reorder; regression risk mitigated by tests and user visual check.
- Signed-release runtime acceptance (Oscars, photosynthesis, Web) pending user.

## Implementation Task List
- [ ] Read `WikipediaContent.kt` selection/candidate/score/compose and current F1 diff + tests.
- [ ] Add body-only `evidenceTerms`/`bodyLength` to Candidate construction (prose, list, table paths).
- [ ] Replace F1 comparator/greedy with gated coverage greedy + best singleton; keep fallback/marker/compose.
- [ ] Update/add `WikipediaContentTest` cases per the list; run focused tests.
- [ ] Remove temporary diagnostics in controller/search + temporary reader test; grep clean; run focused tests.
- [ ] Run full `:app:testDebugUnitTest`, `:app:compileDebugAndroidTestKotlin`, `:app:assembleRelease`.
- [ ] Zipalign P16, sign with existing key, verify cert/alignment/version/non-debuggable/DEX/hash; write both output APKs.
- [ ] Verify cert compatibility with 5558, `install -r`, pull and compare hash.
- [ ] Update `tasks.md` and `AGENTS.md`; write `app/build/reports/wikipedia-1.2.9/coverage-selector/report.md` with evidence.
- [ ] Report files changed, test counts, APK hash/size, installed hash, remaining risks.
