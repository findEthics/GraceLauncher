import json
import pathlib

import pytest

# Single shared copy; the Kotlin JVM test reads the same file.
VECTORS = json.loads(
    (
        pathlib.Path(__file__).resolve().parents[4]
        / "app/src/test/resources/memory/subject_key_vectors.json"
    ).read_text(encoding="utf-8")
)


def test_vector_file_is_populated():
    assert len(VECTORS) >= 40
    assert all(len(v) == 2 for v in VECTORS)


@pytest.mark.parametrize(
    "raw,expected", VECTORS, ids=[repr(v[0])[:30] for v in VECTORS]
)
def test_subject_key_matches_shared_vector(db, raw, expected):
    assert (
        db.execute("select grace_memory._subject_key(%s)", (raw,)).fetchone()[0]
        == expected
    )


def test_subject_key_is_immutable_and_internal(db):
    assert (
        db.execute(
            "select provolatile from pg_proc where oid = 'grace_memory._subject_key(text)'::regprocedure"
        ).fetchone()[0]
        == "i"
    )


def test_remember_stores_the_helper_key(w):
    for raw, expected in VECTORS:
        if (
            not raw.strip()
            or len(raw.strip()) > 200
            or len(expected) > 200
            or not expected.strip(" ")
        ):
            continue  # not storable subjects; see the bad_entry tests
        e = w.entry(subject=raw)
        w.rpc("memory_remember", e, None)
        assert w.rows(
            "select subject_key from grace_memory.memory_entries where id=%s",
            (e["id"],),
        ) == [(expected,)], raw


def test_remember_rejects_subject_whose_key_is_empty_or_too_long(w):
    import psycopg

    for subject in ("\t", "ß" * 150):  # key '' ; key 300 chars (> 200 after ß→ss)
        with pytest.raises(psycopg.errors.RaiseException, match="bad_entry"):
            w.rpc("memory_remember", w.entry(subject=subject), None)
