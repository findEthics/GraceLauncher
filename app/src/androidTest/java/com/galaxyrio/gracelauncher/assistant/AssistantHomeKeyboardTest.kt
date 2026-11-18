package com.galaxyrio.gracelauncher.assistant

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.galaxyrio.gracelauncher.magic.MagicController
import com.galaxyrio.gracelauncher.magic.MagicDataSource
import com.galaxyrio.gracelauncher.magic.SyntheticMagicDataSource
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
    private lateinit var magic: MagicController
    private var originalSource: MagicDataSource? = null
    private lateinit var assistant: AssistantController
    private var originallyEnabled = false
    private var originallyHomeButtonVisible = true
    @Before fun enableAssistant() {
        compose.runOnUiThread {
            val viewModel = ViewModelProvider(compose.activity)[LauncherViewModel::class.java]
            magic = viewModel.magic
            magic.close()
            originalSource = magic.dataSource
            magic.dataSource = SyntheticMagicDataSource()
            assistant = viewModel.assistant
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
            magic.close()
            magic.dataSource = originalSource
            assistant.closeChat()
            assistant.showHomeButton(originallyHomeButtonVisible)
            assistant.enable(originallyEnabled)
        }
    }
    @Test fun hiddenChatButtonLeavesAssistantShortcutsAvailable() {
        compose.runOnUiThread { assistant.showHomeButton(false) }
        compose.onNodeWithTag("assistant_chat_button").assertDoesNotExist()
        for ((key, prefix, meta) in listOf(
            Triple(KeyEvent.KEYCODE_SPACE, "", 0),
        )) {
            compose.runOnUiThread {
                val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, key, 0, meta)
                assertTrue(assistant.enabled)
                if (key == KeyEvent.KEYCODE_SPACE) assertEquals(' '.code, down.unicodeChar)
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
    @Test fun shiftedBangOpensMagicInsteadOfAssistantAndPreservesRapidTyping() {
        compose.runOnUiThread {
            val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1, 0, KeyEvent.META_SHIFT_ON)
            assertTrue(compose.activity.dispatchKeyEvent(down))
            assertTrue(compose.activity.dispatchKeyEvent(KeyEvent.changeTimeRepeat(down, 1, 1)))
            assertTrue(compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_T)))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_T))
            assertTrue(compose.activity.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP)))
        }
        compose.onNodeWithTag("assistant_chat_window").assertDoesNotExist()
        compose.onNodeWithTag("magic_input").assertTextContains("t").assertIsFocused()
        compose.runOnUiThread {
            assertEquals(com.galaxyrio.gracelauncher.magic.MagicKind.Alarm, magic.session!!.kind)
            assertEquals("t", magic.session!!.input.text)
            assertEquals("", assistant.chat.draft)
        }
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
        compose.onNodeWithText("Earlier reply").performScrollTo().assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("assistant_chat_window").assertDoesNotExist()
        compose.runOnUiThread { assertTrue(assistant.chat.turns.isEmpty()); assertEquals("", assistant.chat.draft) }
    }

    @Test fun pauseResumeThenHomeAllowsSpaceToReopenFocusedChatWithDraftAndHistory() {
        compose.onNodeWithTag("assistant_chat_button").performClick()
        compose.runOnUiThread {
            val turn = assistant.chat.begin("Mariana Trench", false)
            assistant.chat.update(turn, "Earlier reply", complete = true, inContext = true)
        }
        compose.onNodeWithTag("assistant_chat_input").performTextInput("space reentry draft")
        compose.runOnUiThread { assertTrue(compose.activity.currentFocus?.onCheckIsTextEditor() == true) }

        // Model an external source activity's pause/resume, then deliver the real HOME intent path.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnUiThread {
            compose.activity.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setClass(compose.activity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        compose.waitUntil(10_000) { !assistant.chat.visible && assistant.homeShortcutEligible }
        compose.onNodeWithTag("assistant_chat_window").assertDoesNotExist()

        compose.runOnUiThread {
            assertEquals("space reentry draft", assistant.chat.draft)
            assertFalse(compose.activity.currentFocus?.onCheckIsTextEditor() == true)
            val space = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 0, 0)
            assertTrue(compose.activity.dispatchKeyEvent(space))
            assertEquals("space reentry draft", assistant.chat.draft)
            assertTrue(compose.activity.dispatchKeyEvent(KeyEvent.changeTimeRepeat(space, 1, 1)))
            assertTrue(compose.activity.dispatchKeyEvent(KeyEvent.changeAction(space, KeyEvent.ACTION_UP)))
            val suffix = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_X)
            assertTrue(compose.activity.dispatchKeyEvent(suffix))
            assertTrue(compose.activity.dispatchKeyEvent(KeyEvent.changeAction(suffix, KeyEvent.ACTION_UP)))
        }
        compose.onNodeWithTag("assistant_chat_input").assertTextContains("space reentry draftx").assertIsFocused()
        compose.onNodeWithText("Earlier reply").performScrollTo().assertIsDisplayed()
        compose.runOnUiThread { assertEquals("space reentry draftx", assistant.chat.draft) }
    }

    @Test fun spaceOpensWithoutAddingTextAndHeldSpaceIsConsumedOnlyForTheOpener() {
        compose.runOnUiThread {
            val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE)
            assertTrue(compose.activity.dispatchKeyEvent(down))
            assertEquals("", assistant.chat.draft)
            assertTrue(compose.activity.dispatchKeyEvent(KeyEvent.changeTimeRepeat(down, 1, 1)))
            val next = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_H)
            assertTrue(compose.activity.dispatchKeyEvent(next))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_H))
            assertTrue(compose.activity.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP)))
        }
        compose.onNodeWithTag("assistant_chat_window").assertIsDisplayed()
        compose.onNodeWithTag("assistant_chat_input").assertTextContains("h").assertIsFocused()
        compose.runOnUiThread { assertEquals("h", assistant.chat.draft) }
    }

}
