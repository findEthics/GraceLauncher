package com.galaxyrio.gracelauncher.ui.home

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/** Activity bridge for idle HOME, including when no Compose row has focus yet. */
class HomeAppNavigation {
    var move: ((Int) -> Boolean)? = null
    private val pressed = mutableSetOf<Int>()

    fun key(event: KeyEvent, textEditorFocused: Boolean): Boolean {
        if (event.action == KeyEvent.ACTION_UP && pressed.remove(event.keyCode)) return true
        if (event.action != KeyEvent.ACTION_DOWN || textEditorFocused || event.isCtrlPressed ||
            event.isAltPressed || event.isMetaPressed || event.isCanceled) return false
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> 1
            KeyEvent.KEYCODE_DPAD_UP -> -1
            KeyEvent.KEYCODE_TAB -> if (event.isShiftPressed) -1 else 1
            else -> return false
        }
        val handled = move?.invoke(direction) == true
        if (handled) pressed += event.keyCode
        return handled
    }

    fun motion(event: MotionEvent, textEditorFocused: Boolean): Boolean {
        if (textEditorFocused || event.actionMasked != MotionEvent.ACTION_SCROLL) return false
        val amount = when {
            event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER) -> event.getAxisValue(MotionEvent.AXIS_SCROLL)
            else -> event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        }
        if (!amount.isFinite() || amount == 0f) return false
        return move?.invoke(if (amount > 0) -1 else 1) == true
    }

    fun release() { pressed.clear() }
}
