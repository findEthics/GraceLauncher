package com.galaxyrio.gracelauncher.assistant

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.galaxyrio.gracelauncher.R

@Composable
internal fun AssistantLifecycle(assistant: AssistantController) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(assistant, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) assistant.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        assistant.permissionResult(it.isNotEmpty() && it.values.all { granted -> granted })
    }
    LaunchedEffect(assistant.permissionRequest) {
        if (assistant.permissionRequest.isNotEmpty()) permissions.launch(assistant.consumePermissionRequest())
    }
}

@Composable
fun AssistantHome(assistant: AssistantController, modifier: Modifier = Modifier) {
    if (!assistant.enabled || !assistant.homeButtonVisible) return
    FilledIconButton(
        onClick = { assistant.openChat() }, shape = CircleShape,
        modifier = modifier.size(56.dp).testTag("assistant_chat_button"),
    ) {
        Icon(painterResource(R.drawable.ic_assistant_chat), stringResource(R.string.assistant_open_chat), Modifier.size(26.dp))
    }
}

@Composable
internal fun AssistantChat(assistant: AssistantController) {
    if (!assistant.chat.visible) return
    AssistantChatWindow(assistant.chat, assistant.busy, assistant.canCancel, assistant.status,
        onSend = assistant::submit, onStop = assistant::cancel, onClose = assistant::closeChat,
        onFocus = assistant::composerFocusChanged)
}

@Composable
internal fun AssistantChatWindow(
    chat: AssistantChatState, busy: Boolean, canCancel: Boolean, status: String,
    onSend: () -> Unit, onStop: () -> Unit, onClose: () -> Unit, onFocus: (Boolean) -> Unit = {},
) {
    BackHandler(onBack = onClose)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scroll = rememberLazyListState()
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    DisposableEffect(Unit) { onDispose { onFocus(false) } }
    LaunchedEffect(chat.turns.size, chat.turns.lastOrNull()?.response) {
        if (chat.turns.isNotEmpty() && !scroll.isScrollInProgress) scroll.scrollToItem(chat.turns.lastIndex)
    }
    val send = { if (!busy && chat.draft.isNotBlank()) onSend() }
    val messageDescription = stringResource(R.string.assistant_message)
    Surface(Modifier.fillMaxSize().testTag("assistant_chat_window")) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            if (chat.contextDropped) Text(stringResource(R.string.assistant_context_rolled), Modifier.padding(16.dp).testTag("assistant_context_notice"), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("assistant_messages"), state = scroll,
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(chat.turns, key = { it.id }) { turn ->
                    Column(Modifier.fillMaxWidth()) {
                        Surface(Modifier.align(Alignment.End).widthIn(max = 560.dp),
                            shape = RoundedCornerShape(24.dp, 24.dp, 6.dp, 24.dp),
                            color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(turn.request, Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("assistant_request:${turn.id}"))
                        }
                        Spacer(Modifier.height(8.dp))
                        Surface(Modifier.align(Alignment.Start).widthIn(max = 640.dp),
                            shape = RoundedCornerShape(24.dp, 24.dp, 24.dp, 6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                if (turn.action) Text(stringResource(R.string.assistant_action_result), style = MaterialTheme.typography.labelSmall)
                                Text(turn.response.ifBlank { stringResource(R.string.assistant_processing) },
                                    Modifier.testTag("assistant_response:${turn.id}"))
                            }
                        }
                    }
                }
            }
            if (status.isNotBlank() && status != chat.turns.lastOrNull()?.takeIf { it.complete }?.response)
                Text(status, Modifier.padding(horizontal = 16.dp).testTag("assistant_status"), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = chat.input, onValueChange = { chat.input = it },
                    modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { onFocus(it.isFocused) }
                        .semantics { contentDescription = messageDescription }
                        .testTag("assistant_chat_input").onPreviewKeyEvent { event ->
                            if (event.key != Key.Enter && event.key != Key.NumPadEnter) false
                            else {
                                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                                    if (event.isShiftPressed) {
                                        val value = chat.input
                                        val start = value.selection.min
                                        chat.input = TextFieldValue(value.text.replaceRange(start, value.selection.max, "\n"), TextRange(start + 1))
                                    } else send()
                                }
                                true // Consume both down and up; neither may insert a second newline/send.
                            }
                        },
                    shape = RoundedCornerShape(28.dp), maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                    trailingIcon = {
                        if (busy) IconButton(
                            onClick = onStop,
                            enabled = canCancel,
                            modifier = Modifier.testTag("assistant_stop"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_stop_filled),
                                contentDescription = stringResource(R.string.assistant_stop),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun AssistantSettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val assistant = remember(context) { AssistantController(context, scope) }
    DisposableEffect(assistant) { onDispose { assistant.close() } }
    val homeButtonDescription = stringResource(R.string.assistant_show_home_button)
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.assistant_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.assistant_privacy), style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.assistant_enable), Modifier.weight(1f))
            Switch(checked = assistant.enabled, onCheckedChange = assistant::enable, modifier = Modifier.testTag("assistant_enable"))
        }
        Text(assistant.status, Modifier.testTag("assistant_download_status"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(homeButtonDescription, Modifier.weight(1f))
            Switch(checked = assistant.homeButtonVisible, onCheckedChange = assistant::showHomeButton,
                modifier = Modifier.testTag("assistant_show_home_button").semantics { contentDescription = homeButtonDescription })
        }
        Text(stringResource(R.string.assistant_show_home_button_hint), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(assistant.selected.label, Modifier.testTag("assistant_model_Gemma4"), style = MaterialTheme.typography.titleMedium)
        Text(if (assistant.ready()) stringResource(R.string.assistant_ready) else "${assistant.selected.bytes / 1_000_000} MB")
        if (!assistant.ready()) Button(enabled = !assistant.busy, onClick = { assistant.download(assistant.selected, "") }) {
            Text(stringResource(R.string.assistant_download))
        } else TextButton(enabled = !assistant.busy, onClick = { assistant.delete(assistant.selected) }) {
            Text(stringResource(R.string.assistant_delete))
        }
        if (assistant.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (assistant.canCancel) TextButton(onClick = assistant::cancel) { Text(stringResource(R.string.assistant_cancel)) }
        }
        Text(stringResource(R.string.assistant_chat_hint), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.assistant_direct_actions), style = MaterialTheme.typography.bodySmall)
    }
}
