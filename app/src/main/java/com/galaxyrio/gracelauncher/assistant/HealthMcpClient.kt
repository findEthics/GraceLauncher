package com.galaxyrio.gracelauncher.assistant

import com.galaxyrio.gracelauncher.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import org.json.JSONObject

const val HEALTH_MCP_URL = "https://findethics-health-dashboard.hf.space/api/mcp/"

/** Data from one Health turn. [contexts] holds "chat_reply" and, when asked for, "weekly_coach". */
internal class HealthSnapshot(val status: JSONObject, val contexts: Map<String, JSONObject>)

internal sealed class HealthFailure(message: String) : Exception(message) {
    object Offline : HealthFailure("offline")
    object Unauthorized : HealthFailure("unauthorized")
    class Server(val code: Int) : HealthFailure("server $code")
    object Protocol : HealthFailure("protocol")
    object ToolError : HealthFailure("tool error")
}

internal class HealthReply(val code: Int, val headers: Map<String, String>, val body: String)

/** Lower-case header names in, lower-case header names out; throws IOException when the network fails. */
internal typealias HealthTransport = suspend (method: String, url: String, headers: Map<String, String>, body: String?) -> HealthReply

/**
 * Minimal Streamable-HTTP MCP client for the health-coach server. One session per turn; only the two
 * read-only tools can ever be called. Never logs tokens, bodies or health values.
 */
