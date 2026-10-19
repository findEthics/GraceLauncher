package com.galaxyrio.gracelauncher.assistant

import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelFilesTest {
    @Test
    fun integrityRequiresExactLengthAndDigest() {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val actual = java.security.MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())
        assertEquals(hash, digestHex(actual))
        verifyModelIntegrity(3, digestHex(actual), 3, hash)
        for (size in listOf(0L, 2L, 4L)) {
            assertThrows(java.io.IOException::class.java) { verifyModelIntegrity(size, hash, 3, hash) }
        }
        assertThrows(java.io.IOException::class.java) { verifyModelIntegrity(3, "0".repeat(64), 3, hash) }
        assertThrows(java.io.IOException::class.java) { verifyModelIntegrity(3, hash.uppercase(), 3, hash) }
    }

    @Test
    fun credentialsNeverFollowCrossHostRedirects() {
        val source = URL("https://huggingface.co/model")
        assertTrue(sameHttpsOrigin(source, URL("https://huggingface.co:443/next")))
        assertFalse(sameHttpsOrigin(source, URL("https://cdn-lfs.hf.co/model")))
        assertFalse(sameHttpsOrigin(source, URL("http://huggingface.co/model")))
        assertFalse(sameHttpsOrigin(source, URL("https://huggingface.co:444/model")))
    }

    @Test
    fun redirectsRequireTrustedHttpsOrigins() {
        val source = URL("https://huggingface.co/repo/resolve/revision/model")
        assertEquals("https://huggingface.co/next", validatedRedirect(source, "/next").toString())
        for (host in listOf("cdn-lfs.huggingface.co", "cdn-lfs.hf.co", "cdn-lfs-us-1.hf.co", "cdn-lfs-eu-1.hf.co", "cas-bridge.xethub.hf.co", "us.aws.cdn.hf.co", "eu.aws.cdn.hf.co")) {
            assertEquals(host, validatedRedirect(source, "https://$host/model?signature=value").host)
        }
        for (location in listOf("http://huggingface.co/model", "https://evil.example/model", "https://huggingface.co.evil.example/model", "https://evil-hf.co/model", "https://user@huggingface.co/model", "https://huggingface.co:444/model", "https://huggingface.co/model#fragment", "file:///tmp/model")) {
            assertThrows(IllegalArgumentException::class.java) { validatedRedirect(source, location) }
        }
    }
}
