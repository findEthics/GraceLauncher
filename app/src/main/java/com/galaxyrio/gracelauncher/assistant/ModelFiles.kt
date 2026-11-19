package com.galaxyrio.gracelauncher.assistant

import android.content.Context
import androidx.annotation.StringRes
import com.galaxyrio.gracelauncher.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.delay
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
    @StringRes val note: Int,
    val extraContext: Map<String, Any> = emptyMap(),
) {
    Gemma4(
        "Gemma 4 E2B", "litert-community/gemma-4-E2B-it-litert-lm",
        "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1", "gemma-4-E2B-it.litertlm",
        2588147712L, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", false,
        R.string.assistant_model_note_gemma4,
    ),
    Gemma4E4B(
        "Gemma 4 E4B", "litert-community/gemma-4-E4B-it-litert-lm",
        "f254b5ba6e36f7d89cc7892e268e9877bc20f0a0", "gemma-4-E4B-it.litertlm",
        3659530240L, "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0", false,
        R.string.assistant_model_note_gemma4_e4b,
    ),
    // The wi4b32 bundle is the one verified on LiteRT-LM 0.18 GPU; the plain Qwen3_1.7B bundle needs 0.19.
    Qwen3_1_7B(
        "Qwen3 1.7B", "litert-community/Qwen3-1.7B",
        "c421cc3775575372ac2ca4aa1bebc97300ae36b6", "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
        977184032L, "2a72330b504a6596d53a2c00923c57173b10f1b83e6a541213ffec08d1fc5de8", false,
        R.string.assistant_model_note_qwen3, mapOf("enable_thinking" to false),
    ),
    Qwen2_5_1_5B(
        "Qwen2.5 1.5B", "litert-community/Qwen2.5-1.5B-Instruct",
        "60257f9f1d00ca1f24cc5ce3b829b4139be1ca2b", "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        1597922856L, "c4a9b7289365ab80fe599b844b77ad253eb3c53fb0e5d963d699146a6f01255e", false,
        R.string.assistant_model_note_qwen25,
    ),
}

/** Unknown or legacy stored values (for example the removed FunctionGemma) fall back to the default. */
fun parseSelectedModel(stored: String?): AssistantModel =
    AssistantModel.entries.firstOrNull { it.name == stored } ?: AssistantModel.Gemma4

internal fun modelUrl(model: AssistantModel) =
    "https://huggingface.co/${model.repository}/resolve/${model.revision}/${model.filename}"

/** Every file in the models dir except the target's model, `.sha256`, `.part` and `.sha256.part`. */
internal fun sweepTargets(dir: File, target: AssistantModel): List<File> {
    val keep = "${target.name}-${target.filename}"
    return dir.listFiles()?.filter { it.name != keep && !it.name.startsWith("$keep.") }.orEmpty()
}

internal fun staleEngineCaches(dir: File, target: AssistantModel): List<File> {
    val keep = "${target.name}-${target.filename}_"
    return dir.listFiles()?.filter {
        it.isFile && (it.name.endsWith(".xnnpack_cache") || it.name.endsWith("_mldrift_weight_cache.bin")) &&
            !it.name.startsWith(keep)
    }.orEmpty()
}

internal const val SWITCH_MARGIN_BYTES = 200L * 1_000_000

/** Free space plus the bytes about to be deleted must cover the new model and a margin. */
internal fun hasSpaceForSwitch(usableSpace: Long, deletableBytes: Long, newModelBytes: Long): Boolean =
    usableSpace + deletableBytes >= newModelBytes + SWITCH_MARGIN_BYTES

