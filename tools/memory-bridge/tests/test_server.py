import asyncio
import json
import uuid

import httpx
import pytest
import respx

from memory_bridge import server
from memory_bridge.client import BridgeError
from conftest import ANON, TOKEN, URL

RPC = f"{URL}/rest/v1/rpc"
ID = "00000000-0000-0000-0000-000000000001"
SPEC_SIX = {"memory_remember", "memory_recall", "memory_forget", "memory_purge", "memory_history", "memory_export"}


def route(fn, payload):
    return respx.post(f"{RPC}/{fn}").mock(return_value=httpx.Response(200, json=payload))


def sent(r):
    return json.loads(r.calls.last.request.content)


def test_tools_listed_no_token_param():
    tools = asyncio.run(server.mcp.list_tools())
    names = {t.name for t in tools}
    assert SPEC_SIX <= names and names == SPEC_SIX | {"memory_changes"}
    for t in tools:
        props = " ".join(t.inputSchema.get("properties", {}))
        assert "token" not in props.lower() and "key" not in props.lower()
        if t.name in {"memory_remember", "memory_forget", "memory_purge"}:
            assert "user request" in t.description.lower()


@respx.mock
def test_remember_body_and_result(env):
    r = route("memory_remember", {"conflict": False, "id": "x"})
    out = server.memory_remember(type="fact", subject="Home", body="Berlin", client="claude")
    entry = sent(r)["p_entry"]
    uuid.UUID(entry["id"])
    assert entry["source"] == "bridge:claude" and entry["type"] == "fact"
    assert sent(r)["p_supersede_id"] is None
    assert out == {"conflict": False, "id": "x"}
    assert TOKEN not in json.dumps(sent(r)) and TOKEN not in json.dumps(out)


@respx.mock
def test_remember_supersede_and_conflict_surfaced(env):
    r = route("memory_remember", {"conflict": True, "head_id": "h", "id": "n"})
    out = server.memory_remember(type="fact", subject="Home", body="Hamburg", supersede_id=ID, occurred_at="2026-01-01T00:00:00Z")
    assert sent(r)["p_supersede_id"] == ID and sent(r)["p_entry"]["occurred_at"] == "2026-01-01T00:00:00Z"
    assert out["conflict"] is True and out["head_id"] == "h"


@respx.mock
@pytest.mark.parametrize(
    "kw",
    [
        dict(type="nope", subject="s", body="b"),
        dict(type="fact", subject="", body="b"),
        dict(type="fact", subject="s" * 201, body="b"),
        dict(type="fact", subject="s", body="  "),
        dict(type="fact", subject="s", body="b" * 8001),
        dict(type="fact", subject="s", body="b", supersede_id="not-a-uuid"),
        dict(type="fact", subject="s", body="b", client="c" * 100),
    ],
)
def test_remember_validation_no_http(env, kw):
    r = route("memory_remember", {})
    with pytest.raises(BridgeError) as e:
        server.memory_remember(**kw)
    assert e.value.code == "bad_entry" and not r.called


@respx.mock
def test_forget_recall_history_changes_export_paths(env):
    f = route("memory_forget", {"forgotten": True})
    rc = route("memory_recall", [])
    h = route("memory_history", [])
    c = route("memory_changes", {"entries": [], "purged": [], "max_seq": 4})
    ex = route("memory_export", [])
    assert server.memory_forget(ID) == {"forgotten": True} and sent(f) == {"p_id": ID}
    server.memory_recall("coffee", 5)
    assert sent(rc) == {"p_query": "coffee", "p_limit": 5}
    server.memory_history(ID)
    assert sent(h) == {"p_id": ID}
    assert server.memory_changes(3)["max_seq"] == 4 and sent(c) == {"p_since_seq": 3}
    assert server.memory_export().startswith("===== index.md =====")
    assert sent(ex) == {}
    for rt in (f, rc, h, c, ex):
        hd = rt.calls.last.request.headers
        assert hd["apikey"] == ANON and hd["x-grace-token"] == TOKEN
        assert hd["content-profile"] == hd["accept-profile"] == "grace_memory"


@respx.mock
@pytest.mark.parametrize("confirm", [None, "", "purge", "yes", "PURGE "])
def test_purge_without_confirm_no_http(env, confirm):
    r = route("memory_purge", {"purged": 1})
    with pytest.raises(BridgeError) as e:
        server.memory_purge(ID, confirm)
    assert e.value.code == "confirm_required" and not r.called


@respx.mock
def test_purge_with_confirm_sends_PURGE(env):
    r = route("memory_purge", {"purged": 2})
    assert server.memory_purge(ID, "PURGE") == {"purged": 2}
    assert sent(r) == {"p_id": ID, "p_confirm": "PURGE"}


@respx.mock
def test_error_code_mapped_through_tool(env):
    respx.post(f"{RPC}/memory_recall").mock(return_value=httpx.Response(400, json={"message": "invalid_token"}))
    with pytest.raises(BridgeError) as e:
        server.memory_recall("x")
    assert e.value.code == "invalid_token" and TOKEN not in str(e.value)


def test_mcp_call_error_is_tool_error_without_leak(monkeypatch):
    for k in ("SUPABASE_URL", "SUPABASE_ANON_KEY", "GRACE_MEMORY_TOKEN"):
        monkeypatch.delenv(k, raising=False)
    monkeypatch.setenv("SUPABASE_URL", URL)
    with pytest.raises(Exception) as e:
        asyncio.run(server.mcp.call_tool("memory_recall", {"query": "x"}))
    assert "missing_config" in str(e.value) and URL not in str(e.value)
