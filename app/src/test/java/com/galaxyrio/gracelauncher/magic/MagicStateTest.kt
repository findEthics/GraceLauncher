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
    @Test fun displayPrefixesKeepTriggerCharactersSeparate() {
        assertEquals(listOf("@", "📞", "📅", "☑", "📝", "⏰"), MagicKind.entries.map { it.displayPrefix })
        assertEquals(listOf('@', '#', '+', '(', ',', '!'), MagicKind.entries.map { it.prefix })
    }

    @Test fun singleNewlineInsertionAcceptsOnlyOneInsertedNewline() {
        for ((previous, updated) in listOf("" to "\n", "text" to "text\n", "text" to "te\nxt", "text" to "\ntext", "a\nb" to "a\n\nb")) {
            assertTrue(isSingleNewlineInsertion(previous, updated))
        }
        for ((previous, updated) in listOf("text" to "text", "text" to "textx", "text" to "text\n\n", "text" to "te\nxy", "text" to "te\nt", "text" to "tex")) {
            assertFalse(isSingleNewlineInsertion(previous, updated))
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
