## Objective
- Investigate why Grace Launcher's on-device assistant (Gemma4-E2B + Tavily) web search failed in day-to-day usage and on physical Unihertz Titan 2 Elite release builds, implement and verify the web search and R8 keep-rule fixes, build and sign the `1.2.7` (`versionCode 15`) release APK, update `AGENTS.md`, and commit the changes locally.

## Important Details
- User role directives for this session: orchestrate and delegate research/scouting/implementation to subagents; ask questions only for unclear requirements or material blockers.
- Architecture decision (user-approved): Upgrade direct Tavily REST (`https://api.tavily.com/search`) rather than adopting remote Tavily MCP (`https://mcp.tavily.com/mcp`) or Google AI Edge Gallery's dynamic `runMcpTool` pattern. Live testing showed raw Tavily MCP returns 14.3–16.1 KB of un-truncated text per search (5x–10x larger than Grace's 1,800–2,860 B web context budget inside the 4,096-token engine window) and requires a 2-round-trip stateful StreamableHTTP session plus heavy JSON schemas.
- `oracle` plan-review constraints folded into implementation:
  - Keep `@Tool fun search_web(query: String)` single-parameter (`{query}`) and keep Gemma4 as the Step-0 query generator to preserve `AGENTS.md`, `strings.xml:523` privacy disclosure ("sends generated queries to Tavily"), and `WebSearchTest` fake-step assertions.
  - Preserve `resultBudget = webBudget / 2` (two-search budget invariant in `AssistantChatStateTest`), 3 final `WebSource` blocks (`<= 320 B` per excerpt), and pinned `WEB_INSTRUCTIONS` substrings in `WebToolTextTest`.
  - Keep `TavilyWebSearch.parse(body: String, maxResults: Int = 3, candidateLimit: Int = maxResults)` default signature intact so direct unit/instrumentation test calls examine the first 3 candidates while live `TavilyWebSearch.search(query)` passes `maxResults = 3, candidateLimit = 6`.
- Physical Unihertz Titan 2 Elite compatibility & R8 root cause:
  - User confirmed the previous release APK (`e0ef52c`) ran the assistant on Unihertz Titan 2 Elite without engine issues, and confirmed the updated debug build on `emulator-5558` works as expected (`"us stock market news today"`).
  - User's screenshot from Titan (`"I need to search for current US stock market news.\n\nSearch: US stock market news today"`) exposed a Release-only R8 bug: `app/proguard-rules.pro` kept `MobileActionTools` but did not keep `WebSearchTools`, causing R8 to strip `WebSearchTools.search_web(java.lang.String)` as unused dead code (`app/build/outputs/mapping/release/usage.txt:29797–29799`). Without the reflected `@Tool` method, Gemma 4 in release builds lacked the `search_web` schema and emitted plain-text `"Search: ..."`.
  - Fixed in `app/proguard-rules.pro` by keeping `WebSearchTools`, `@com.google.ai.edge.litertlm.Tool <methods>`, and `MethodParameters`, and bumped `versionName = "1.2.7"` / `versionCode = 15` in `app/build.gradle.kts` (visible in **Grace Launcher Settings → About** as `Version 1.2.7`).
  - `app/src/main/AndroidManifest.xml` is unchanged from `e0ef52c`, and physical devices (`hasHardwareGpu(Build.HARDWARE, Build.FINGERPRINT, Build.MODEL) == true`) use `Backend.GPU()` -> one-shot `Backend.CPU()` fallback and `cacheDir = context.noBackupFilesDir.absolutePath` without emulator cache cleanup.
- Emulator target & configuration:
  - `emulator-5558` (AVD `Notes_Automation_Phone_API36`, configured to `hw.ramSize = 12288` / 12 GB RAM, legacy debug cert `33c2ff93…8414c`) uses emulator-only CPU-first routing (`!hasHardwareGpu`) and low-disk cache handling (`resolveEngineCacheDir`).

## Work State
### Completed
- Audited existing web search implementation (`WebSearch.kt`, `AssistantController.kt`, `AssistantChatState.kt`, `AssistantUi.kt`) and researched `google-ai-edge/gallery` MCP (`b3e99136`) and `tavily-ai/tavily-mcp`.
- Ran live read-only Tavily REST & MCP stress probe across 8 day-to-day queries, identifying 6 code-level failure causes plus the release-only R8 `WebSearchTools.search_web` stripping bug.
- Implemented web search, R8 keep-rule, and emulator fixes (committed locally at `2abc25a`):
  - `app/proguard-rules.pro`: Preserved `com.galaxyrio.gracelauncher.assistant.WebSearchTools { *; }`, `@com.google.ai.edge.litertlm.Tool <methods>`, and `MethodParameters`.
  - `app/build.gradle.kts`: Bumped `versionCode = 15` and `versionName = "1.2.7"`.
  - `app/src/main/java/com/galaxyrio/gracelauncher/assistant/WebSearch.kt`:
    - Allowed apostrophes (`'`) in `WebToolText.query()` and narrowed `WebToolText.suspicious()` to avoid `"call: "` prose false positives.
    - Added `resolveSearchParams(query)` for broader recency (`day`/`week`/`month`), live market/crypto `topic = "news"`, and `country` boost on general queries while keeping `newsTimeRange()` and `selectNewsSources()` backward-compatible.
    - Updated `TavilyWebSearch.search()` to request `max_results = 6, chunks_per_source = 2`, clean snippet clutter, deprioritize hub/category URLs and pirate-stream spam titles (`[[[`, fullwidth ASCII), deduplicate identical `(host, title)` pairs, and return the top 3 concrete sources.
  - `app/src/main/java/com/galaxyrio/gracelauncher/assistant/AssistantController.kt` & `AssistantChatState.kt`:
    - Reserved up to 900 B for `chat.context(...)` when `webSearch` is enabled (while guaranteeing at least `WEB_CONTEXT_BYTES = 1800` for web evidence) so short follow-up prompts do not drop the preceding turn.
    - Added `[1] Title (host)` source footnotes (up to 120 B) on replayed turns and updated `WEB_INSTRUCTIONS` to guide follow-up searches by source title and domain.
    - Scoped `hasHardwareGpu` CPU-first routing and `resolveEngineCacheDir` strictly to Android emulators (`!hasHardwareGpu`).
  - Added and updated unit tests in `WebFreshnessTest.kt`, `WebToolTextTest.kt`, `AssistantChatStateTest.kt`, `WebFollowUpTest.kt`, `EngineBackendTest.kt`, and `EngineCacheDirTest.kt`.
  - Updated `AGENTS.md` and `tasks.md` with implementation, `1.2.7` release artifact verification, and R8 guidance.
