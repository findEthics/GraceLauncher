package com.galaxyrio.gracelauncher.magic

import androidx.compose.ui.text.input.TextFieldValue
import com.galaxyrio.gracelauncher.platform.SelectedNote
import com.katoaapps.openminilaunch.features.messaging.MessagingSendRoute
import com.katoaapps.openminilaunch.features.messaging.resolvedMessageSendRoute
import com.katoaapps.openminilaunch.model.userEnteredRecipient
import com.katoaapps.openminilaunch.model.CommunicationRecipient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagicStateTest {
    @Test fun callEnterSubmitsBeforeFocusedEditorAndOwnsRepeatAndRelease() {
        for (key in listOf(android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            val controller = MagicController()
            controller.open(MagicKind.Call)
            controller.focused()
            var calls = 0
            controller.onSubmit = { calls++; controller.close() }
            assertTrue(controller.hardwareKey(key, android.view.KeyEvent.ACTION_DOWN, 0, 0, 0, true))
            assertEquals(1, calls)
            assertTrue(controller.hardwareKey(key, android.view.KeyEvent.ACTION_DOWN, 1, 0, 0, true))
            assertTrue(controller.hardwareKey(key, android.view.KeyEvent.ACTION_UP, 0, 0, 0, true))
            assertEquals(1, calls)
        }
    }

    @Test fun displayPrefixesKeepTriggerCharactersSeparate() {
        assertEquals(listOf("@", "📞", "📅", "✅", "📝", "⏰"), MagicKind.entries.map { it.displayPrefix })
        assertEquals(listOf('@', '#', '+', '-', ',', '!'), MagicKind.entries.map { it.prefix })
    }

    @Test fun singleNewlineInsertionAcceptsOnlyOneInsertedNewline() {
        for ((previous, updated) in listOf("" to "\n", "text" to "text\n", "text" to "te\nxt", "text" to "\ntext", "a\nb" to "a\n\nb")) {
            assertTrue(isSingleNewlineInsertion(previous, updated))
        }
        for ((previous, updated) in listOf("text" to "text", "text" to "textx", "text" to "text\n\n", "text" to "te\nxy", "text" to "te\nt", "text" to "tex")) {
            assertFalse(isSingleNewlineInsertion(previous, updated))
        }
    }

    @Test fun singleTabInsertionAcceptsOnlyOneInsertedTab() {
        for ((previous, updated) in listOf("" to "\t", "text" to "text\t", "text" to "te\txt", "text" to "\ttext", "a\tb" to "a\t\tb")) {
            assertTrue(isSingleTabInsertion(previous, updated))
        }
        for ((previous, updated) in listOf("text" to "text", "text" to "textx", "text" to "text\n", "text" to "text\t\t", "text" to "te\txy", "text" to "te\tt", "text" to "tex")) {
            assertFalse(isSingleTabInsertion(previous, updated))
        }
    }

    @Test fun savedPillSurvivesCloseAndOnlyItsCurrentRevisionCanClearIt() {
        val controller = MagicController()
        controller.open(MagicKind.Todo)
        controller.showSavedPill("✅ To-do added")
        val firstRevision = controller.pillRevision
        controller.close()
        assertNull(controller.session)
        assertEquals("✅ To-do added", controller.pillMessage)
        controller.open(MagicKind.Note)
        controller.showSavedPill("📝 Note added")
        assertEquals(firstRevision + 1, controller.pillRevision)
        controller.clearSavedPill(firstRevision)
        assertEquals("📝 Note added", controller.pillMessage)
        val revision = controller.pillRevision
        controller.showSavedPill("📝 Note added")
        assertEquals(revision + 1, controller.pillRevision)
        controller.clearSavedPill(revision)
        assertEquals("📝 Note added", controller.pillMessage)
        controller.clearSavedPill(controller.pillRevision)
        assertNull(controller.pillMessage)
    }

    @Test fun emptyContactSelectionRetainsRecipientAndCannotRequestDirectSms() {
        val session = MagicSession(1, MagicKind.Message)
        session.input = TextFieldValue("Alex")
        val recipient = CommunicationRecipient("+15551234567", "Alex", "+15551234567")
        session.choose(recipient, "com.whatsapp")
        assertEquals(recipient, session.recipient)
        assertEquals("com.whatsapp", session.providerPackage)
        assertEquals("", session.input.text)
        assertNull(session.requestSms())
    }

    @Test fun preFocusTabBuffersDirectionsIgnoresRepeatsAndOwnsRelease() {
        for (kind in listOf(MagicKind.Note, MagicKind.Message, MagicKind.Call)) {
            val controller = MagicController()
            controller.open(kind)
            fun tab(repeat: Int = 0, meta: Int = 0) = controller.hardwareKey(android.view.KeyEvent.KEYCODE_TAB,
                android.view.KeyEvent.ACTION_DOWN, repeat, meta, '\t'.code, false)
            assertTrue(tab())
            assertEquals(1, controller.pendingCycle)
            assertTrue(tab(repeat = 1))
            assertEquals(1, controller.pendingCycle)
            assertTrue(controller.hardwareKey(android.view.KeyEvent.KEYCODE_TAB, android.view.KeyEvent.ACTION_UP, 0, 0, 0, true))
            assertTrue(tab(meta = android.view.KeyEvent.META_SHIFT_ON))
            assertEquals(0, controller.pendingCycle)
            assertTrue(tab(meta = android.view.KeyEvent.META_SHIFT_ON))
            assertEquals(-1, controller.pendingCycle)
            controller.open(kind)
            assertEquals(0, controller.pendingCycle)
            val directions = mutableListOf<Boolean>()
            controller.onCycle = { directions.add(it) }
            assertTrue(tab())
            assertTrue(tab(meta = android.view.KeyEvent.META_SHIFT_ON))
            assertEquals(listOf(false, true), directions)
            controller.focused()
            assertFalse(tab())
            controller.pendingCycle = 2
            controller.close()
            assertNull(controller.onCycle)
            assertEquals(0, controller.pendingCycle)
        }
    }

    @Test fun titanAltIProducedMinusOpensTodoAndOwnsRepeatAndRelease() {
        val controller = MagicController().apply { eligible = true }
        assertTrue(controller.hardwareKey(android.view.KeyEvent.KEYCODE_I, 0, 0, android.view.KeyEvent.META_ALT_ON, '-'.code, false))
        assertEquals(MagicKind.Todo, controller.session!!.kind)
        assertTrue(controller.hardwareKey(android.view.KeyEvent.KEYCODE_I, 0, 1, android.view.KeyEvent.META_ALT_ON, '-'.code, true))
        assertEquals("", controller.session!!.input.text)
        assertTrue(controller.hardwareKey(android.view.KeyEvent.KEYCODE_I, 1, 0, android.view.KeyEvent.META_ALT_ON, '-'.code, true))
    }

    @Test fun reopeningBuffersTabAndCallEnterButLeavesTextToFocusedEditor() {
        for (kind in listOf(MagicKind.Note, MagicKind.Message, MagicKind.Call)) {
            val controller = MagicController()
            controller.open(kind)
            controller.focused()
            controller.onSubmit = { error("Old submit callback") }
            controller.onCycle = { error("Old cycle callback") }
            controller.pendingCycle = 2
            controller.submitPending = true
            controller.open(kind)
            assertNull(controller.onSubmit)
            assertNull(controller.onCycle)
            assertEquals(0, controller.pendingCycle)
            assertFalse(controller.submitPending)
            assertTrue(controller.hardwareKey(android.view.KeyEvent.KEYCODE_TAB, 0, 0,
                android.view.KeyEvent.META_SHIFT_ON, '\t'.code, true))
            assertEquals(-1, controller.pendingCycle)
            assertEquals(kind == MagicKind.Call,
                controller.hardwareKey(android.view.KeyEvent.KEYCODE_ENTER, 0, 0, 0, '\n'.code, true))
            assertEquals(kind == MagicKind.Call, controller.submitPending)
            assertFalse(controller.hardwareKey(android.view.KeyEvent.KEYCODE_W, 0, 0, 0, 'w'.code, true))
            assertEquals("", controller.session!!.input.text)
            controller.focused()
            assertFalse(controller.hardwareKey(android.view.KeyEvent.KEYCODE_TAB, 0, 0, 0, '\t'.code, true))
            assertFalse(controller.hardwareKey(android.view.KeyEvent.KEYCODE_W, 0, 0, 0, 'w'.code, true))
        }
    }

    @Test fun literalPrefixesAreNotBufferedBeforeComposerFocus() {
        val controller = MagicController()
        controller.open(MagicKind.Todo)
        for (editorFocused in listOf(false, true)) {
            for ((key, meta, character) in listOf(
                Triple(android.view.KeyEvent.KEYCODE_MINUS, 0, '-'),
                Triple(android.view.KeyEvent.KEYCODE_COMMA, 0, ','),
            )) {
                assertFalse(controller.hardwareKey(key, 0, 0, meta, character.code, editorFocused))
            }
        }
        assertEquals("", controller.session!!.input.text)
        assertTrue(controller.hardwareKey(android.view.KeyEvent.KEYCODE_W, 0, 0, 0, 'w'.code, false))
        assertEquals("w", controller.session!!.input.text)
        assertTrue(controller.hardwareKey(android.view.KeyEvent.KEYCODE_ENTER, 0, 0, 0, '\n'.code, false))
        assertTrue(controller.submitPending)
    }

    @Test fun bangIsAnIdleHomeOnlyNonContactMagicCommand() {
        val controller = MagicController().apply { eligible = true }
        val key = android.view.KeyEvent.KEYCODE_1
        assertFalse(controller.hardwareKey(key, 0, 0, 0, '!'.code, true))
        controller.eligible = false
        assertFalse(controller.hardwareKey(key, 0, 0, 0, '!'.code, false))
        controller.eligible = true
        assertFalse(controller.hardwareKey(key, 0, 0, android.view.KeyEvent.META_CTRL_ON, '!'.code, false))
        assertTrue(controller.hardwareKey(key, 0, 0, android.view.KeyEvent.META_SHIFT_ON, '!'.code, false))
        assertEquals(MagicKind.Alarm, controller.session!!.kind)
        assertFalse(controller.session!!.kind.usesContacts)
    }

    @Test fun noteSelectionStartsUnhighlightedAndTypingAlwaysClearsHighlight() {
        val session = MagicSession(1, MagicKind.Note)
        assertEquals(-1, session.selectedIndex)
        assertNull(session.selectedNote)
        session.selectedIndex = 2
        session.input = TextFieldValue("new note")
        assertEquals(-1, session.selectedIndex)
        session.selectedIndex = 1
        session.input = session.input.copy(selection = androidx.compose.ui.text.TextRange(0))
        assertEquals(1, session.selectedIndex)
        session.append("changed ")
        assertEquals(-1, session.selectedIndex)
    }

    @Test fun choosingNoteClearsFilterLocksTargetAndSupportsClearing() {
        val session = MagicSession(1, MagicKind.Note)
        val target = SelectedNote(42, "Existing note")
        session.input = TextFieldValue("filter")
        session.selectedIndex = 0
        session.chooseNote(target)
        assertEquals(target, session.selectedNote)
        assertEquals("", session.input.text)
        assertEquals(-1, session.selectedIndex)
        session.append("append this\nsecond line")
        assertEquals(target, session.selectedNote)
        assertEquals("append this\nsecond line", session.input.text)
        assertTrue(session.claim())
        assertFalse(session.claim())
        session.chooseNote(null)
        assertEquals(target, session.selectedNote)
        session.consumed = false
        session.chooseNote(null)
        assertNull(session.selectedNote)
        assertEquals("", session.input.text)
    }

    @Test fun captureSessionsIgnoreContactScopeChanges() {
        for (kind in listOf(MagicKind.Todo, MagicKind.Note)) {
            val session = MagicSession(1, kind)
            session.input = TextFieldValue("keep draft")
            session.updateContactScope(MagicContactScope(null, false, true))
            assertFalse(session.updateContactScope(MagicContactScope("provider", true, false)))
            assertEquals("keep draft", session.input.text)
            assertFalse(kind.usesContacts)
        }
    }

    @Test fun parenthesisDoesNotOpenMagicAndIsBufferedAsOrdinaryText() {
        val controller = MagicController().apply { eligible = true }
        assertFalse(controller.hardwareKey(android.view.KeyEvent.KEYCODE_9, 0, 0, android.view.KeyEvent.META_SHIFT_ON, '('.code, false))
        assertNull(controller.session)
        controller.open(MagicKind.Todo)
        assertTrue(controller.hardwareKey(android.view.KeyEvent.KEYCODE_9, 0, 0, android.view.KeyEvent.META_SHIFT_ON, '('.code, false))
        assertEquals("(", controller.session!!.input.text)
    }

    private val recipient = CommunicationRecipient("+15551234567", "Alex", "+15551234567")

    private fun message(inferred: Boolean) = MagicSession(1, MagicKind.Message).apply {
        choose(this@MagicStateTest.recipient, inferred = inferred)
        input = TextFieldValue("Hello")
    }

    @Test fun inferredRecipientNeedsOneConfirmationBeforeOneClaim() {
        val session = message(inferred = true)
        val request = checkNotNull(session.requestSms())
        assertEquals(recipient, request.draft.recipient)
        assertEquals("Hello", request.draft.body)
        assertFalse(session.claimSms(request))
        assertNull(session.requestSms())
        assertTrue(session.confirmSms(request))
        assertFalse(session.confirmSms(request))
        assertTrue(session.claimSms(request))
        assertFalse(session.claimSms(request))
    }

    @Test fun explicitRecipientKeepsAutomaticSmsRouteWithoutConfirmation() {
        val session = message(inferred = false)
        assertEquals(MessagingSendRoute.DIRECT_SMS, resolvedMessageSendRoute(
            recipient = recipient, selectedConversationPackage = null, defaultSmsPackage = "sms.app",
            sendAutomatically = true, preferredPackage = null, fallbackRoute = MessagingSendRoute.DIRECT_SMS))
        assertTrue(session.claimSms(checkNotNull(session.requestSms())))
    }

    @Test fun typedPhoneKeepsAutomaticSmsRouteWithoutConfirmation() {
        val session = message(inferred = false)
        val typed = userEnteredRecipient("+15551234567")
        session.choose(typed)
        session.input = TextFieldValue("Hello")
        assertEquals(MessagingSendRoute.DIRECT_SMS, resolvedMessageSendRoute(
            recipient = typed, selectedConversationPackage = null, defaultSmsPackage = "sms.app",
            sendAutomatically = true, preferredPackage = null, fallbackRoute = MessagingSendRoute.DIRECT_SMS))
        assertTrue(session.claimSms(checkNotNull(session.requestSms())))
    }

    @Test fun editedOrDismissedRequestsCannotResumeEvenIfTextIsRestored() {
        val session = message(inferred = true)
        val edited = checkNotNull(session.requestSms())
        assertTrue(session.confirmSms(edited))
        session.input = TextFieldValue("Changed")
        session.input = TextFieldValue("Hello")
        assertFalse(session.matchesSms(edited))
        assertFalse(session.claimSms(edited))
        val dismissed = checkNotNull(session.requestSms())
        session.cancelSms()
        assertFalse(session.confirmSms(dismissed))
        assertFalse(session.claimSms(dismissed))
    }

    @Test fun explicitSelectionResetsInferenceAndInvalidatesOldRequest() {
        val session = message(inferred = true)
        val old = checkNotNull(session.requestSms())
        session.choose(recipient)
        session.input = TextFieldValue("Hello")
        assertFalse(session.recipientInferred)
        assertFalse(session.confirmSms(old))
        assertTrue(session.claimSms(checkNotNull(session.requestSms())))
    }

    @Test fun closingGenerationInvalidatesPendingPermissionOrRoleRequest() {
        val controller = MagicController()
        controller.open(MagicKind.Message)
        val session = checkNotNull(controller.session)
        session.choose(recipient, inferred = true)
        session.input = TextFieldValue("Hello")
        val request = checkNotNull(session.requestSms())
        assertTrue(session.confirmSms(request))
        controller.close()
        controller.open(MagicKind.Message)
        assertFalse(session.matchesSms(request))
        assertFalse(session.claimSms(request))
    }

    @Test fun eachGenerationHasItsOwnSingleClaim() {
        val controller = MagicController()
        controller.open(MagicKind.Message)
        val dismissed = checkNotNull(controller.session)

        assertTrue(dismissed.claim())
        assertFalse(dismissed.claim())
        controller.close()
        assertNull(controller.session)

        controller.open(MagicKind.Call)
        val current = checkNotNull(controller.session)
        assertNotSame(dismissed, current)
        assertTrue(current.id > dismissed.id)
        assertFalse(current.consumed)
        assertTrue(current.claim())
        assertFalse(current.claim())
    }

    @Test fun closeClearsDeferredSubmitAndCallbackBeforeTheNextGeneration() {
        val controller = MagicController()
        controller.open(MagicKind.Message)
        var callbackCount = 0
        controller.onSubmit = { callbackCount++ }
        controller.submitPending = true

        controller.close()

        assertNull(controller.session)
        assertNull(controller.onSubmit)
        assertFalse(controller.submitPending)
        assertEquals(0, callbackCount)

        controller.open(MagicKind.Event)
        assertFalse(controller.submitPending)
        assertFalse(checkNotNull(controller.session).consumed)
        assertEquals(0, callbackCount)
    }

    @Test fun consumedOpeningAndRapidTypingReleasesSurviveFocusAndClose() {
        val controller = MagicController()
        controller.open(MagicKind.Message)
        controller.keys.openingKey = 9
        controller.keys.consume(9)
        controller.keys.consume(36)

        controller.focused()
        controller.eligible = false
        controller.close()

        assertTrue(controller.keys.openingRepeat(9, 1))
        assertTrue(controller.keys.release(36))
        assertFalse(controller.keys.release(36))
        assertTrue(controller.keys.release(9))
        assertFalse(controller.keys.openingRepeat(9, 2))
        assertFalse(controller.keys.release(9))
    }

    @Test fun freshPressRecoversAfterAnotherWindowReceivedTheRelease() {
        val keys = MagicKeySequence()
        keys.openingKey = 9
        keys.consume(9)

        assertFalse(keys.openingRepeat(9, 0))
        assertFalse(keys.openingRepeat(9, 1))
        assertFalse(keys.release(9))
        keys.consume(9)
        assertTrue(keys.release(9))
        assertFalse(keys.release(99))
    }
}
