## Objective
- Add Wikipedia search to Grace Launcher's on-device Gemma4 assistant (existing search flow), with generalized, question-aware retrieval (relevant sections, prose, lists, tables) producing grounded answers + sources; deliver a version-bumped signed release tested on emulator-5558.

## Important Details
- Role: parent agent is orchestrator/creative director only (Mastermind workflow, `mastermind-interview` skill). Never implement or read/analyze code itself; fact lookups → `mastermind-explorer` subagent; implementation → `mastermind-implementer-*` with only the plan path. Generic `delegate`/`researcher` subagents now return `Permission denied: subagent`; use `mastermind-explorer` for research/lookups (it can web search).
- Mastermind gates: interview one question at a time via `question` tool; explicit Yes/No greenlight before writing plan to `assets/plans/`; separate implementer-model choice; after each implementer report, lightweight review (re-read plan Acceptance Criteria/Scope Guardrails, `git diff`/grep spot check, no test reruns) and relay with a short review note; discrepancies → offer options via `question`.
- User previously granted all routine approvals/autonomous continuation; stop only for material blockers. Generalization required — no Oscars/awards/year-specific heuristics. Original requirement: research → requirements → independently reviewed plan → implementation → testing.
- Device rules: test only on **emulator-5558** (AVD `Notes_Automation_Phone_API36`, retained at `/Users/susuresh/.android/avd/Notes_Automation_Phone_API36.avd`), user override of default 5584. Never uninstall, clear data, redownload model, wipe AVD, change AVD/network config; same-cert `adb -s emulator-5558 install -r` only. Don't touch 5580/5582/5584. User approved restarting 5558 (done).
- Visual verification belongs to the user now (plan says implementer does no screenshot/visual QA).
- Signing: key `~/.local/share/grace-launcher/signing/grace-launcher-current.p12`, alias `grace-launcher`, password file `grace-launcher-current.password` (`--ks-pass file:…`, omit `--key-pass`), cert SHA-256 `7c928de3824f4c6d91056d5e5615c5cb5414fece82643c0930c0ca600f583a0b`; `zipalign -P 16 -f 4` before signing. Never log secrets/.env. No commits/pushes.
- Build env: JDK21 `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`, `ANDROID_USER_HOME=/Users/susuresh/.android`.
- Architecture decisions: native Wikipedia tools (no WebView/skills runtime); Web/Wikipedia mutually exclusive chips; two logical tool calls (`query_wikipedia(topic,lang,focus)` → discovery lead + heading index; `read_wikipedia_sections(sections,focus)` → 1–2 revision-pinned sections), ≤4 HTTP/turn, 3 inference passes; first inference exposes query-only toolset (`WikipediaQueryTools`) with lookup-first instruction; parse-only jsoup 1.24.1; Wikipedia's 320 B clamp removed, Web limits unchanged; zero-current-evidence → refusal.
- Coverage selector (user-chosen after web research): body-only scoring (inherited headings/table context zero credit, bytes still counted); article-local IDF + binary-TF BM25 length norm; gate excludes one-term chunks when a feasible ≥2-term chunk exists; greedy marginal coverage gain/byte vs best singleton; heading-only match → existing zero-overlap fallback; 400 B heading fixture infeasible (needs 403 B) → best-effort at 400, both facts at 569/700/1024.
- Root-cause history for Oscars failure ("Who won Best Actor and Best Actress at the 2026 Oscars?"): (1) model skipped lookup → fixed by query-only first step; (2) emulator DNS failure → fixed by emulator process restart; (3) greedy overlap ranking let 450 B intro consume 512/569 B, second section got 57 B → fixed by coverage selector (tests only). Same-revision primary evidence (page 79369474, rev 1381253826) names Michael B. Jordan and Jessie Buckley — use only for validation, never feed to app.

## Work State
### Completed
- Wikipedia feature, provider (`WikipediaReader`), parser (`WikipediaContent`), controller integration, parser safety fixes, query-only first lookup, version 1.2.9/17.
- Coverage selector implemented (GPT-6.1 Sol implementer), temporary diagnostics (`TEMP_WIKI_DIAGNOSTIC`/`WikiStage`/`WikipediaDiagnostic`/`ioStage`) removed; tie-test fixture corrected (data only).
- Verification reported by implementer: **37/37 focused + 275/275 full JVM**, `:app:compileDebugAndroidTestKotlin` + `:app:assembleRelease` passed; signed APK verified (cert, P16 alignment, non-debuggable, DEX tool methods/annotations/MethodParameters/LiteRT callbacks, no WikiStage).
- Current candidate: `app/build/outputs/apk/release/app-release-signed.apk` = `app-release.apk`, 59,491,245 B, SHA-256 `df42a435965b6f130fb31a690fa56b4e1192815955d78a54bf641eb65dc2969e`; installed on 5558 via `install -r`, pulled hash identical.
- Parent review: 0 diagnostic markers in `app/src`, proguard keep for `WikipediaQueryTools` present, both APK hashes match, tie test change data-only. Superseded: `5713142e…`, diagnostic `cca6891d…`, `3831902c…`, `f2b446e1…`.
- `tasks.md`, `AGENTS.md`, report `app/build/reports/wikipedia-1.2.9/coverage-selector/report.md` updated.

### Active
- Awaiting user's visual acceptance on 5558: Oscars query (Wikipedia chip), photosynthesis section query, Web search query.

### Blocked
- Runtime grounding unverified: model's actual focus/section choice unlogged; fix proven only by tests.
- Model readiness not visually rechecked after latest install; native ELF 16KB unverified.
- Known out-of-scope issues: Web stable-version answer quality poor (Android beta/YouTube evidence); Space reopening after browser return; generated-summary attribution after Stop; physical keyboard/GPU/actions unchecked.
- Lexical gate may drop valid one-term evidence; greedy not globally budget-monotone.
- context-mode tools currently hitting `disk I/O error` (ctx_batch_execute/ctx_search); use native shell/read fallback.

## Next Move
1. Wait for the user's visual Oscars/photosynthesis/Web results on 5558; if issues, run a new interview round (one question at a time) and/or re-invoke `mastermind-implementer-gpt-6-1-sol` with a fresh tweak description, then review per Mastermind rules.
2. If accepted, record acceptance in `tasks.md`/`AGENTS.md` (via implementer); commits/pushes only if user explicitly requests.

## Relevant Files
- `assets/plans/2026-11-17-wikipedia-coverage-selector.md`: approved plan (acceptance criteria, guardrails).
- `app/src/main/java/com/galaxyrio/gracelauncher/assistant/WikipediaContent.kt`: parser + coverage selector.
- `app/src/main/java/com/galaxyrio/gracelauncher/assistant/WikipediaSearch.kt`: `WikipediaReader`, tool schemas, `WikipediaQueryTools`.
- `app/src/main/java/com/galaxyrio/gracelauncher/assistant/AssistantController.kt`: manual tool loop, budgets, first-step query-only selection.
- `app/src/test/java/com/galaxyrio/gracelauncher/assistant/WikipediaContentTest.kt`, `WikipediaReaderTest.kt`, `WikipediaTurnTest.kt`: focused tests.
- `app/proguard-rules.pro`: R8 keeps for Wikipedia/Web tools.
- `app/build/reports/wikipedia-1.2.9/`: all evidence (implementation, parser-safety, release-artifact, signed-device, oscars-diagnosis, diagnostic*, device-dns*, emulator-process-restart, post-dns-wikipedia, section-evidence-diagnosis, evidence-ranking-repair, coverage-selector).
- `tasks.md`, `AGENTS.md`: current candidate/installed state and caveats.
