import json
import uuid
import psycopg
import pytest

ALL_RPCS = [  # (name, args factory) — every function must reject bad tokens
    ("memory_remember", lambda w: (w.entry(), None)),
    ("memory_forget", lambda w: (str(uuid.uuid4()),)),
    ("memory_purge", lambda w: (str(uuid.uuid4()), "PURGE")),
    ("memory_recall", lambda w: ("x", 5)),
    ("memory_history", lambda w: (str(uuid.uuid4()),)),
    ("memory_changes", lambda w: (0,)),
    ("memory_export", lambda w: ()),
]


def insert_other_user_row(w, **kw):
    """Row owned by user B, inserted as superuser (B cannot authenticate)."""
    e = w.entry(**kw)
    w.conn.execute(
        "insert into grace_memory.memory_entries(id,user_id,type,subject,subject_key,body,valid_from,source) "
        "values (%s,%s,%s,%s,lower(%s),%s,%s,%s)",
        (e["id"], w.b, e["type"], e["subject"], e["subject"], e["body"], e["valid_from"], e["source"]))
    return e["id"]


def status(w, id_):
    r = w.rows("select status, valid_to, superseded_by, supersedes from grace_memory.memory_entries where id=%s", (id_,))
    return r[0] if r else None


# slice 3 + auth matrix ------------------------------------------------------
@pytest.mark.parametrize("fn,mk", ALL_RPCS)
@pytest.mark.parametrize("which", ["tok_revoked", "tok_unknown", "tok_b"])
def test_every_rpc_rejects_bad_tokens(w, fn, mk, which):
    other = insert_other_user_row(w)
    before = w.rows("select id,status,change_seq from grace_memory.memory_entries order by id")
    with pytest.raises(psycopg.errors.RaiseException, match="invalid_token"):
        w.rpc(fn, *mk(w), token=getattr(w, which))
    assert w.rows("select id,status,change_seq from grace_memory.memory_entries order by id") == before
    assert status(w, other)[0] == "active"


def test_last_used_at_never_changes(w):
    e = w.entry()
    w.rpc("memory_remember", e, None)
    w.rpc("memory_remember", w.entry(subject="Home city", body="Hamburg"), e["id"])
    w.rpc("memory_recall", "Berlin", 5)
    w.rpc("memory_history", e["id"])
    w.rpc("memory_changes", 0)
    w.rpc("memory_export")
    w.rpc("memory_forget", e["id"])
    w.rpc("memory_purge", e["id"], "PURGE")
    assert w.rows("select count(*) from public.mcp_tokens where last_used_at is not null") == [(0,)]


# slice 4 remember -------------------------------------------------------------
def test_remember_idempotent_on_id(w):
    e = w.entry()
    r1 = w.rpc("memory_remember", e, None)
    r2 = w.rpc("memory_remember", e, None)
    assert r1["conflict"] is False and r2["conflict"] is False
    assert w.rows("select count(*), min(user_id::text) from grace_memory.memory_entries") == [(1, str(w.a))]


def test_remember_takes_user_from_token_not_input(w):
    e = w.entry(user_id=str(w.b))
    w.rpc("memory_remember", e, None)
    assert w.rows("select user_id from grace_memory.memory_entries") == [(w.a,)]


def test_remember_does_not_overwrite_other_users_id(w):
    oid = insert_other_user_row(w)
    with pytest.raises(psycopg.errors.RaiseException, match="bad_entry"):
        w.rpc("memory_remember", w.entry(id=oid, body="hijack"), None)
    assert w.rows("select body, user_id from grace_memory.memory_entries") == [("Berlin", w.b)]


@pytest.mark.parametrize("patch", [
    {"id": "nope"}, {"id": None}, {"type": "bogus"}, {"subject": ""}, {"subject": "   "}, {"body": ""},
    {"source": ""}, {"body": "x" * 8001}, {"valid_from": "garbage"}, {"occurred_at": "garbage"},
])
def test_remember_bad_entry(w, patch):
    with pytest.raises(psycopg.errors.RaiseException, match="bad_entry"):
        w.rpc("memory_remember", w.entry(**patch), None)
    assert w.rows("select count(*) from grace_memory.memory_entries") == [(0,)]


def test_remember_non_object_is_bad_entry(w):
    with pytest.raises(psycopg.errors.RaiseException, match="bad_entry"):
        w.rpc("memory_remember", [1, 2], None)


def test_remember_missing_required_field_is_bad_entry(w):
    e = w.entry()
    del e["body"]
    with pytest.raises(psycopg.errors.RaiseException, match="bad_entry"):
        w.rpc("memory_remember", e, None)


