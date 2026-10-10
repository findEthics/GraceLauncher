package com.galaxyrio.gracelauncher.assistant

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModelDownloadTest {
    @Test fun publicDownloadVerifiesAndCancellationRemovesPartials() = runBlocking {
        org.junit.Assume.assumeTrue("Opt-in live 2.6 GB download", InstrumentationRegistry.getArguments().getString("modelDownload") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = ModelFiles(context)
        val model = AssistantModel.Gemma4
        files.delete(model)
        try {
            files.download(model, null) { if (it > 0) throw kotlinx.coroutines.CancellationException("test cancellation") }
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertFalse(files.ready(model))
        assertFalse(java.io.File(files.file(model).path + ".part").exists())
        files.download(model, null) { }
        assertTrue(files.ready(model))
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        files.file(model).inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        assertEquals(model.sha256, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }
}
