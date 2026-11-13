package com.galaxyrio.gracelauncher.platform

import android.view.KeyEvent
import com.galaxyrio.gracelauncher.magic.MagicController
import com.galaxyrio.gracelauncher.magic.MagicKind
import org.junit.Assert.*
import org.junit.Test

class NotesCaptureTest {
    private fun MagicController.press(
        keyCode: Int = KeyEvent.KEYCODE_N,
        character: Char = '\u0000',
        meta: Int = 0,
        focused: Boolean = false,
        repeat: Int = 0,
        action: Int = KeyEvent.ACTION_DOWN,
    ) = hardwareKey(keyCode, action, repeat, meta, character.code, focused)

    @Test fun unicodeAndHardwareAltOpenTheFloatingKinds() {
        for ((input, expected) in listOf(
            Triple(KeyEvent.KEYCODE_T, '(', 0) to MagicKind.Todo,
            Triple(KeyEvent.KEYCODE_MINUS, '-', 0) to MagicKind.Todo,
            Triple(KeyEvent.KEYCODE_N, ',', 0) to MagicKind.Note,
            Triple(KeyEvent.KEYCODE_T, '\u0000', KeyEvent.META_ALT_ON) to MagicKind.Todo,
            Triple(KeyEvent.KEYCODE_N, '\u0000', KeyEvent.META_ALT_ON) to MagicKind.Note,
            Triple(KeyEvent.KEYCODE_A, '\u0000', KeyEvent.META_ALT_ON) to MagicKind.Note,
        )) {
            val controller = MagicController().apply { eligible = true }
            assertTrue(controller.press(input.first, input.second, input.third))
            assertEquals(expected, controller.session?.kind)
            assertEquals("", controller.session?.input?.text)
        }
    }

    @Test fun minusOpensEmptyTodoAndOwnsOpeningRepeatAndRelease() {
        val controller = MagicController().apply { eligible = true }
        assertTrue(controller.press(KeyEvent.KEYCODE_MINUS, '-'))
        val session = controller.session
        assertEquals(MagicKind.Todo, session?.kind)
        assertEquals("", session?.input?.text)
        assertTrue(controller.press(KeyEvent.KEYCODE_MINUS, '-', repeat = 1))
        controller.focused()
        assertTrue(controller.press(KeyEvent.KEYCODE_MINUS, '-', focused = true, repeat = 2))
        assertSame(session, controller.session)
        assertEquals("", session?.input?.text)
        assertTrue(controller.press(KeyEvent.KEYCODE_MINUS, action = KeyEvent.ACTION_UP))
        assertFalse(controller.press(KeyEvent.KEYCODE_MINUS, action = KeyEvent.ACTION_UP))
        controller.close()
        assertFalse(controller.press(KeyEvent.KEYCODE_MINUS, '-', repeat = 3))
        assertNull(controller.session)
    }

    @Test fun minusRequiresEligibleHomeWithoutAnEditorOrControlMeta() {
        for (eligible in listOf(false, true)) for (focused in listOf(false, true)) {
            if (eligible && !focused) continue
            val controller = MagicController().apply { this.eligible = eligible }
            assertFalse(controller.press(KeyEvent.KEYCODE_MINUS, '-', focused = focused))
            assertNull(controller.session)
            assertFalse(controller.press(KeyEvent.KEYCODE_MINUS, action = KeyEvent.ACTION_UP))
        }
        for (meta in listOf(KeyEvent.META_CTRL_ON, KeyEvent.META_META_ON)) {
            val controller = MagicController().apply { eligible = true }
            assertFalse(controller.press(KeyEvent.KEYCODE_MINUS, '-', meta = meta))
            assertNull(controller.session)
        }
    }

    @Test fun minusInEveryOpenSessionPassesThroughBeforeAndAfterFocus() {
        for (kind in MagicKind.entries) {
            val controller = MagicController().apply { eligible = true; open(kind) }
            val session = checkNotNull(controller.session)
            session.append("follow")
            assertFalse(controller.press(KeyEvent.KEYCODE_MINUS, '-', focused = true))
            assertSame(session, controller.session)
            assertEquals("follow", session.input.text)
            assertFalse(controller.press(KeyEvent.KEYCODE_MINUS, action = KeyEvent.ACTION_UP))
            controller.focused()
            for (editorFocused in listOf(false, true)) {
                assertFalse(controller.press(KeyEvent.KEYCODE_MINUS, '-', focused = editorFocused))
                assertFalse(controller.press(KeyEvent.KEYCODE_MINUS, action = KeyEvent.ACTION_UP))
                assertSame(session, controller.session)
                assertEquals("follow", session.input.text)
            }
        }
    }

