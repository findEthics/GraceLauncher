package com.galaxyrio.gracelauncher.assistant

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.galaxyrio.gracelauncher.MainActivity
import com.galaxyrio.gracelauncher.ui.LauncherViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real HOME dispatch/Compose path, synthetic keys only. Never submits an action or changes default HOME. */
class AssistantHomeKeyboardTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var assistant: AssistantController
    private var originallyEnabled = false
    private var originallyHomeButtonVisible = true
    @Before fun enableAssistant() {
        compose.runOnUiThread {
            assistant = ViewModelProvider(compose.activity)[LauncherViewModel::class.java].assistant
            originallyEnabled = assistant.enabled
            originallyHomeButtonVisible = assistant.homeButtonVisible
            assistant.enable(true)
            assistant.showHomeButton(true)
            assistant.closeChat()
        }
        compose.waitUntil(15_000) { assistant.homeShortcutEligible }
    }
    @After fun restoreAssistant() {
        compose.runOnUiThread {
            assistant.closeChat()
            assistant.showHomeButton(originallyHomeButtonVisible)
            assistant.enable(originallyEnabled)
        }
    }
    @Test fun hiddenChatButtonLeavesBothShortcutsAvailable() {
        compose.runOnUiThread { assistant.showHomeButton(false) }
        compose.onNodeWithTag("assistant_chat_button").assertDoesNotExist()
        for ((key, prefix) in listOf(KeyEvent.KEYCODE_1 to "!", KeyEvent.KEYCODE_2 to "")) {
            compose.runOnUiThread {
                val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, key, 0, KeyEvent.META_SHIFT_ON)
                assertTrue(assistant.enabled)
                assertTrue(compose.activity.dispatchKeyEvent(down))
                assertTrue(compose.activity.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP)))
            }
            compose.onNodeWithTag("assistant_chat_input").assertTextContains(prefix).assertIsFocused()
            compose.runOnUiThread { assertEquals(prefix, assistant.chat.draft); assistant.closeChat() }
            compose.waitUntil { assistant.homeShortcutEligible }
            compose.onNodeWithTag("assistant_chat_button").assertDoesNotExist()
        }
        compose.runOnUiThread { assistant.showHomeButton(true) }
        compose.onNodeWithTag("assistant_chat_button").assertIsDisplayed()
    }
    @Test fun shiftedBangOpensAndRapidFollowingTextIsNotLostOrDuplicated() {
        compose.runOnUiThread {
            val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1, 0, KeyEvent.META_SHIFT_ON)
            assertTrue(compose.activity.dispatchKeyEvent(down))
            assertTrue(compose.activity.dispatchKeyEvent(KeyEvent.changeTimeRepeat(down, 1, 1)))
            assertTrue(compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_T)))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_T))
            assertTrue(compose.activity.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP)))
        }
        compose.onNodeWithTag("assistant_chat_window").assertIsDisplayed()
        compose.onNodeWithTag("assistant_chat_input").assertTextContains("!t").assertIsFocused()
        compose.runOnUiThread { assertEquals("!t", assistant.chat.draft) }
        compose.onNodeWithTag("assistant_chat_input").performTextInput("!")
        compose.runOnUiThread { assertEquals("!t!", assistant.chat.draft) }
        compose.onNodeWithTag("assistant_chat_window").assertIsDisplayed()
    }
    @Test fun homeReturnRequestMinimizesWithoutSavingOrClearingButBackEndsSession() {
        compose.onNodeWithTag("assistant_chat_button").performClick()
        compose.onNodeWithTag("assistant_chat_input").performTextInput("unsent question")
        compose.runOnUiThread {
            val turn = assistant.chat.begin("Earlier question", false)
            assistant.chat.update(turn, "Earlier reply", complete = true, inContext = true)
            assistant.chat.draft = "unsent question"
            // Exercise the exact return flow used by onNewIntent without turning the test task into HOME.
            ViewModelProvider(compose.activity)[LauncherViewModel::class.java].requestReturnHome()
        }
        compose.waitUntil { !assistant.chat.visible }
        compose.onNodeWithTag("assistant_chat_window").assertDoesNotExist()
        compose.onNodeWithTag("assistant_chat_button").performClick()
        compose.onNodeWithTag("assistant_chat_input").assertTextContains("unsent question")
        compose.onNodeWithText("Earlier reply").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("assistant_chat_window").assertDoesNotExist()
        compose.runOnUiThread { assertTrue(assistant.chat.turns.isEmpty()); assertEquals("", assistant.chat.draft) }
    }

    @Test fun shiftedAtOpensWithoutAddingTextAndRapidFollowingTypingIsPreserved() {
        compose.runOnUiThread {
            val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2, 0, KeyEvent.META_SHIFT_ON)
            assertEquals('@'.code, down.unicodeChar)
            assertTrue(compose.activity.dispatchKeyEvent(down))
            assertEquals("", assistant.chat.draft)
            assertTrue(compose.activity.dispatchKeyEvent(KeyEvent.changeTimeRepeat(down, 1, 1)))
            assertTrue(compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_H)))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_H))
            assertTrue(compose.activity.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP)))
        }
        compose.onNodeWithTag("assistant_chat_window").assertIsDisplayed()
        compose.onNodeWithTag("assistant_chat_input").assertTextContains("h").assertIsFocused()
        compose.runOnUiThread { assertEquals("h", assistant.chat.draft) }
        compose.onNodeWithTag("assistant_chat_input").performTextInput("@")
        compose.runOnUiThread { assertEquals("h@", assistant.chat.draft) }
    }

    @Test fun atPreservesRetainedDraftAndSelection() {
        val retained = TextFieldValue("unsent question", TextRange(6))
        compose.onNodeWithTag("assistant_chat_button").performClick()
        compose.runOnUiThread {
            assistant.chat.input = retained
            ViewModelProvider(compose.activity)[LauncherViewModel::class.java].requestReturnHome()
        }
        compose.waitUntil { !assistant.chat.visible && assistant.homeShortcutEligible }
        compose.runOnUiThread {
            val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_AT)
            assertEquals('@'.code, down.unicodeChar)
            assertTrue(compose.activity.dispatchKeyEvent(down))
            assertTrue(compose.activity.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP)))
        }
        compose.onNodeWithTag("assistant_chat_input").assertTextContains(retained.text).assertIsFocused()
        compose.runOnUiThread { assertEquals(retained, assistant.chat.input) }
    }
}
