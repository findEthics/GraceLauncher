import json
import random
from pathlib import Path

from memory_bridge.export import render, bundle

G = Path(__file__).parent / "golden"
LEDGER = json.loads((G / "ledger.json").read_text())


def test_golden_files():
    files = render(LEDGER)
    exp = G / "expected"
    expected = {p.relative_to(exp).as_posix(): p.read_bytes() for p in exp.rglob("*") if p.is_file()}
    assert {k: v.encode() for k, v in files.items()} == expected


def test_byte_identical_and_order_independent():
    a = render(LEDGER)
    shuffled = LEDGER[:]
    random.Random(1).shuffle(shuffled)
    assert render(shuffled) == a == render(LEDGER)
    assert bundle(a) == bundle(render(shuffled))


def test_lf_only_and_trailing_newline():
    for text in render(LEDGER).values():
        assert "\r" not in text and text.endswith("\n")


def test_index_one_current_value_per_subject():
    idx = render(LEDGER)["index.md"]
    assert idx.count("Hamburg") == 1 and "Berlin" not in idx  # superseded value only on the page
    assert "Coffee" in idx and "(none)" in idx


def test_page_lists_superseded_and_invalidated_history():
    files = render(LEDGER)
    home = next(v for k, v in files.items() if k.startswith("subjects/") and "Home" in v)
    assert "Berlin" in home and "Hamburg" in home and "superseded" in home
    coffee = next(v for k, v in files.items() if k.startswith("subjects/") and "Oat flat white" in v)
    assert "invalidated" in coffee


def test_log_lines_and_utc_dates():
    lines = [l for l in render(LEDGER)["log.md"].splitlines() if l.startswith("## [")]
    assert lines == [
        "## [2026-01-05] save | Home city",
        "## [2026-02-01] save | Coffee",  # 07:00+02:00 is 05:00Z, same UTC day
        "## [2026-03-01] save | Dentist visit",
        "## [2026-04-01] forget | Coffee",
        "## [2026-06-10] supersede | Home City",
        "## [2026-07-01] save | Ünïcode ☕",
    ]


def test_purged_rows_absent():
    files = render([e for e in LEDGER if e["subject"] != "Coffee"])  # purge = row gone from export
    assert not any("Coffee" in v for v in files.values())