- Verified builds, tests, and signed release APK (`1.2.7` / `versionCode 15`):
  - `bash ./gradlew :app:testDebugUnitTest :app:assembleRelease`: 234 unit tests passed, 0 failures.
  - Verified `WebSearchTools.search_web(java.lang.String)` and runtime `@Tool` / `@ToolParam` retention in `mapping.txt`, `usage.txt`, and `dexdump -a`.
  - 16KB-aligned and signed release APK at `app/build/outputs/apk/release/app-release-signed.apk` and `app/build/outputs/apk/release/app-release.apk`:
    - SHA-256: `a076306bfe94cabf4b11ea8d6d339ab21a84189978e43fdf7aef498fcff013ef`
    - Verified `zipalign -c -P 16 4`, `apksigner verify --verbose --print-certs` (cert SHA-256 `7c928de3824f4c6d91056d5e5615c5cb5414fece82643c0930c0ca600f583a0b`), and `aapt2 dump badging` (`1.2.7` / `15`).
  - Verified visually on `emulator-5558` (12 GB RAM) across test prompts and confirmed by user (`"us stock market news today"`).
  - Committed all 13 project files locally at commit `2abc25a` (`Improve assistant web search, R8 tool keep rules, and bump to 1.2.7`).

### Active
- `emulator-5558` (`Notes_Automation_Phone_API36`, 12 GB RAM) remains running with the updated debug build loaded.
- Signed release APK `1.2.7` (`versionCode 15`, SHA-256 `a076306bfe94cabf4b11ea8d6d339ab21a84189978e43fdf7aef498fcff013ef`) is ready for installation and runtime verification on physical Unihertz Titan 2 Elite.

### Blocked
- (none)

## Next Move
1. Install `app/build/outputs/apk/release/app-release-signed.apk` (`1.2.7` / `versionCode 15`) on the physical Unihertz Titan 2 Elite, verify `Version 1.2.7` in **Settings → About**, and confirm live web search queries (`"us stock market news today"`) execute structured `search_web` calls on device.

## Relevant Files
- `/Users/susuresh/genie/grace-launcher/GraceLauncher/app/proguard-rules.pro`: R8 keep rules preserving `WebSearchTools`, `@Tool` methods, and `MethodParameters` in release builds.
- `/Users/susuresh/genie/grace-launcher/GraceLauncher/app/build.gradle.kts`: Version bumped to `versionName = "1.2.7"`, `versionCode = 15`.
- `/Users/susuresh/genie/grace-launcher/GraceLauncher/app/build/outputs/apk/release/app-release-signed.apk`: Verified 16KB-aligned signed release APK (`1.2.7` / `15`, SHA-256 `a076306bfe94cabf4b11ea8d6d339ab21a84189978e43fdf7aef498fcff013ef`).
- `/Users/susuresh/genie/grace-launcher/GraceLauncher/app/src/main/java/com/galaxyrio/gracelauncher/assistant/WebSearch.kt`: Upgraded Tavily REST parameter router (`resolveSearchParams`), 6-candidate retrieval, snippet cleaning, hub/spam deprioritization, apostrophe recovery, and narrowed `suspicious()` check.
- `/Users/susuresh/genie/grace-launcher/GraceLauncher/app/src/main/java/com/galaxyrio/gracelauncher/assistant/AssistantController.kt`: Follow-up history budget reservation, source title/domain footnote replay, and emulator-only CPU-first/cache handling (`hasHardwareGpu`, `resolveEngineCacheDir`).
- `/Users/susuresh/genie/grace-launcher/GraceLauncher/app/src/main/java/com/galaxyrio/gracelauncher/assistant/AssistantChatState.kt`: Updated `turnBytes` accounting to include replayed source footnotes within the rolling history budget.
- `/Users/susuresh/genie/grace-launcher/GraceLauncher/AGENTS.md`: Updated repository documentation for `1.2.7`, R8 `WebSearchTools` keep rules, web search routing, and emulator/Titan engine behavior.
- `/Users/susuresh/genie/grace-launcher/GraceLauncher/tasks.md`: Recorded `1.2.7` release verification evidence (`app/build/reports/release-websearch-1.2.7/`) and emulator test results.
