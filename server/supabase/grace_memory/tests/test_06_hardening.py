"""Security-review P2 hardening: self-supersede/cycles and cross-user tombstones."""

import uuid
import psycopg
import pytest

TIMEOUT = (
    "3s"  # RED run fails fast with QueryCanceled instead of hanging on a looping CTE
)


def put(w, uid, id_, **kw):
    e = w.entry(id=id_, **kw)
    w.conn.execute(
        "insert into grace_memory.memory_entries(id,user_id,type,subject,subject_key,body,valid_from,source) "
        "values (%s,%s,%s,%s,lower(%s),%s,%s,%s)",
        (
            id_,
            uid,
            e["type"],
            e["subject"],
            e["subject"],
            e["body"],
            e["valid_from"],
            e["source"],
        ),
    )


def inject_cycle(w, kind):
    """Superuser-inserted cycle among A's rows; returns member ids."""
    a, b = str(uuid.uuid4()), str(uuid.uuid4())
    put(w, w.a, a)
    if kind == "self":
        w.conn.execute(
            "update grace_memory.memory_entries set status='superseded', superseded_by=id, supersedes=id where id=%s",
            (a,),
        )
        return [a]
    put(w, w.a, b, body="other")
    w.conn.execute(
        "update grace_memory.memory_entries set status='superseded', superseded_by=%s, supersedes=%s where id=%s",
        (b, b, a),
    )
    w.conn.execute(
        "update grace_memory.memory_entries set status='superseded', superseded_by=%s, supersedes=%s where id=%s",
        (a, a, b),
    )
    return [a, b]


def make_owner(w, uid):
    """Let B authenticate (owner is a singleton): swap personal_owner to uid and activate."""
    w.conn.execute("update public.users set is_active = (id = %s)", (uid,))
    w.conn.execute("update public.personal_owner set user_id = %s", (uid,))


def tomb(w, id_):
    return w.rows(
        "select id, user_id, purged_at, change_seq from grace_memory.purged_ids where id=%s order by user_id",
        (id_,),
    )


# 1. self-supersede ---------------------------------------------------------------
def test_self_supersede_rejected(w):
    e = w.entry()
    with pytest.raises(psycopg.errors.RaiseException, match="bad_entry"):
        w.rpc("memory_remember", e, e["id"])
    assert w.rows("select count(*) from grace_memory.memory_entries") == [(0,)]


# 2. pre-existing cycles terminate --------------------------------------------------
@pytest.mark.parametrize("kind", ["self", "two"])
def test_cycle_purge_terminates(w, kind):
    ids = inject_cycle(w, kind)
    w.conn.execute(f"set statement_timeout = '{TIMEOUT}'")
    assert w.rpc("memory_purge", ids[0], "PURGE")["purged"] == len(ids)
    assert w.rows("select count(*) from grace_memory.memory_entries") == [(0,)]


@pytest.mark.parametrize("kind", ["self", "two"])
def test_cycle_history_terminates(w, kind):
    ids = inject_cycle(w, kind)
    w.conn.execute(f"set statement_timeout = '{TIMEOUT}'")
    assert sorted(r["id"] for r in w.rpc("memory_history", ids[0])) == sorted(ids)


@pytest.mark.parametrize("kind", ["self", "two"])
def test_cycle_remember_head_follow_terminates(w, kind):
    ids = inject_cycle(w, kind)
    w.conn.execute(f"set statement_timeout = '{TIMEOUT}'")
    new = w.entry(body="late")
    r = w.rpc("memory_remember", new, ids[0])
    assert r == {
        "conflict": True,
        "head_id": None,
        "id": new["id"],
    }  # no active head in a cycle


# 3. cross-user tombstones ----------------------------------------------------------
def test_remember_rejects_id_tombstoned_by_other_user(w):
    x = w.entry()
    w.rpc("memory_remember", x, None)
    w.rpc("memory_purge", x["id"], "PURGE")
    make_owner(w, w.b)
    with pytest.raises(psycopg.errors.RaiseException, match="bad_entry"):
        w.rpc("memory_remember", x, None, token=w.tok_b)
    assert w.rows("select count(*) from grace_memory.memory_entries") == [(0,)]
    assert [t[1] for t in tomb(w, x["id"])] == [w.a]


def test_remember_allows_own_tombstoned_id(w):
    x = w.entry()
    w.rpc("memory_remember", x, None)
    w.rpc("memory_purge", x["id"], "PURGE")
    assert w.rpc("memory_remember", x, None)["conflict"] is False


def test_purge_never_modifies_other_users_tombstone(w):
    # Pre-fix reachable state: A's tombstone for X, then B holds a live X (inserted directly now).
    x = str(uuid.uuid4())
    w.conn.execute("insert into grace_memory.purged_ids(id,user_id,change_seq) values (%s,%s,0)", (x, w.a))
    a_before = tomb(w, x)
    put(w, w.b, x)
    make_owner(w, w.b)
    assert w.rpc("memory_purge", x, "PURGE", token=w.tok_b)["purged"] == 1
    assert tomb(w, x) == a_before  # A's tombstone untouched (incl. purged_at/change_seq); B's purge writes none


def test_changes_returns_only_callers_tombstones(w):
    x, y = str(uuid.uuid4()), str(uuid.uuid4())
    w.conn.execute("insert into grace_memory.purged_ids(id,user_id,change_seq) values (%s,%s,0),(%s,%s,0)", (x, w.a, y, w.b))
    a_seq, b_seq = tomb(w, x)[0][3], tomb(w, y)[0][3]
    a_ch = w.rpc("memory_changes", 0)
    assert [p["id"] for p in a_ch["purged"]] == [x] and a_ch["purged"][0]["change_seq"] == a_seq
    make_owner(w, w.b)
    b_ch = w.rpc("memory_changes", 0, token=w.tok_b)
    assert [p["id"] for p in b_ch["purged"]] == [y] and b_ch["purged"][0]["change_seq"] == b_seq
