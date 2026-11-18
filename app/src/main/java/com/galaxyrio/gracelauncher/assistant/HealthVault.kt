package com.galaxyrio.gracelauncher.assistant

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Tiny seam so JVM tests can use an in-memory AES key instead of AndroidKeyStore. */
internal interface Aead {
    fun seal(b: ByteArray): ByteArray
    fun open(b: ByteArray): ByteArray
}

/** AES-256-GCM; the random 12-byte IV is prepended to the ciphertext. */
internal class GcmAead(private val key: () -> SecretKey) : Aead {
    override fun seal(b: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(b)
    }
    override fun open(b: ByteArray): ByteArray {
        require(b.size > IV_BYTES + 16) { "short ciphertext" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, IV_BYTES))
        }
        return cipher.doFinal(b, IV_BYTES, b.size - IV_BYTES)
    }
    companion object {
        const val IV_BYTES = 12
        const val KEY_ALIAS = "grace_health_mcp"
        /** Non-exportable key, no user-auth requirement. */
        @Synchronized fun keystoreKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
        }
    }
}

internal class HealthCache(val retrievedAt: Long, val status: JSONObject, val contexts: Map<String, JSONObject>)

/**
 * Encrypted token + last-snapshot store in noBackupFilesDir/health-mcp (never backed up, never SharedPreferences).
 * The cache is isolated by token fingerprint, expires after 7 days and is wiped on remove/replace/401.
 */
internal class HealthVault(private val dir: File, private val aead: Aead) {
    private val tokenFile get() = File(dir, "token.bin")
    private val cacheFile get() = File(dir, "cache.bin")
    fun tokenSaved() = tokenFile.exists()

    /** False for a malformed token. Any save (even the same token) wipes the cache and clears "rejected". */
    fun saveToken(token: String): Boolean {
        if (!TOKEN.matches(token)) return false
        write(tokenFile, token.toByteArray(Charsets.UTF_8))
        wipeCache()
        rejectedFingerprint = null
        return true
    }

    fun token(): String? = read(tokenFile)?.let { String(it, Charsets.UTF_8) }?.takeIf { TOKEN.matches(it) }
        ?: run { tokenFile.delete(); wipeCache(); null }

    fun removeToken() { tokenFile.delete(); wipeCache(); rejectedFingerprint = null }
    fun wipeCache() { cacheFile.delete() }

    fun markRejected(token: String) { rejectedFingerprint = fingerprint(token) }
    fun isRejected(token: String) = rejectedFingerprint == fingerprint(token)

    fun saveCache(token: String, snapshot: HealthSnapshot, retrievedAt: Long) {
        val contexts = JSONObject().apply { snapshot.contexts.forEach { (k, v) -> put(k, v) } }
        val json = JSONObject().put("fingerprint", fingerprint(token)).put("retrievedAtEpochMs", retrievedAt)
            .put("status", snapshot.status).put("contexts", contexts)
        write(cacheFile, json.toString().toByteArray(Charsets.UTF_8))
    }

    /** Null (and the file deleted) when absent, undecryptable, for another token, or older than 7 days. */
    fun loadCache(token: String, now: Long): HealthCache? {
        val bytes = read(cacheFile) ?: return null
        return try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val at = json.getLong("retrievedAtEpochMs")
            if (json.getString("fingerprint") != fingerprint(token) || now - at > MAX_AGE_MS) throw IllegalStateException()
            val contexts = json.getJSONObject("contexts")
            HealthCache(at, json.getJSONObject("status"), contexts.keys().asSequence().associateWith { contexts.getJSONObject(it) })
        } catch (_: Exception) { cacheFile.delete(); null }
    }

    private fun write(file: File, bytes: ByteArray) {
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeBytes(aead.seal(bytes))
        check(tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))) { "write failed" }
    }

    private fun read(file: File): ByteArray? {
        if (!file.exists()) return null
        return try { aead.open(file.readBytes()) } catch (_: Exception) { file.delete(); null }
    }

    companion object {
        val TOKEN = Regex("^hc_[A-Za-z0-9_-]{43}$")
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        /** In-memory only (process-wide): set on 401, cleared by the next Save/Remove. */
        @Volatile private var rejectedFingerprint: String? = null
        fun fingerprint(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)
        fun create(context: android.content.Context) =
            HealthVault(File(context.noBackupFilesDir, "health-mcp"), GcmAead { GcmAead.keystoreKey() })
    }
}
