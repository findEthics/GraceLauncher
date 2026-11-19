package com.galaxyrio.gracelauncher.assistant

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelCatalogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun catalogIsPinnedAndUnique() {
        val all = AssistantModel.entries
        assertEquals(listOf("Gemma4", "Gemma4E4B", "Qwen3_1_7B", "Qwen2_5_1_5B"), all.map { it.name })
        assertEquals(all.size, all.map { it.filename }.distinct().size)
        for (m in all) {
            assertTrue(m.name, Regex("[0-9a-f]{40}").matches(m.revision))
            assertTrue(m.name, Regex("[0-9a-f]{64}").matches(m.sha256))
            assertTrue(m.bytes > 0)
            assertFalse(m.tokenRequired)
            assertTrue(m.filename.endsWith(".litertlm"))
            assertEquals("https://huggingface.co/${m.repository}/resolve/${m.revision}/${m.filename}", modelUrl(m))
        }
        assertEquals("gemma-4-E2B-it.litertlm", AssistantModel.Gemma4.filename)
        assertEquals("b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1", AssistantModel.Gemma4.revision)
    }

    @Test
    fun onlyQwen3DisablesThinking() {
        for (m in AssistantModel.entries) {
            if (m == AssistantModel.Qwen3_1_7B) assertEquals(mapOf<String, Any>("enable_thinking" to false), m.extraContext)
            else assertTrue(m.name, m.extraContext.isEmpty())
        }
    }

    @Test
    fun parseSelectedModelFallsBackToDefault() {
        assertEquals(AssistantModel.Gemma4, parseSelectedModel(null))
        assertEquals(AssistantModel.Gemma4, parseSelectedModel("Gemma4"))
        assertEquals(AssistantModel.Gemma4E4B, parseSelectedModel("Gemma4E4B"))
        assertEquals(AssistantModel.Gemma4, parseSelectedModel("FunctionGemma"))
        assertEquals(AssistantModel.Gemma4, parseSelectedModel("garbage"))
    }

    private fun seed(vararg names: String): File = tmp.newFolder().also { d -> names.forEach { File(d, it).writeText("x") } }

    @Test
    fun sweepKeepsOnlyTargetFiles() {
        val t = AssistantModel.Qwen3_1_7B
        val base = "${t.name}-${t.filename}"
        val dir = seed(base, "$base.sha256", "$base.part", "$base.sha256.part",
            "Gemma4-gemma-4-E2B-it.litertlm", "Gemma4-gemma-4-E2B-it.litertlm.sha256",
            "Gemma4E4B-gemma-4-E4B-it.litertlm.part", "FunctionGemma-mobile_actions_q8_ekv1024.litertlm")
        val doomed = sweepTargets(dir, t).map { it.name }.toSet()
        assertEquals(setOf("Gemma4-gemma-4-E2B-it.litertlm", "Gemma4-gemma-4-E2B-it.litertlm.sha256",
            "Gemma4E4B-gemma-4-E4B-it.litertlm.part", "FunctionGemma-mobile_actions_q8_ekv1024.litertlm"), doomed)
    }

    @Test
    fun sweepHandlesMissingDir() {
        assertTrue(sweepTargets(File(tmp.root, "nope"), AssistantModel.Gemma4).isEmpty())
    }

    @Test
    fun spaceCheckCountsDeletableBytesAndMargin() {
        val need = 1_000L
        val margin = 200L * 1_000_000
        assertTrue(hasSpaceForSwitch(need + margin, 0, need))
        assertFalse(hasSpaceForSwitch(need + margin - 1, 0, need))
        assertTrue(hasSpaceForSwitch(need + margin - 500, 500, need))
        assertFalse(hasSpaceForSwitch(need + margin - 501, 500, need))
    }

    @Test
    fun cleanModelText() {
        assertEquals("hello", cleanModelText("hello"))
        assertEquals("answer", cleanModelText("<think>reasoning</think>answer"))
        assertEquals("answer", cleanModelText("<think>\n\n</think>\n\nanswer"))
        assertEquals("", cleanModelText("<think>still thinking"))
        assertNull(cleanModelText("hi <tool_call>{}</tool_call>"))
        assertNull(cleanModelText("</tool_call>"))
    }
}
