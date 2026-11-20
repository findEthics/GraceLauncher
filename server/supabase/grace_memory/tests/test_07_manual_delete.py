import re
import uuid
from conftest import HERE

SQL = (HERE.parent / "manual-delete.sql").read_text()
QUERIES = {int(n): body.strip() for n, body in re.findall(r"^-- @query (\d+)[^\n]*\n(.*?)(?=^-- @query |\Z)", SQL, re.S | re.M)}


def seed(w):
    """Owner chain a<-b<-c, unrelated note u, and another user's note x."""
    ids = {k: str(uuid.uuid4()) for k in "abcux"}
    rows = [("a", w.a, None, ids["b"]), ("b", w.a, ids["a"], ids["c"]), ("c", w.a, ids["b"], None),
            ("u", w.a, None, None), ("x", w.b, None, None)]
    for k, uid, sup, sby in rows:
        w.conn.execute(
            "insert into grace_memory.memory_entries(id,user_id,type,subject,subject_key,body,valid_from,source) "
            "values (%s,%s,'fact',%s,%s,'b','2026-01-01T00:00:00Z','test')", (ids[k], uid, "S" + k, "s" + k))
    # links after insert (self-FKs; set all in one statement per row is fine, targets now exist)
    for k, _, sup, sby in rows:
        w.conn.execute("update grace_memory.memory_entries set supersedes=%s, superseded_by=%s where id=%s", (sup, sby, ids[k]))
    return ids


def test_queries_extracted():
    assert sorted(QUERIES) == [1, 2, 3, 4]


def test_list_queries_only_owner(w):
    ids = seed(w)
    got = {str(r[0]) for r in w.conn.execute(QUERIES[1]).fetchall()}
    assert got == {ids[k] for k in "abcu"}
    assert w.conn.execute(QUERIES[4]).fetchall() == []


def test_purge_one_chain_tombstones_and_changes(w):
    ids = seed(w)
    w.conn.execute(QUERIES[2].replace("<NOTE-ID>", ids["b"]))  # any chain member purges the whole chain
    left = {str(r[0]) for r in w.rows("select id from grace_memory.memory_entries")}
    assert left == {ids["u"], ids["x"]}
    tomb = {str(r[0]) for r in w.conn.execute(QUERIES[4]).fetchall()}
    assert tomb == {ids[k] for k in "abc"}
    ch = w.rpc("memory_changes", 0)
    assert {p["id"] for p in ch["purged"]} == {ids[k] for k in "abc"}
    assert all(p["change_seq"] > 0 for p in ch["purged"])


def test_purge_other_users_note_is_a_noop(w):
    ids = seed(w)
    w.conn.execute(QUERIES[2].replace("<NOTE-ID>", ids["x"]))
    assert len(w.rows("select 1 from grace_memory.memory_entries")) == 5
    assert w.rows("select 1 from grace_memory.purged_ids") == []


def test_purge_all_owner_rows_only(w):
    ids = seed(w)
    w.conn.execute(QUERIES[3])
    assert [str(r[0]) for r in w.rows("select id from grace_memory.memory_entries")] == [ids["x"]]
    assert {str(r[0]) for r in w.rows("select id from grace_memory.purged_ids")} == {ids[k] for k in "abcu"}
    assert w.rows("select count(*) from grace_memory.purged_ids where change_seq <= 0") == [(0,)]