def test_remember_defaults_valid_from_and_subject_key(w):
    e = w.entry(subject="  Home   City ")
    del e["valid_from"]
    w.rpc("memory_remember", e, None)
    assert w.rows("select subject_key, valid_from is not null, status, valid_to from grace_memory.memory_entries") \
        == [("home city", True, "active", None)]


# slice 5 supersede ------------------------------------------------------------
def test_supersede_closes_active_target(w):
    old = w.entry(valid_from="2026-01-01T00:00:00Z")
    new = w.entry(body="Hamburg", valid_from="2026-03-01T00:00:00Z")
    w.rpc("memory_remember", old, None)
    r = w.rpc("memory_remember", new, old["id"])
    assert r["conflict"] is False
    st, vto, sby, _ = status(w, old["id"])
    assert st == "superseded" and sby == uuid.UUID(new["id"]) and vto.isoformat().startswith("2026-03-01")
    nst, nvto, _, sups = status(w, new["id"])
    assert nst == "active" and nvto is None and sups == uuid.UUID(old["id"])


def test_supersede_non_active_target_conflict(w):
    a = w.entry(valid_from="2026-01-01T00:00:00Z")
    b = w.entry(body="Hamburg", valid_from="2026-02-01T00:00:00Z")
    c = w.entry(body="Munich", valid_from="2026-03-01T00:00:00Z")
    w.rpc("memory_remember", a, None)
    w.rpc("memory_remember", b, a["id"])
    before = status(w, a["id"])
    r = w.rpc("memory_remember", c, a["id"])  # a is already superseded; head is b
    assert r == {"conflict": True, "head_id": b["id"], "id": c["id"]}
    assert status(w, a["id"]) == before
    assert status(w, b["id"])[0] == "active"
    assert status(w, c["id"]) == ("active", None, None, None)


def test_supersede_invalidated_target_conflict_without_head(w):
    a = w.entry()
    w.rpc("memory_remember", a, None)
    w.rpc("memory_forget", a["id"])
    c = w.entry(body="X")
    r = w.rpc("memory_remember", c, a["id"])
    assert r["conflict"] is True and r["head_id"] is None
    assert status(w, a["id"])[0] == "invalidated"
    assert status(w, c["id"])[0] == "active"


def test_supersede_unknown_target_conflict(w):
    r = w.rpc("memory_remember", w.entry(), str(uuid.uuid4()))
    assert r["conflict"] is True and r["head_id"] is None


def test_supersede_other_users_target_never_touched(w):
    oid = insert_other_user_row(w)
    before = w.rows("select * from grace_memory.memory_entries where id=%s", (oid,))
    r = w.rpc("memory_remember", w.entry(body="mine"), oid)
    assert r["conflict"] is True and r["head_id"] is None
    assert w.rows("select * from grace_memory.memory_entries where id=%s", (oid,)) == before


def test_recall_flags_two_active_heads_as_conflict(w):
    w.rpc("memory_remember", w.entry(body="Berlin"), None)
    w.rpc("memory_remember", w.entry(body="Hamburg"), None)
    w.rpc("memory_remember", w.entry(type="note", subject="Idea", body="a"), None)
    w.rpc("memory_remember", w.entry(type="note", subject="Idea", body="b"), None)
    res = w.rpc("memory_recall", "", 10)
    flags = {(r["type"], r["body"]): r["conflict"] for r in res}
    assert flags[("fact", "Berlin")] and flags[("fact", "Hamburg")]
    assert not flags[("note", "a")] and not flags[("note", "b")]


# slice 6 forget ---------------------------------------------------------------
def test_forget_soft_invalidates(w):
    e = w.entry()
    w.rpc("memory_remember", e, None)
    r = w.rpc("memory_forget", e["id"])
    assert r["forgotten"] is True
    st, vto, _, _ = status(w, e["id"])
    assert st == "invalidated" and vto is not None
    assert w.rpc("memory_recall", "Berlin", 5) == []


def test_forget_other_users_row_noop(w):
    oid = insert_other_user_row(w)
    assert w.rpc("memory_forget", oid)["forgotten"] is False
    assert status(w, oid)[0] == "active"


def test_forget_superseded_noop(w):
    a, b = w.entry(), w.entry(body="Hamburg", valid_from="2026-02-01T00:00:00Z")
    w.rpc("memory_remember", a, None)
    w.rpc("memory_remember", b, a["id"])
    assert w.rpc("memory_forget", a["id"])["forgotten"] is False
    assert status(w, a["id"])[0] == "superseded"


