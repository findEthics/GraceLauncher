package com.galaxyrio.gracelauncher.assistant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.galaxyrio.gracelauncher.R
import com.galaxyrio.gracelauncher.assistant.memory.*
import com.galaxyrio.gracelauncher.magic.isSingleNewlineInsertion
import kotlinx.coroutines.launch
import java.net.URI

@Composable
internal fun AssistantLifecycle(assistant: AssistantController) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(assistant, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) assistant.pause() else if (event == Lifecycle.Event.ON_RESUME) assistant.refreshHealth() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
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
        onFocus = assistant::composerFocusChanged, healthAvailable = assistant.healthAvailable, memoryActions = assistant.memoryActions)
}

private fun memoryChipLabel(chip: MemoryChip) = when (chip) {
    MemoryChip.SAVE -> R.string.memory_chip_save
    MemoryChip.RECALL -> R.string.memory_chip_recall
    MemoryChip.DELETE -> R.string.memory_chip_delete
    MemoryChip.PURGE -> R.string.memory_chip_purge
}

private fun SlashCommand.chip(): MemoryChip? = when (this) {
    SlashCommand.REMEMBER -> MemoryChip.SAVE
    SlashCommand.RECALL -> MemoryChip.RECALL
    SlashCommand.FORGET -> MemoryChip.DELETE
    SlashCommand.PURGE -> MemoryChip.PURGE
    else -> null
}

private fun SlashCommand.source(): ChatSource? = when (this) {
    SlashCommand.WEB -> ChatSource.WEB
    SlashCommand.WIKIPEDIA -> ChatSource.WIKIPEDIA
    SlashCommand.HEALTH -> ChatSource.HEALTH
    else -> null
}

private fun SlashCommand.description() = when (this) {
    SlashCommand.WEB -> R.string.slash_web
    SlashCommand.WIKIPEDIA -> R.string.slash_wikipedia
    SlashCommand.HEALTH -> R.string.slash_health
    SlashCommand.REMEMBER -> R.string.slash_remember
    SlashCommand.RECALL -> R.string.slash_recall
    SlashCommand.FORGET -> R.string.slash_forget
    SlashCommand.PURGE -> R.string.slash_purge
    SlashCommand.HISTORY -> R.string.slash_history
}

/** A source/memory row becomes the same chip the + menu sets (one-shot memory chips replace each other); /history just types its prefix. */
private fun applySlash(chat: AssistantChatState, command: SlashCommand) {
    val source = command.source()
    val chip = command.chip()
    when {
        source != null -> { chat.source = source; chat.draft = "" }
        chip != null -> { chat.memoryChip = chip; chat.draft = "" }
        else -> chat.draft = "/history "
    }
}

private fun sourceLabel(source: ChatSource) = when (source) {
    ChatSource.WIKIPEDIA -> R.string.assistant_wikipedia
    ChatSource.HEALTH -> R.string.assistant_health
    else -> R.string.assistant_web_search
}

