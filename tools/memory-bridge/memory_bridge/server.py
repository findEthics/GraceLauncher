"""stdio MCP server mirroring the ledger RPCs. Tools never take a token; config is read from env per call."""
from __future__ import annotations

import uuid
from typing import Any

from mcp.server.fastmcp import FastMCP

from .client import BridgeError, Config, RpcClient
from .export import bundle, render

mcp = FastMCP("memory-bridge")

TYPES = ("fact", "preference", "event", "place", "note")
_USER_ONLY = " Only on explicit user request."


def _rpc(fn: str, body: dict[str, Any]) -> Any:
    return RpcClient(Config.from_env()).call(fn, body)


def _uuid(v: str) -> str:
    try:
        return str(uuid.UUID(v))
    except (ValueError, AttributeError, TypeError):
        raise BridgeError("bad_entry", "bad_entry: id is not a valid uuid") from None


@mcp.tool(description="Save a memory (type: fact|preference|event|place|note). Pass supersede_id to replace an existing active entry; "
          "a result with conflict=true means that entry was no longer active and head_id is the current head." + _USER_ONLY)
def memory_remember(type: str, subject: str, body: str, occurred_at: str | None = None,
                    supersede_id: str | None = None, client: str = "agent") -> dict:
    subj, text, who = subject.strip(), body.strip(), client.strip()
    if (type not in TYPES or not 1 <= len(subj) <= 200 or not 1 <= len(text) <= 8000
            or not 1 <= len(who) <= 90):
        raise BridgeError("bad_entry", "bad_entry: invalid type, subject, body or client")
    entry = {"id": str(uuid.uuid4()), "type": type, "subject": subj, "body": body,
             "source": f"bridge:{who}"}
    if occurred_at:
        entry["occurred_at"] = occurred_at
    sid = _uuid(supersede_id) if supersede_id else None
    return _rpc("memory_remember", {"p_entry": entry, "p_supersede_id": sid})


@mcp.tool(description="Search active memories (empty query lists newest). limit 1-50.")
def memory_recall(query: str = "", limit: int = 10) -> list:
    return _rpc("memory_recall", {"p_query": query, "p_limit": limit})


@mcp.tool(description="Soft-forget an active memory by id (kept in history)." + _USER_ONLY)
def memory_forget(id: str) -> dict:
    return _rpc("memory_forget", {"p_id": _uuid(id)})


@mcp.tool(description="PERMANENTLY delete a memory and its whole supersede chain. Requires confirm='PURGE'." + _USER_ONLY)
def memory_purge(id: str, confirm: str | None = None) -> dict:
    if confirm != "PURGE":  # checked before any HTTP
        raise BridgeError("confirm_required", "confirm_required: purge needs confirm='PURGE'")
    return _rpc("memory_purge", {"p_id": _uuid(id), "p_confirm": "PURGE"})


@mcp.tool(description="Full version chain (all versions, oldest first) of the memory with this id.")
def memory_history(id: str) -> list:
    return _rpc("memory_history", {"p_id": _uuid(id)})


@mcp.tool(description="Entries and purge tombstones changed after since_seq; returns max_seq to resume from.")
def memory_changes(since_seq: int = 0) -> dict:
    return _rpc("memory_changes", {"p_since_seq": since_seq})


@mcp.tool(description="Export the whole ledger as deterministic markdown: index.md, subjects/*.md, log.md concatenated with ===== path ===== headers.")
def memory_export() -> str:
    return bundle(render(_rpc("memory_export", {})))


def main() -> None:
    mcp.run("stdio")
