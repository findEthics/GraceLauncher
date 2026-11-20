import json
import psycopg
import pytest


def caller(w, header):
    """Call the private helper as its owner role (anon cannot), under FORCE RLS stubs."""
    with w.conn.transaction():
        w.conn.execute("select set_config('request.headers', %s, true)", (header,))
        w.conn.execute("set local role grace_memory_owner")
        return w.conn.execute("select grace_memory._caller_user_id()").fetchone()[0]


def hdr(tok):
    return json.dumps({"x-grace-token": tok})


def test_valid_active_owner_token(w):
    assert caller(w, hdr(w.tok)) == w.a


def test_whitespace_padded_token_accepted(w):
    assert caller(w, hdr("  " + w.tok + "  ")) == w.a


def bad_headers(w):
    return {
        "revoked": hdr(w.tok_revoked),
        "unknown": hdr(w.tok_unknown),
        "non_owner_inactive": hdr(w.tok_b),
        "missing_header": json.dumps({"other": "x"}),
        "empty": hdr(""),
        "whitespace": hdr("   "),
        "null_value": json.dumps({"x-grace-token": None}),
        "malformed_json": "{not json",
        "empty_headers": "",
    }


def test_all_failures_are_identical_invalid_token(w):
    msgs = set()
    for name, h in bad_headers(w).items():
        with pytest.raises(psycopg.errors.RaiseException) as ei:
            caller(w, h)
        msgs.add((ei.value.diag.message_primary, ei.value.diag.message_detail, ei.value.diag.message_hint))
    assert msgs == {("invalid_token", None, None)}


def test_unset_headers_setting_rejected(w):
    with pytest.raises(psycopg.errors.RaiseException, match="invalid_token"):
        with w.conn.transaction():
            w.conn.execute("set local role grace_memory_owner")
            w.conn.execute("select grace_memory._caller_user_id()")


def test_error_never_echoes_token(w):
    for tok in (w.tok_revoked, w.tok_unknown, w.tok_b):
        with pytest.raises(psycopg.errors.RaiseException) as ei:
            caller(w, hdr(tok))
        assert tok not in str(ei.value)
        assert tok not in "".join(str(x) for x in (ei.value.diag.message_primary, ei.value.diag.context))


def test_inactive_owner_rejected(w):
    w.conn.execute("update public.users set is_active=false where id=%s", (w.a,))
    with pytest.raises(psycopg.errors.RaiseException, match="invalid_token"):
        caller(w, hdr(w.tok))


def test_token_of_non_singleton_owner_rejected_even_if_active(w):
    w.conn.execute("update public.users set is_active=true where id=%s", (w.b,))
    with pytest.raises(psycopg.errors.RaiseException, match="invalid_token"):
        caller(w, hdr(w.tok_b))


def test_anon_cannot_call_helper(w):
    with pytest.raises(psycopg.errors.InsufficientPrivilege):
        w.rpc("_caller_user_id")


def test_bypassrls_is_what_makes_force_rls_readable(w):
    w.conn.execute("alter role grace_memory_owner nobypassrls")
    try:
        with pytest.raises(psycopg.errors.RaiseException, match="invalid_token"):
            caller(w, hdr(w.tok))  # FORCE RLS without policies hides every row
    finally:
        w.conn.execute("alter role grace_memory_owner bypassrls")
