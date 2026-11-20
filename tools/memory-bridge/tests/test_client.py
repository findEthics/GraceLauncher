import json

import httpx
import pytest
import respx

from memory_bridge.client import BridgeError, Config, RpcClient
from conftest import ANON, TOKEN, URL

RPC = f"{URL}/rest/v1/rpc"


def client(env):
    return RpcClient(Config.from_env())


@respx.mock
def test_headers_and_path(env):
    route = respx.post(f"{RPC}/memory_export").mock(return_value=httpx.Response(200, json=[]))
    client(env).call("memory_export", {})
    req = route.calls.last.request
    assert req.headers["apikey"] == ANON
    assert req.headers["x-grace-token"] == TOKEN
    assert req.headers["content-profile"] == "grace_memory"
    assert req.headers["accept-profile"] == "grace_memory"


@respx.mock
def test_token_not_in_url_or_body(env):
    route = respx.post(f"{RPC}/memory_forget").mock(return_value=httpx.Response(200, json={"forgotten": True}))
    client(env).call("memory_forget", {"p_id": "x"})
    req = route.calls.last.request
    assert TOKEN not in str(req.url)
    assert TOKEN.encode() not in req.content
    assert json.loads(req.content) == {"p_id": "x"}


@respx.mock
@pytest.mark.parametrize("code", ["invalid_token", "bad_entry", "confirm_required"])
def test_error_codes_mapped(env, code):
    respx.post(f"{RPC}/memory_forget").mock(
        return_value=httpx.Response(400, json={"code": "P0001", "message": code, "details": None})
    )
    with pytest.raises(BridgeError) as e:
        client(env).call("memory_forget", {})
    assert e.value.code == code
    assert TOKEN not in str(e.value)


@respx.mock
def test_unknown_error_is_generic_and_leak_free(env):
    respx.post(f"{RPC}/memory_forget").mock(
        return_value=httpx.Response(500, json={"message": f"boom {TOKEN}"})
    )
    with pytest.raises(BridgeError) as e:
        client(env).call("memory_forget", {})
    assert e.value.code == "rpc_error"
    assert TOKEN not in str(e.value) and "500" in str(e.value)


@respx.mock
def test_network_error_leak_free(env):
    respx.post(f"{RPC}/memory_export").mock(side_effect=httpx.ConnectError(f"fail {TOKEN}"))
    with pytest.raises(BridgeError) as e:
        client(env).call("memory_export", {})
    assert e.value.code == "network_error"
    assert TOKEN not in str(e.value)


def test_missing_config_names_only(monkeypatch):
    for k in ("SUPABASE_URL", "SUPABASE_ANON_KEY", "GRACE_MEMORY_TOKEN"):
        monkeypatch.delenv(k, raising=False)
    monkeypatch.setenv("SUPABASE_URL", URL)
    with pytest.raises(BridgeError) as e:
        Config.from_env()
    msg = str(e.value)
    assert e.value.code == "missing_config"
    assert "SUPABASE_ANON_KEY" in msg and "GRACE_MEMORY_TOKEN" in msg
    assert URL not in msg
