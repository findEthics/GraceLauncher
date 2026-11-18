package com.galaxyrio.gracelauncher.assistant

import com.galaxyrio.gracelauncher.assistant.HealthFixtures.TOKEN_A
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fake transport only: this proves the client logic, not the real server (see the live smoke script). */
class HealthMcpClientTest {
    private class Call(val method: String, val url: String, val headers: Map<String, String>, val body: JSONObject?)

    private fun sse(vararg lines: String) = lines.joinToString("") { "event: message\ndata: $it\n\n" }
    private fun result(id: Int, result: String) = """{"jsonrpc":"2.0","id":$id,"result":$result}"""
    private fun text(obj: String) = JSONObject().put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", obj))).toString()
    private val tools = """{"tools":[{"name":"get_connection_status"},{"name":"get_coach_context"}]}"""

    private class Fake(val toolList: String, val statusOverride: HealthReply? = null, val onPost: (String) -> HealthReply? = { null }) {
        val calls = mutableListOf<Call>()
        suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HealthReply {
            val json = body?.let { JSONObject(it) }
            calls += Call(method, url, headers, json)
            if (method == "DELETE") return HealthReply(405, emptyMap(), "")
            onPost(json!!.optString("method"))?.let { return it }
            val id = json!!.optInt("id")
            return when (json.getString("method")) {
                "initialize" -> HealthReply(200, mapOf("mcp-session-id" to "s1"), "event: message\ndata: " + """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18"}}""" + "\n\n")
                "notifications/initialized" -> HealthReply(202, emptyMap(), "")
                "tools/list" -> HealthReply(200, emptyMap(), """{"jsonrpc":"2.0","id":$id,"result":$toolList}""")
                else -> {
                    val name = json.getJSONObject("params").getString("name")
                    val payload = if (name == "get_connection_status") """{"latest_metric_date":"2026-11-17"}""" else """{"mode":"${json.getJSONObject("params").getJSONObject("arguments").getString("mode")}"}"""
                    // A ping/notification precedes the response, as an SSE stream may do.
                    HealthReply(200, emptyMap(), "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/ping\"}\n\nevent: message\ndata: " +
                        """{"jsonrpc":"2.0","id":$id,"result":${JSONObject().put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", payload)))}}""" + "\n\n")
                }
            }
        }
    }

    private fun client(f: Fake, url: String = HEALTH_MCP_URL) = HealthMcpClient(f::send, url, "1.2.9")
    private fun <T : HealthFailure> failure(block: suspend () -> Unit): HealthFailure = runBlocking {
        try { block(); fail("no failure"); error("unreachable") } catch (e: HealthFailure) { e }
    }

    @Test fun handshakeOrderHeadersAndWeeklyOnlyWhenAsked() = runBlocking {
        val f = Fake(tools)
        val snap = client(f).fetch(TOKEN_A, "Europe/Berlin", weekly = true)
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/call", "tools/call", "tools/call", null), f.calls.map { it.body?.optString("method") })
        assertEquals("DELETE", f.calls.last().method)
        assertEquals(listOf("get_connection_status", "get_coach_context", "get_coach_context"), f.calls.filter { it.body?.optString("method") == "tools/call" }.map { it.body!!.getJSONObject("params").getString("name") })
        val modes = f.calls.filter { it.body?.optString("method") == "tools/call" }.drop(1).map { it.body!!.getJSONObject("params").getJSONObject("arguments") }
        assertEquals(listOf("chat_reply", "weekly_coach"), modes.map { it.getString("mode") }); assertTrue(modes.all { it.getString("timezone") == "Europe/Berlin" })
        assertEquals(setOf("chat_reply", "weekly_coach"), snap.contexts.keys)
        val init = f.calls[0]
        assertEquals("Bearer $TOKEN_A", init.headers["authorization"]); assertEquals("application/json", init.headers["content-type"])
        assertEquals("application/json, text/event-stream", init.headers["accept"]); assertNull(init.headers["mcp-session-id"])
        assertEquals("2025-06-18", init.body!!.getJSONObject("params").getString("protocolVersion"))
        assertEquals("s1", f.calls[2].headers["mcp-session-id"]); assertEquals("2025-06-18", f.calls[2].headers["mcp-protocol-version"])
        assertTrue(f.calls.all { it.url == HEALTH_MCP_URL })
        val plain = Fake(tools); client(plain).fetch(TOKEN_A, "UTC", weekly = false)
        assertEquals(2, plain.calls.count { it.body?.optString("method") == "tools/call" })
    }

