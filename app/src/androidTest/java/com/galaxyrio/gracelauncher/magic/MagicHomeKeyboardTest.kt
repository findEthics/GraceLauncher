package com.galaxyrio.gracelauncher.magic

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.KeyEvent
import android.text.InputType
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.galaxyrio.gracelauncher.MainActivity
import com.galaxyrio.gracelauncher.assistant.AssistantController
import com.galaxyrio.gracelauncher.ui.LauncherViewModel
import com.katoaapps.openminilaunch.model.CommunicationRecipient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Synthetic input and state only. Never sends SMS, places a call, opens a provider, or runs inference. */
class MagicHomeKeyboardTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var viewModel: LauncherViewModel
    private lateinit var magic: MagicController
    private lateinit var assistant: AssistantController
    private val synthetic = SyntheticMagicDataSource()
    private var originalSource: MagicDataSource? = null
    private var originalMessaging = emptyMap<String, Any?>()
    private lateinit var messagingPreferences: SharedPreferences
    private var originalAssistantEnabled = false
    private var originalHomeButtonVisible = true

    @Before fun setUp() {
        compose.runOnUiThread {
            viewModel = ViewModelProvider(compose.activity)[LauncherViewModel::class.java]
            magic = viewModel.magic
            assistant = viewModel.assistant
            originalAssistantEnabled = assistant.enabled
            originalHomeButtonVisible = assistant.homeButtonVisible
            magic.close()
            originalSource = magic.dataSource
            magic.dataSource = synthetic
            messagingPreferences = compose.activity.getSharedPreferences("magic_commands", Context.MODE_PRIVATE)
            originalMessaging = listOf("provider", "automatic").associateWith { messagingPreferences.all[it] }
            messagingPreferences.edit().remove("provider").putBoolean("automatic", false).commit()
            assistant.closeChat()
            assistant.enable(false)
        }
        compose.waitUntil(15_000) { magic.eligible }
    }

    @After fun restoreAssistant() {
        compose.runOnUiThread {
            magic.close()
            magic.dataSource = originalSource
            messagingPreferences.edit().apply {
                for ((key, value) in originalMessaging) when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    else -> remove(key)
                }
            }.commit()
            assistant.closeChat()
            assistant.showHomeButton(originalHomeButtonVisible)
            assistant.enable(originalAssistantEnabled)
        }
    }

    @Test fun strictProviderResultsStaySeparateAndSuppressGenericAndRawRows() {
        fun conversation(pkg: String, label: String) = MagicConversation(
            com.katoaapps.openminilaunch.model.LauncherShortcutTarget(label, pkg, label, 0, false),
            com.galaxyrio.gracelauncher.data.LauncherShortcut(label, pkg, label, null), emptySet())
        synthetic.conversationSnapshot = listOf(conversation("com.whatsapp", "WA fixture"),
            conversation("com.whatsapp.w4b", "Business fixture"), conversation("sms.app", "SMS fixture"))
        synthetic.contactSnapshots = mapOf(null to listOf(com.galaxyrio.gracelauncher.data.SearchContact(
            1, android.net.Uri.parse("content://synthetic/1"), "Generic fixture", null, listOf("15550000"))))
        compose.runOnUiThread {
            messagingPreferences.edit().putString("provider", "com.whatsapp").commit()
            magic.open(MagicKind.Message)
        }
        compose.onNodeWithText("WA fixture").assertExists()
        compose.onNodeWithText("Business fixture").assertDoesNotExist()
        compose.onNodeWithText("SMS fixture").assertDoesNotExist()
        compose.onNodeWithText("Generic fixture").assertDoesNotExist()
        compose.onNodeWithTag("magic_input").performTextInput("15559999")
        compose.onAllNodesWithTag("magic_result:0").assertCountEquals(0)
        compose.runOnUiThread {
            magic.session!!.choose(CommunicationRecipient("15550000", "Stale fixture", "15550000"), "com.whatsapp")
            messagingPreferences.edit().putString("provider", "com.whatsapp.w4b").commit()
        }
        compose.onNodeWithText("Business fixture").assertExists()
        compose.onNodeWithText("WA fixture").assertDoesNotExist()
        compose.runOnUiThread { assertNull(magic.session!!.recipient); assertEquals("", magic.session!!.input.text) }
    }

    @Test fun syncedPhoneSnapshotSelectsExactProviderAndPermissionLossFallsBackToConversations() {
        val pkg = "com.whatsapp"
        synthetic.contactSnapshots = mapOf(pkg to listOf(com.galaxyrio.gracelauncher.data.SearchContact(
            7, android.net.Uri.parse("content://synthetic/7"), "Synced fixture", null, listOf("15550007"))))
        synthetic.conversationSnapshot = listOf(MagicConversation(
            com.katoaapps.openminilaunch.model.LauncherShortcutTarget("Recent fixture", pkg, "recent", 0, false),
            com.galaxyrio.gracelauncher.data.LauncherShortcut("recent", pkg, "Recent fixture", null), emptySet()))
        compose.runOnUiThread {
            messagingPreferences.edit().putString("provider", pkg).commit()
            magic.open(MagicKind.Message)
        }
        compose.onNodeWithTag("magic_result:0").assertTextContains("Synced fixture").performClick()
        compose.runOnUiThread { assertEquals(pkg, magic.session!!.providerPackage) }
        synthetic.granted = false
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Synced fixture").assertDoesNotExist()
        compose.onNodeWithText("Recent fixture").assertExists()
        compose.runOnUiThread { assertNull(magic.session!!.recipient); assertFalse(synthetic.externalActionsAllowed) }
    }

    @Test fun missingSelectedProviderHasNoSmsContactsOrTypedRecipientFallback() {
        synthetic.installedPackages = emptySet()
        compose.runOnUiThread {
            messagingPreferences.edit().putString("provider", "com.whatsapp").commit()
            magic.open(MagicKind.Message)
        }
        compose.onNodeWithTag("magic_input").performTextInput("15559999")
        compose.onAllNodesWithTag("magic_result:0").assertCountEquals(0)
        compose.runOnUiThread { assertFalse(synthetic.externalActionsAllowed) }
    }

    @Test fun recipientAutofillExclusionEndsWhenEditingMessageBodyOrEvent() {
        fun dataType() = compose.onNodeWithTag("magic_input").fetchSemanticsNode()
            .config[SemanticsProperties.ContentDataType]
        for (kind in listOf(MagicKind.Message, MagicKind.Call)) {
            compose.runOnUiThread { magic.open(kind) }
            compose.onNodeWithTag("magic_input").assertIsFocused()
                .assertContentDescriptionEquals(compose.activity.getString(com.galaxyrio.gracelauncher.R.string.magic_recipient))
            assertEquals(ContentDataType.None, dataType())
            compose.runOnUiThread { magic.close() }
        }
        compose.runOnUiThread { magic.open(MagicKind.Message) }
        compose.onNodeWithTag("magic_input").performTextInput("Synthetic hello")
        compose.runOnUiThread {
            magic.session!!.choose(CommunicationRecipient("+15551234567", "Synthetic", "+15551234567"))
        }
        compose.onNodeWithTag("magic_input").assertIsFocused().assertTextContains("hello")
        assertEquals(ContentDataType.Text, dataType())
        compose.runOnUiThread { magic.close(); magic.open(MagicKind.Event) }
        compose.onNodeWithTag("magic_input").assertIsFocused()
        assertEquals(ContentDataType.Text, dataType())
        compose.runOnUiThread { magic.close(); magic.open(MagicKind.Message) }
        compose.onNodeWithTag("magic_input").assertIsFocused()
        assertEquals(ContentDataType.None, dataType())
    }

    @Test fun recipientPlatformRequestPreservesConnectionAndBodyRequestPassesThrough() {
        val connection = BaseInputConnection(compose.activity.window.decorView, false)
        val originalType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        val originalOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        val request = object : PlatformTextInputMethodRequest {
            override fun createInputConnection(outAttributes: EditorInfo) = connection.also {
                outAttributes.inputType = originalType
                outAttributes.imeOptions = originalOptions
            }
        }
        val searchInfo = EditorInfo()
        assertSame(connection, magicEditorRequest(request, true).createInputConnection(searchInfo))
        assertEquals(recipientSearchInputType(originalType), searchInfo.inputType)
        assertEquals(recipientSearchImeOptions(originalOptions), searchInfo.imeOptions)
        val bodyInfo = EditorInfo()
        assertSame(request, magicEditorRequest(request, false))
        assertSame(connection, magicEditorRequest(request, false).createInputConnection(bodyInfo))
        assertEquals(originalType, bodyInfo.inputType)
        assertEquals(originalOptions, bodyInfo.imeOptions)
    }

    @Test fun shiftedAtHashAndPlusOpenMagicAndConsumeOnlyTheirOpeningKey() {
        val shortcuts = listOf(
            Triple(KeyEvent.KEYCODE_2, '@', MagicKind.Message),
            Triple(KeyEvent.KEYCODE_3, '#', MagicKind.Call),
            Triple(KeyEvent.KEYCODE_EQUALS, '+', MagicKind.Event),
        )
        for ((keyCode, character, kind) in shortcuts) {
            compose.runOnUiThread {
                val opener = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_SHIFT_ON)
                assertEquals(character.code, opener.unicodeChar)
                assertTrue(compose.activity.dispatchKeyEvent(opener))
                assertEquals(kind, magic.session?.kind)
                assertEquals("", magic.session?.input?.text)
                assertTrue(compose.activity.dispatchKeyEvent(KeyEvent.changeTimeRepeat(opener, 1, 1)))
                assertEquals("", magic.session?.input?.text)

                val following = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_H)
                assertTrue(compose.activity.dispatchKeyEvent(following))
            }
            compose.onNodeWithTag("magic_window").assertIsDisplayed()
            compose.onNodeWithTag("magic_input").assertTextContains("h").assertIsFocused()
            compose.runOnUiThread {
                assertTrue(compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_H)))
                val repeat = KeyEvent(0, 1, KeyEvent.ACTION_DOWN, keyCode, 1, KeyEvent.META_SHIFT_ON)
                assertTrue(compose.activity.dispatchKeyEvent(repeat))
                assertTrue(compose.activity.dispatchKeyEvent(KeyEvent.changeAction(repeat, KeyEvent.ACTION_UP)))
                assertEquals("h", magic.session?.input?.text)
                assertFalse(assistant.enabled)
                assertFalse(assistant.chat.visible)
                magic.close()
            }
            compose.waitUntil(10_000) { magic.session == null && magic.eligible }
        }
    }

    @Test fun questionMarkReusesTheExistingAppSearchRoute() {
        compose.runOnUiThread {
            val opener = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SLASH, 0, KeyEvent.META_SHIFT_ON)
            assertEquals('?'.code, opener.unicodeChar)
            assertTrue(compose.activity.dispatchKeyEvent(opener))
            assertTrue(compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Q)))
        }

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("app_search").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("settings_search").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag("app_search").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("app_search").assertIsDisplayed()
        } else {
            compose.onNodeWithTag("settings_search").assertIsDisplayed()
        }
        compose.onNodeWithTag("magic_window").assertDoesNotExist()
        compose.runOnUiThread {
            assertTrue(compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Q)))
            val repeat = KeyEvent(0, 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SLASH, 1, KeyEvent.META_SHIFT_ON)
            assertTrue(compose.activity.dispatchKeyEvent(repeat))
            assertTrue(compose.activity.dispatchKeyEvent(KeyEvent.changeAction(repeat, KeyEvent.ACTION_UP)))
            assertNull(magic.session)
            assertFalse(assistant.chat.visible)
        }
    }

    @Test fun magicPrefixesInsideAssistantComposerAreOrdinaryInput() {
        compose.runOnUiThread {
            assistant.showHomeButton(true)
            assistant.enable(true)
            assistant.openChat()
        }
        compose.waitUntil(10_000) { assistant.chat.visible && magic.eligible }
        compose.onNodeWithTag("assistant_chat_input").assertIsFocused()

        compose.runOnUiThread {
            for ((keyCode, character) in listOf(
                KeyEvent.KEYCODE_2 to '@',
                KeyEvent.KEYCODE_3 to '#',
                KeyEvent.KEYCODE_EQUALS to '+',
            )) {
                val opener = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_SHIFT_ON)
                assertEquals(character.code, opener.unicodeChar)
                compose.activity.dispatchKeyEvent(opener)
                compose.activity.dispatchKeyEvent(KeyEvent.changeAction(opener, KeyEvent.ACTION_UP))
            }
        }

        compose.onNodeWithTag("assistant_chat_input").assertTextContains("@#+")
        compose.onNodeWithTag("magic_window").assertDoesNotExist()
        compose.runOnUiThread { assertNull(magic.session) }
    }

    @Test fun messageResultCanBeChosenByTouchOrEnterWithoutDispatching() {
        val touchRecipient = "NoSuchMagicContact_${System.nanoTime()}"
        openMessageComposer()
        compose.onNodeWithTag("magic_input").performTextInput("$touchRecipient message body")
        waitForRawRecipient()

        val resultBounds = compose.onNodeWithTag("magic_result:0").fetchSemanticsNode().boundsInRoot
        val inputBounds = compose.onNodeWithTag("magic_input").fetchSemanticsNode().boundsInRoot
        val submitBounds = compose.onNodeWithTag("magic_submit").fetchSemanticsNode().boundsInRoot
        val windowBounds = compose.onNodeWithTag("magic_window").fetchSemanticsNode().boundsInRoot
        assertTrue("results must be above the composer", resultBounds.bottom <= inputBounds.top)
        assertTrue("the composer must remain below results and inside the window", submitBounds.top > resultBounds.bottom && submitBounds.bottom <= windowBounds.bottom)

        compose.onNodeWithTag("magic_result:0").performClick()
        compose.runOnUiThread {
            assertEquals(touchRecipient, magic.session?.recipient?.address)
            assertEquals("message body", magic.session?.input?.text)
            magic.close()
        }

        val enterRecipient = "NoSuchMagicContact_${System.nanoTime()}"
        openMessageComposer()
        compose.onNodeWithTag("magic_input").performTextInput("$enterRecipient message body")
        waitForRawRecipient()
        compose.onNodeWithTag("magic_input").performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        compose.runOnUiThread {
            assertEquals(enterRecipient, magic.session?.recipient?.address)
            assertEquals("message body", magic.session?.input?.text)
        }
    }

    @Test fun tabAndShiftTabTraverseComposerAndArrowsSelectWithoutDispatching() {
        val recipient = "NoSuchMagicContact_${System.nanoTime()}"
        openMessageComposer()
        compose.onNodeWithTag("magic_input").performTextInput("$recipient message body")
        waitForRawRecipient()
        // Tab must also leave the editor after touch switches SystemDefined buttons out of focus traversal.
        compose.onNodeWithTag("magic_input").performTouchInput { click() }
        compose.onNodeWithTag("magic_input").assertIsFocused()
        compose.onNodeWithTag("magic_submit").assertIsEnabled()
        compose.onNodeWithTag("magic_input").performKeyInput { pressKey(Key.Tab) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("magic_submit") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnUiThread { assertEquals("$recipient message body", magic.session?.input?.text) }
        compose.onNodeWithTag("magic_submit").assertIsFocused()
        compose.onNodeWithTag("magic_submit").performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("magic_input") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnUiThread { assertEquals("$recipient message body", magic.session?.input?.text) }
        compose.onNodeWithTag("magic_input").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionUp)
            pressKey(Key.Enter)
        }
        compose.runOnUiThread {
            assertEquals(recipient, magic.session?.recipient?.address)
            assertEquals("message body", magic.session?.input?.text)
            assertFalse(magic.session!!.consumed)
        }
    }

    @Test fun escapeBackAndHomeClearDraftsAndDeferredSubmitState() {
        openMessageComposer()
        compose.onNodeWithTag("magic_input").performTextInput("discard on escape")
        compose.runOnUiThread {
            magic.submitPending = true
            assertTrue(compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)))
            assertNull(magic.session)
            assertFalse(magic.submitPending)
            assertNull(magic.onSubmit)
        }
        compose.onNodeWithTag("magic_window").assertDoesNotExist()

        openMessageComposer()
        compose.onNodeWithTag("magic_input").performTextInput("discard on back")
        compose.runOnUiThread {
            magic.submitPending = true
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitUntil(10_000) { magic.session == null }
        compose.onNodeWithTag("magic_window").assertDoesNotExist()

        openMessageComposer()
        val dismissed = checkNotNull(magic.session)
        compose.onNodeWithTag("magic_input").performTextInput("discard on HOME")
        compose.runOnUiThread {
            magic.submitPending = true
            viewModel.requestReturnHome()
        }
        compose.waitUntil(10_000) { magic.session == null }
        compose.runOnUiThread {
            assertNull(magic.onSubmit)
            assertFalse(magic.submitPending)
        }

        openMessageComposer()
        compose.runOnUiThread {
            assertTrue(magic.session!!.id > dismissed.id)
            assertEquals("", magic.session?.input?.text)
        }
    }

    @Test fun returningFromACompletedHandoffRetainsTheDraftGeneration() {
        openMessageComposer()
        compose.onNodeWithTag("magic_input").performTextInput("provider-owned draft")
        val handedOff = checkNotNull(magic.session)
        compose.runOnUiThread {
            handedOff.consumed = true
            handedOff.handedOff = true
        }

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { magic.session === handedOff && !handedOff.consumed && !handedOff.handedOff }

        compose.onNodeWithTag("magic_input").assertTextContains("provider-owned draft")
    }

    @Test fun automaticSmsIsOffForNewPreferenceStorage() {
        val isolatedName = "magic_commands_magic_regression"
        val isolatedPreferences = compose.activity.getSharedPreferences(isolatedName, Context.MODE_PRIVATE)
        isolatedPreferences.edit().clear().commit()
        val isolatedContext = object : ContextWrapper(compose.activity) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = isolatedPreferences
        }

        try {
            MagicPreferences(isolatedContext).use { assertFalse(it.automatic) }
        } finally {
            isolatedPreferences.edit().clear().commit()
        }
    }

    private fun openMessageComposer() {
        compose.waitUntil(10_000) { magic.eligible && !assistant.chat.visible }
        compose.runOnUiThread {
            val opener = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2, 0, KeyEvent.META_SHIFT_ON)
            assertEquals('@'.code, opener.unicodeChar)
            assertTrue(compose.activity.dispatchKeyEvent(opener))
            assertTrue(compose.activity.dispatchKeyEvent(KeyEvent.changeAction(opener, KeyEvent.ACTION_UP)))
            assertEquals(MagicKind.Message, magic.session?.kind)
        }
        compose.onNodeWithTag("magic_input").assertIsDisplayed().assertIsFocused()
    }

    private fun waitForRawRecipient() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("magic_result:0").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