@Composable
internal fun AssistantChatWindow(
    chat: AssistantChatState, busy: Boolean, canCancel: Boolean, status: String,
    onSend: () -> Unit, onStop: () -> Unit, onClose: () -> Unit, onFocus: (Boolean) -> Unit = {}, healthAvailable: Boolean = false,
    memoryActions: MemoryActions = MemoryActions.None,
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
    val send = { if (!busy && (chat.draft.isNotBlank() || chat.memoryChip == MemoryChip.RECALL)) onSend() }
    val messageDescription = stringResource(R.string.assistant_message)
    val removeWebDescription = stringResource(R.string.assistant_remove_web_search)
    val removeWikipediaDescription = stringResource(R.string.assistant_remove_wikipedia)
    val removeHealthDescription = stringResource(R.string.assistant_remove_health)
    val toolsDescription = stringResource(R.string.assistant_tools)
    val uriHandler = LocalUriHandler.current
    var toolsExpanded by remember { mutableStateOf(false) }
    // Both reset on any text edit, so Escape hides the popup only until the next keystroke.
    var slashDismissed by remember(chat.draft) { mutableStateOf(false) }
    var slashIndex by remember(chat.draft) { mutableIntStateOf(0) }
    val slashRows = if (slashDismissed) emptyList() else slashSuggestions(chat.draft, healthAvailable)
    val slashSelected = slashIndex.coerceIn(0, (slashRows.size - 1).coerceAtLeast(0))
    val slashDescription = stringResource(R.string.slash_popup)
    // Every Enter path (raw key, IME Send, IME-committed newline, Activity fallback) applies the highlighted row first, like the magic panels.
    val enter = { if (slashRows.isNotEmpty()) applySlash(chat, slashRows[slashSelected]) else send() }
    SideEffect { chat.onEnter = enter }
    DisposableEffect(chat) { onDispose { chat.onEnter = null } }
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
                                if (turn.webSearch) Text(stringResource(R.string.assistant_web_search), style = MaterialTheme.typography.labelSmall)
                                if (turn.source == ChatSource.WIKIPEDIA) Text(stringResource(R.string.assistant_wikipedia), style = MaterialTheme.typography.labelSmall)
                                if (turn.source == ChatSource.HEALTH) Text(stringResource(R.string.assistant_health), style = MaterialTheme.typography.labelSmall)
                                if (turn.searchWarning.isNotBlank()) Text(turn.searchWarning,
                                    Modifier.testTag("assistant_search_warning:${turn.id}"), color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall)
                                Text(turn.response.ifBlank { stringResource(R.string.assistant_processing) },
                                    Modifier.testTag("assistant_response:${turn.id}"))
                                turn.healthCard?.let { HealthStatusCard(turn.id, it) }
                                turn.memoryCard?.let { MemoryCardView(turn.id, it, memoryActions) }
                                if (turn.webSearch && turn.complete && turn.inContext && turn.response.isNotBlank() &&
                                    turn.searchWarning.isBlank() && turn.sources.isNotEmpty()) {
                                    Text(pluralStringResource(R.plurals.assistant_web_results_disclosure, turn.sources.size, turn.sources.size),
                                        Modifier.padding(top = 8.dp).testTag("assistant_web_results_disclosure:${turn.id}"),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                turn.sources.forEachIndexed { index, source ->
                                    if (turn.webSearch) WebSourceCard(turn.id, index, turn.number(index), source, uriHandler)
                                    else TextButton(onClick = { if (WebSource.safeUrl(source.url)) runCatching { uriHandler.openUri(source.url) } },
                                        modifier = Modifier.testTag("assistant_source:${turn.id}:$index")) {
                                        Text("[${turn.number(index)}] ${source.title} — ${URI(source.url).host}" +
                                            (if (source.provider == ChatSource.WIKIPEDIA) " — " + stringResource(R.string.assistant_wikipedia_contributors) else "") +
                                            (formatPublishedDate(source.publishedDate)?.let { " — $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (turn.showWikipediaSummaryAttribution) {
                                    Text(stringResource(R.string.assistant_wikipedia_adapted), style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.testTag("assistant_wikipedia_attribution:${turn.id}"))
                                }
                                if (turn.source == ChatSource.WIKIPEDIA && turn.sources.isNotEmpty()) {
                                    TextButton(onClick = { runCatching { uriHandler.openUri(WikipediaSearch.LICENSE_URL) } },
                                        modifier = Modifier.testTag("assistant_wikipedia_license:${turn.id}")) { Text("CC BY-SA 4.0") }
                                }
                            }
                        }
                    }
                }
            }
            if (status.isNotBlank() && status != chat.turns.lastOrNull()?.takeIf { it.complete }?.response)
                Text(status, Modifier.padding(horizontal = 16.dp).testTag("assistant_status"), style = MaterialTheme.typography.bodySmall)
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chat.memoryChip?.let { chip ->
                        val label = stringResource(memoryChipLabel(chip))
                        val remove = stringResource(R.string.memory_chip_remove, label)
                        InputChip(selected = true, onClick = { chat.memoryChip = null }, label = { Text(label) }, trailingIcon = { Text("×") },
                            modifier = Modifier.testTag("assistant_memory_chip").semantics { contentDescription = remove })
                    }
                    if (chat.source != ChatSource.NONE) InputChip(selected = true,
                        onClick = { chat.source = ChatSource.NONE },
                        label = { Text(stringResource(sourceLabel(chat.source))) }, trailingIcon = { Text("×") },
                        modifier = Modifier.testTag(when (chat.source) { ChatSource.WIKIPEDIA -> "assistant_wikipedia_chip"; ChatSource.HEALTH -> "assistant_health_chip"; else -> "assistant_web_chip" })
                            .semantics { contentDescription = when (chat.source) { ChatSource.WIKIPEDIA -> removeWikipediaDescription; ChatSource.HEALTH -> removeHealthDescription; else -> removeWebDescription } })
                }
                if (slashRows.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("assistant_slash_popup")
                    .semantics { contentDescription = slashDescription }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    slashRows.forEachIndexed { index, command ->
                        val description = stringResource(command.description())
                        Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().semantics { selected = index == slashSelected },
                            color = if (index == slashSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                            TextButton(onClick = { applySlash(chat, command) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusProperties { canFocus = false }
                                    .testTag("assistant_slash_row_${command.token}")
                                    .semantics { contentDescription = "/${command.token}, $description" }) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("/${command.token}", style = MaterialTheme.typography.titleSmall)
                                    Text(description, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = chat.input,
                    onValueChange = { if (slashRows.isNotEmpty() && isSingleNewlineInsertion(chat.input.text, it.text)) enter() else chat.input = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { onFocus(it.isFocused) }
                        .semantics { contentDescription = messageDescription }
                        .testTag("assistant_chat_input").onPreviewKeyEvent { event ->
                            val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                            val popupKey = slashRows.isNotEmpty() && (event.key == Key.DirectionUp || event.key == Key.DirectionDown ||
                                event.key == Key.Tab || event.key == Key.Escape || (isEnter && !event.isShiftPressed))
                            if (popupKey) {
                                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                                    val last = slashRows.lastIndex
                                    when (event.key) {
                                        Key.DirectionUp -> slashIndex = (slashSelected - 1).coerceAtLeast(0)
                                        Key.DirectionDown -> slashIndex = (slashSelected + 1).coerceAtMost(last)
                                        Key.Tab -> slashIndex = if (event.isShiftPressed) (slashSelected + last) % (last + 1) else (slashSelected + 1) % (last + 1)
                                        Key.Escape -> slashDismissed = true
                                        else -> enter()
                                    }
                                }
                                true
                            } else if (!isEnter) false
                            else {
                                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                                    if (event.isShiftPressed) {
                                        val value = chat.input
                                        val start = value.selection.min
                                        chat.input = TextFieldValue(value.text.replaceRange(start, value.selection.max, "\n"), TextRange(start + 1))
                                    } else enter()
                                }
                                true // Consume both down and up; neither may insert a second newline/send.
                            }
                        },
                    shape = RoundedCornerShape(28.dp), maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { enter() }),
                    leadingIcon = {
                        Box {
                            IconButton(onClick = { toolsExpanded = true }, modifier = Modifier.testTag("assistant_tools")) {
                                Text("+", Modifier.semantics { contentDescription = toolsDescription }, style = MaterialTheme.typography.titleLarge)
                            }
                            DropdownMenu(expanded = toolsExpanded, onDismissRequest = { toolsExpanded = false },
                                shape = RoundedCornerShape(28.dp), modifier = Modifier.testTag("assistant_tools_menu")) {
                                for (source in listOf(ChatSource.WEB, ChatSource.WIKIPEDIA) + if (healthAvailable) listOf(ChatSource.HEALTH) else emptyList()) {
                                    DropdownMenuItem(text = { Text(stringResource(sourceLabel(source))) },
                                        leadingIcon = { Checkbox(checked = chat.source == source, onCheckedChange = null) },
                                        onClick = { chat.source = if (chat.source == source) ChatSource.NONE else source; toolsExpanded = false },
                                        modifier = Modifier.testTag(when (source) { ChatSource.WIKIPEDIA -> "assistant_wikipedia_option"; ChatSource.HEALTH -> "assistant_health_option"; else -> "assistant_web_search_option" }).semantics {
                                            role = Role.Checkbox; toggleableState = ToggleableState(chat.source == source)
                                        })
                                }
                                HorizontalDivider()
                                for (chip in MemoryChip.entries) {
                                    DropdownMenuItem(text = { Text(stringResource(memoryChipLabel(chip))) },
                                        leadingIcon = { Checkbox(checked = chat.memoryChip == chip, onCheckedChange = null) },
                                        onClick = { chat.memoryChip = if (chat.memoryChip == chip) null else chip; toolsExpanded = false },
                                        modifier = Modifier.testTag("assistant_memory_option_${chip.name.lowercase()}").semantics {
                                            role = Role.Checkbox; toggleableState = ToggleableState(chat.memoryChip == chip)
                                        })
                                }
                            }
                        }
                    },
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
private fun HealthStatusCard(turnId: Long, card: HealthCard) {
    var expanded by remember(turnId) { mutableStateOf(false) }
    val zone = remember { java.time.ZoneId.systemDefault() }
    val title = when (val f = card.freshness) {
        is HealthFreshness.Live -> stringResource(R.string.assistant_health_live, HealthEvidence.clock(f.retrievedAt, zone))
        is HealthFreshness.Cached -> stringResource(R.string.assistant_health_cached, HealthEvidence.time(f.retrievedAt, zone), f.start ?: "?", f.end ?: "?")
        is HealthFreshness.Unavailable -> stringResource(R.string.assistant_health_unavailable, f.reason)
    }
    Surface(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("assistant_health_card:$turnId"),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, Modifier.testTag("assistant_health_status:$turnId"), style = MaterialTheme.typography.bodySmall)
            if (card.details.isNotBlank()) {
                if (expanded) Text(card.details, Modifier.testTag("assistant_health_details:$turnId"), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp).testTag("assistant_health_expand:$turnId")) {
                    Text(stringResource(if (expanded) R.string.assistant_health_details_hide else R.string.assistant_health_details_show))
                }
            }
        }
    }
}

private val MEMORY_DATE: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val MEMORY_DATE_TIME: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
private fun memoryEntryText(e: MemoryEntry): String {
    val zone = remember { java.time.ZoneId.systemDefault() }
    val saved = MEMORY_DATE.format(java.time.Instant.ofEpochMilli(e.validFrom).atZone(zone))
    val whenText = e.occurredAt?.takeIf { e.type == MemoryType.EVENT }
        ?.let { MEMORY_DATE_TIME.format(java.time.Instant.ofEpochMilli(it).atZone(zone)) + " · " }.orEmpty()
    val flags = (if (e.conflict) " · " + stringResource(R.string.memory_conflict) else "") +
        (if (e.status != MemoryStatus.ACTIVE) " · " + e.status.wire else "")
    return "${e.type.wire} · ${e.subject}: ${e.body} ($whenText$saved)$flags"
}

/** App-rendered memory cards: never model text and never part of model context. */
@Composable
private fun MemoryCardView(turnId: Long, card: MemoryCard, actions: MemoryActions) {
    val cancel = stringResource(R.string.assistant_cancel)
    Surface(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("assistant_memory_card:$turnId"),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (card) {
                is MemoryCard.Save -> MemorySaveCard(turnId, card, actions)
                is MemoryCard.Pick -> {
                    card.state.cards.forEachIndexed { i, e ->
                        TextButton(onClick = { actions.pick(turnId, i + 1) }, modifier = Modifier.heightIn(min = 48.dp).testTag("assistant_memory_pick:$turnId:$i")) {
                            Text("${i + 1}. " + memoryEntryText(e), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = { actions.cancel(turnId) }, modifier = Modifier.heightIn(min = 48.dp).testTag("assistant_memory_cancel:$turnId")) { Text(cancel) }
                }
                is MemoryCard.Confirm -> {
                    Text(memoryEntryText(card.state.card), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { actions.confirm(turnId) }, modifier = Modifier.heightIn(min = 48.dp).testTag("assistant_memory_confirm:$turnId")) {
                            Text(stringResource(R.string.memory_erase_confirm))
                        }
                        TextButton(onClick = { actions.cancel(turnId) }, modifier = Modifier.heightIn(min = 48.dp).testTag("assistant_memory_cancel:$turnId")) { Text(cancel) }
                    }
                }
                is MemoryCard.Entries -> card.entries.forEachIndexed { i, e ->
                    Text("${i + 1}. " + memoryEntryText(e), Modifier.testTag("assistant_memory_entry:$turnId:$i"), style = MaterialTheme.typography.bodySmall)
                }
                is MemoryCard.Note -> Unit // the turn's response text carries the note
            }
        }
    }
}

@Composable
private fun MemorySaveCard(turnId: Long, card: MemoryCard.Save, actions: MemoryActions) {
    var type by remember(turnId, card.draft.type) { mutableStateOf(card.draft.type) }
    var subject by remember(turnId, card.draft.subject) { mutableStateOf(card.draft.subject) }
    var body by remember(turnId, card.draft.body) { mutableStateOf(card.draft.body) }
    var typeMenu by remember { mutableStateOf(false) }
    val draft = MemoryDraft(type, subject, body, card.draft.occurredAt)
    val zone = remember { java.time.ZoneId.systemDefault() }
    val subjectLabel = stringResource(R.string.memory_field_subject)
    val textLabel = stringResource(R.string.memory_field_text)
    Box {
        TextButton(onClick = { typeMenu = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("assistant_memory_type:$turnId")) {
            Text(stringResource(R.string.memory_field_type, type.wire))
        }
        DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
            MemoryType.entries.forEach { t -> DropdownMenuItem(text = { Text(t.wire) }, onClick = { type = t; typeMenu = false; if (draft.type != t) actions.edit(turnId, draft.copy(type = t)) }) }
        }
    }
    OutlinedTextField(subject, { subject = it }, label = { Text(subjectLabel) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("assistant_memory_subject:$turnId").semantics { contentDescription = subjectLabel })
    OutlinedTextField(body, { body = it }, label = { Text(textLabel) }, maxLines = 5,
        modifier = Modifier.fillMaxWidth().testTag("assistant_memory_text:$turnId").semantics { contentDescription = textLabel })
    card.draft.occurredAt?.takeIf { type == MemoryType.EVENT }?.let {
        Text(stringResource(R.string.memory_field_when, MEMORY_DATE_TIME.format(java.time.Instant.ofEpochMilli(it).atZone(zone))), style = MaterialTheme.typography.bodySmall)
    }
    if (card.candidates.isNotEmpty()) {
        Text(stringResource(R.string.memory_replaces), style = MaterialTheme.typography.labelSmall)
        card.candidates.forEachIndexed { i, c ->
            val on = card.replaceId == c.entry.id
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = on, role = Role.Checkbox,
                onClick = { actions.toggleReplace(turnId, if (on) null else c.entry.id) }).testTag("assistant_memory_replace:$turnId:$i"),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = on, onCheckedChange = null)
                Text(memoryEntryText(c.entry), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (card.invalid) Text(stringResource(R.string.memory_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { actions.save(turnId, draft) }, modifier = Modifier.heightIn(min = 48.dp).testTag("assistant_memory_save:$turnId")) {
            Text(stringResource(R.string.memory_save))
        }
        TextButton(onClick = { actions.cancel(turnId) }, modifier = Modifier.heightIn(min = 48.dp).testTag("assistant_memory_cancel:$turnId")) {
            Text(stringResource(R.string.assistant_cancel))
        }
    }
}

@Composable
private fun MemorySettings() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<MemorySyncStatus?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var syncing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SyncOutcome?>(null) }
    LaunchedEffect(refresh) { status = MemoryRuntime.get(context).status() }
    val zone = remember { java.time.ZoneId.systemDefault() }
    val text = when (val s = status) {
        null, MemorySyncStatus.LocalOnly -> stringResource(R.string.memory_status_local_only)
        is MemorySyncStatus.Synced -> stringResource(R.string.memory_status_synced, MEMORY_DATE_TIME.format(java.time.Instant.ofEpochMilli(s.at).atZone(zone)))
        is MemorySyncStatus.Pending -> stringResource(R.string.memory_status_pending, s.count)
    }
    Text(text, Modifier.testTag("assistant_memory_status"), style = MaterialTheme.typography.bodySmall)
    Button(enabled = !syncing, modifier = Modifier.heightIn(min = 48.dp).testTag("assistant_memory_sync"), onClick = {
        syncing = true; result = null
        scope.launch {
            result = MemoryRuntime.get(context).syncLocalNow()
            syncing = false; refresh++
        }
    }) { Text(stringResource(if (syncing) R.string.memory_sync_running else R.string.memory_sync_now)) }
    result?.let {
        Text(stringResource(when (it) {
            SyncOutcome.Synced -> R.string.memory_sync_result_synced
            SyncOutcome.Failed -> R.string.memory_sync_result_failed
            SyncOutcome.LocalOnly -> R.string.memory_sync_result_local_only
        }), Modifier.testTag("assistant_memory_sync_result"), style = MaterialTheme.typography.bodySmall)
    }
    HelpSection(stringResource(R.string.memory_help_title), "assistant_memory_help_toggle", "assistant_memory_help") {
        Text(stringResource(R.string.memory_help_body), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.memory_sql_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.memory_sql_warning), style = MaterialTheme.typography.bodySmall)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, sql) in listOf(R.string.memory_sql_list to MemorySql.LIST, R.string.memory_sql_purge_one to MemorySql.PURGE_ONE,
                    R.string.memory_sql_purge_all to MemorySql.PURGE_ALL, R.string.memory_sql_tombstones to MemorySql.TOMBSTONES)) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
                    Text(sql, Modifier.horizontalScroll(rememberScrollState()), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, softWrap = false)
                }
            }
        }
    }
}