class ModelFiles(context: Context) {
    private val cacheDir = context.noBackupFilesDir
    private val storage = File(cacheDir, "assistant-models")

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
                if (!storage.isDirectory && !storage.mkdirs()) throw SafeDownloadException("Cannot create model storage")
                val target = file(model)
                val partial = File(storage, "${target.name}.part")
                val hashPartial = File(storage, "${target.name}.sha256.part")
                remove(hashPartial)
                // Keep a partial file from an interrupted attempt so the download can resume.
                if (partial.length() > model.bytes) remove(partial)
                if (storage.usableSpace < model.bytes - partial.length() + 64L * 1024 * 1024) {
                    throw SafeDownloadException("Not enough storage for model and 64 MiB reserve")
                }
                var published = false
                try {
                    var url = URL(modelUrl(model))
                    var sendBearer = bearer != null
                    var redirects = 0
                    var lastProgress = 0L
                    var attempts = 0
                    val coroutine = currentCoroutineContext()
                    while (true) {
                        coroutine.ensureActive()
                        val connection = url.openConnection() as HttpsURLConnection
                        try {
                            connection.instanceFollowRedirects = false
                            connection.connectTimeout = 15_000
                            connection.readTimeout = 15_000
                            connection.setRequestProperty("Accept-Encoding", "identity")
                            var have = if (partial.isFile) partial.length() else 0L
                            if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")
                            if (sendBearer) connection.setRequestProperty("Authorization", "Bearer $bearer")
                            val status = connection.responseCode
                            if (status in listOf(301, 302, 303, 307, 308)) {
                                if (++redirects > 8) throw SafeDownloadException("Too many model redirects")
                                val next = validatedRedirect(url, connection.getHeaderField("Location")
                                    ?: throw SafeDownloadException("Missing model redirect"))
                                sendBearer = sendBearer && sameHttpsOrigin(url, next)
                                url = next
                                continue
                            }
                            if (status == 401 || status == 403) throw ModelAccessException()
                            if (status == 200) have = 0L // server ignored Range: restart from zero
                            else if (status != 206 || connection.getHeaderField("Content-Range")?.startsWith("bytes $have-") != true) {
                                throw SafeDownloadException("Model download failed (HTTP $status)")
                            }
                            val length = connection.contentLengthLong
                            if (length >= 0 && length != model.bytes - have) throw SafeDownloadException("Model size mismatch")
                            val encoding = connection.getHeaderField("Content-Encoding")
                            if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                                throw SafeDownloadException("Unexpected model encoding")
                            }
                            val digest = MessageDigest.getInstance("SHA-256")
                            var bytes = have
                            if (have > 0) partial.inputStream().use { old ->
                                val buf = ByteArray(64 * 1024)
                                while (true) { val n = old.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
                            }
                            onProgress(bytes)
                            connection.inputStream.use { input ->
                                FileOutputStream(partial, have > 0).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        coroutine.ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        if (count == 0) continue
                                        bytes += count
                                        if (bytes > model.bytes) throw SafeDownloadException("Model size mismatch")
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
                        } catch (e: IOException) {
                            // Transient network drop: resume from the partial file (max 5 retries).
                            if (e is SafeDownloadException || e is ModelAccessException || ++attempts > 5) throw e
                            android.util.Log.w("ModelDl", e.javaClass.name + " retry " + attempts)
                            url = URL(modelUrl(model))
                            sendBearer = bearer != null; redirects = 0
                            delay(1_000L * attempts)
                        } finally {
                            connection.disconnect()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ModelAccessException) {
                    throw e
                } catch (e: SafeDownloadException) {
                    partial.delete() // integrity/size failure: never resume corrupt data
                    throw e
                } catch (e: Exception) {
                    // Class name only: exception text can contain signed redirect URLs.
                    android.util.Log.w("ModelDl", e.javaClass.name + " " + (e.message ?: "").substringBefore("?"))
                    throw IOException("Model download failed (${e.javaClass.simpleName}); check network, access and storage")
                } finally {
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

    /** Bytes the switch to [target] would free. */
    fun deletableBytes(target: AssistantModel): Long = sweepTargets(storage, target).sumOf { it.length() }
    fun hasAnyModel(): Boolean = storage.listFiles()?.any { it.isFile && !it.name.endsWith(".part") } == true
    fun usableSpace(): Long = (if (storage.isDirectory) storage else storage.parentFile ?: storage).usableSpace

    suspend fun sweepExcept(target: AssistantModel) = withContext(Dispatchers.IO) {
        mutation.withLock {
            sweepTargets(storage, target).forEach { remove(it) }
            // Disposable native caches must not prevent switching if a delete fails.
            staleEngineCaches(cacheDir, target).forEach { runCatching { it.delete() } }
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

private class SafeDownloadException(m: String) : IOException(m)

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
