package com.galaxyrio.gracelauncher.assistant

import com.galaxyrio.gracelauncher.assistant.HealthFixtures.TOKEN_A
import com.galaxyrio.gracelauncher.assistant.HealthFixtures.TOKEN_B
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** JVM test with an in-memory AES key (not AndroidKeyStore). */
class HealthVaultTest {
    private val now = 1_800_000_000_000L
    private fun dir() = java.nio.file.Files.createTempDirectory("hv").toFile()

    @Test fun tokenRoundTripAndFormatRejection() {
        val d = dir(); val v = HealthFixtures.vault(d)
        assertNull(v.token())
        assertFalse(v.saveToken("hc_short")); assertFalse(v.saveToken(TOKEN_A + "x")); assertFalse(v.saveToken("xx_" + TOKEN_A.drop(3)))
        assertTrue(v.saveToken(TOKEN_A)); assertEquals(TOKEN_A, v.token())
        assertFalse(File(d, "token.bin").readText(Charsets.ISO_8859_1).contains("hc_")) // encrypted at rest
    }

    @Test fun cacheRoundTripWithFingerprintIsolation() {
        val v = HealthFixtures.vault(); v.saveToken(TOKEN_A)
        v.saveCache(TOKEN_A, HealthFixtures.snapshot(weekly = true), now)
        val c = v.loadCache(TOKEN_A, now + 1000)!!
        assertEquals(now, c.retrievedAt); assertEquals(setOf("chat_reply", "weekly_coach"), c.contexts.keys)
        assertNull(v.loadCache(TOKEN_B, now)); assertNull(v.loadCache(TOKEN_A, now)) // mismatch deleted the file
    }

    @Test fun olderThanSevenDaysAndCorruptDataAreDeleted() {
        val d = dir(); val v = HealthFixtures.vault(d); v.saveToken(TOKEN_A)
        v.saveCache(TOKEN_A, HealthFixtures.snapshot(), now)
        assertNotNull(v.loadCache(TOKEN_A, now + HealthVault.MAX_AGE_MS))
        assertNull(v.loadCache(TOKEN_A, now + HealthVault.MAX_AGE_MS + 1)); assertFalse(File(d, "cache.bin").exists())
        v.saveCache(TOKEN_A, HealthFixtures.snapshot(), now)
        File(d, "cache.bin").writeBytes(ByteArray(80) { 7 })
        assertNull(v.loadCache(TOKEN_A, now)); assertFalse(File(d, "cache.bin").exists())
    }

    @Test fun removeReplaceAndRejectionWipe() {
        val d = dir(); val v = HealthFixtures.vault(d); v.saveToken(TOKEN_A)
        v.saveCache(TOKEN_A, HealthFixtures.snapshot(), now); v.saveToken(TOKEN_A) // same token still wipes
        assertFalse(File(d, "cache.bin").exists())
        v.saveCache(TOKEN_A, HealthFixtures.snapshot(), now); v.saveToken(TOKEN_B)
        assertFalse(File(d, "cache.bin").exists()); assertEquals(TOKEN_B, v.token())
        v.saveCache(TOKEN_B, HealthFixtures.snapshot(), now); v.removeToken()
        assertFalse(File(d, "token.bin").exists()); assertFalse(File(d, "cache.bin").exists()); assertFalse(v.tokenSaved())
        v.saveToken(TOKEN_A); v.markRejected(TOKEN_A); assertTrue(v.isRejected(TOKEN_A))
        v.saveToken(TOKEN_A); assertFalse(v.isRejected(TOKEN_A)) // a new Save clears the in-memory state
    }
}
