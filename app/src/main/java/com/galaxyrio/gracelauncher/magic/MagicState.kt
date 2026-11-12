package com.galaxyrio.gracelauncher.magic

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.galaxyrio.gracelauncher.platform.SelectedNote
import com.katoaapps.openminilaunch.model.CommunicationRecipient
import com.katoaapps.openminilaunch.features.messaging.MessageDraft

internal enum class MagicKind(val prefix: Char) {
    Message('@'), Call('#'), Event('+'), Todo('('), Note(',');
    val usesContacts get() = this == Message || this == Call
}

internal class MagicSmsRequest(val draft: MessageDraft, val inferred: Boolean) {
    var confirmed = !inferred; internal set
}

/** RAM-only command generations; a dismissed or consumed generation cannot dispatch again. */
internal class MagicSession(val id: Long, val kind: MagicKind) {
    private var inputValue by mutableStateOf(TextFieldValue())
    var input: TextFieldValue
        get() = inputValue
        set(value) {
            if (value.text != inputValue.text) {
                cancelSms()
                if (kind == MagicKind.Note) selectedIndex = -1
            }
            inputValue = value
        }
    var recipient by mutableStateOf<CommunicationRecipient?>(null)
    var recipientInferred = false; private set
    private var smsRequest: MagicSmsRequest? = null
    var conversation by mutableStateOf<MagicConversation?>(null)
    var selectedNote by mutableStateOf<SelectedNote?>(null); private set
    var selectedIndex by mutableStateOf(-1)
    var providerPackage: String? = null
    var consumed by mutableStateOf(false)
    var handedOff = false
    var status by mutableStateOf("")
    val query get() = input.text.trimStart().substringBefore(' ')
    val body get() = input.text.substringAfter(' ', "").trimStart()
    private var contactScope: MagicContactScope? = null
    fun updateContactScope(scope: MagicContactScope): Boolean {
        val changed = kind.usesContacts && contactScope != null && contactScope != scope
        if (changed) {
            cancelSms()
            recipient = null
            recipientInferred = false
            conversation = null
            providerPackage = null
            input = TextFieldValue()
            status = ""
        }
        contactScope = scope
        return changed
    }
    fun append(text: String) {
        val start = input.selection.min
        val value = input.text.replaceRange(start, input.selection.max, text)
        input = TextFieldValue(value, TextRange(start + text.length))
    }
    fun choose(value: CommunicationRecipient, packageName: String? = null, inferred: Boolean = false) {
        val message = body
        cancelSms()
        recipient = value
        recipientInferred = inferred
        providerPackage = packageName
        input = TextFieldValue(message, TextRange(message.length))
    }
    fun chooseNote(value: SelectedNote?) {
        if (consumed) return
        selectedNote = value
        selectedIndex = -1
        input = TextFieldValue()
        status = ""
    }
    fun requestSms(): MagicSmsRequest? {
        val selected = recipient ?: return null
        if (consumed || smsRequest != null || input.text.isBlank()) return null
        return MagicSmsRequest(MessageDraft(selected, input.text, providerPackage), recipientInferred).also { smsRequest = it }
    }
    fun matchesSms(request: MagicSmsRequest) = !consumed && smsRequest === request &&
        recipient == request.draft.recipient && input.text == request.draft.body && providerPackage == request.draft.conversationPackage
    fun confirmSms(request: MagicSmsRequest): Boolean {
        if (!matchesSms(request) || request.confirmed) return false
        request.confirmed = true
        return true
    }
    fun claimSms(request: MagicSmsRequest): Boolean = matchesSms(request) && request.confirmed && claim()
    fun cancelSms() { smsRequest = null }
    fun claim(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }
}

internal class MagicController {
    var session by mutableStateOf<MagicSession?>(null); private set
    var dataSource: MagicDataSource? = null
    var fakeCall: ((String, Boolean) -> Boolean)? = null
    var fakeCallPermission = false
    var eligible = false
    private var generation = 0L
    internal val keys = MagicKeySequence()
    private var composerEverFocused = false
    var onSubmit: (() -> Unit)? = null
    var submitPending = false
    var onOpenSearch: (() -> Unit)? = null
    var onSearchText: ((String) -> Unit)? = null
    var searchOpening = false

