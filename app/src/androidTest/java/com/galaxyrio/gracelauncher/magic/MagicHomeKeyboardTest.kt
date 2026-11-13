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
            magic.clearSavedPill(magic.pillRevision)
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
            magic.clearSavedPill(magic.pillRevision)
            magic.fakeCall = null
            magic.fakeCallPermission = false
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

    @Test fun alarmAndTimerPreviewAndInvalidInputStayLocalWithoutAssistant() {
        compose.runOnUiThread { magic.open(MagicKind.Alarm) }
        compose.onNodeWithTag("magic_input").performTextInput("alarm 7:30am")
        compose.onNodeWithTag("magic_alarm_preview").assertTextEquals("Alarm · 07:30")
        compose.onNodeWithTag("magic_input").performTextReplacement("timer 10m")
        compose.onNodeWithTag("magic_alarm_preview").assertTextEquals("Timer · 10m 00s")
        compose.onNodeWithTag("magic_input").performTextReplacement("not a command")
        compose.onNodeWithTag("magic_alarm_preview").assertDoesNotExist()
        compose.onNodeWithTag("magic_submit").performClick()
        compose.onNodeWithTag("magic_status").assertTextEquals(compose.activity.getString(com.galaxyrio.gracelauncher.R.string.magic_alarm_status_invalid))
        compose.runOnUiThread { assertFalse(assistant.chat.visible); assertFalse(magic.session!!.consumed) }
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
        compose.onNodeWithTag("magic_result:0").assertTextContains("Recent fixture")
        compose.onNodeWithTag("magic_result:1").assertTextContains("Synced fixture").performClick()
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

    @Test fun preferredConversationPrecedesOtherMatchesAndContactNumbersForPrefixes() {
        fun conversation(pkg: String, id: String, label: String) = MagicConversation(
            com.katoaapps.openminilaunch.model.LauncherShortcutTarget(label, pkg, id, 0, false),
            com.galaxyrio.gracelauncher.data.LauncherShortcut(id, pkg, label, null), emptySet())
        synthetic.conversationSnapshot = listOf(conversation("other.app", "other", "Alex other"),
            conversation("preferred.app", "preferred", "Alex preferred"),
            conversation("preferred.app", "unmatched", "Zebra"))
        synthetic.contactSnapshots = mapOf(null to listOf(com.galaxyrio.gracelauncher.data.SearchContact(
            1, android.net.Uri.parse("content://synthetic/1"), "Alex contact", null, listOf("15550001", "15550002"))))
        compose.runOnUiThread {
            messagingPreferences.edit().putString("provider", "preferred.app").commit()
            magic.open(MagicKind.Message)
        }
        for (prefix in listOf("", "A", "Al", "Alex")) {
            compose.onNodeWithTag("magic_input").performTextClearance()
            if (prefix.isNotEmpty()) compose.onNodeWithTag("magic_input").performTextInput(prefix)
            compose.onNodeWithTag("magic_results").performScrollToIndex(0)
            compose.onNodeWithTag("magic_result:0").assertTextContains("Alex preferred")
            val zebraMatches = "Zebra".contains(prefix, ignoreCase = true)
            val otherIndex = if (zebraMatches) 2 else 1
            if (zebraMatches) {
                compose.onNodeWithTag("magic_results").performScrollToIndex(1)
                compose.onNodeWithTag("magic_result:1").assertTextContains("Zebra")
            }
            for ((index, text) in listOf(otherIndex to "Alex other", otherIndex + 1 to "15550001", otherIndex + 2 to "15550002")) {
                compose.onNodeWithTag("magic_results").performScrollToIndex(index)
                compose.onNodeWithTag("magic_result:$index").assertTextContains(text)
            }
            if (!zebraMatches) compose.onNodeWithText("Zebra").assertDoesNotExist()
        }
    }

    @Test fun selectingEnterReleaseDoesNotActivateFocusedCall() {
        synthetic.contactSnapshots = mapOf(null to listOf(com.galaxyrio.gracelauncher.data.SearchContact(
            1, android.net.Uri.parse("content://synthetic/1"), "Call fixture", null, listOf("15550001"))))
        compose.runOnUiThread { magic.open(MagicKind.Call) }
        compose.onNodeWithTag("magic_result:0").assertExists()
        compose.onNodeWithTag("magic_input").performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        compose.onNodeWithTag("magic_confirm_call").assertIsFocused()
        compose.onNodeWithTag("magic_confirm_call").assertExists()
        compose.runOnUiThread { assertFalse(magic.session!!.consumed) }
        compose.onNodeWithTag("magic_confirm_call").performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("magic_confirm_call").assertDoesNotExist()
        compose.runOnUiThread { assertFalse(magic.session!!.consumed); assertFalse(synthetic.externalActionsAllowed) }
    }

    @Test fun heldSelectionEnterCannotCallButFreshEnterCallsOnce() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        synthetic.contactSnapshots = mapOf(null to listOf(com.galaxyrio.gracelauncher.data.SearchContact(
            1, android.net.Uri.parse("content://synthetic/1"), "Call fixture", null, listOf("15550001"))))
        compose.runOnUiThread {
            magic.fakeCall = { number, chooser -> calls.add(number to chooser); true }
            magic.fakeCallPermission = true
            magic.open(MagicKind.Call)
        }
        compose.onNodeWithTag("magic_result:0").assertExists()
        compose.onNodeWithTag("magic_input").assertIsFocused()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val time = android.os.SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
        compose.onNodeWithTag("magic_confirm_call").assertIsFocused()
        instrumentation.sendKeySync(KeyEvent(time, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 1))
        compose.runOnUiThread { assertTrue("repeat activated fake call: $calls", calls.isEmpty()) }
        instrumentation.sendKeySync(KeyEvent(time, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
        compose.waitForIdle()
        compose.runOnUiThread { assertTrue("release activated fake call: $calls", calls.isEmpty()); assertFalse(magic.session!!.consumed) }
        compose.onNodeWithTag("magic_confirm_call").assertExists().performKeyInput { pressKey(Key.Enter) }
        compose.runOnUiThread { assertEquals(listOf("15550001" to false), calls); assertNull(magic.session) }
    }

    @Test fun callFocusLossDisarmsStaleEnterRelease() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        synthetic.contactSnapshots = mapOf(null to listOf(com.galaxyrio.gracelauncher.data.SearchContact(
            1, android.net.Uri.parse("content://synthetic/1"), "Call fixture", null, listOf("15550001"))))
        compose.runOnUiThread { magic.fakeCall = { number, chooser -> calls.add(number to chooser); true }; magic.fakeCallPermission = true; magic.open(MagicKind.Call) }
        compose.onNodeWithTag("magic_result:0").performClick()
        compose.onNodeWithTag("magic_confirm_call").assertIsFocused()
        val instrument = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val t = android.os.SystemClock.uptimeMillis()
        fun key(action: Int, code: Int, meta: Int = 0) = instrument.sendKeySync(KeyEvent(t, android.os.SystemClock.uptimeMillis(), action, code, 0, meta))
        fun tab(meta: Int = 0) { key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, meta); key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, meta) }
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
        tab(); tab()
        compose.onNodeWithText(compose.activity.getString(com.galaxyrio.gracelauncher.R.string.magic_cancel)).assertIsFocused()
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
        tab(KeyEvent.META_SHIFT_ON); tab(KeyEvent.META_SHIFT_ON)
        compose.onNodeWithTag("magic_confirm_call").assertIsFocused()
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
        compose.runOnUiThread { assertTrue("stale release called: $calls", calls.isEmpty()) }
        compose.onNodeWithTag("magic_confirm_call").performKeyInput { pressKey(Key.Enter) }
        compose.runOnUiThread { assertEquals(listOf("15550001" to false), calls) }
    }

    @Test fun canceledCallEnterDoesNotDispatch() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        synthetic.contactSnapshots = mapOf(null to listOf(com.galaxyrio.gracelauncher.data.SearchContact(
            1, android.net.Uri.parse("content://synthetic/1"), "Call fixture", null, listOf("15550001"))))
        compose.runOnUiThread { magic.fakeCall = { number, chooser -> calls.add(number to chooser); true }; magic.fakeCallPermission = true; magic.open(MagicKind.Call) }
        compose.onNodeWithTag("magic_result:0").performClick()
        compose.onNodeWithTag("magic_confirm_call").assertIsFocused()
        val instrument = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val t = android.os.SystemClock.uptimeMillis()
        instrument.sendKeySync(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
        instrument.sendKeySync(KeyEvent(t, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, 0, -1, 0, KeyEvent.FLAG_CANCELED))
        compose.runOnUiThread { assertTrue("canceled release called: $calls", calls.isEmpty()) }
        compose.onNodeWithTag("magic_confirm_call").performKeyInput { pressKey(Key.Enter) }
        compose.runOnUiThread { assertEquals(listOf("15550001" to false), calls) }
    }

    @Test fun dialogWindowFocusLossCannotCompleteHeldCall() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        synthetic.contactSnapshots = mapOf(null to listOf(com.galaxyrio.gracelauncher.data.SearchContact(
            1, android.net.Uri.parse("content://synthetic/1"), "Call fixture", null, listOf("15550001"))))
        compose.runOnUiThread { magic.fakeCall = { number, chooser -> calls.add(number to chooser); true }; magic.fakeCallPermission = true; magic.open(MagicKind.Call) }
        compose.onNodeWithTag("magic_result:0").performClick()
        compose.onNodeWithTag("magic_confirm_call").assertIsFocused()
        val instrument = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        var dialogRoot: android.view.View? = null
        compose.runOnUiThread {
            dialogRoot = android.view.inspector.WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }
        }
        val t = android.os.SystemClock.uptimeMillis()
        instrument.sendKeySync(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
        androidx.test.core.app.ActivityScenario.launch(com.galaxyrio.gracelauncher.SettingsActivity::class.java).use {
            instrument.waitForIdleSync()
            compose.runOnUiThread { assertFalse("dialog must actually lose window focus", dialogRoot!!.hasWindowFocus()) }
        }
        compose.onNodeWithTag("magic_confirm_call").assertIsFocused()
        instrument.sendKeySync(KeyEvent(t, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
        compose.runOnUiThread { assertTrue("window-stale release called: $calls", calls.isEmpty()) }
        compose.onNodeWithTag("magic_confirm_call").performKeyInput { pressKey(Key.Enter) }
        compose.runOnUiThread { assertEquals(listOf("15550001" to false), calls) }
    }

    @Test fun lostSelectionReleaseDoesNotBlockTouchReopenedCall() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        synthetic.contactSnapshots = mapOf(null to listOf(com.galaxyrio.gracelauncher.data.SearchContact(
            1, android.net.Uri.parse("content://synthetic/1"), "Call fixture", null, listOf("15550001"))))
        compose.runOnUiThread { magic.fakeCall = { number, chooser -> calls.add(number to chooser); true }; magic.fakeCallPermission = true; magic.open(MagicKind.Call) }
        compose.onNodeWithTag("magic_result:0").assertExists()
        compose.onNodeWithTag("magic_input").assertIsFocused()
        val instrument = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val t = android.os.SystemClock.uptimeMillis()
        instrument.sendKeySync(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
        compose.onNodeWithTag("magic_confirm_call").assertIsFocused()
        compose.onNodeWithText(compose.activity.getString(com.galaxyrio.gracelauncher.R.string.magic_cancel)).performClick()
        // The opening release is lost outside both the editor and confirmation window.
        compose.onNodeWithTag("magic_result:0").performClick()
        compose.onNodeWithTag("magic_confirm_call").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnUiThread { assertEquals(listOf("15550001" to false), calls) }
    }

    @Test fun heldSelectionAfterDismissAndTouchReopenCannotCall() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        synthetic.contactSnapshots = mapOf(null to listOf(com.galaxyrio.gracelauncher.data.SearchContact(
            1, android.net.Uri.parse("content://synthetic/1"), "Call fixture", null, listOf("15550001"))))
        compose.runOnUiThread { magic.fakeCall = { number, chooser -> calls.add(number to chooser); true }; magic.fakeCallPermission = true; magic.open(MagicKind.Call) }
        compose.onNodeWithTag("magic_result:0").assertExists()
        compose.onNodeWithTag("magic_input").assertIsFocused()
        val instrument = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val t = android.os.SystemClock.uptimeMillis()
        instrument.sendKeySync(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
        compose.onNodeWithTag("magic_confirm_call").assertIsFocused()
        compose.onNodeWithText(compose.activity.getString(com.galaxyrio.gracelauncher.R.string.magic_cancel)).performClick()
        compose.onNodeWithTag("magic_result:0").performClick()
        compose.onNodeWithTag("magic_confirm_call").assertIsFocused()
        instrument.sendKeySync(KeyEvent(t, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 1))
        instrument.sendKeySync(KeyEvent(t, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
        compose.runOnUiThread { assertTrue("held selection called: $calls", calls.isEmpty()) }
        compose.onNodeWithTag("magic_confirm_call").performKeyInput { pressKey(Key.Enter) }
        compose.runOnUiThread { assertEquals(listOf("15550001" to false), calls) }
    }

    @Test fun touchConfirmationCancelChooserAndInvalidSessionAreSafe() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        synthetic.contactSnapshots = mapOf(null to listOf(com.galaxyrio.gracelauncher.data.SearchContact(
            1, android.net.Uri.parse("content://synthetic/1"), "Call fixture", null, listOf("15550001"))))
        compose.runOnUiThread { magic.fakeCall = { number, chooser -> calls.add(number to chooser); true }; magic.fakeCallPermission = true; magic.open(MagicKind.Call) }
        compose.onNodeWithTag("magic_result:0").performClick()
        compose.onNodeWithText(compose.activity.getString(com.galaxyrio.gracelauncher.R.string.magic_cancel)).performClick()
        compose.runOnUiThread { assertTrue(calls.isEmpty()); assertFalse(magic.session!!.consumed) }
        compose.onNodeWithTag("magic_result:0").performClick()
        compose.onNodeWithText(compose.activity.getString(com.galaxyrio.gracelauncher.R.string.magic_call_app)).performClick()
        compose.runOnUiThread { assertEquals(listOf("15550001" to true), calls); magic.open(MagicKind.Call) }
        compose.onNodeWithTag("magic_result:0").performClick()
        compose.runOnUiThread { magic.fakeCallPermission = false }
        compose.onNodeWithTag("magic_confirm_call").performKeyInput { pressKey(Key.Enter) }
        compose.runOnUiThread { assertEquals(1, calls.size); assertFalse(magic.session!!.consumed); magic.fakeCallPermission = true }
        compose.onNodeWithTag("magic_result:0").performClick()
        compose.runOnUiThread { magic.open(MagicKind.Call) }
        compose.onNodeWithTag("magic_confirm_call").assertDoesNotExist()
        compose.runOnUiThread { assertEquals(1, calls.size) }
        compose.onNodeWithTag("magic_result:0").performClick()
        compose.onNodeWithTag("magic_confirm_call").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnUiThread { assertEquals(listOf("15550001" to true, "15550001" to false), calls); assertNull(magic.session) }
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

    @Test fun parenthesisMinusAndAltTOpenTodoWindowAndPrefixesRemainLiteralInput() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        for ((keyCode, modifiers) in listOf(
            KeyEvent.KEYCODE_9 to KeyEvent.META_SHIFT_ON,
            KeyEvent.KEYCODE_MINUS to 0,
            KeyEvent.KEYCODE_T to KeyEvent.META_ALT_ON,
        )) {
            compose.runOnUiThread {
                val opener = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, modifiers)
                assertTrue(compose.activity.dispatchKeyEvent(opener))
                assertTrue(compose.activity.dispatchKeyEvent(KeyEvent.changeAction(opener, KeyEvent.ACTION_UP)))
                assertEquals(MagicKind.Todo, magic.session?.kind)
                assertEquals("", magic.session?.input?.text)
            }
            compose.onNodeWithTag("magic_window").assertIsDisplayed()
            compose.onNodeWithTag("magic_input").assertIsFocused()
            val opened = checkNotNull(magic.session)
            for ((literalKey, literalModifiers) in listOf(
                KeyEvent.KEYCODE_MINUS to 0,
                KeyEvent.KEYCODE_COMMA to 0,
                KeyEvent.KEYCODE_9 to KeyEvent.META_SHIFT_ON,
            )) {
                val time = android.os.SystemClock.uptimeMillis()
                val literal = KeyEvent(time, time, KeyEvent.ACTION_DOWN, literalKey, 0, literalModifiers)
                instrumentation.sendKeySync(literal)
                instrumentation.sendKeySync(KeyEvent.changeAction(literal, KeyEvent.ACTION_UP))
            }
            compose.onNodeWithTag("magic_input").assertTextContains("-,(").assertIsFocused()
            compose.runOnUiThread {
                assertSame(opened, magic.session)
                assertEquals("-,(", opened.input.text)
                assertTrue(compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                assertFalse(compose.activity.isFinishing)
                assertFalse(assistant.chat.visible)
            }
            compose.onNodeWithTag("magic_input").performKeyInput { pressKey(Key.Escape) }
            compose.onNodeWithTag("magic_window").assertDoesNotExist()
            compose.waitUntil(10_000) { magic.session == null && magic.eligible }
        }
    }

    @Test fun commaAndAltNOpenUnselectedNoteWindowAndEscapeOrBackReturnsHome() {
        synthetic.noteSnapshot = listOf(com.galaxyrio.gracelauncher.platform.SelectedNote(1, "Packing list"))
        for ((keyCode, modifiers) in listOf(
            KeyEvent.KEYCODE_COMMA to 0,
            KeyEvent.KEYCODE_N to KeyEvent.META_ALT_ON,
        )) {
            for (escape in listOf(true, false)) {
                compose.runOnUiThread {
                    val opener = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, modifiers)
                    assertTrue(compose.activity.dispatchKeyEvent(opener))
                    assertTrue(compose.activity.dispatchKeyEvent(KeyEvent.changeAction(opener, KeyEvent.ACTION_UP)))
                    assertEquals(MagicKind.Note, magic.session?.kind)
                    assertEquals("", magic.session?.input?.text)
                }
                compose.onNodeWithTag("magic_window").assertIsDisplayed()
                compose.onNodeWithTag("magic_input").assertIsFocused()
                compose.onNodeWithText("Packing list").assertIsDisplayed()
                compose.onAllNodes(isSelected() and hasAnyAncestor(hasTestTag("magic_results"))).assertCountEquals(0)
                compose.runOnUiThread {
                    assertEquals(-1, magic.session?.selectedIndex)
                    assertNull(magic.session?.selectedNote)
                    assertTrue(compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                    assertFalse(assistant.chat.visible)
                }
                compose.onNodeWithTag("magic_input").performTextInput("discard this note draft")
                if (escape) compose.onNodeWithTag("magic_input").performKeyInput { pressKey(Key.Escape) }
                else compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.waitUntil(10_000) { magic.session == null && magic.eligible }
                compose.onNodeWithTag("magic_window").assertDoesNotExist()
                compose.runOnUiThread {
                    assertNull(magic.onSubmit)
                    assertFalse(magic.submitPending)
                    assertFalse(compose.activity.isFinishing)
                    assertTrue(compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                }
            }
        }
    }

    @Test fun noteOptionsLoadWithoutAQueryFilterWithoutSelectingAndKeepSaveInsideTheEditor() {
        synthetic.noteSnapshot = listOf(
            com.galaxyrio.gracelauncher.platform.SelectedNote(2, "Groceries"),
            com.galaxyrio.gracelauncher.platform.SelectedNote(1, "Packing list"),
        )
        compose.runOnUiThread { magic.open(MagicKind.Note) }
        compose.waitUntil(5_000) { synthetic.noteQueries.contains("") }
        compose.onNodeWithText("Packing list").assertIsDisplayed()
        compose.onAllNodes(isSelected() and hasAnyAncestor(hasTestTag("magic_results"))).assertCountEquals(0)
        compose.onNodeWithTag("magic_submit").assertIsDisplayed()
        compose.onNodeWithTag("magic_input").performTextInput("pAcKiNg")
        compose.waitUntil(5_000) { synthetic.noteQueries.contains("pAcKiNg") }
        compose.onNodeWithText("Packing list").assertIsDisplayed()
        compose.onNodeWithText("Groceries").assertDoesNotExist()
        compose.runOnUiThread { assertEquals(-1, magic.session!!.selectedIndex) }
        compose.onNodeWithTag("magic_input").performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.Enter) }
        compose.runOnUiThread { assertEquals(1L, magic.session!!.selectedNote?.id); assertEquals("", magic.session!!.input.text) }
        compose.onNodeWithText("Packing list").assertIsDisplayed()
        val input = compose.onNodeWithTag("magic_input").fetchSemanticsNode().boundsInRoot
         val save = compose.onNodeWithTag("magic_submit").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
         assertTrue("Save must fit inside the editor", save.left >= input.left && save.right <= input.right && save.top >= input.top && save.bottom <= input.bottom)
         val clear = compose.onNodeWithTag("magic_clear").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
         val tolerance = compose.activity.resources.displayMetrics.density * 2f
         assertEquals("Clear and Save centers: $clear / $save", save.center.x, clear.center.x, tolerance)
         assertEquals("Clear and Save right edges: $clear / $save", save.right, clear.right, tolerance)
        compose.onNodeWithText(compose.activity.getString(com.galaxyrio.gracelauncher.R.string.magic_clear)).performClick()
        compose.onNodeWithText("Packing list").assertIsDisplayed()
        compose.runOnUiThread { assertNull(magic.session!!.selectedNote); assertEquals(-1, magic.session!!.selectedIndex) }
    }

    @Test fun minusOpenedTodoSavesLiteralHyphenShowsAddedPillAndClosesWindow() {
        compose.runOnUiThread {
            val opener = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MINUS)
            assertTrue(compose.activity.dispatchKeyEvent(opener))
            assertTrue(compose.activity.dispatchKeyEvent(KeyEvent.changeAction(opener, KeyEvent.ACTION_UP)))
            assertEquals(MagicKind.Todo, magic.session?.kind)
            assertEquals("", magic.session?.input?.text)
        }
        compose.onNodeWithTag("magic_input").assertIsFocused().performTextInput("Check")
        compose.onNodeWithText("✅", useUnmergedTree = true).assertExists()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MINUS)
        compose.onNodeWithTag("magic_input").performTextInput("in")
        compose.onNodeWithTag("magic_input").assertTextContains("Check-in")
        compose.onNodeWithTag("magic_input").performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(5_000) { magic.session == null }
        compose.onNodeWithTag("magic_window").assertDoesNotExist()
        compose.onNodeWithTag("magic_saved_pill").assertIsDisplayed().assert(hasAnyDescendant(hasText("✅ To-do added")))
        compose.runOnUiThread {
            assertEquals(listOf(Triple("Check-in", true, null)), synthetic.saves)
            assertEquals(listOf("✅ To-do added"), synthetic.toasts)
        }
    }

    @Test fun submittingUnselectedNoteSavesNewNoteShowsAddedPillAndClosesWindow() {
        synthetic.noteSnapshot = listOf(com.galaxyrio.gracelauncher.platform.SelectedNote(1, "Packing list"))
        compose.runOnUiThread { magic.open(MagicKind.Note) }
        compose.onNodeWithTag("magic_input").performTextInput("Packing")
        compose.onNodeWithText("Packing list").assertIsDisplayed()
        compose.runOnUiThread { assertEquals(-1, magic.session!!.selectedIndex); assertNull(magic.session!!.selectedNote) }
        compose.onNodeWithTag("magic_submit").performClick()
        compose.waitUntil(5_000) { magic.session == null }
        compose.onNodeWithTag("magic_window").assertDoesNotExist()
        compose.onNodeWithTag("magic_saved_pill").assertIsDisplayed().assert(hasAnyDescendant(hasText("📝 Note added")))
        compose.runOnUiThread {
            assertEquals(listOf(Triple("Packing", false, null)), synthetic.saves)
            assertEquals(listOf("📝 Note added"), synthetic.toasts)
        }
    }

    @Test fun submittingSelectedNoteSavesToItsIdShowsUpdatedPillAndClosesWindow() {
        synthetic.noteSnapshot = listOf(com.galaxyrio.gracelauncher.platform.SelectedNote(7, "Packing list"))
        compose.runOnUiThread { magic.open(MagicKind.Note) }
        compose.onNodeWithTag("magic_result:0").performClick()
        compose.runOnUiThread { assertEquals(7L, magic.session!!.selectedNote?.id) }
        compose.onNodeWithTag("magic_input").performTextInput("Add sunscreen")
        compose.onNodeWithTag("magic_input").performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(5_000) { magic.session == null }
        compose.onNodeWithTag("magic_window").assertDoesNotExist()
        compose.onNodeWithTag("magic_saved_pill").assertIsDisplayed().assert(hasAnyDescendant(hasText("📝 Note updated")))
        compose.runOnUiThread {
            assertEquals(listOf(Triple("Add sunscreen", false, 7L)), synthetic.saves)
            assertEquals(listOf("📝 Note updated"), synthetic.toasts)
        }
    }

    @Test fun failedCaptureShowsErrorKeepsDraftOpenAndDoesNotToast() {
        synthetic.saveResult = false
        compose.runOnUiThread { magic.open(MagicKind.Todo) }
        compose.onNodeWithTag("magic_input").performTextInput("Keep this draft")
        compose.onNodeWithTag("magic_submit").performClick()
        val error = compose.activity.getString(com.galaxyrio.gracelauncher.R.string.magic_capture_failed)
        compose.waitUntil(5_000) { magic.session?.status == error }
        compose.onNodeWithTag("magic_window").assertIsDisplayed()
        compose.onNodeWithTag("magic_status").assertTextEquals(error)
        compose.onNodeWithTag("magic_input").assertTextContains("Keep this draft")
        compose.onNodeWithTag("magic_submit").assertIsEnabled()
        compose.onNodeWithTag("magic_saved_pill").assertDoesNotExist()
        compose.runOnUiThread {
            assertEquals(listOf(Triple("Keep this draft", true, null)), synthetic.saves)
            assertTrue(synthetic.toasts.isEmpty())
            assertFalse(magic.session!!.consumed)
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

    @Test fun tabAndShiftTabCycleResultsWithoutLeavingComposerAndEnterSelects() {
        synthetic.noteSnapshot = listOf(
            com.galaxyrio.gracelauncher.platform.SelectedNote(1, "First note"),
            com.galaxyrio.gracelauncher.platform.SelectedNote(2, "Second note"),
        )
        synthetic.contactSnapshots = mapOf(null to listOf(
            com.galaxyrio.gracelauncher.data.SearchContact(1, android.net.Uri.parse("content://synthetic/1"), "First contact", null, listOf("15550001")),
            com.galaxyrio.gracelauncher.data.SearchContact(2, android.net.Uri.parse("content://synthetic/2"), "Second contact", null, listOf("15550002")),
        ))
        fun assertHighlight(index: Int) {
            compose.onNodeWithTag("magic_input").assertIsFocused()
            compose.onNodeWithTag("magic_result:$index").assertIsNotFocused().assert(hasAnyAncestor(isSelected()))
            compose.onAllNodes(isSelected() and hasAnyAncestor(hasTestTag("magic_results"))).assertCountEquals(1)
            compose.runOnUiThread {
                if (magic.session!!.kind == MagicKind.Note) assertEquals(index, magic.session!!.selectedIndex)
                assertEquals("", magic.session!!.input.text)
            }
        }
        for (kind in listOf(MagicKind.Note, MagicKind.Message, MagicKind.Call)) {
            for (enter in listOf<Key?>(Key.Enter, Key.NumPadEnter, null)) {
                compose.runOnUiThread { magic.close(); magic.open(kind) }
                compose.onNodeWithTag("magic_result:1").assertIsDisplayed()
                // Touch mode must not change Tab behavior or transfer focus to a button.
                compose.onNodeWithTag("magic_input").performTouchInput { click() }
                if (kind == MagicKind.Note) {
                    compose.onAllNodes(isSelected() and hasAnyAncestor(hasTestTag("magic_results"))).assertCountEquals(0)
                    compose.onNodeWithTag("magic_input").performKeyInput { pressKey(Key.Tab) }
                }
                assertHighlight(0)
                compose.onNodeWithTag("magic_input").performKeyInput { pressKey(Key.Tab) }
                assertHighlight(1)
                compose.onNodeWithTag("magic_input").performKeyInput { pressKey(Key.Tab) }
                assertHighlight(0)
                compose.onNodeWithTag("magic_input").performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft) }
                assertHighlight(1)
                compose.onNodeWithTag("magic_input").performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft) }
                assertHighlight(0)
                compose.onNodeWithTag("magic_input").performKeyInput { pressKey(Key.DirectionDown) }
                assertHighlight(1)
                compose.onNodeWithTag("magic_input").performKeyInput { pressKey(Key.DirectionUp) }
                assertHighlight(0)
                compose.onNodeWithTag("magic_input").performKeyInput { pressKey(Key.Tab) }
                assertHighlight(1)
                // Call selection intentionally opens a focused confirmation dialog, not an editor.
                if (kind == MagicKind.Call) continue
                if (enter == null) {
                    // Emulate an IME text commit rather than a hardware Enter.
                    compose.onNodeWithTag("magic_input").performTextInput("\n")
                } else compose.onNodeWithTag("magic_input").performKeyInput { pressKey(enter) }
                compose.onNodeWithTag("magic_input").assertIsFocused()
                compose.runOnUiThread {
                    if (kind == MagicKind.Note) assertEquals(2L, magic.session!!.selectedNote?.id)
                    else assertEquals("15550002", magic.session!!.recipient?.address)
                    assertEquals("", magic.session!!.input.text)
                    assertFalse(magic.session!!.consumed)
                    assertTrue(synthetic.saves.isEmpty())
                }
                compose.onNodeWithTag("magic_input").performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Enter); keyUp(Key.ShiftLeft) }
                compose.runOnUiThread { assertEquals("\n", magic.session!!.input.text); assertFalse(magic.session!!.consumed) }
            }
        }
    }

    @Test fun imeTabCyclesNotesWithoutEditingAndFirstEnterOrNewlineSelects() {
        synthetic.noteSnapshot = listOf(
            com.galaxyrio.gracelauncher.platform.SelectedNote(1, "First note"),
            com.galaxyrio.gracelauncher.platform.SelectedNote(2, "Second note"),
        )
        for (imeEnter in listOf(false, true)) {
            compose.runOnUiThread { magic.close(); magic.open(MagicKind.Note) }
            compose.onNodeWithTag("magic_result:1").assertIsDisplayed()
            compose.onNodeWithTag("magic_input").performTextInput("\t")
            compose.onNodeWithTag("magic_result:0").assert(hasAnyAncestor(isSelected()))
            compose.onNodeWithTag("magic_input").performTextInput("\t")
            compose.onNodeWithTag("magic_result:1").assert(hasAnyAncestor(isSelected()))
            compose.runOnUiThread { assertEquals("", magic.session!!.input.text); assertEquals(1, magic.session!!.selectedIndex) }
            if (imeEnter) compose.onNodeWithTag("magic_input").performTextInput("\n")
            else compose.onNodeWithTag("magic_input").performKeyInput { pressKey(Key.Enter) }
            compose.onNodeWithTag("magic_input").assertIsFocused()
            compose.runOnUiThread {
                assertEquals(2L, magic.session!!.selectedNote?.id)
                assertEquals("", magic.session!!.input.text)
                assertFalse(magic.session!!.consumed)
                assertTrue(synthetic.saves.isEmpty())
            }
        }
    }

    @Test fun tabImmediatelyAfterOpeningIsAppliedWhenResultsLoad() {
        synthetic.noteSnapshot = listOf(
            com.galaxyrio.gracelauncher.platform.SelectedNote(1, "First note"),
            com.galaxyrio.gracelauncher.platform.SelectedNote(2, "Second note"),
        )
        synthetic.contactSnapshots = mapOf(null to listOf(
            com.galaxyrio.gracelauncher.data.SearchContact(1, android.net.Uri.parse("content://synthetic/1"), "First contact", null, listOf("15550001")),
            com.galaxyrio.gracelauncher.data.SearchContact(2, android.net.Uri.parse("content://synthetic/2"), "Second contact", null, listOf("15550002")),
        ))
        for (kind in listOf(MagicKind.Note, MagicKind.Message, MagicKind.Call)) {
            for (backward in listOf(false, true)) {
                compose.runOnUiThread {
                    magic.close()
                    magic.open(kind)
                    val tab = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, if (backward) KeyEvent.META_SHIFT_ON else 0)
                    assertTrue(compose.activity.dispatchKeyEvent(tab))
                    assertTrue(compose.activity.dispatchKeyEvent(KeyEvent.changeAction(tab, KeyEvent.ACTION_UP)))
                    assertEquals(if (backward) -1 else 1, magic.pendingCycle)
                }
                val expected = if (kind == MagicKind.Note && !backward) 0 else 1
                compose.onNodeWithTag("magic_result:$expected").assert(hasAnyAncestor(isSelected()))
                compose.onNodeWithTag("magic_input").assertIsFocused()
                compose.runOnUiThread { assertEquals(0, magic.pendingCycle); assertEquals("", magic.session!!.input.text) }
            }
        }
    }

    @Test fun noteArrowUpFromNoSelectionHighlightsLastResult() {
        synthetic.noteSnapshot = listOf(
            com.galaxyrio.gracelauncher.platform.SelectedNote(1, "First note"),
            com.galaxyrio.gracelauncher.platform.SelectedNote(2, "Second note"),
        )
        compose.runOnUiThread { magic.open(MagicKind.Note) }
        compose.onNodeWithTag("magic_result:1").assertIsDisplayed()
        compose.onNodeWithTag("magic_input").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("magic_input").assertIsFocused()
        compose.onNodeWithTag("magic_result:1").assert(hasAnyAncestor(isSelected()))
        compose.runOnUiThread { assertEquals(1, magic.session!!.selectedIndex); assertNull(magic.session!!.selectedNote) }
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