# slice 7 purge ----------------------------------------------------------------
def chain(w, n=4):
    ids, prev = [], None
    for i in range(n):
        e = w.entry(body=f"v{i}", valid_from=f"2026-0{i+1}-01T00:00:00Z")
        w.rpc("memory_remember", e, prev)
        ids.append(e["id"])
        prev = e["id"]
    return ids


@pytest.mark.parametrize("confirm", [None, "", "purge", "PURGE ", "yes"])
def test_purge_requires_exact_confirm(w, confirm):
    ids = chain(w, 2)
    with pytest.raises(psycopg.errors.RaiseException, match="confirm_required"):
        w.rpc("memory_purge", ids[0], confirm)
    assert w.rows("select count(*) from grace_memory.memory_entries") == [(2,)]


@pytest.mark.parametrize("start", [0, 1, 3])  # from tail, middle, head: both directions
def test_purge_removes_whole_chain_with_tombstones(w, start):
    ids = chain(w, 4)
    other = w.rpc("memory_remember", w.entry(subject="Other", body="keep"), None)
    r = w.rpc("memory_purge", ids[start], "PURGE")
    assert r["purged"] == 4
    assert w.rows("select count(*) from grace_memory.memory_entries") == [(1,)]
    tomb = w.rows("select id::text from grace_memory.purged_ids order by id")
    assert [t[0] for t in tomb] == sorted(ids)
    cols = {c[0] for c in w.rows("select column_name from information_schema.columns where table_name='purged_ids'")}
    assert cols == {"id", "user_id", "purged_at", "change_seq"}  # no content kept


def test_purge_other_users_chain_untouched(w):
    oid = insert_other_user_row(w)
    assert w.rpc("memory_purge", oid, "PURGE")["purged"] == 0
    assert status(w, oid)[0] == "active"
    assert w.rows("select count(*) from grace_memory.purged_ids") == [(0,)]


# slice 8 recall ---------------------------------------------------------------
def test_recall_fts_active_only_ranked_then_newest(w):
    w.rpc("memory_remember", w.entry(subject="Coffee", body="likes espresso", type="preference", valid_from="2026-01-01T00:00:00Z"), None)
    w.rpc("memory_remember", w.entry(subject="Drinks", body="espresso espresso espresso", type="note", valid_from="2026-01-02T00:00:00Z"), None)
    forgotten = w.entry(subject="Old", body="espresso old", type="note")
    w.rpc("memory_remember", forgotten, None)
    w.rpc("memory_forget", forgotten["id"])
    w.rpc("memory_remember", w.entry(subject="Tea", body="green", type="note"), None)
    res = w.rpc("memory_recall", "espresso", 10)
    assert [r["subject"] for r in res] == ["Drinks", "Coffee"]  # rank first
    assert all(r["status"] == "active" for r in res)
    assert all("fts" not in r and "user_id" not in r for r in res)


def test_recall_equal_rank_newest_first(w):
    w.rpc("memory_remember", w.entry(subject="A", body="zebra", type="note", valid_from="2026-01-01T00:00:00Z"), None)
    w.rpc("memory_remember", w.entry(subject="B", body="zebra", type="note", valid_from="2026-02-01T00:00:00Z"), None)
    assert [r["subject"] for r in w.rpc("memory_recall", "zebra", 10)] == ["B", "A"]


def test_recall_websearch_syntax_and_german(w):
    w.rpc("memory_remember", w.entry(subject="Wohnort", body="Ich wohne in München", type="fact"), None)
    w.rpc("memory_remember", w.entry(subject="Auto", body="Ich fahre Fahrrad", type="note"), None)
    assert len(w.rpc("memory_recall", "München", 5)) == 1
    assert len(w.rpc("memory_recall", '"Ich wohne" -Fahrrad', 5)) == 1
    assert w.rpc("memory_recall", "München -wohne", 5) == []


@pytest.mark.parametrize("q", ["", "   ", None])
def test_recall_empty_query_most_recent(w, q):
    for i in range(3):
        w.rpc("memory_remember", w.entry(subject=f"S{i}", body="b", type="note", valid_from=f"2026-01-0{i+1}T00:00:00Z"), None)
    assert [r["subject"] for r in w.rpc("memory_recall", q, 2)] == ["S2", "S1"]


def test_recall_limit_capped_and_floored(w):
    for i in range(55):
        w.rpc("memory_remember", w.entry(subject=f"S{i}", body="b", type="note"), None)
    assert len(w.rpc("memory_recall", "", 1000)) == 50
    assert len(w.rpc("memory_recall", "", 0)) == 1
    assert len(w.rpc("memory_recall", "", -5)) == 1
    assert len(w.rpc("memory_recall", "", None)) == 10


