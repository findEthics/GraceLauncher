package com.galaxyrio.gracelauncher.platform

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotesCaptureTest {
    private fun capture(
        keyCode: Int = KeyEvent.KEYCODE_N,
        action: Int = KeyEvent.ACTION_DOWN,
        repeatCount: Int = 0,
        metaState: Int = KeyEvent.META_ALT_ON,
        textEditorFocused: Boolean = false,
        idleHome: Boolean = true,
        inputActive: Boolean = false,
    ) = notesCaptureAction(keyCode, action, repeatCount, metaState, textEditorFocused, idleHome, inputActive)

    @Test fun altPhysicalKeysMapToCaptureActions() {
        assertEquals("com.example.notes.action.NEW_NOTE", capture(KeyEvent.KEYCODE_N))
        assertEquals("com.example.notes.action.NEW_TODO", capture(KeyEvent.KEYCODE_T))
        assertEquals("com.example.notes.action.APPEND_NOTE", capture(KeyEvent.KEYCODE_A))
        assertNull(capture(KeyEvent.KEYCODE_B))
    }

    @Test fun plainShiftAndControlTypingAreNotShortcuts() {
        assertNull(capture(metaState = 0))
        assertNull(capture(metaState = KeyEvent.META_SHIFT_ON))
        assertNull(capture(metaState = KeyEvent.META_CTRL_ON))
    }

    @Test fun altMustBeTheOnlyModifier() {
        for (modifier in listOf(KeyEvent.META_SHIFT_ON, KeyEvent.META_SHIFT_LEFT_ON,
            KeyEvent.META_CTRL_ON, KeyEvent.META_CTRL_RIGHT_ON, KeyEvent.META_META_ON,
            KeyEvent.META_META_LEFT_ON, KeyEvent.META_SYM_ON, KeyEvent.META_FUNCTION_ON)) {
            assertNull(capture(metaState = KeyEvent.META_ALT_ON or modifier))
        }
    }

    @Test fun eitherAltKeyAndLockStatesAreAccepted() {
        for (alt in listOf(KeyEvent.META_ALT_LEFT_ON, KeyEvent.META_ALT_RIGHT_ON,
            KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)) {
            assertEquals("com.example.notes.action.NEW_NOTE", capture(metaState = alt))
        }
        assertEquals("com.example.notes.action.NEW_NOTE",
            capture(metaState = KeyEvent.META_ALT_ON or KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_NUM_LOCK_ON))
    }

    @Test fun repeatsAndReleasesNeverCapture() {
        assertNull(capture(repeatCount = 1))
        assertNull(capture(action = KeyEvent.ACTION_UP))
        assertNull(capture(action = KeyEvent.ACTION_MULTIPLE))
    }

    @Test fun textFocusAndActiveInputsNeverCapture() {
        assertNull(capture(textEditorFocused = true))
        assertNull(capture(inputActive = true))
        assertNull(capture(idleHome = false))
    }
}
