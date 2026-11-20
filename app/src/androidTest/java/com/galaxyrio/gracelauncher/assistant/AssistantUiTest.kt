package com.galaxyrio.gracelauncher.assistant

import android.graphics.Bitmap
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
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

    @Test fun wikipediaSelectionIsExclusiveAndAttributionSurvivesSwitchToWeb() {
        val chat = AssistantChatState().apply { draft = "keep draft" }
        compose.setContent { MaterialTheme {
            AssistantChatWindow(chat, false, true, "", onSend = {}, onStop = {}, onClose = chat::clear)
        } }
        compose.onNodeWithTag("assistant_tools").performClick()
        compose.onNodeWithTag("assistant_wikipedia_option").assertIsOff().performClick()
        compose.onNodeWithTag("assistant_web_chip").assertDoesNotExist()
        compose.onNodeWithTag("assistant_wikipedia_chip").assertIsSelected()
            .assertContentDescriptionEquals(compose.activity.getString(R.string.assistant_remove_wikipedia))
        compose.onNodeWithTag("assistant_wikipedia_chip").performClick().assertDoesNotExist()
        compose.onNodeWithTag("assistant_tools").performClick()
        compose.onNodeWithTag("assistant_web_search_option").assertIsOff()
        compose.onNodeWithTag("assistant_wikipedia_option").assertIsOff().performClick()
        var turn = 0L
        compose.runOnIdle {
            assertEquals("keep draft", chat.draft)
            turn = chat.begin("Ada?", false)
            chat.searchDetails(turn, listOf(WebSource("Ada", "https://en.wikipedia.org/wiki/Ada_Lovelace", "evidence", provider = ChatSource.WIKIPEDIA)), "")
            chat.update(turn, "Supported fact [1]", complete = true, wikipediaSummaryGenerated = true)
            chat.source = ChatSource.WEB
        }
        closeSoftKeyboard()
        compose.onNodeWithTag("assistant_wikipedia_attribution:$turn").assertIsDisplayed()
        compose.onNodeWithTag("assistant_wikipedia_license:$turn").assertHasClickAction()
        compose.onNodeWithTag("assistant_source:$turn:0").assertTextContains("Wikipedia contributors", substring = true)
        compose.onNodeWithTag("assistant_web_chip").assertIsSelected()
    }

    @Test fun wikipediaFailuresKeepSourceCreditsWithoutClaimingAnAdaptedSummary() {
        val source = WebSource("Ada", "https://en.wikipedia.org/wiki/Ada", "Evidence", provider = ChatSource.WIKIPEDIA)
        val chat = AssistantChatState().apply { this.source = ChatSource.WIKIPEDIA }
        val failedTurns = listOf(
            "Could not finish the local reply. No action performed.",
            WikipediaSearch.INABILITY,
            "Cancelled; nothing executed.",
        ).mapIndexed { index, response ->
            val turn = chat.begin("Question $index", false)
            chat.searchDetails(turn, listOf(source), "")
            chat.update(turn, response, complete = true)
            turn
        }
        val streaming = chat.begin("Streaming", false)
        chat.searchDetails(streaming, listOf(source), "")
        chat.update(streaming, "Partial response")
        val emptyWiki = chat.begin("No current source", false)
        chat.update(emptyWiki, WikipediaSearch.INABILITY, complete = true)
        chat.source = ChatSource.NONE
        val toolFree = chat.begin("Hello", false)
        chat.update(toolFree, "Hi", complete = true)

        compose.setContent { MaterialTheme {
            AssistantChatWindow(chat, false, true, "", onSend = {}, onStop = {}, onClose = {})
        } }
        closeSoftKeyboard()
        for (turn in failedTurns + streaming) {
            compose.onNodeWithTag("assistant_response:$turn").performScrollTo()
            compose.onNodeWithTag("assistant_wikipedia_attribution:$turn").assertDoesNotExist()
            compose.onNodeWithTag("assistant_wikipedia_license:$turn").assertHasClickAction()
            compose.onNodeWithTag("assistant_source:$turn:0").assertTextContains("Wikipedia contributors", substring = true)
        }
        for (turn in listOf(emptyWiki, toolFree)) {
            compose.onNodeWithTag("assistant_response:$turn").performScrollTo()
            compose.onNodeWithTag("assistant_wikipedia_attribution:$turn").assertDoesNotExist()
            compose.onNodeWithTag("assistant_wikipedia_license:$turn").assertDoesNotExist()
        }
    }

    @Test fun webSearchMenuSelectsPersistentRemovableChipWithoutEditingDraft() {
        val chat = AssistantChatState().apply { draft = "keep my draft" }
        compose.setContent { MaterialTheme {
            AssistantChatWindow(chat, false, true, "", onSend = { chat.begin(chat.draft, false) }, onStop = {}, onClose = chat::clear)
        } }
        compose.onNodeWithTag("assistant_web_chip").assertIsSelected()
        compose.onNodeWithTag("assistant_tools").performClick()
        compose.onNodeWithTag("assistant_web_search_option").assertIsOn()
        val screenshot = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("test-screenshots"), "assistant-tools-rounded.png")
        screenshot.outputStream().use { compose.onNodeWithTag("assistant_tools_menu").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag("assistant_web_search_option").performClick()
        compose.onNodeWithTag("assistant_web_chip").assertDoesNotExist()
        compose.onNodeWithTag("assistant_tools").performClick()
        compose.onNodeWithTag("assistant_web_search_option").assertIsOff().performClick()
        compose.onNodeWithTag("assistant_web_chip").assertIsSelected()
            .assertContentDescriptionEquals(compose.activity.getString(R.string.assistant_remove_web_search))
        compose.onNodeWithTag("assistant_tools").performClick()
        compose.onNodeWithTag("assistant_web_search_option").assertIsOn().performClick()
        compose.onNodeWithTag("assistant_web_chip").assertDoesNotExist()
        compose.onNodeWithTag("assistant_tools").performClick()
        compose.onNodeWithTag("assistant_web_search_option").assertIsOff().performClick()
        compose.runOnIdle { assertEquals("keep my draft", chat.draft) }
        compose.onNodeWithTag("assistant_chat_input").performImeAction()
        compose.runOnIdle { assertTrue(chat.turns.single().webSearch); assertTrue(chat.webSearchEnabled) }
        compose.onNodeWithTag("assistant_web_chip").performClick().assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(chat.webSearchEnabled)
            chat.minimize(); chat.open()
            assertFalse(chat.webSearchEnabled)
            chat.clear(); chat.open()
        }
        compose.onNodeWithTag("assistant_web_chip").assertIsSelected()
    }

    @Test fun searchFailureWarningAndStructuredSourcesRemainVisible() {
        val chat = AssistantChatState()
        chat.webSearchEnabled = true
        val turn = chat.begin("news", false)
        chat.searchDetails(turn, listOf(WebSource("Example source", "https://example.org", "excerpt")), "Search failed; unverified offline answer")
        chat.update(turn, "Offline answer", complete = true)
        compose.setContent { MaterialTheme {
            AssistantChatWindow(chat, false, true, "", onSend = {}, onStop = {}, onClose = chat::clear)
        } }
        closeSoftKeyboard()
        compose.onNodeWithTag("assistant_search_warning:$turn").assertIsDisplayed()
        compose.onNodeWithTag("assistant_source_card:$turn:0").assertIsDisplayed()
        compose.onNodeWithTag("assistant_source_open:$turn:0").assertHasClickAction()
        compose.onNodeWithTag("assistant_response:$turn").assertTextEquals("Offline answer")
    }

    @Test fun sourceCardsShowOnlyAvailablePublishedDates() {
        val chat = AssistantChatState()
        val turn = chat.begin("news", false)
        chat.searchDetails(turn, listOf(WebSource("Dated", "https://example.org", "evidence", "2026-11-03"),
            WebSource("Undated", "https://example.com", "evidence")), "")
        chat.update(turn, "Summary", complete = true, inContext = true)
        compose.setContent { MaterialTheme {
            AssistantChatWindow(chat, false, true, "", onSend = {}, onStop = {}, onClose = chat::clear)
        } }
        closeSoftKeyboard()
        val date = formatPublishedDate("2026-11-03")!!
        compose.onNodeWithTag("assistant_source_card:$turn:0").assertIsDisplayed()
        compose.onNodeWithTag("assistant_source_title:$turn:0").assertTextEquals("[1] Dated")
        compose.onNodeWithTag("assistant_source_metadata:$turn:0").assertTextEquals("example.org · $date")
        compose.onNodeWithTag("assistant_source_title:$turn:1").assertTextEquals("[2] Undated")
        compose.onNodeWithTag("assistant_source_metadata:$turn:1").assertTextEquals("example.com")
        compose.onNodeWithTag("assistant_source_expand:$turn:0").assertDoesNotExist()
        compose.onNodeWithTag("assistant_web_results_disclosure:$turn").assertTextEquals("2 results · answer uses selected sources")
    }

    @Test fun webCardsKeepFullTextExpandLocallyAndOpenSeparateAccessibleLinks() {
        val title = "A long headline that wraps naturally ".repeat(10)
        val excerpt = "The provider excerpt includes supporting detail. ".repeat(20)
        val url = "https://example.org/story"
        val chat = AssistantChatState()
        val turn = chat.begin("news", false)
        chat.searchDetails(turn, listOf(WebSource(title, url, excerpt)), "")
        chat.update(turn, "Answer", complete = true, inContext = true)
        val opened = mutableListOf<String>()
        var sends = 0
        val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalUriHandler provides handler, LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme { AssistantChatWindow(chat, false, true, "", onSend = { sends++ }, onStop = {}, onClose = {}) }
            }
        }
        closeSoftKeyboard()

        compose.onNodeWithTag("assistant_source_title:$turn:0").assertTextEquals("[1] $title")
        compose.onNodeWithTag("assistant_source_metadata:$turn:0").assertTextEquals("example.org")
        compose.onNodeWithTag("assistant_source_excerpt:$turn:0").assertTextEquals(excerpt)
        compose.onNodeWithTag("assistant_web_results_disclosure:$turn").assertTextEquals("1 result · answer uses a selected source")
        val expand = compose.onNodeWithTag("assistant_source_expand:$turn:0")
            .assertContentDescriptionEquals("Show more").assertHasClickAction()
        assertEquals("Collapsed", expand.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        val open = compose.onNodeWithTag("assistant_source_open:$turn:0")
            .assertContentDescriptionEquals("Open source").assertHasClickAction()
        val minTouch = 48f * compose.activity.resources.displayMetrics.density
        assertTrue(expand.fetchSemanticsNode().boundsInRoot.height >= minTouch)
        assertTrue(open.fetchSemanticsNode().boundsInRoot.height >= minTouch)
        open.performClick()
        compose.runOnIdle { assertEquals(listOf(url), opened); assertEquals(0, sends) }
        expand.assertContentDescriptionEquals("Show more")
        expand.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        expand.performKeyInput { pressKey(Key.Enter) }
        expand.assertContentDescriptionEquals("Show less")
        assertEquals("Expanded", expand.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        compose.runOnIdle { assertEquals(listOf(url), opened); assertEquals(0, sends) }
    }

    @Test fun webResultsDisclosureDoesNotClaimSynthesisWithoutACompletedAnswer() {
        val chat = AssistantChatState()
        val turn = chat.begin("news", false)
        chat.searchDetails(turn, listOf(WebSource("Source", "https://example.org", "evidence")), "")
        chat.update(turn, "", complete = false, inContext = true)
        compose.setContent { MaterialTheme {
            AssistantChatWindow(chat, false, true, "", onSend = {}, onStop = {}, onClose = {})
        } }
        compose.onNodeWithTag("assistant_web_results_disclosure:$turn").assertDoesNotExist()
    }

    @Test fun offlineAssistantExposesGemma4WithoutSpeechSetupOrKeyboardInstructions() {
        compose.setContent { MaterialTheme { AssistantSettings() } }
        compose.onNodeWithText(compose.activity.getString(R.string.magic_help)).assertDoesNotExist()
        compose.onNodeWithTag("assistant_model_Gemma4").assertExists()
        compose.onNodeWithTag("assistant_model_NoSuchModel").assertDoesNotExist()
        compose.onNodeWithTag("assistant_token").assertDoesNotExist()
        compose.onNodeWithTag("assistant_speech_status").assertDoesNotExist()
        compose.onNodeWithTag("assistant_speech_settings").assertDoesNotExist()
        compose.onNodeWithTag("assistant_help_toggle").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.assistant_chat_hint)).performScrollTo().assertIsDisplayed()
    }

    @Test fun advancedHelpSectionsStartCollapsedAndExpand() {
        compose.setContent { MaterialTheme { AssistantSettings() } }
        for ((toggle, content) in listOf("assistant_help_toggle" to "assistant_help", "assistant_memory_help_toggle" to "assistant_memory_help")) {
            compose.onNodeWithTag(content).assertDoesNotExist()
            compose.onNodeWithTag(toggle).performScrollTo().assertIsDisplayed()
            assertEquals("collapsed", compose.onNodeWithTag(toggle).fetchSemanticsNode().config[SemanticsProperties.StateDescription])
            compose.onNodeWithTag(toggle).performClick()
            compose.onNodeWithTag(content).assertExists()
            assertEquals("expanded", compose.onNodeWithTag(toggle).fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        }
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
            val toggle = compose.onNodeWithTag("assistant_show_home_button").performScrollTo()
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
    @Test fun stopIconIsInsideComposerAndPreservesTheNextDraft() {
        val chat = AssistantChatState().apply { draft = "next message" }
        var stops = 0
        compose.setContent { MaterialTheme {
            AssistantChatWindow(chat, true, true, "", onSend = {}, onStop = { stops++ }, onClose = {})
        } }
        val input = compose.onNodeWithTag("assistant_chat_input")
        val stop = compose.onNodeWithTag("assistant_stop")
            .assertContentDescriptionEquals(compose.activity.getString(R.string.assistant_stop))
            .assertIsEnabled()
        val inputBounds = input.fetchSemanticsNode().boundsInRoot
        val stopBounds = stop.fetchSemanticsNode().boundsInRoot
        assertTrue(stopBounds.left >= inputBounds.left && stopBounds.right <= inputBounds.right)
        assertTrue(stopBounds.top >= inputBounds.top && stopBounds.bottom <= inputBounds.bottom)
        compose.onNodeWithText(compose.activity.getString(R.string.assistant_stop)).assertDoesNotExist()
        stop.performClick()
        compose.runOnIdle { assertEquals(1, stops); assertEquals("next message", chat.draft) }
    }
    @Test fun streamingReplyHasNoStatusFooterAndCanStillBeStopped() {
        val chat = AssistantChatState().apply { open() }
        val turn = chat.begin("Hello", false)
        var stops = 0
        compose.setContent { MaterialTheme {
            AssistantChatWindow(chat, true, true, "", onSend = {}, onStop = { stops++ }, onClose = {})
        } }
        compose.onNodeWithTag("assistant_response:$turn").assertTextEquals("...")
        compose.onNodeWithTag("assistant_status").assertDoesNotExist()
        compose.onNodeWithText("Replying locally — no action").assertDoesNotExist()
        compose.runOnIdle { chat.update(turn, "Hello back") }
        compose.onNodeWithTag("assistant_response:$turn").assertTextEquals("Hello back")
        compose.onNodeWithTag("assistant_status").assertDoesNotExist()
        compose.onNodeWithTag("assistant_stop").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }
    @Test fun hardwareShortcutsOpenOnceAndDoNotStealEditorOrOverlayInput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val controller = AssistantController(instrumentation.targetContext, CoroutineScope(Dispatchers.Main))
            val enabled = controller.enabled
            try {
                controller.enable(true)
                controller.homeShortcutEligible = true
                // @ belongs to Magic; the assistant must leave it for that dispatcher.
                assertFalse(controller.hardwareKey(AndroidKeyEvent(0, 0, AndroidKeyEvent.ACTION_DOWN,
                    AndroidKeyEvent.KEYCODE_2, 0, AndroidKeyEvent.META_SHIFT_ON), false))
                assertFalse(controller.chat.visible)
                assertFalse(controller.hardwareKey(AndroidKeyEvent(0, 0, AndroidKeyEvent.ACTION_DOWN,
                    AndroidKeyEvent.KEYCODE_1, 0, AndroidKeyEvent.META_SHIFT_ON), false))
                assertFalse(controller.chat.visible)
                for ((key, prefix, meta) in listOf(
                    Triple(AndroidKeyEvent.KEYCODE_SPACE, "", 0),
                )) {
                    val down = AndroidKeyEvent(0, 0, AndroidKeyEvent.ACTION_DOWN, key, 0, meta)
                    if (key == AndroidKeyEvent.KEYCODE_SPACE) assertEquals(' '.code, down.unicodeChar)
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
        closeSoftKeyboard()
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
        closeSoftKeyboard()
        compose.onNodeWithText("Action result").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("assistant_response:$turn").assertTextEquals(result)
        compose.onNodeWithTag("assistant_status").assertTextEquals("Download Gemma 4 in Advanced settings")
    }

    private fun slashChat(health: Boolean = false, onSend: () -> Unit = {}): AssistantChatState {
        val chat = AssistantChatState()
        compose.setContent { MaterialTheme {
            AssistantChatWindow(chat, false, true, "", onSend = onSend, onStop = {}, onClose = {}, healthAvailable = health)
        } }
        return chat
    }

    @Test fun slashPopupShowsFiltersAndHidesOnSpaceOrNoMatch() {
        slashChat()
        val input = compose.onNodeWithTag("assistant_chat_input")
        compose.onNodeWithTag("assistant_slash_popup").assertDoesNotExist()
        input.performTextInput("/")
        compose.onNodeWithTag("assistant_slash_popup").assertIsDisplayed()
        for (t in listOf("web", "wikipedia", "remember", "recall", "forget", "purge", "history"))
            compose.onNodeWithTag("assistant_slash_row_$t").assertExists()
        compose.onNodeWithTag("assistant_slash_row_health").assertDoesNotExist()
        input.performTextClearance(); input.performTextInput("/del")
        compose.onNodeWithTag("assistant_slash_row_forget").assertExists()
        compose.onNodeWithTag("assistant_slash_row_web").assertDoesNotExist()
        input.performTextClearance(); input.performTextInput("/xyz")
        compose.onNodeWithTag("assistant_slash_popup").assertDoesNotExist()
        input.performTextClearance(); input.performTextInput("/remember milk")
        compose.onNodeWithTag("assistant_slash_popup").assertDoesNotExist()
    }

    @Test fun slashPopupShowsHealthRowOnlyWithToken() {
        slashChat(health = true)
        compose.onNodeWithTag("assistant_chat_input").performTextInput("/")
        compose.onNodeWithTag("assistant_slash_row_health").assertExists()
    }

    @Test fun slashEnterAppliesFirstRowAndDownEnterTheSecond() {
        val chat = slashChat()
        val input = compose.onNodeWithTag("assistant_chat_input")
        input.performTextInput("/w")
        input.performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(ChatSource.WEB, chat.source); assertEquals("", chat.draft) }
        input.performTextInput("/w")
        input.performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(ChatSource.WIKIPEDIA, chat.source); assertEquals("", chat.draft) }
        compose.onNodeWithTag("assistant_wikipedia_chip").assertIsSelected()
    }

    @Test fun slashMemoryRowSelectsOneShotChipAndTapAppliesRow() {
        val chat = slashChat()
        val input = compose.onNodeWithTag("assistant_chat_input")
        input.performTextInput("/rec")
        compose.onNodeWithTag("assistant_slash_row_recall").performClick()
        compose.runOnIdle { assertEquals(com.galaxyrio.gracelauncher.assistant.memory.MemoryChip.RECALL, chat.memoryChip); assertEquals("", chat.draft) }
        compose.onNodeWithTag("assistant_memory_chip").assertIsDisplayed()
        input.performTextInput("/forg")
        input.performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(com.galaxyrio.gracelauncher.assistant.memory.MemoryChip.DELETE, chat.memoryChip) }
    }

    @Test fun slashEscapeHidesUntilNextEditAndHistoryInsertsText() {
        val chat = slashChat()
        val input = compose.onNodeWithTag("assistant_chat_input")
        input.performTextInput("/")
        input.performKeyInput { pressKey(Key.Escape) }
        compose.onNodeWithTag("assistant_slash_popup").assertDoesNotExist()
        compose.runOnIdle { assertEquals("/", chat.draft) }
        input.performTextInput("h")
        compose.onNodeWithTag("assistant_slash_row_history").assertExists()
        input.performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals("/history ", chat.draft) }
        compose.onNodeWithTag("assistant_slash_popup").assertDoesNotExist()
    }

    @Test fun slashShiftEnterStillInsertsNewlineAndNoMatchEnterSubmits() {
        var sends = 0
        val chat = slashChat(onSend = { sends++ })
        val input = compose.onNodeWithTag("assistant_chat_input")
        input.performTextInput("/xyz")
        input.performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(1, sends) }
        input.performTextClearance(); input.performTextInput("/")
        input.performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Enter); keyUp(Key.ShiftLeft) }
        compose.runOnIdle { assertEquals("/\n", chat.draft); assertEquals(1, sends) }
    }

    @Test fun recallChipIsInMenuAndAllowsEmptySubmit() {
        var sends = 0
        val chat = slashChat(onSend = { sends++ })
        compose.onNodeWithTag("assistant_chat_input").performImeAction()
        compose.runOnIdle { assertEquals(0, sends) }
        compose.onNodeWithTag("assistant_tools").performClick()
        compose.onNodeWithTag("assistant_memory_option_recall").assertIsOff().performClick()
        compose.onNodeWithTag("assistant_memory_chip").assertIsDisplayed()
        compose.onNodeWithTag("assistant_chat_input").performImeAction()
        compose.runOnIdle { assertEquals(1, sends); assertEquals(com.galaxyrio.gracelauncher.assistant.memory.MemoryChip.RECALL, chat.memoryChip) }
    }
}