def test_recall_never_leaks_other_users(w):
    insert_other_user_row(w, body="secret berlin")
    assert w.rpc("memory_recall", "secret", 10) == []
    assert w.rpc("memory_recall", "", 10) == []


# slice 9 history --------------------------------------------------------------
def test_history_full_chain_ordered_from_any_member(w):
    ids = chain(w, 3)
    for i in ids:
        res = w.rpc("memory_history", i)
        assert [r["id"] for r in res] == ids
    assert [r["status"] for r in w.rpc("memory_history", ids[1])] == ["superseded", "superseded", "active"]


def test_history_other_user_or_unknown_empty(w):
    oid = insert_other_user_row(w)
    assert w.rpc("memory_history", oid) == []
    assert w.rpc("memory_history", str(uuid.uuid4())) == []


# slice 10 changes -------------------------------------------------------------
def test_change_seq_unique_increasing_across_entries_and_tombstones(w):
    ids = chain(w, 2)
    w.rpc("memory_purge", ids[0], "PURGE")
    w.rpc("memory_remember", w.entry(subject="Z", body="z"), None)
    seqs = [r[0] for r in w.rows("select change_seq from grace_memory.memory_entries union all select change_seq from grace_memory.purged_ids")]
    assert len(seqs) == len(set(seqs))
    # update bumps seq
    e = w.entry(subject="Q", body="q")
    w.rpc("memory_remember", e, None)
    s1 = w.rows("select change_seq from grace_memory.memory_entries where id=%s", (e["id"],))[0][0]
    w.rpc("memory_forget", e["id"])
    s2 = w.rows("select change_seq from grace_memory.memory_entries where id=%s", (e["id"],))[0][0]
    assert s2 > s1


def test_changes_since_cursor_rows_tombstones_ordered_max_seq(w):
    keep = w.entry(subject="Keep", body="k", type="note")
    w.rpc("memory_remember", keep, None)
    first = w.rpc("memory_changes", 0)
    assert [e["id"] for e in first["entries"]] == [keep["id"]]
    assert first["purged"] == [] and first["max_seq"] == first["entries"][0]["change_seq"]
    cursor = first["max_seq"]
    assert w.rpc("memory_changes", cursor) == {"entries": [], "purged": [], "max_seq": cursor}

    ids = chain(w, 2)
    w.rpc("memory_forget", keep["id"])
    w.rpc("memory_purge", ids[0], "PURGE")
    ch = w.rpc("memory_changes", cursor)
    assert [e["id"] for e in ch["entries"]] == [keep["id"]]  # chain rows were purged, only keep survives
    assert sorted(p["id"] for p in ch["purged"]) == sorted(ids)
    seqs = [e["change_seq"] for e in ch["entries"]]
    pseqs = [p["change_seq"] for p in ch["purged"]]
    assert seqs == sorted(seqs) and pseqs == sorted(pseqs)
    assert all(s > cursor for s in seqs + pseqs)
    assert ch["max_seq"] == max(seqs + pseqs)
    assert all("user_id" not in p for p in ch["purged"])


def test_changes_overlap_repull_is_idempotent(w):
    for i in range(3):
        w.rpc("memory_remember", w.entry(subject=f"S{i}", body="b", type="note"), None)
    a = w.rpc("memory_changes", 0)
    b = w.rpc("memory_changes", a["max_seq"] - 50)  # overlap
    by_id = {e["id"]: e for e in b["entries"]}
    assert by_id == {e["id"]: e for e in a["entries"]}
    assert b["max_seq"] == a["max_seq"]


def test_changes_null_cursor_treated_as_zero_and_no_leak(w):
    insert_other_user_row(w)
    assert w.rpc("memory_changes", None) == {"entries": [], "purged": [], "max_seq": 0}


# slice 11 export --------------------------------------------------------------
def test_export_all_rows_including_history(w):
    ids = chain(w, 3)
    f = w.entry(subject="Gone", body="x", type="note")
    w.rpc("memory_remember", f, None)
    w.rpc("memory_forget", f["id"])
    insert_other_user_row(w)
    res = w.rpc("memory_export")
    assert {r["id"] for r in res} == set(ids) | {f["id"]}
    assert {r["status"] for r in res} == {"active", "superseded", "invalidated"}
    assert all("fts" not in r and "user_id" not in r for r in res)


def test_export_empty(w):
    assert w.rpc("memory_export") == []


def test_supersede_stale_target_follows_multi_hop_to_head(w):
    ids = chain(w, 3)
    r = w.rpc("memory_remember", w.entry(body="late"), ids[0])
    assert r["conflict"] is True and r["head_id"] == ids[2]