    @Test fun editorsAndExistingSessionsKeepCaptureCharactersAndChordsAsInput() {
        for (activeSession in listOf(false, true)) for (focused in listOf(false, true)) {
            if (!activeSession && !focused) continue
            for ((key, char, meta) in listOf(
                Triple(KeyEvent.KEYCODE_T, '(', 0), Triple(KeyEvent.KEYCODE_N, ',', 0),
                Triple(KeyEvent.KEYCODE_T, '(', KeyEvent.META_ALT_ON),
                Triple(KeyEvent.KEYCODE_N, ',', KeyEvent.META_ALT_ON),
                Triple(KeyEvent.KEYCODE_T, '\u0000', KeyEvent.META_ALT_ON),
                Triple(KeyEvent.KEYCODE_N, '\u0000', KeyEvent.META_ALT_ON),
            )) {
                val controller = MagicController().apply { eligible = true; if (activeSession) open(MagicKind.Note) }
                val session = controller.session
                assertFalse(controller.press(key, char, meta, focused))
                assertSame(session, controller.session)
                assertFalse(controller.press(key, action = KeyEvent.ACTION_UP))
            }
        }
    }

    @Test fun captureRequiresIdleHomeAndNoSearchOpening() {
        val controller = MagicController()
        assertFalse(controller.press(character = ','))
        controller.eligible = true
        controller.searchOpening = true
        assertFalse(controller.press(meta = KeyEvent.META_ALT_ON))
        assertNull(controller.session)
    }

    @Test fun openingRepeatAndReleaseAreOwnedAcrossFocusAndClose() {
        for ((char, meta) in listOf('(' to 0, '\u0000' to KeyEvent.META_ALT_ON)) {
            val controller = MagicController().apply { eligible = true }
            assertTrue(controller.press(KeyEvent.KEYCODE_T, char, meta))
            val session = controller.session
            assertTrue(controller.press(KeyEvent.KEYCODE_T, char, meta, focused = true, repeat = 1))
            assertSame(session, controller.session)
            controller.close()
            assertTrue(controller.press(KeyEvent.KEYCODE_T, action = KeyEvent.ACTION_UP))
            assertFalse(controller.press(KeyEvent.KEYCODE_T, action = KeyEvent.ACTION_UP))
            assertFalse(controller.press(KeyEvent.KEYCODE_T, char, meta, repeat = 2))
        }
    }

    @Test fun hardwareChordsRejectOtherModifiersAndUnownedRepeatsReleases() {
        for (modifier in listOf(KeyEvent.META_SHIFT_ON, KeyEvent.META_SHIFT_LEFT_ON,
            KeyEvent.META_CTRL_ON, KeyEvent.META_CTRL_RIGHT_ON, KeyEvent.META_META_ON,
            KeyEvent.META_META_LEFT_ON, KeyEvent.META_SYM_ON, KeyEvent.META_FUNCTION_ON)) {
            val controller = MagicController().apply { eligible = true }
            assertFalse(controller.press(KeyEvent.KEYCODE_T, meta = KeyEvent.META_ALT_ON or modifier))
            assertNull(controller.session)
        }
        val controller = MagicController().apply { eligible = true }
        assertFalse(controller.press(meta = KeyEvent.META_ALT_ON, repeat = 1))
        assertFalse(controller.press(meta = KeyEvent.META_ALT_ON, action = KeyEvent.ACTION_UP))
        assertFalse(controller.press(meta = KeyEvent.META_ALT_ON, action = KeyEvent.ACTION_MULTIPLE))
    }

    @Test fun unicodeCaptureAllowsTitanAltAndSymButNotControlOrMeta() {
        for (char in listOf('(', ',')) for (meta in listOf(0, KeyEvent.META_ALT_ON,
            KeyEvent.META_SYM_ON, KeyEvent.META_ALT_ON or KeyEvent.META_SYM_ON)) {
            val controller = MagicController().apply { eligible = true }
            assertTrue(controller.press(KeyEvent.KEYCODE_UNKNOWN, char, meta))
            assertEquals(if (char == '(') MagicKind.Todo else MagicKind.Note, controller.session?.kind)
        }
        for (meta in listOf(KeyEvent.META_CTRL_ON, KeyEvent.META_META_ON)) {
            val controller = MagicController().apply { eligible = true }
            assertFalse(controller.press(character = '(', meta = meta))
        }
    }

    @Test fun eitherAltKeyAndLockStatesAreAccepted() {
        for (alt in listOf(KeyEvent.META_ALT_LEFT_ON, KeyEvent.META_ALT_RIGHT_ON,
            KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON,
            KeyEvent.META_ALT_ON or KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_NUM_LOCK_ON)) {
            val controller = MagicController().apply { eligible = true }
            assertTrue(controller.press(meta = alt))
            assertEquals(MagicKind.Note, controller.session?.kind)
        }
    }
}
