# Wikipedia test fixtures

Real MediaWiki API responses (`format=json&formatversion=2`), fetched serially on 2026-11-18 with the app's
User-Agent and exactly the parameters `WikipediaReader` sends. Text: "Wikipedia contributors", licensed
CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/). Used only by JVM tests; not packaged in the app.

Per case: `discovery.json` (search, as returned that day — not revision-pinned), `sections.json`
(`action=parse&oldid=REV&prop=sections|revid`), `section-N.json`
(`action=parse&oldid=REV&section=N&prop=text&disableeditsection=1&disabletoc=1&disablelimitreport=1`).

| Dir | Article (en.wikipedia.org) | Revision | Section files |
|---|---|---|---|
| `oscars` | 98th Academy Awards | 1381253826 | 2 (Awards) |
| `actor-award` | Academy Award for Best Actor | 1386199286 | 13 (2020s) |
| `curie` | Marie Curie | 1384840538 | – (lead via discovery) |
| `tokyo` | Tokyo | 1386294178 | 0 (introduction + infobox), 26 (Demographics) |
| `greater-tokyo` | Greater Tokyo Area | 1375954984 | – (lead via discovery) |
| `beatles` | The Beatles | 1385714445 | 33 (Members) |

Section `0` is requested like any other section (`section=0`).
