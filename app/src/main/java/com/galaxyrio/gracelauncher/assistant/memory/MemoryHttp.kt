package com.galaxyrio.gracelauncher.assistant.memory

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class MemoryHttpReply(val code: Int, val body: String)
internal typealias MemoryTransport = suspend (url: String, headers: Map<String, String>, body: String) -> MemoryHttpReply

internal class MemoryHttpRequest(val url: String, val headers: Map<String, String>, val body: String)

private val READS = setOf("memory_recall", "memory_history", "memory_changes", "memory_export")

/** The token only ever goes into the `x-grace-token` header; never into the URL, body or an exception message. */
internal fun buildRpcRequest(baseUrl: String, anonKey: String, token: String, fn: String, args: Map<String, Any?>): MemoryHttpRequest {
    require(fn in MemoryRpcContract.FUNCTIONS) { "unknown rpc" }
    val headers = linkedMapOf(
        "apikey" to anonKey,
        MemoryRpcContract.TOKEN_HEADER to token,
        "Content-Type" to "application/json",
        "Accept" to "application/json",
        "Content-Profile" to MemoryRpcContract.PROFILE,
    )
    if (fn in READS) headers["Accept-Profile"] = MemoryRpcContract.PROFILE
    return MemoryHttpRequest("${baseUrl.trimEnd('/')}/rest/v1/rpc/$fn", headers, toJson(args).toString())
}

internal fun toJson(v: Any?): Any = when (v) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), toJson(x)) } }
    is Collection<*> -> JSONArray().also { a -> v.forEach { a.put(toJson(it)) } }
    else -> v
}

internal fun fromJson(v: Any?): Any? = when (v) {
    null, JSONObject.NULL -> null
    is JSONObject -> v.keys().asSequence().associateWith { fromJson(v.get(it)) }
    is JSONArray -> (0 until v.length()).map { fromJson(v.get(it)) }
    else -> v
}

private const val MAX_BODY = 4 * 1024 * 1024

/** Plain HTTPS POST, no redirects, bounded body, socket interrupted on cancellation (same pattern as HealthMcpClient). */
internal suspend fun memoryUrlConnectionTransport(url: String, headers: Map<String, String>, body: String): MemoryHttpReply =
    withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        coroutineScope {
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { connection.disconnect() } }
            try {
                connection.apply {
                    requestMethod = "POST"; instanceFollowRedirects = false
                    connectTimeout = 10_000; readTimeout = 20_000
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val data = stream?.use { input ->
                    val buffer = ByteArray(MAX_BODY + 1)
                    var size = 0
                    while (size < buffer.size) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer, size, buffer.size - size)
                        if (n < 0) break
                        size += n
                    }
                    buffer.copyOf(size)
                } ?: ByteArray(0)
                if (data.size > MAX_BODY) throw IOException("too large")
                MemoryHttpReply(code, String(data, Charsets.UTF_8))
            } finally { cancellation.cancel(); connection.disconnect() }
        }
    }

/**
 * Null (local-only, nothing sent) unless the build has a Supabase URL + anon key and a `hc_` token is saved.
 * [tokenSaved] must be cheap (UI thread safe); [token] may block and runs on the IO dispatcher.
 */
internal fun memoryRpcOrNull(
    baseUrl: String, anonKey: String, tokenSaved: () -> Boolean, token: () -> String?,
    transport: MemoryTransport = ::memoryUrlConnectionTransport,
): MemoryRpc? =
    if (baseUrl.isBlank() || anonKey.isBlank() || !tokenSaved()) null else HttpMemoryRpc(baseUrl, anonKey, token, transport)

/** Errors never carry response bodies or the token. Network/auth/server → IOException (retried); permanent write refusal → [MemoryRpcRejected]. */
internal class HttpMemoryRpc(
    private val baseUrl: String, private val anonKey: String,
    private val token: () -> String?, private val transport: MemoryTransport,
) : MemoryRpc {
    private suspend fun call(fn: String, args: Map<String, Any?>, write: Boolean): Any? = withContext(Dispatchers.IO) {
        val t = token() ?: throw IOException("no token")
        val request = buildRpcRequest(baseUrl, anonKey, t, fn, args)
        val reply = try {
            transport(request.url, request.headers, request.body)
        } catch (e: CancellationException) { throw e }
        catch (e: IOException) { throw IOException("network") }
        catch (_: Exception) { throw IOException("transport") }
        if (reply.code !in 200..299) throw failure(reply, write)
        try { fromJson(org.json.JSONTokener(reply.body).nextValue()) } catch (_: Exception) { throw IOException("protocol") }
    }

    private fun failure(reply: MemoryHttpReply, write: Boolean): Exception {
        val message = try { JSONObject(reply.body).optString("message") } catch (_: Exception) { "" }
        return if (write && reply.code == 400 && message in PERMANENT) MemoryRpcRejected(message) else IOException("http ${reply.code}")
    }

    private fun map(v: Any?): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return v as? Map<String, Any?> ?: throw IOException("protocol")
    }

    private fun rows(v: Any?): List<MemoryEntry> {
        val list = v as? List<*> ?: throw IOException("protocol")
        return list.mapNotNull { r -> (r as? Map<*, *>)?.let { @Suppress("UNCHECKED_CAST") entryFromRpc(it as Map<String, Any?>) } }
    }

    override suspend fun remember(entry: MemoryEntry, supersedeId: String?): RememberResult {
        val r = map(call("memory_remember", mapOf("p_entry" to entry.toRpcEntry(), "p_supersede_id" to supersedeId), write = true))
        return RememberResult(r["conflict"] == true, r["head_id"] as? String)
    }

    override suspend fun forget(id: String): Boolean = map(call("memory_forget", mapOf("p_id" to id), write = true))["forgotten"] == true

    override suspend fun purge(id: String, confirm: String): Int =
        (map(call("memory_purge", mapOf("p_id" to id, "p_confirm" to confirm), write = true))["purged"] as? Number)?.toInt() ?: throw IOException("protocol")

    override suspend fun recall(query: String, limit: Int): List<MemoryEntry> =
        rows(call("memory_recall", mapOf("p_query" to query, "p_limit" to limit), write = false))

    override suspend fun history(id: String): List<MemoryEntry> = rows(call("memory_history", mapOf("p_id" to id), write = false))

    override suspend fun changes(sinceSeq: Long): MemoryChanges {
        val r = map(call("memory_changes", mapOf("p_since_seq" to sinceSeq), write = false))
        val purged = (r["purged"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("id") as? String } ?: throw IOException("protocol")
        val max = (r["max_seq"] as? Number)?.toLong() ?: throw IOException("protocol")
        return MemoryChanges(rows(r["entries"]), purged, max)
    }

    override suspend fun export(): List<MemoryEntry> = rows(call("memory_export", emptyMap(), write = false))

    private companion object {
        val PERMANENT = setOf("bad_entry", "confirm_required")
    }
}
