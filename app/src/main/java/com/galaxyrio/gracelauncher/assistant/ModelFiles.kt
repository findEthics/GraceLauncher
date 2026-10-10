package com.galaxyrio.gracelauncher.assistant

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class AssistantModel(
    val label: String,
    val repository: String,
    val revision: String,
    val filename: String,
    val bytes: Long,
    val sha256: String,
    val tokenRequired: Boolean,
) {
    FunctionGemma(
        "FunctionGemma 270M", "litert-community/functiongemma-270m-ft-mobile-actions",
        "f752a74080682b379823794defdbbdf8c2663609", "mobile_actions_q8_ekv1024.litertlm",
        288964608L, "33e295cbd996b419bb1de8f3f85c5b6b01ee058a2c89bdb2173cf3e6ff4ce9d0", true,
    ),
    Gemma4(
        "Gemma 4 E2B", "litert-community/gemma-4-E2B-it-litert-lm",
        "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1", "gemma-4-E2B-it.litertlm",
        2588147712L, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", false,
    ),
}

class ModelFiles(context: Context) {
    private val storage = File(context.noBackupFilesDir, "assistant-models")

    fun file(model: AssistantModel) = File(storage, "${model.name}-${model.filename}")
    private fun sidecar(model: AssistantModel) = File(storage, "${file(model).name}.sha256")

    fun ready(model: AssistantModel): Boolean = try {
        val hash = sidecar(model)
        file(model).isFile && file(model).length() == model.bytes &&
            hash.isFile && hash.length() == model.sha256.length.toLong() && hash.readText() == model.sha256
    } catch (_: IOException) {
        false
    }

