"""Real stdio handshake against `python -m memory_bridge`; no network is touched (no tool is called)."""
import sys

import anyio
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


def test_stdio_lists_tools():
    async def go():
        params = StdioServerParameters(command=sys.executable, args=["-m", "memory_bridge"], env={})
        async with stdio_client(params) as (r, w):
            async with ClientSession(r, w) as s:
                await s.initialize()
                return {t.name for t in (await s.list_tools()).tools}

    names = anyio.run(go)
    assert names == {"memory_remember", "memory_recall", "memory_forget", "memory_purge",
                     "memory_history", "memory_changes", "memory_export"}