    fun open(kind: MagicKind) {
        session = MagicSession(++generation, kind)
        composerEverFocused = false
        submitPending = false
    }
    fun close() {
        session?.cancelSms()
        session = null
        searchOpening = false
        onSubmit = null
        submitPending = false
        composerEverFocused = false
    }
    fun focused() { composerEverFocused = true; searchOpening = false }

    fun hardwareKey(event: KeyEvent, textEditorFocused: Boolean): Boolean =
        if (event.isCanceled && event.action == KeyEvent.ACTION_DOWN) false else hardwareKey(event.keyCode, event.action, event.repeatCount,
            event.metaState, event.unicodeChar, textEditorFocused)

    internal fun hardwareKey(keyCode: Int, action: Int, repeatCount: Int, metaState: Int,
        character: Int, textEditorFocused: Boolean): Boolean {
        // Key ownership outlives composer/search focus and disposal until the matching release.
        if (action == KeyEvent.ACTION_UP) {
            return keys.release(keyCode)
        }
        if (action != KeyEvent.ACTION_DOWN) return false
        if (keys.openingRepeat(keyCode, repeatCount)) return true
        return keyDown(keyCode, repeatCount, metaState, character, textEditorFocused).also { if (it) keys.consume(keyCode) }
    }

    private fun keyDown(keyCode: Int, repeatCount: Int, metaState: Int, character: Int, textEditorFocused: Boolean): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && session != null) { close(); return true }
        val ctrlOrMeta = metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK) != 0
        val otherModifiers = KeyEvent.META_SHIFT_MASK or KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK or
            KeyEvent.META_SYM_ON or KeyEvent.META_FUNCTION_ON
        val altOnly = metaState and KeyEvent.META_ALT_MASK != 0 && metaState and otherModifiers == 0
        val captureKind = if (!altOnly) null else when (keyCode) {
            KeyEvent.KEYCODE_T -> MagicKind.Todo
            KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_A -> MagicKind.Note
            else -> null
        }
        if (session == null && eligible && !textEditorFocused && !searchOpening && !ctrlOrMeta && repeatCount == 0) {
            if (captureKind != null) {
                keys.openingKey = keyCode
                open(captureKind)
                return true
            }
            if (character in "@#+?(,".map(Char::code)) {
                keys.openingKey = keyCode
                if (character == '?'.code) { searchOpening = true; onOpenSearch?.invoke() }
                else open(MagicKind.entries.first { it.prefix.code == character })
                return true
            }
        }
        if (ctrlOrMeta) return false
        if (searchOpening) {
            if (textEditorFocused) searchOpening = false
            else if (printable(character)) { onSearchText?.invoke(String(Character.toChars(character))); return true }
        }
        val current = session ?: return false
        if (textEditorFocused || character == '('.code || character == ','.code || captureKind != null) return false
        // Compose can take a frame to focus after the opening key.
        if (!composerEverFocused) {
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (repeatCount == 0) {
                    if (metaState and KeyEvent.META_SHIFT_MASK != 0) current.append("\n")
                    else onSubmit?.invoke() ?: run { submitPending = true }
                }
                return true
            }
            if (printable(character)) { current.append(String(Character.toChars(character))); return true }
        }
        return false
    }
    private fun printable(value: Int) = value != 0 && Character.isValidCodePoint(value) && !Character.isISOControl(value)
}

internal class MagicKeySequence {
    var openingKey: Int? = null
    private val consumed = mutableSetOf<Int>()

    fun openingRepeat(keyCode: Int, repeatCount: Int): Boolean {
        if (repeatCount != 0) return keyCode == openingKey
        // A fresh press recovers if another window received the previous release.
        consumed.remove(keyCode)
        if (openingKey == keyCode) openingKey = null
        return false
    }
    fun consume(keyCode: Int) { consumed.add(keyCode) }
    fun release(keyCode: Int): Boolean {
        if (openingKey == keyCode) openingKey = null
        return consumed.remove(keyCode)
    }
}
