# Web/news: up to ten readable results

**Status: APPROVED — implementation completed 2026-11-18.**

## Baseline before implementation (supplied facts; not newly researched)
- `WebSearch.kt` requests six candidates, parses three, and has a hidden candidate ceiling of eight.
- Deduplication currently runs only when `candidateLimit > maxResults`.
- `AssistantController` independently applies `take(3)` before filtering usable results.
- Up to two queries can therefore produce six UI items today, not a reply-wide three-result limit.
- Sources currently use tiny combined title/host/date links; UI title/excerpt limits are 60/320 bytes.
- Model context is 4,096 tokens; evidence gets roughly 1,800–2,860 bytes shared across two queries.
- Ten long excerpts cannot simply be added to the model input.

## Approved result semantics
- Return **up to ten unique usable web/news results total per assistant reply**, including a refined query.
- Keep the existing maximum of two queries; do not add requests merely to fill ten slots.
- Clean, filter, deduplicate and rank candidates before applying the reply-wide cap.
- Apply deduplication regardless of candidate/result-limit equality, including across both queries.
- Remove premature three-result truncation and reconcile the hidden eight-candidate ceiling.
- Tavily's API reference documents `max_results` 0–20 and `chunks_per_source` 1–3; the implementation requests 20 results and two chunks per source: https://docs.tavily.com/documentation/api-reference/endpoint/search.
- Never pad, promise ten results, or promise provider relevance, freshness or factual quality.
- Assign citation numbers after final selection; preserve each number-to-source mapping once exposed.
- Later turns retain the originating reply's mapping; scope reference handling to existing web-source
  follow-ups and necessary mapping retention, not a speculative general reference resolver.

## Readable cards (web/news only)
- Use existing theme colors/fonts: full-width rounded source cards, no images.
- Collapsed: `[n]` and a wrapping title of up to three lines; hostname/date on a distinct line
  (omit absent date); cleaned search-provider excerpt of up to three lines.
- Retain fuller cleaned title/excerpt separately from collapsed display, with safe bounded storage;
  do not preserve the current 60/320-byte display cuts as the only available text.
- Inline **Show more / Show less** reveals only the retained full title/excerpt, not fetched article text
  or a model-written summary. Hide expansion when nothing is truncated.
- Every card provides a clear **Open source** action launching its external source URL.
- Expansion and opening are separate actions, each with at least 48dp touch targets, accessible labels,
  visible keyboard focus and keyboard activation; expose expanded/collapsed state to accessibility.
- Preserve existing URL validation, untrusted-content handling and failure notices.

Illustrative single card (placeholder content, collapsed):
```text
╭──────────────────────────────────────────────────╮
│ [1] A longer news headline wraps naturally        │
│     instead of becoming a tiny combined link     │
│ example.org · 18 Nov 2026                         │
│ Cleaned provider excerpt gives useful context.    │
│ It may wrap across up to three collapsed lines.  │
│ Show more                         Open source    │
╰──────────────────────────────────────────────────╯
```

## Cards are not all model-used evidence
- Preserve the existing context/evidence budget and bounded history; ten cards do not mean ten long inputs.
- A short assistant answer may synthesize a bounded selected subset, citing the stable card numbers.
- Show a concise result-list disclosure: **“10 results · answer uses selected sources”**, with dynamic
  count/pluralization; do not claim synthesis when no answer was generated.
- Do not imply all displayed sources were read or verified, or silently add ten model-written summaries.
- If synthesis of all ten is wanted, revise the evidence/context plan and obtain fresh approval.

## Narrow implementation and acceptance scope
- Touch existing web parser/request limits, controller aggregation/evidence mapping, source-card UI,
  existing string resources and directly related tests only; reuse existing components and dependencies.
- Preserve Wikipedia behavior and styling, action isolation, query routing and existing error handling.
- Focused parser/controller fixtures: zero/fewer/ten/more-than-ten usable results, invalid entries before
  valid entries, duplicate candidates at equal limits, cross-query duplicates and reply-wide cap.
- Check stable citations/follow-up mapping and unchanged evidence bounds across two queries.
- Focused UI checks: long titles/excerpts, missing date, expansion without network/model calls,
  external opening, large text, accessibility and keyboard separation of the two actions.
- No new dependencies, database changes, images, article-fetching feature or Wikipedia restyle.

## Approval and deployment gates
- Approved scope: ten unique results total per reply, up to three selected sources per query for synthesis, and the card layout above. All-ten synthesis was not selected.
- No APK build, signing, install, device command or instrumentation is authorized by this implementation scope; keep the existing signed release/model on emulator `5558` untouched.
- Wikipedia stress work remains separate and budget-paused.
- Implementation, focused JVM tests and AndroidTest Kotlin compilation are recorded in `app/build/reports/web-search-top10/report.md` and the Web section of `tasks.md`. Live Tavily quality and Android runtime/visual/accessibility behavior remain unverified.
