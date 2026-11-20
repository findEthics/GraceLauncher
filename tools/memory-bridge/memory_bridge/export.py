"""Deterministic markdown view of the ledger (no LLM): same input -> same bytes.

Purged rows are gone from memory_export(), so no purge lines are emitted (spec: omit).
"""
from __future__ import annotations

import hashlib
import re
import unicodedata
from datetime import datetime, timezone
from typing import Any

Entry = dict[str, Any]


def _utc(ts: str) -> datetime:
    d = datetime.fromisoformat(ts.replace("Z", "+00:00"))
    if d.tzinfo is None:
        d = d.replace(tzinfo=timezone.utc)
    return d.astimezone(timezone.utc)


def _day(ts: str) -> str:
    return _utc(ts).strftime("%Y-%m-%d")


def _one_line(s: str) -> str:
    return " ".join(s.split())


def _cell(s: str) -> str:
    return s.replace("|", "\\|").replace("\r\n", "\n").replace("\n", "<br>")


def _slug(key: str) -> str:
    ascii_ = unicodedata.normalize("NFKD", key).encode("ascii", "ignore").decode()
    slug = re.sub(r"[^a-z0-9]+", "-", ascii_.lower()).strip("-")[:40].strip("-") or "subject"
    return f"{slug}-{hashlib.sha1(key.encode()).hexdigest()[:6]}"  # hash keeps names unique + stable


def _order(e: Entry) -> tuple:
    return (_utc(e["valid_from"]), _utc(e["created_at"]), e["id"])


def render(entries: list[Entry]) -> dict[str, str]:
    groups: dict[str, list[Entry]] = {}
    for e in entries:
        groups.setdefault(e["subject_key"], []).append(e)

    index = ["# Memory index", ""]
    pages: dict[str, str] = {}
    log: list[tuple] = []

    for key in sorted(groups):
        hist = sorted(groups[key], key=_order)
        latest = hist[-1]
        active = [e for e in hist if e["status"] == "active"]
        head = active[-1] if active else None
        name = _one_line((head or latest)["subject"])
        path = f"subjects/{_slug(key)}.md"

        page = [f"# {name}", ""]
        page.append(f"Current: {_cell(head['body']) if head else '(none)'}")
        if len(active) > 1:
            page.append(f"Note: {len(active)} active entries share this subject.")
        page += ["", "| From | Until | Status | Type | Value | Source |", "|---|---|---|---|---|---|"]
        for e in hist:
            until = _day(e["valid_to"]) if e.get("valid_to") else ""
            page.append(
                f"| {_day(e['valid_from'])} | {until} | {e['status']} | {e['type']} | {_cell(e['body'])} | {_cell(e['source'])} |"
            )
            kind = "supersede" if e.get("supersedes") else "save"
            log.append((_utc(e["valid_from"]), 0, e["id"], kind, _one_line(e["subject"])))
            if e["status"] == "invalidated" and e.get("valid_to"):
                log.append((_utc(e["valid_to"]), 1, e["id"], "forget", _one_line(e["subject"])))
        pages[path] = "\n".join(page) + "\n"

        stamps = [_utc(e["valid_from"]) for e in hist] + [_utc(e["valid_to"]) for e in hist if e.get("valid_to")]
        value = _one_line(head["body"]) if head else "(none)"
        if len(value) > 120:
            value = value[:119] + "…"
        index.append(f"- [{name}]({path}) — {latest['type']}: {value} ({max(stamps).strftime('%Y-%m-%d')})")

    log_lines = ["# Log", ""]
    for when, _, _id, kind, name in sorted(log):
        log_lines.append(f"## [{when.strftime('%Y-%m-%d')}] {kind} | {name}")

    files = {"index.md": "\n".join(index) + "\n", "log.md": "\n".join(log_lines) + "\n"}
    files.update(pages)
    return dict(sorted(files.items()))


def bundle(files: dict[str, str]) -> str:
    return "".join(f"===== {p} =====\n{files[p]}\n" for p in sorted(files))
