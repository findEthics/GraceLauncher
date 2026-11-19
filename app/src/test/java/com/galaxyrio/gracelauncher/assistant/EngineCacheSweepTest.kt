package com.galaxyrio.gracelauncher.assistant

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineCacheSweepTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun cache(model: AssistantModel, suffix: String) = "${model.name}-${model.filename}_1_1024$suffix"
    private val suffixes = listOf(".xnnpack_cache", "_mldrift_weight_cache.bin")

    @Test fun keepsTargetAndSelectsOtherModelsAndLegacyForEveryTarget() {
        val dir = tmp.newFolder()
        val names = AssistantModel.entries.flatMap { model -> suffixes.map { cache(model, it) } } +
            suffixes.map { "FunctionGemma-mobile_actions_q8_ekv1024.litertlm_1_1024$it" }
        names.forEach { File(dir, it).writeText("cache") }
        for (target in AssistantModel.entries) {
            assertEquals(target.name, names.filterNot { it.startsWith("${target.name}-${target.filename}_") }.toSet(),
                staleEngineCaches(dir, target).map { it.name }.toSet())
        }
    }

    @Test fun selectsOnlyTopLevelCacheFilesAndRequiresFullTargetPrefix() {
        val dir = tmp.newFolder()
        val target = AssistantModel.Gemma4
        val kept = listOf("private-apps.json", "private-apps.json.bak", "private-apps.json.new", "unrelated",
            "cache.xnnpack_cache.part", "cache_mldrift_weight_cache.bin.tmp", "${target.name}-${target.filename}")
        kept.forEach { File(dir, it).writeText("keep") }
        listOf("health-mcp", "assistant-models", "directory.xnnpack_cache", "directory_mldrift_weight_cache.bin")
            .forEach { name -> File(dir, name).apply { mkdir(); File(this, "nested.xnnpack_cache").writeText("keep") } }
        val stale = "${target.name}-${target.filename}.old_1.xnnpack_cache"
        File(dir, stale).writeText("cache")
        assertEquals(listOf(stale), staleEngineCaches(dir, target).map { it.name })
        assertTrue(kept.all { File(dir, it).isFile })
        assertTrue(File(dir, "health-mcp/nested.xnnpack_cache").isFile)
    }

    @Test fun missingOrUnlistableDirectoryIsEmpty() {
        assertTrue(staleEngineCaches(File(tmp.root, "missing"), AssistantModel.Gemma4).isEmpty())
        assertTrue(staleEngineCaches(tmp.newFile(), AssistantModel.Gemma4).isEmpty())
        val unlistable = object : File(tmp.root, "unlistable") {
            override fun listFiles(): Array<File>? = null
        }
        assertTrue(staleEngineCaches(unlistable, AssistantModel.Gemma4).isEmpty())
    }
}
