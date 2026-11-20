package com.galaxyrio.gracelauncher.assistant.memory

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MemoryHttpTest {
    private val base = "https://x.supabase.co/"
    private val token = "hc_" + "A".repeat(43)

    @Test fun requestHasHeadersAndTokenOnlyInHeader() {
        val r = buildRpcRequest(base, "anon", token, "memory_remember", mapOf("p_entry" to mapOf("id" to "a"), "p_supersede_id" to null))
        assertEquals("https://x.supabase.co/rest/v1/rpc/memory_remember", r.url)
        assertEquals("anon", r.headers["apikey"]); assertEquals(token, r.headers["x-grace-token"])
        assertEquals("grace_memory", r.headers["Content-Profile"]); assertNull(r.headers["Accept-Profile"])
        assertFalse(r.url.contains(token)); assertFalse(r.body.contains(token)); assertFalse(r.body.contains("hc_"))
        assertTrue(r.body.contains("\"p_supersede_id\":null"))
    }

    @Test fun readsAlsoSendAcceptProfile() {
        for (fn in listOf("memory_recall", "memory_history", "memory_changes", "memory_export"))
            assertEquals(fn, "grace_memory", buildRpcRequest(base, "k", token, fn, emptyMap()).headers["Accept-Profile"])
        assertNull(buildRpcRequest(base, "k", token, "memory_forget", mapOf("p_id" to "a")).headers["Accept-Profile"])
    }

    @Test fun unknownFunctionRejected() {
        assertThrows(IllegalArgumentException::class.java) { buildRpcRequest(base, "k", token, "drop_table", emptyMap()) }
    }

    @Test fun emptyConfigOrNoTokenIsLocalOnly() {
        assertNull(memoryRpcOrNull("", "k", { true }, { token }))
        assertNull(memoryRpcOrNull(base, "", { true }, { token }))
        assertNull(memoryRpcOrNull(base, "k", { false }, { token }))
        assertNotNull(memoryRpcOrNull(base, "k", { true }, { token }))
    }

    private fun rpc(reply: () -> MemoryHttpReply, seen: MutableList<MemoryHttpRequest> = mutableListOf()) =
        HttpMemoryRpc(base, "anon", { token }, { url, h, b -> seen += MemoryHttpRequest(url, h, b); reply() })

    @Test fun changesParsesEntriesTombstonesAndMaxSeq() = runBlocking {
        val body = """{"entries":[{"id":"a","type":"fact","subject":"S","body":"B","status":"active","valid_from":"2026-11-20T10:00:00+00:00","change_seq":7,"subject_key":"s"}],
            "purged":[{"id":"p","purged_at":"2026-11-20T10:00:00Z","change_seq":8}],"max_seq":8}"""
        val seen = mutableListOf<MemoryHttpRequest>()
        val c = rpc({ MemoryHttpReply(200, body) }, seen).changes(3)
        assertEquals(listOf("a"), c.entries.map { it.id }); assertEquals(listOf("p"), c.purgedIds); assertEquals(8L, c.maxSeq)
        assertTrue(seen.single().body.contains("\"p_since_seq\":3"))
    }

    @Test fun rememberConflictAndPermanentRejection() = runBlocking {
        val r = rpc({ MemoryHttpReply(200, """{"conflict":true,"id":"a","head_id":"h"}""") }).remember(entry("a", "S"), "t")
        assertTrue(r.conflict); assertEquals("h", r.headId)
        val bad = runCatching { rpc({ MemoryHttpReply(400, """{"code":"P0001","message":"bad_entry"}""") }).remember(entry("a", "S"), null) }
        assertTrue(bad.exceptionOrNull() is MemoryRpcRejected)
    }

    @Test fun authAndServerFailuresAreRetriableIoAndNeverLeakBodyOrToken() = runBlocking {
        for (code in listOf(401, 403, 500)) {
            val e = runCatching { rpc({ MemoryHttpReply(code, "secret-body $token") }).remember(entry("a", "S"), null) }.exceptionOrNull()
            assertTrue(e is IOException); assertFalse(e!!.message!!.contains("secret")); assertFalse(e.message!!.contains(token))
        }
        val invalid = runCatching { rpc({ MemoryHttpReply(400, """{"message":"invalid_token"}""") }).remember(entry("a", "S"), null) }.exceptionOrNull()
        assertTrue(invalid is IOException)
        val junk = runCatching { rpc({ MemoryHttpReply(200, "<html>") }).changes(0) }.exceptionOrNull()
        assertTrue(junk is IOException)
    }

    @Test fun purgeSendsConfirmArg() = runBlocking {
        val seen = mutableListOf<MemoryHttpRequest>()
        assertEquals(2, rpc({ MemoryHttpReply(200, """{"purged":2}""") }, seen).purge("a", "PURGE"))
        assertTrue(seen.single().body.contains("\"p_confirm\":\"PURGE\""))
    }
}

class MemoryBuildConfigTest {
    @Test fun buildConfigFieldsExistAndEmptyConfigIsLocalOnly() {
        val url = com.galaxyrio.gracelauncher.BuildConfig.SUPABASE_URL
        val key = com.galaxyrio.gracelauncher.BuildConfig.SUPABASE_ANON_KEY
        assertTrue(url.isEmpty() || url.startsWith("https://"))
        // Empty config (the default without .env entries) never produces a client, even with a saved token.
        assertNull(memoryRpcOrNull("", "", { true }, { "hc_" + "A".repeat(43) }))
        if (url.isEmpty() || key.isEmpty()) assertNull(memoryRpcOrNull(url, key, { true }, { "x" }))
    }
}
