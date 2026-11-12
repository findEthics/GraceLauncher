package com.galaxyrio.gracelauncher.magic

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.ImeAction

internal val MagicSession.searchingForRecipient: Boolean
    get() = kind.usesContacts && recipient == null && conversation == null

internal fun magicKeyboardOptions(recipientSearch: Boolean) = KeyboardOptions(
    autoCorrectEnabled = if (recipientSearch) false else null,
    imeAction = ImeAction.Send,
)

internal fun recipientSearchInputType(inputType: Int): Int =
    (inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT.inv()) or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

internal fun recipientSearchImeOptions(imeOptions: Int): Int =
    imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

internal fun magicEditorRequest(
    request: PlatformTextInputMethodRequest,
    recipientSearch: Boolean,
): PlatformTextInputMethodRequest = if (!recipientSearch) request else object : PlatformTextInputMethodRequest {
    override fun createInputConnection(outAttributes: EditorInfo) =
        request.createInputConnection(outAttributes).also {
            // Standard Android requests; keyboards may choose whether to honor them.
            outAttributes.inputType = recipientSearchInputType(outAttributes.inputType)
            outAttributes.imeOptions = recipientSearchImeOptions(outAttributes.imeOptions)
        }
}

/** Only the enclosed editor's input session changes; no shared Home View flags are touched. */
@Composable
internal fun MagicEditorInput(recipientSearch: Boolean, content: @Composable () -> Unit) {
    val interceptor = remember(recipientSearch) {
        PlatformTextInputInterceptor { request, nextHandler ->
            nextHandler.startInputMethod(magicEditorRequest(request, recipientSearch))
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}
