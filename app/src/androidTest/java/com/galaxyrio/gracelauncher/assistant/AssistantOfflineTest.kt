package com.galaxyrio.gracelauncher.assistant

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AssistantOfflineTest {
    @Test fun bothVerifiedModelsProduceOfflineActions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = AssistantController(context, this)
        try {
            for (model in AssistantModel.entries) {
                // Missing prerequisites are failures, never a misleading skipped inference test.
                assertTrue("Seed verified ${model.name} into ${controller.files.file(model)}", controller.files.ready(model))
                val action = controller.infer(model, "Open Wi-Fi settings")
                assertEquals("openWifiSettings", action.name)
                assertTrue(action.arguments.isEmpty())
            }
        } finally { controller.close() }
    }
}