    suspend fun download(model: AssistantModel, token: String?, onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            mutation.withLock {
                if (ready(model)) return@withLock
                val bearer = token?.takeIf { it.isNotBlank() }
                require(!model.tokenRequired || bearer != null) { "A Hugging Face access token is required" }
                require(bearer == null || bearer.all { it.code in 33..126 }) { "Invalid access token" }
                if (!storage.isDirectory && !storage.mkdirs()) throw IOException("Cannot create model storage")
                val target = file(model)
                val partial = File(storage, "${target.name}.part")
                val hashPartial = File(storage, "${target.name}.sha256.part")
                remove(partial)
                remove(hashPartial)
                if (storage.usableSpace < model.bytes + 64L * 1024 * 1024) {
                    throw IOException("Not enough storage for model and 64 MiB reserve")
                }
                var published = false
                try {
                    var url = URL("https://huggingface.co/${model.repository}/resolve/${model.revision}/${model.filename}")
                    var sendBearer = bearer != null
                    var redirects = 0
                    var lastProgress = 0L
                    val coroutine = currentCoroutineContext()
                    while (true) {
                        coroutine.ensureActive()
                        val connection = url.openConnection() as HttpsURLConnection
                        try {
                            connection.instanceFollowRedirects = false
                            connection.connectTimeout = 15_000
                            connection.readTimeout = 15_000
                            connection.setRequestProperty("Accept-Encoding", "identity")
                            if (sendBearer) connection.setRequestProperty("Authorization", "Bearer $bearer")
                            val status = connection.responseCode
                            if (status in listOf(301, 302, 303, 307, 308)) {
                                if (++redirects > 8) throw IOException("Too many model redirects")
                                val next = validatedRedirect(url, connection.getHeaderField("Location")
                                    ?: throw IOException("Missing model redirect"))
                                sendBearer = sendBearer && sameHttpsOrigin(url, next)
                                url = next
                                continue
                            }
                            if (status == 401 || status == 403) throw ModelAccessException()
                            if (status != 200) throw IOException("Model download failed (HTTP $status)")
                            val length = connection.contentLengthLong
                            if (length >= 0 && length != model.bytes) throw IOException("Model size mismatch")
                            val encoding = connection.getHeaderField("Content-Encoding")
                            if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                                throw IOException("Unexpected model encoding")
                            }
                            val digest = MessageDigest.getInstance("SHA-256")
                            var bytes = 0L
                            onProgress(bytes)
                            connection.inputStream.use { input ->
                                FileOutputStream(partial).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        coroutine.ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        if (count == 0) continue
                                        bytes += count
                                        if (bytes > model.bytes) throw IOException("Model size mismatch")
                                        output.write(buffer, 0, count)
                                        digest.update(buffer, 0, count)
                                        val now = android.os.SystemClock.elapsedRealtime()
                                        if (now - lastProgress >= 200 || bytes == model.bytes) {
                                            onProgress(bytes); lastProgress = now
                                        }
                                    }
                                    output.fd.sync()
                                }
                            }
                            coroutine.ensureActive()
                            verifyModelIntegrity(bytes, digestHex(digest.digest()), model.bytes, model.sha256)
                            // Invalidate any old marker before replacing an unverified file.
                            remove(sidecar(model))
                            Files.move(partial.toPath(), target.toPath(), ATOMIC_MOVE)
                            published = true
                            FileOutputStream(hashPartial).use { output ->
                                output.write(model.sha256.toByteArray(Charsets.US_ASCII))
                                output.fd.sync()
                            }
                            coroutine.ensureActive()
                            Files.move(hashPartial.toPath(), sidecar(model).toPath(), ATOMIC_MOVE)
                            published = false
                            break
                        } finally {
                            connection.disconnect()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ModelAccessException) {
                    throw e
                } catch (_: Exception) {
                    // Never expose signed redirect URLs or request headers through exception text.
                    throw IOException("Model download failed; check access, integrity, and storage")
                } finally {
                    partial.delete()
                    hashPartial.delete()
                    if (published) {
                        sidecar(model).delete()
                        target.delete()
                    }
                }
            }
        }

    suspend fun delete(model: AssistantModel) = withContext(Dispatchers.IO) {
        mutation.withLock {
            remove(sidecar(model))
            remove(file(model))
            remove(File(storage, "${file(model).name}.part"))
            remove(File(storage, "${file(model).name}.sha256.part"))
        }
    }

    suspend fun cleanStalePartials() = withContext(Dispatchers.IO) {
        mutation.withLock {
            AssistantModel.entries.forEach { model ->
                remove(File(storage, "${file(model).name}.part"))
                remove(File(storage, "${file(model).name}.sha256.part"))
            }
        }
    }

    private fun remove(file: File) {
        if (file.exists() && !file.delete()) throw IOException("Cannot remove model file")
    }

    companion object {
        // ponytail: one process-wide mutation lock; per-directory locks if concurrent downloads matter.
        private val mutation = Mutex()
    }
}

private class ModelAccessException : IOException("Download requires a valid Hugging Face token and acceptance of the model license")

internal fun validatedRedirect(source: URL, location: String): URL {
    val target = URL(source, location)
    val host = target.host.lowercase(Locale.ROOT)
    require(target.protocol == "https" && target.userInfo == null &&
        (target.port == -1 || target.port == 443) && target.ref == null &&
        host in setOf("huggingface.co", "cdn-lfs.huggingface.co", "cdn-lfs.hf.co",
            "cdn-lfs-us-1.hf.co", "cdn-lfs-eu-1.hf.co", "cas-bridge.xethub.hf.co",
            "us.aws.cdn.hf.co", "eu.aws.cdn.hf.co")) {
        "Untrusted model redirect"
    }
    return target
}

internal fun sameHttpsOrigin(source: URL, target: URL): Boolean =
    source.protocol == "https" && target.protocol == "https" &&
        source.host.equals(target.host, ignoreCase = true) &&
        (if (source.port == -1) 443 else source.port) == (if (target.port == -1) 443 else target.port)

internal fun digestHex(digest: ByteArray): String =
    digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun verifyModelIntegrity(bytes: Long, sha256: String, expectedBytes: Long, expectedSha256: String) {
    if (bytes != expectedBytes || sha256 != expectedSha256) throw IOException("Model integrity mismatch")
}