/** Collapsed-by-default toggle row; also used by magic/ keyboard help. */
@Composable
internal fun HelpSection(title: String, toggleTag: String, contentTag: String, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(toggleTag)
        .semantics { stateDescription = if (open) "expanded" else "collapsed" }) {
        Text((if (open) "▾ " else "▸ ") + title, Modifier.fillMaxWidth())
    }
    if (open) Column(Modifier.testTag(contentTag), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun HealthSettings() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val vault = remember(context) { HealthVault.create(context) }
    var saved by remember { mutableStateOf(runCatching { vault.tokenSaved() }.getOrDefault(false)) }
    var field by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(if (saved) context.getString(R.string.assistant_health_status_saved) else "") }
    var checking by remember { mutableStateOf(false) }
    val tokenDescription = stringResource(R.string.assistant_health_token)
    Text(stringResource(R.string.assistant_health_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.assistant_health_hint), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(value = field, onValueChange = { field = it.trim() }, singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        label = { Text(tokenDescription) },
        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().testTag("assistant_health_token").semantics { contentDescription = tokenDescription })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !checking && field.isNotEmpty(), modifier = Modifier.testTag("assistant_health_save"), onClick = {
            val token = field
            if (!vault.saveToken(token)) { status = context.getString(R.string.assistant_health_status_invalid); return@Button }
            field = ""; saved = true; checking = true
            MemoryRuntime.get(context).syncSoon() // notes queued before a token existed
            status = context.getString(R.string.assistant_health_status_saved)
            scope.launch {
                status = try {
                    val reply = HealthMcpClient().verify(token)
                    context.getString(R.string.assistant_health_status_connected, reply.optString("latest_metric_date").ifBlank { "—" })
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: HealthFailure.Unauthorized) { vault.wipeCache(); vault.markRejected(token); context.getString(R.string.assistant_health_status_rejected) }
                catch (_: Exception) { context.getString(R.string.assistant_health_status_unreachable) }
                checking = false
            }
        }) { Text(stringResource(R.string.assistant_health_save)) }
        TextButton(enabled = saved && !checking, modifier = Modifier.testTag("assistant_health_remove"), onClick = {
            vault.removeToken(); saved = false; field = ""; status = context.getString(R.string.assistant_health_status_removed)
        }) { Text(stringResource(R.string.assistant_health_remove)) }
    }
    if (status.isNotBlank()) Text(status, Modifier.testTag("assistant_health_token_status"), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun WebSourceCard(turnId: Long, index: Int, number: Int, source: WebSource, uriHandler: androidx.compose.ui.platform.UriHandler) {
    var expanded by remember(turnId, index) { mutableStateOf(false) }
    var titleOverflow by remember(source.title) { mutableStateOf(false) }
    var excerptOverflow by remember(source.content) { mutableStateOf(false) }
    val showMore = stringResource(R.string.assistant_source_show_more)
    val showLess = stringResource(R.string.assistant_source_show_less)
    val openSource = stringResource(R.string.assistant_source_open)
    val expandedLabel = stringResource(R.string.assistant_source_expanded)
    val collapsedLabel = stringResource(R.string.assistant_source_collapsed)
    val host = runCatching { URI(source.url).host.orEmpty() }.getOrDefault("")
    val date = formatPublishedDate(source.publishedDate)
    val metadata = listOf(host, date.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
    Surface(
        Modifier.fillMaxWidth().testTag("assistant_source_card:$turnId:$index"),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("[$number] ${source.title}", Modifier.testTag("assistant_source_title:$turnId:$index"),
                style = MaterialTheme.typography.titleSmall,
                maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                onTextLayout = { titleOverflow = it.hasVisualOverflow })
            if (metadata.isNotBlank()) Text(metadata, Modifier.testTag("assistant_source_metadata:$turnId:$index"),
                style = MaterialTheme.typography.bodySmall)
            Text(source.content, Modifier.testTag("assistant_source_excerpt:$turnId:$index"),
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                onTextLayout = { excerptOverflow = it.hasVisualOverflow })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (expanded || titleOverflow || excerptOverflow) {
                    val label = if (expanded) showLess else showMore
                    TextButton(onClick = { expanded = !expanded },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("assistant_source_expand:$turnId:$index")
                            .semantics { contentDescription = label; stateDescription = if (expanded) expandedLabel else collapsedLabel }) {
                        Text(label)
                    }
                }
                TextButton(onClick = { if (WebSource.safeUrl(source.url)) runCatching { uriHandler.openUri(source.url) } },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("assistant_source_open:$turnId:$index")
                        .semantics { contentDescription = openSource }) {
                    Text(openSource)
                }
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
    var picking by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<AssistantModel?>(null) }
    fun gb(model: AssistantModel) = String.format(java.util.Locale.getDefault(), "%.1f", model.bytes / 1e9)
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.assistant_title), style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.assistant_enable), Modifier.weight(1f))
            Switch(checked = assistant.enabled, onCheckedChange = assistant::enable, modifier = Modifier.testTag("assistant_enable"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(homeButtonDescription, Modifier.weight(1f))
            Switch(checked = assistant.homeButtonVisible, onCheckedChange = assistant::showHomeButton,
                modifier = Modifier.testTag("assistant_show_home_button").semantics { contentDescription = homeButtonDescription })
        }
        TextButton(enabled = !assistant.busy, onClick = { picking = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("assistant_model_picker")) {
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.assistant_model_title), style = MaterialTheme.typography.bodySmall)
                Text(assistant.selected.label, Modifier.testTag("assistant_model_${assistant.selected.name}"), style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(if (assistant.ready()) stringResource(R.string.assistant_ready) else "${gb(assistant.selected)} GB")
        if (!assistant.ready()) Button(enabled = !assistant.busy, onClick = { assistant.download(assistant.selected, "") }) {
            Text(stringResource(R.string.assistant_download))
        } else TextButton(enabled = !assistant.busy, onClick = { assistant.delete(assistant.selected) }) {
            Text(stringResource(R.string.assistant_delete))
        }
        if (assistant.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (assistant.canCancel) TextButton(onClick = assistant::cancel) { Text(stringResource(R.string.assistant_cancel)) }
        }
        Text(assistant.status, Modifier.testTag("assistant_download_status"))
        HorizontalDivider()
        HealthSettings()
        HorizontalDivider()
        Text(stringResource(R.string.memory_title), style = MaterialTheme.typography.titleMedium)
        MemorySettings()
        HorizontalDivider()
        HelpSection(stringResource(R.string.assistant_help_title), "assistant_help_toggle", "assistant_help") {
            Text(stringResource(R.string.assistant_privacy), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.assistant_show_home_button_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.assistant_chat_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.assistant_direct_actions), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (picking) AlertDialog(
        onDismissRequest = { picking = false },
        title = { Text(stringResource(R.string.assistant_model_picker_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                AssistantModel.entries.forEach { model ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("assistant_model_option_${model.name}")
                        .selectable(selected = model == assistant.selected, role = Role.RadioButton, onClick = {
                            picking = false
                            if (model != assistant.selected) pending = model
                        }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = model == assistant.selected, onClick = null)
                        Text(stringResource(R.string.assistant_model_option, model.label, gb(model), stringResource(model.note)),
                            Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.assistant_cancel)) } },
    )
    pending?.let { target ->
        val installed = assistant.files.hasAnyModel()
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.assistant_model_switch_title)) },
            text = {
                Text(if (installed) stringResource(R.string.assistant_model_switch_confirm, assistant.selected.label, target.label, gb(target))
                else stringResource(R.string.assistant_model_download_confirm, target.label, gb(target)))
            },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("assistant_model_confirm"), onClick = { pending = null; assistant.switchModel(target) }) {
                    Text(stringResource(if (installed) R.string.assistant_model_switch_action else R.string.assistant_model_download_action))
                }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.assistant_cancel)) } },
        )
    }
}
