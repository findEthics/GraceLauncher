"""httpx → Supabase PostgREST RPC. The token only ever goes in the x-grace-token header."""
from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any

import httpx

SCHEMA = "grace_memory"
CODES = ("invalid_token", "bad_entry", "confirm_required")
_MESSAGES = {
    "invalid_token": "invalid_token: the memory token was rejected",
    "bad_entry": "bad_entry: the ledger rejected the entry",
    "confirm_required": "confirm_required: purge needs confirm='PURGE'",
}
_ENV = ("SUPABASE_URL", "SUPABASE_ANON_KEY", "GRACE_MEMORY_TOKEN")


class BridgeError(Exception):
    """Short stable code + fixed message; never carries config values or server text."""

    def __init__(self, code: str, message: str | None = None):
        self.code = code
        super().__init__(message or code)


@dataclass(frozen=True)
class Config:
    url: str
    anon_key: str
    token: str

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> "Config":
        env = os.environ if env is None else env
        vals = {k: (env.get(k) or "").strip() for k in _ENV}
        missing = [k for k, v in vals.items() if not v]
        if missing:
            raise BridgeError("missing_config", "missing_config: set env " + ", ".join(missing))
        return cls(vals["SUPABASE_URL"].rstrip("/"), vals["SUPABASE_ANON_KEY"], vals["GRACE_MEMORY_TOKEN"])


class RpcClient:
    def __init__(self, config: Config, timeout: float = 30.0):
        self._c = config
        self._timeout = timeout

    def call(self, fn: str, body: dict[str, Any]) -> Any:
        c = self._c
        headers = {
            "apikey": c.anon_key,
            "x-grace-token": c.token,
            "Content-Profile": SCHEMA,  # POST RPC
            "Accept-Profile": SCHEMA,  # reads; harmless on writes, keeps one code path
        }
        try:
            r = httpx.post(f"{c.url}/rest/v1/rpc/{fn}", json=body, headers=headers, timeout=self._timeout)
        except httpx.HTTPError:
            raise BridgeError("network_error", "network_error: request to the ledger failed") from None
        if r.status_code >= 400:
            try:
                msg = str(r.json().get("message", ""))
            except Exception:
                msg = ""
            for code in CODES:
                if code in msg:
                    raise BridgeError(code, _MESSAGES[code]) from None
            raise BridgeError("rpc_error", f"rpc_error: HTTP {r.status_code}") from None
        return r.json()
