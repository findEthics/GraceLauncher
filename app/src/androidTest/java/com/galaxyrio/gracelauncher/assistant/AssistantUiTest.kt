package com.galaxyrio.gracelauncher.assistant

import android.graphics.Bitmap
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxyrio.gracelauncher.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AssistantUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun advancedExposesGemma4WithoutSpeechSetup() {
        compose.setContent { MaterialTheme { AssistantSettings() } }
        compose.onNodeWithTag("assistant_model_Gemma4").assertExists()
        compose.onNodeWithTag("assistant_model_FunctionGemma").assertDoesNotExist()
        compose.onNodeWithTag("assistant_token").assertDoesNotExist()
        compose.onNodeWithTag("assistant_speech_status").assertDoesNotExist()
        compose.onNodeWithTag("assistant_speech_settings").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.assistant_chat_hint)).performScrollTo().assertIsDisplayed()
    }
    @Test fun advancedHomeButtonPreferenceUpdatesOtherOwnersAndSurvivesRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("offline_assistant", android.content.Context.MODE_PRIVATE)
        val hadPreference = prefs.contains("show_home_button")
        val original = prefs.getBoolean("show_home_button", true)
        lateinit var observer: AssistantController
        compose.runOnUiThread {
            prefs.edit().remove("show_home_button").apply()
            observer = AssistantController(context, CoroutineScope(Dispatchers.Main))
            assertTrue(observer.homeButtonVisible) // Existing installs keep the button by default.
        }
        try {
            compose.setContent { MaterialTheme { AssistantSettings() } }
            val toggle = compose.onNodeWithTag("assistant_show_home_button")
            toggle.assertContentDescriptionEquals(context.getString(R.string.assistant_show_home_button))
            toggle.assertIsOn().performClick().assertIsOff()
            compose.runOnUiThread {
                assertFalse(observer.homeButtonVisible)
                assertFalse(prefs.getBoolean("show_home_button", true))
                val recreated = AssistantController(context, CoroutineScope(Dispatchers.Main))
                try { assertFalse(recreated.homeButtonVisible) } finally { recreated.close() }
            }
            toggle.performClick().assertIsOn()
            compose.runOnUiThread { assertTrue(observer.homeButtonVisible) }
        } finally {
            compose.runOnUiThread {
                observer.close()
                val edit = prefs.edit()
                if (hadPreference) edit.putBoolean("show_home_button", original) else edit.remove("show_home_button")
                edit.apply()
            }
        }
    }
    @Test fun enterSendsOnceAndShiftEnterInsertsNewline() {
        val chat = AssistantChatState()
        var sends = 0
        compose.setContent { MaterialTheme {
            AssistantChatWindow(chat, false, true, "", onSend = { sends++; chat.draft = "" }, onStop = {}, onClose = {})
        } }
        val input = compose.onNodeWithTag("assistant_chat_input")
        input.performTextInput("hello")
        input.performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Enter); keyUp(Key.ShiftLeft) }
        compose.runOnIdle { assertEquals("hello\n", chat.draft); assertEquals(0, sends) }
        input.performTextInput("world")
        input.performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(1, sends); assertEquals("", chat.draft) }
        input.performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(1, sends) }
    }
    @Test fun imeSendUsesGuardedSubmissionWithoutAnExtraButton() {
        val chat = AssistantChatState()
        var sends = 0
        compose.setContent { MaterialTheme {
            AssistantChatWindow(chat, false, true, "", onSend = { sends++; chat.draft = "" }, onStop = {}, onClose = {})
        } }
        compose.onNodeWithTag("assistant_send").assertDoesNotExist()
        compose.onNodeWithTag("assistant_chat_input").performImeAction()
        compose.runOnIdle { assertEquals(0, sends) }
        compose.onNodeWithTag("assistant_chat_input").performTextInput("question")
        compose.onNodeWithTag("assistant_chat_input").performImeAction()
        compose.runOnIdle { assertEquals(1, sends) }
        compose.onNodeWithTag("assistant_chat_input").performTextInput("! Turn on the flashlight")
        compose.onNodeWithTag("assistant_chat_input").performImeAction()
        compose.runOnIdle { assertEquals(2, sends) }
    }
    @Test fun busyEnterDoesNotSubmitAndCommittedActionCannotBeStopped() {
        val chat = AssistantChatState().apply { draft = "another request" }
        var sends = 0
        compose.setContent { MaterialTheme {
            AssistantChatWindow(chat, true, false, "Executing locally", onSend = { sends++ }, onStop = {}, onClose = {})
        } }
        compose.onNodeWithTag("assistant_chat_input").performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("assistant_stop").assertIsNotEnabled()
        compose.onNodeWithTag("assistant_status").assertTextEquals("Executing locally")
        compose.runOnIdle { assertEquals(0, sends); assertEquals("another request", chat.draft) }
    }
    @Test fun streamingReplyHasNoStatusFooterAndCanStillBeStopped() {
        val chat = AssistantChatState().apply { open() }
        val turn = chat.begin("Hello", false)
        var stops = 0
        compose.setContent { MaterialTheme {
            AssistantChatWindow(chat, true, true, "", onSend = {}, onStop = { stops++ }, onClose = {})
        } }
        compose.onNodeWithTag("assistant_response:$turn").assertTextEquals("Processing locally…")
        compose.onNodeWithTag("assistant_status").assertDoesNotExist()
        compose.onNodeWithText("Replying locally — no action").assertDoesNotExist()
        compose.runOnIdle { chat.update(turn, "Hello back") }
        compose.onNodeWithTag("assistant_response:$turn").assertTextEquals("Hello back")
        compose.onNodeWithTag("assistant_status").assertDoesNotExist()
        compose.onNodeWithTag("assistant_stop").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }
    @Test fun rejectedAlarmShowsOwnedValidationReasonAndEndsTheTurnBeforeDispatch() {
        lateinit var controller: AssistantController
        var turn = 0L
        compose.runOnUiThread {
            controller = AssistantController(InstrumentationRegistry.getInstrumentation().targetContext, CoroutineScope(Dispatchers.Main))
            controller.chat.open()
            turn = controller.chat.begin("!alarm 7:00", true)
        }
        try {
            compose.setContent { MaterialTheme { AssistantChat(controller) } }
            compose.runOnUiThread {
                assertFalse(controller.validateRequestForTurn(turn,
                    MobileAction("createAlarm", mapOf("time" to "07:00", "label" to "")), "alarm 7:00"))
                assertTrue(controller.chat.turns.single().complete)
                assertFalse(controller.chat.turns.single().inContext)
                assertFalse(controller.busy)
            }
            compose.onNodeWithTag("assistant_response:$turn").assertTextEquals("Say an explicit alarm time that matches. No alarm was set.")
            compose.onNodeWithText("No valid action produced; nothing executed.").assertDoesNotExist()
            compose.onNodeWithTag("assistant_status").assertDoesNotExist() // The response already contains the reason.
        } finally { compose.runOnUiThread { controller.close() } }
    }
    @Test fun hardwareShortcutsOpenOnceAndDoNotStealEditorOrOverlayInput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val controller = AssistantController(instrumentation.targetContext, CoroutineScope(Dispatchers.Main))
            val enabled = controller.enabled
            try {
                controller.enable(true)
                for ((key, prefix) in listOf(AndroidKeyEvent.KEYCODE_1 to "!", AndroidKeyEvent.KEYCODE_2 to "")) {
                    val down = AndroidKeyEvent(0, 0, AndroidKeyEvent.ACTION_DOWN, key, 0, AndroidKeyEvent.META_SHIFT_ON)
                    assertEquals(if (prefix.isEmpty()) '@'.code else '!'.code, down.unicodeChar)
                    controller.homeShortcutEligible = false
                    assertFalse(controller.hardwareKey(down, false)) // Other overlay/drawer, not idle HOME.
                    controller.homeShortcutEligible = true
                    assertFalse(controller.hardwareKey(down, true)) // A widget/text editor owns input.
                    assertTrue(controller.hardwareKey(down, false))
                    assertTrue(controller.chat.visible)
                    assertEquals(prefix, controller.chat.draft)
                    assertTrue(controller.hardwareKey(AndroidKeyEvent.changeTimeRepeat(down, 1, 1), false))
                    assertTrue(controller.hardwareKey(AndroidKeyEvent.changeAction(down, AndroidKeyEvent.ACTION_UP), false))
                    assertEquals(prefix, controller.chat.draft)
                    controller.composerFocusChanged(true)
                    assertFalse(controller.hardwareKey(down, true)) // Inside chat both characters are ordinary text.
                    controller.composerFocusChanged(false)
                    assertFalse(controller.hardwareKey(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_ENTER), false)) // Moving focus away must not submit.
                    controller.chat.clear()
                    controller.enable(false)
                    assertFalse(controller.hardwareKey(down, false))
                    controller.enable(true)
                }
            } finally { controller.enable(enabled); controller.close() }
        }
    }
    @Test fun backClearsButTemporaryMinimizeRetainsTranscriptAndDraft() {
        val chat = AssistantChatState()
        val turn = chat.begin("Hello", false)
        chat.update(turn, "Hello back", complete = true, inContext = true)
        chat.draft = "unsent"
        chat.open(); chat.minimize(); chat.open()
        compose.setContent { MaterialTheme {
            AssistantChatWindow(chat, false, true, "", onSend = {}, onStop = {}, onClose = chat::clear)
        } }
        compose.onNodeWithTag("assistant_request:$turn").assertTextEquals("Hello")
        compose.onNodeWithTag("assistant_response:$turn").assertTextEquals("Hello back")
        compose.onNodeWithTag("assistant_chat_input").assertTextContains("unsent")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertFalse(chat.visible); assertTrue(chat.turns.isEmpty()); assertEquals("", chat.draft) }
    }

    @Test fun refinedChatHasRoundedBubblesWithoutChromeOrDuplicateResultFooter() {
        val chat = AssistantChatState().apply { open() }
        val first = chat.begin("how long to boil an egg", false)
        chat.update(first, "Soft: 6 min. Jammy: 8 min. Hard: 11 min — from boiling, then straight into ice water.", complete = true)
        val second = chat.begin("and at altitude?", false)
        val reply = "Add roughly 1 minute per 1,000 ft above sea level. Water boils cooler up there."
        chat.update(second, reply, complete = true)
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            AssistantChatWindow(chat, false, true, reply, onSend = {}, onStop = {}, onClose = chat::clear)
        } }
        compose.onNodeWithTag("assistant_chat_close").assertDoesNotExist()
        compose.onNodeWithTag("assistant_send").assertDoesNotExist()
        compose.onNodeWithText("Offline assistant").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.assistant_chat_hint)).assertDoesNotExist()
        compose.onNodeWithText("Message").assertDoesNotExist()
        compose.onNodeWithContentDescription("Message").assertIsDisplayed()
        compose.onNodeWithText("Chat reply — no action performed").assertDoesNotExist()
        compose.onNodeWithText("Reply complete — no action").assertDoesNotExist()
        compose.onNodeWithTag("assistant_status").assertDoesNotExist()
        compose.onNodeWithTag("assistant_response:$second").assertTextEquals(reply)
        closeSoftKeyboard()
        val screenshot = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("test-screenshots"), "assistant-chat-refined.png")
        screenshot.outputStream().use { compose.onNodeWithTag("assistant_chat_window").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun actionOutcomeAndPreTurnErrorsRemainVisible() {
        val chat = AssistantChatState().apply { open() }
        val turn = chat.begin("! Turn on the flashlight", true)
        val result = "No valid action produced; nothing executed."
        chat.update(turn, result, complete = true)
        compose.setContent { MaterialTheme {
            AssistantChatWindow(chat, false, true, "Download Gemma 4 in Advanced settings", onSend = {}, onStop = {}, onClose = chat::clear)
        } }
        compose.onNodeWithText("Action result").assertIsDisplayed()
        compose.onNodeWithTag("assistant_response:$turn").assertTextEquals(result)
        compose.onNodeWithTag("assistant_status").assertTextEquals("Download Gemma 4 in Advanced settings")
    }
}
