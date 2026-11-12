package com.galaxyrio.gracelauncher.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.widget.Toast
import com.galaxyrio.gracelauncher.R

internal fun notesCaptureAction(
    keyCode: Int,
    action: Int,
    repeatCount: Int,
    metaState: Int,
    textEditorFocused: Boolean,
    idleHome: Boolean,
    inputActive: Boolean,
): String? {
    val otherModifiers = KeyEvent.META_SHIFT_MASK or KeyEvent.META_CTRL_MASK or
        KeyEvent.META_META_MASK or KeyEvent.META_SYM_ON or KeyEvent.META_FUNCTION_ON
    if (action != KeyEvent.ACTION_DOWN || repeatCount != 0 || textEditorFocused || !idleHome || inputActive ||
        metaState and KeyEvent.META_ALT_MASK == 0 || metaState and otherModifiers != 0) return null
    return when (keyCode) {
        KeyEvent.KEYCODE_N -> "com.example.notes.action.NEW_NOTE"
        KeyEvent.KEYCODE_T -> "com.example.notes.action.NEW_TODO"
        KeyEvent.KEYCODE_A -> "com.example.notes.action.APPEND_NOTE"
        else -> null
    }
}

internal fun launchNotesCapture(context: Context, action: String) {
    val intent = Intent(action).setPackage("com.example.notes").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            return
        }
    } catch (_: ActivityNotFoundException) {
        // The target may disappear between resolution and launch.
    } catch (_: SecurityException) {
        // Older Notes builds may not expose the capture activity.
    }
    Toast.makeText(context, R.string.notes_capture_unavailable, Toast.LENGTH_SHORT).show()
}
