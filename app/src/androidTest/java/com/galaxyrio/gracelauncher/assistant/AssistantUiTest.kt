package com.galaxyrio.gracelauncher.assistant

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class AssistantUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun homeIsOptInAndSettingsAllowModelSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("offline_assistant", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("enabled", false).commit()
        try {
            compose.setContent { androidx.compose.material3.MaterialTheme { AssistantHome() } }
            compose.onNodeWithTag("assistant_microphone").assertDoesNotExist()
            compose.runOnIdle { prefs.edit().putBoolean("enabled", true).commit() }
            compose.onNodeWithTag("assistant_microphone").assertExists().assertHasClickAction()
        } finally { prefs.edit().putBoolean("enabled", false).commit() }
    }
    @Test fun tokenAppearsOnlyForGatedModel() {
        compose.setContent { androidx.compose.material3.MaterialTheme { AssistantSettings() } }
        compose.onNodeWithTag("assistant_model_Gemma4").performScrollTo().performClick()
        compose.onNodeWithTag("assistant_token").assertDoesNotExist()
        compose.onNodeWithTag("assistant_model_FunctionGemma").performScrollTo().performClick()
        compose.onNodeWithTag("assistant_token").assertExists()
    }
}
