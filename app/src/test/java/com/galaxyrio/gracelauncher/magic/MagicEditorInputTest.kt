package com.galaxyrio.gracelauncher.magic

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import com.katoaapps.openminilaunch.model.CommunicationRecipient
import org.junit.Assert.*
import org.junit.Test

class MagicEditorInputTest {
    @Test fun onlyMessageAndCallRecipientSearchAreExcluded() {
        assertTrue(MagicSession(1, MagicKind.Message).searchingForRecipient)
        assertTrue(MagicSession(2, MagicKind.Call).searchingForRecipient)
        assertFalse(MagicSession(3, MagicKind.Event).searchingForRecipient)
    }

    @Test fun selectingRecipientRestoresBodyOptionsAndFreshSessionRestoresExclusion() {
        val controller = MagicController()
        controller.open(MagicKind.Message)
        val search = checkNotNull(controller.session)
        assertEquals(false, magicKeyboardOptions(search.searchingForRecipient).autoCorrectEnabled)
        search.choose(CommunicationRecipient("+15551234567", "Synthetic", "+15551234567"))
        assertFalse(search.searchingForRecipient)
        assertEquals(KeyboardOptions(imeAction = ImeAction.Send), magicKeyboardOptions(search.searchingForRecipient))
        controller.close()
        controller.open(MagicKind.Message)
        assertTrue(checkNotNull(controller.session).searchingForRecipient)
    }

    @Test fun recipientFlagsPreserveTextAndMultilineWithoutPasswordSemantics() {
        val original = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        val configured = recipientSearchInputType(original)
        assertEquals(InputType.TYPE_CLASS_TEXT, configured and InputType.TYPE_MASK_CLASS)
        assertEquals(InputType.TYPE_TEXT_VARIATION_NORMAL, configured and InputType.TYPE_MASK_VARIATION)
        assertEquals(InputType.TYPE_TEXT_FLAG_MULTI_LINE, configured and InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        assertEquals(0, configured and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        assertEquals(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, configured and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    }

    @Test fun privacyFlagPreservesSendAndOtherImeFlags() {
        val original = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        val configured = recipientSearchImeOptions(original)
        assertEquals(original, configured and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING.inv())
        assertEquals(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, configured and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
    }
}
