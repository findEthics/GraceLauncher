package com.galaxyrio.gracelauncher.assistant

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineCacheDirTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun dir(space: Long) = object : File(temporary.root.absolutePath) {
        override fun getUsableSpace() = space
    }

    private fun cache(name: String, bytes: Long = 16L * 1024 * 1024) = File(temporary.root, name).apply {
        RandomAccessFile(this, "rw").use { it.setLength(bytes) }
    }

    @Test fun removesPartialCachesButPreservesModelsAndUnrelatedFiles() {
        val root = dir(1_500_000_000L)
        val partialCpu = cache("model.xnnpack_cache", 4096)
        val partialGpu = cache("model_mldrift_weight_cache.bin", 16L * 1024 * 1024 - 1)
        val models = File(root, "assistant-models").apply { mkdir() }
        val model = File(models, "Gemma4-model.litertlm").apply { writeText("model") }
        val digest = File(models, "Gemma4-model.litertlm.sha256").apply { writeText("digest") }
        val unrelated = cache("unrelated.bin", 4)

        assertEquals(root.absolutePath, AssistantController.resolveEngineCacheDir(root, gpu = true))
        assertFalse(partialCpu.exists())
        assertFalse(partialGpu.exists())
        assertEquals("model", model.readText())
        assertEquals("digest", digest.readText())
        assertTrue(unrelated.exists())
    }

    @Test fun reusesValidGpuCacheEvenWithoutFreeSpace() {
        val root = dir(0)
        val valid = cache("model_mldrift_weight_cache.bin")
        assertEquals(root.absolutePath, AssistantController.resolveEngineCacheDir(root, gpu = true))
        assertTrue(valid.exists())
    }

    @Test fun cpuFallbackRemovesGpuCacheAndReusesValidCpuCache() {
        val root = dir(0)
        val gpu = cache("model_mldrift_weight_cache.bin")
        val cpu = cache("model.xnnpack_cache")
        assertEquals(root.absolutePath, AssistantController.resolveEngineCacheDir(root, gpu = false))
        assertFalse(gpu.exists())
        assertTrue(cpu.exists())
    }

    @Test fun lowSpaceWithoutActiveCacheDeletesOtherBackendCache() {
        val root = dir(1_499_999_999L)
        val cpu = cache("model.xnnpack_cache")
        assertEquals(":nocache", AssistantController.resolveEngineCacheDir(root, gpu = true))
        assertFalse(cpu.exists())
    }

    @Test fun cpuFallbackRemovesStrandedGpuCacheAndDisablesCaching() {
        val root = dir(658L * 1024 * 1024)
        val gpu = cache("model_mldrift_weight_cache.bin")
        val partialCpu = cache("model.xnnpack_cache", 4096)
        assertEquals(":nocache", AssistantController.resolveEngineCacheDir(root, gpu = false))
        assertFalse(gpu.exists())
        assertFalse(partialCpu.exists())
    }

    @Test fun insufficientSpaceWithoutCachesDisablesCaching() {
        assertEquals(":nocache", AssistantController.resolveEngineCacheDir(dir(0), gpu = true))
        assertEquals(":nocache", AssistantController.resolveEngineCacheDir(dir(0), gpu = false))
    }

    @Test fun unreadableDirectoryDisablesCaching() {
        val root = object : File(temporary.root.absolutePath) {
            override fun listFiles(): Array<File>? = null
        }
        assertEquals(":nocache", AssistantController.resolveEngineCacheDir(root, gpu = true))
    }

    @Test fun failedPartialCacheRemovalDisablesCaching() {
        val partial = object : File(temporary.root, "model.xnnpack_cache") {
            override fun isFile() = true
            override fun length() = 4096L
            override fun delete() = false
        }
        val root = object : File(temporary.root.absolutePath) {
            override fun listFiles() = arrayOf<File>(partial)
            override fun getUsableSpace() = Long.MAX_VALUE
        }
        assertEquals(":nocache", AssistantController.resolveEngineCacheDir(root, gpu = true))
    }
}