    @Test fun plainJsonResponseIsAccepted() = runBlocking {
        val f = Fake(tools, onPost = { m -> if (m == "tools/list") HealthReply(200, emptyMap(), result(2, tools)) else null })
        assertEquals("2026-11-17", client(f).verify(TOKEN_A).getString("latest_metric_date"))
    }

    @Test fun errorTaxonomy() {
        assertTrue(failure<HealthFailure.Unauthorized> { client(Fake(tools, onPost = { HealthReply(401, emptyMap(), "{}") })).verify(TOKEN_A) } is HealthFailure.Unauthorized)
        assertEquals(503, (failure<HealthFailure.Server> { client(Fake(tools, onPost = { HealthReply(503, emptyMap(), "") })).verify(TOKEN_A) } as HealthFailure.Server).code)
        assertTrue(failure<HealthFailure.Offline> { HealthMcpClient({ _, _, _, _ -> throw IOException() }).verify(TOKEN_A) } is HealthFailure.Offline)
        assertTrue(failure<HealthFailure.Protocol> { client(Fake("""{"tools":[{"name":"get_connection_status"}]}""")).verify(TOKEN_A) } is HealthFailure.Protocol)
        assertTrue(failure<HealthFailure.Protocol> { client(Fake(tools, onPost = { m -> if (m == "initialize") HealthReply(200, emptyMap(), result(1, """{"protocolVersion":"2025-06-18"}""")) else null })).verify(TOKEN_A) } is HealthFailure.Protocol) // no session id
        val isError = Fake(tools, onPost = { m -> if (m == "tools/call") HealthReply(200, emptyMap(), result(10, """{"isError":true,"content":[{"type":"text","text":"x"}]}""")) else null })
        assertTrue(failure<HealthFailure.ToolError> { client(isError).verify(TOKEN_A) } is HealthFailure.ToolError)
        val notJson = Fake(tools, onPost = { m -> if (m == "tools/call") HealthReply(200, emptyMap(), result(10, text("not json"))) else null })
        assertTrue(failure<HealthFailure.Protocol> { client(notJson).verify(TOKEN_A) } is HealthFailure.Protocol)
    }

    @Test fun nonHttpsRefusedBeforeAnyRequestAndOversizedBodyRejected() {
        val f = Fake(tools)
        assertTrue(failure<HealthFailure.Protocol> { client(f, "http://findethics-health-dashboard.hf.space/api/mcp/").verify(TOKEN_A) } is HealthFailure.Protocol)
        assertTrue(f.calls.isEmpty())
        val big = Fake(tools, onPost = { m -> if (m == "tools/list") HealthReply(200, emptyMap(), "x".repeat(HealthMcpClient.MAX_BODY_BYTES + 1)) else null })
        assertTrue(failure<HealthFailure.Protocol> { client(big).verify(TOKEN_A) } is HealthFailure.Protocol)
    }

    @Test fun onlyAllowListedToolsAndModesCanBeCalled() = runBlocking {
        val f = Fake(tools)
        val session = client(f).Session(TOKEN_A, "s1", "2025-06-18")
        val bad = listOf("run_query" to JSONObject(), "write_note" to JSONObject(), "get_coach_context " to JSONObject().put("mode", "chat_reply"),
            "get_coach_context" to JSONObject().put("mode", "chart_explainer"), "get_coach_context" to JSONObject().put("mode", "chat_reply").put("user_id", "x"),
            "get_connection_status" to JSONObject().put("x", 1))
        for ((name, args) in bad) {
            try { session.call(name, args); fail("allowed $name") } catch (_: IllegalArgumentException) { }
        }
        assertTrue(f.calls.isEmpty())
    }
}