internal class HealthMcpClient(
    private val transport: HealthTransport = ::urlConnectionTransport,
    private val url: String = HEALTH_MCP_URL,
    private val clientVersion: String = BuildConfig.VERSION_NAME,
) {
    /** get_connection_status → chat_reply [→ weekly_coach]. */
    suspend fun fetch(token: String, zone: String, weekly: Boolean): HealthSnapshot = session(token) { s ->
        val status = s.call(STATUS, JSONObject())
        val contexts = linkedMapOf("chat_reply" to s.call(CONTEXT, JSONObject().put("mode", "chat_reply").put("timezone", zone)))
        if (weekly) contexts["weekly_coach"] = s.call(CONTEXT, JSONObject().put("mode", "weekly_coach").put("timezone", zone))
        HealthSnapshot(status, contexts)
    }

    /** Settings "Save" check. */
    suspend fun verify(token: String): JSONObject = session(token) { it.call(STATUS, JSONObject()) }

    internal inner class Session(val token: String, val id: String, val version: String) {
        private var next = 10
        suspend fun call(name: String, args: JSONObject): JSONObject {
            require(name in ALLOWED_TOOLS) { "Tool is not allowed" }
            if (name == CONTEXT) require(args.optString("mode") in ALLOWED_MODES && args.keys().asSequence().all { it in setOf("mode", "timezone") }) { "Mode is not allowed" }
            else require(!args.keys().hasNext()) { "Arguments are not allowed" }
            val result = rpc(token, "tools/call", JSONObject().put("name", name).put("arguments", args), next++, id, version)
            if (result.optBoolean("isError", false)) throw HealthFailure.ToolError
            val content = result.optJSONArray("content")?.optJSONObject(0) ?: throw HealthFailure.Protocol
            if (content.optString("type") != "text") throw HealthFailure.Protocol
            return try { JSONObject(content.getString("text")) } catch (_: Exception) { throw HealthFailure.Protocol }
        }
    }

    private suspend fun <T> session(token: String, block: suspend (Session) -> T): T {
        if (!url.startsWith("https://")) throw HealthFailure.Protocol
        try {
            return withTimeout(TURN_TIMEOUT_MS) {
                val init = JSONObject().put("protocolVersion", PROTOCOL).put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "grace-launcher").put("version", clientVersion))
                val reply = post(token, JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize").put("params", init), null, null)
                val result = result(reply, 1)
                val version = result.optString("protocolVersion")
                if (version !in SUPPORTED) throw HealthFailure.Protocol
                val id = reply.headers["mcp-session-id"]?.takeIf { it.isNotBlank() } ?: throw HealthFailure.Protocol
                try {
                    post(token, JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), id, version) // expects 202
                    val tools = rpc(token, "tools/list", JSONObject(), 2, id, version).optJSONArray("tools") ?: throw HealthFailure.Protocol
                    val names = (0 until tools.length()).mapNotNull { tools.optJSONObject(it)?.optString("name") }.toSet()
                    if (!names.containsAll(ALLOWED_TOOLS)) throw HealthFailure.Protocol
                    block(Session(token, id, version))
                } finally {
                    // Best effort; 404/405 and every network error are ignored. Cancellation still propagates.
                    withContext(NonCancellable) { try { withTimeout(3_000) { transport("DELETE", url, headers(token, id, version, json = false), null) } } catch (_: Exception) { } }
                }
            }
        } catch (e: TimeoutCancellationException) { throw HealthFailure.Offline }
        catch (e: IOException) { throw HealthFailure.Offline }
    }

    private suspend fun rpc(token: String, method: String, params: JSONObject, id: Int, session: String?, version: String?): JSONObject =
        result(post(token, JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params), session, version), id)

    private suspend fun post(token: String, body: JSONObject, session: String?, version: String?): HealthReply =
        transport("POST", url, headers(token, session, version, json = true), body.toString()).also { check(it) }

    private fun headers(token: String, session: String?, version: String?, json: Boolean) = buildMap {
        put("authorization", "Bearer $token")
        if (json) { put("content-type", "application/json"); put("accept", "application/json, text/event-stream") }
        session?.let { put("mcp-session-id", it) }
        version?.let { put("mcp-protocol-version", it) }
    }

    private fun check(reply: HealthReply) {
        when {
            reply.code == 401 -> throw HealthFailure.Unauthorized
            reply.code !in 200..299 -> throw HealthFailure.Server(reply.code)
            reply.body.length > MAX_BODY_BYTES -> throw HealthFailure.Protocol
        }
    }

    /** First JSON-RPC message with a matching id from a JSON or SSE body; notifications/pings are skipped. */
    private fun result(reply: HealthReply, id: Int): JSONObject {
        val text = reply.body.trim()
        val messages = if (text.startsWith("{")) listOf(text) else text.lineSequence()
            .filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }.toList()
        for (data in messages) {
            val message = try { JSONObject(data) } catch (_: Exception) { continue }
            if (!message.has("id") || message.optInt("id", -1) != id) continue
            if (message.has("error")) throw HealthFailure.Protocol
            return message.optJSONObject("result") ?: throw HealthFailure.Protocol
        }
        throw HealthFailure.Protocol
    }

    companion object {
        const val STATUS = "get_connection_status"
        const val CONTEXT = "get_coach_context"
        val ALLOWED_TOOLS = setOf(STATUS, CONTEXT)
        val ALLOWED_MODES = setOf("chat_reply", "weekly_coach")
        const val PROTOCOL = "2025-06-18"
        val SUPPORTED = setOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")
        const val MAX_BODY_BYTES = 256 * 1024
        const val TURN_TIMEOUT_MS = 30_000L

        suspend fun urlConnectionTransport(method: String, url: String, headers: Map<String, String>, body: String?): HealthReply =
            withContext(Dispatchers.IO) {
                val connection = URL(url).openConnection() as HttpURLConnection
                coroutineScope {
                    // Interrupt an in-flight socket on cancellation, not after the read timeout.
                    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { connection.disconnect() } }
                    try {
                        connection.apply {
                            requestMethod = method; instanceFollowRedirects = false
                            connectTimeout = 10_000; readTimeout = 10_000
                            headers.forEach { (k, v) -> setRequestProperty(k, v) }
                        }
                        if (body != null) {
                            val bytes = body.toByteArray(Charsets.UTF_8)
                            connection.doOutput = true
                            connection.setFixedLengthStreamingMode(bytes.size)
                            connection.outputStream.use { it.write(bytes) }
                        }
                        val code = connection.responseCode
                        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                        val data = stream?.use { input ->
                            val buffer = ByteArray(MAX_BODY_BYTES + 1)
                            var size = 0
                            while (size < buffer.size) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer, size, buffer.size - size)
                                if (count < 0) break
                                size += count
                            }
                            buffer.copyOf(size)
                        } ?: ByteArray(0)
                        if (data.size > MAX_BODY_BYTES) throw HealthFailure.Protocol
                        val responseHeaders = connection.headerFields.filterKeys { it != null }
                            .mapKeys { it.key.lowercase() }.mapValues { it.value.firstOrNull().orEmpty() }
                        HealthReply(code, responseHeaders, String(data, Charsets.UTF_8))
                    } finally { cancellation.cancel(); connection.disconnect() }
                }
            }
    }
}
