package com.galaxyrio.gracelauncher.magic

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.galaxyrio.gracelauncher.R
import com.galaxyrio.gracelauncher.data.*
import com.galaxyrio.gracelauncher.platform.SelectedNote
import com.katoaapps.openminilaunch.features.calendar.engine.CalendarPhraseEngine
import com.katoaapps.openminilaunch.features.calendar.model.*
import com.katoaapps.openminilaunch.features.messaging.*
import com.katoaapps.openminilaunch.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

private data class MagicRow(val key: String, val label: String, val detail: String, val choose: () -> Unit)

@Composable
internal fun MagicCommandWindow(controller: MagicController, apps: List<LauncherApp>, onLaunchShortcut: (LauncherShortcut) -> Unit) {
    val session = controller.session ?: return
    key(session.id) { MagicCommandContent(controller, session, apps, onLaunchShortcut) }
}

@Composable
private fun MagicCommandContent(c: MagicController, s: MagicSession, apps: List<LauncherApp>, onLaunchShortcut: (LauncherShortcut) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val actions = remember(context) { MagicActions(context) }
    val prefs = remember(context) { MagicPreferences(context) }
    DisposableEffect(prefs) { onDispose { prefs.close() } }
    val contactsSource = remember(context, actions, c.dataSource) { c.dataSource ?: PlatformMagicDataSource(context, actions) }
    var contactsGranted by remember(contactsSource) { mutableStateOf(s.kind.usesContacts && contactsSource.hasContactAccess()) }
    var resumeVersion by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, contactsSource) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                contactsGranted = s.kind.usesContacts && contactsSource.hasContactAccess()
                resumeVersion++
                if (s.handedOff) { s.handedOff = false; s.consumed = false }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val strictProvider = if (s.kind == MagicKind.Message) strictContactProvider(prefs.provider) else null
    val providerInstalled = remember(contactsSource, strictProvider, resumeVersion) {
        strictProvider == null || contactsSource.installed(strictProvider)
    }
    val contactScope = MagicContactScope(if (s.kind == MagicKind.Message) prefs.provider else null, contactsGranted, providerInstalled)
    if (s.updateContactScope(contactScope)) c.submitPending = false
    // Remember state by scope so old rows disappear before a replacement coroutine starts.
    var contacts by remember(contactsSource, contactScope, resumeVersion) { mutableStateOf<List<SearchContact>?>(null) }
    LaunchedEffect(contactsSource, contactScope, resumeVersion) {
        if (!s.kind.usesContacts || !contactsGranted || !providerInstalled) contacts = emptyList()
        else contactsSource.contacts(strictProvider).collect { contacts = it }
    }
    var conversations by remember(contactsSource, contactScope, prefs.automatic, apps, resumeVersion) {
        mutableStateOf(emptyList<MagicConversation>())
    }
    LaunchedEffect(contactsSource, contactScope, prefs.automatic, apps, resumeVersion) {
        conversations = if (s.kind == MagicKind.Message && providerInstalled)
            contactsSource.conversations(apps, prefs.automatic, prefs.provider)
                .filter { strictProvider == null || it.target.packageName == strictProvider }
            else emptyList()
    }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var notes by remember(contactsSource, s.input.text, resumeVersion) { mutableStateOf(emptyList<SelectedNote>()) }
    LaunchedEffect(contactsSource, s.input.text, s.selectedNote, resumeVersion) {
        if (s.kind == MagicKind.Note && s.selectedNote == null) {
            val result = contactsSource.notes(s.input.text)
            notes = result.orEmpty()
            if (result == null) s.status = resources.getString(R.string.notes_capture_unavailable)
            else if (s.status == resources.getString(R.string.notes_capture_unavailable)) s.status = ""
        }
    }
    val focusManager = LocalFocusManager.current
    val inputModeManager = LocalInputModeManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var highlight by remember(contactScope) { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    var providerPicker by remember(contactScope) { mutableStateOf(false) }
    var callConfirmation by remember(contactScope) { mutableStateOf<CommunicationRecipient?>(null) }
    var pendingCall by remember(contactScope) { mutableStateOf<CommunicationRecipient?>(null) }
    var pendingSms by remember(contactScope) { mutableStateOf<MagicSmsRequest?>(null) }
    var smsConfirmation by remember(contactScope) { mutableStateOf<MagicSmsRequest?>(null) }
    var roleDisclosure by remember(contactScope) { mutableStateOf(false) }
    var calendarReview by remember { mutableStateOf<CalendarDraft?>(null) }
    fun active() = contactsSource.externalActionsAllowed && c.session === s && !s.consumed
    fun activeCall() = (contactsSource.externalActionsAllowed || c.fakeCall != null) && c.session === s && !s.consumed
    fun call(phone: String, chooser: Boolean = false) = if (contactsSource.externalActionsAllowed)
        actions.call(phone, chooser) else c.fakeCall?.invoke(phone, chooser) == true
    fun handoff(opened: Boolean) {
        s.handedOff = opened
        if (!opened) { s.consumed = false; s.status = resources.getString(R.string.magic_no_app) }
    }
    fun openProvider(pkg: String?) {
        val recipient = s.recipient ?: return
        if (!active() || !s.claim()) return
        providerPicker = false
        val result = actions.messaging.openPreferredMessageDraft(recipient, s.input.text, pkg,
            allowSmsFallback = strictProvider == null || pkg != strictProvider)
        handoff(result != PreferredMessageDraftResult.FAILED)
        s.status = resources.getString(when (result) {
            PreferredMessageDraftResult.OPENED -> R.string.magic_draft_retained
            PreferredMessageDraftResult.OPENED_WITH_RECIPIENT_PICKER -> R.string.magic_reselect_recipient
            PreferredMessageDraftResult.FALLBACK_OPENED -> R.string.magic_sms_fallback
            PreferredMessageDraftResult.FAILED -> R.string.magic_no_app
        })
    }
    fun validSms(request: MagicSmsRequest) = active() && prefs.automatic && prefs.provider == null && s.matchesSms(request) && request.confirmed
    fun sendDirect(request: MagicSmsRequest) {
        if (!validSms(request) || !s.claimSms(request)) return
        val draft = request.draft
        when (actions.sendSms(draft.recipient.address, draft.body)) {
            SmsOutcome.Queued -> { Toast.makeText(context, R.string.magic_sms_queued, Toast.LENGTH_LONG).show(); c.close() }
            SmsOutcome.Unknown -> s.status = resources.getString(R.string.magic_sms_uncertain)
            SmsOutcome.NoSim, SmsOutcome.Unavailable -> {
                val result = actions.messaging.openPreferredMessageDraft(draft.recipient, draft.body, null)
                handoff(result != PreferredMessageDraftResult.FAILED)
                s.status = resources.getString(R.string.magic_sms_fallback)
            }
        }
    }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val draft = pendingSms; pendingSms = null
        if (draft != null && validSms(draft)) {
            if (granted) sendDirect(draft) else { s.cancelSms(); s.status = resources.getString(R.string.magic_permission_denied) }
        }
    }
    val roleSelection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val draft = pendingSms; pendingSms = null
        if (draft != null && validSms(draft)) {
            if (!actions.assistantRoleHeld()) { s.cancelSms(); s.status = resources.getString(R.string.magic_role_not_selected) }
            else if (actions.hasPermission(Manifest.permission.SEND_SMS)) sendDirect(draft)
            else { pendingSms = draft; smsPermission.launch(Manifest.permission.SEND_SMS) }
        }
    }
    fun continueSms(request: MagicSmsRequest) {
        if (!validSms(request)) return
        if (!actions.assistantRoleHeld()) { pendingSms = request; roleDisclosure = true }
        else if (!actions.hasPermission(Manifest.permission.SEND_SMS)) { pendingSms = request; smsPermission.launch(Manifest.permission.SEND_SMS) }
        else sendDirect(request)
    }
    fun placeCall(recipient: CommunicationRecipient) {
        if (activeCall() && s.claim()) { if (call(recipient.address)) c.close() else handoff(false) }
    }
    val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val recipient = pendingCall; pendingCall = null
        if (active() && recipient != null) {
            if (granted) placeCall(recipient) else s.status = resources.getString(R.string.magic_permission_denied)
        }
    }
    val contactPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        contactsGranted = contactsSource.hasContactAccess()
        if (!it) s.status = resources.getString(R.string.magic_permission_denied)
    }
    val contactRows = contacts.orEmpty().flatMap { contact -> contact.dialNumbers.map { number ->
        ContactResult(contact.uri.toString(), contact.name, number, number)
    } }
    fun chooseRecipient(recipient: CommunicationRecipient, pkg: String? = null, inferred: Boolean = false) {
        if (s.kind == MagicKind.Call) {
            if (recipient.address.looksLikePhoneRecipient()) callConfirmation = recipient
            else if (active() && s.claim()) { handoff(actions.call(recipient.address, chooser = true)) }
        } else { s.choose(recipient, pkg, inferred); highlight = 0; focus.requestFocus() }
    }
    fun chooseConversation(conversation: MagicConversation) {
        if (!contactsSource.conversationEligible(conversation)) { s.status = resources.getString(R.string.magic_conversation_unavailable); return }
        when (val choice = resolveRecentConversationSelection(conversation.target, contactsGranted && strictProvider == null &&
            conversation.target.userSerial == actions.currentUserSerial,
            canDraftToShortcut = { contactsSource.externalActionsAllowed && it.userSerial == actions.currentUserSerial &&
                actions.messaging.canDraftToConversationShortcut(it.packageName, it.shortcutId, conversation.categories) },
            canAddressDraft = actions.messaging::canAddressConversationDraft,
            searchContacts = { contactRows.filter { row -> row.name.equals(it, ignoreCase = true) } })) {
            is RecentConversationSelection.ShortcutDraft -> {
                val body = s.body
                s.conversation = conversation
                s.input = TextFieldValue(body, TextRange(body.length))
                focus.requestFocus()
            }
            is RecentConversationSelection.DraftRecipient -> chooseRecipient(choice.contact.toCommunicationRecipient(), choice.packageName, inferred = true)
            is RecentConversationSelection.DirectOpen -> { if (active()) { onLaunchShortcut(conversation.shortcut); c.close() } }
        }
    }
    val conversationRows = if (s.kind == MagicKind.Message)
        prioritizeMagicPackages(conversations.filter { it.target.label.contains(s.query, ignoreCase = true) },
            contactsSource.effectivePreferredPackage(prefs.provider)) { it.target.packageName }.take(8).map { conversation ->
            MagicRow(conversation.target.selectionKey, conversation.target.label, actions.appLabel(conversation.target.packageName)) { chooseConversation(conversation) }
        } else emptyList()
    val rows = buildList {
        if (s.kind == MagicKind.Note && s.selectedNote == null) {
            notes.filter { it.title.contains(s.input.text, ignoreCase = true) }.forEach { note ->
                add(MagicRow("note:${note.id}", note.title, "") { s.chooseNote(note); focus.requestFocus() })
            }
        }
        if (s.kind.usesContacts && s.recipient == null && s.conversation == null) {
            addAll(conversationRows)
            val query = SearchQuery(s.query, fuzzy = false)
            contacts.orEmpty().filter { s.query.isBlank() || it.score(query) != null }.take(5).forEach { contact ->
                contact.dialNumbers.forEach { number ->
                    add(MagicRow("${contact.id}:$number", contact.name, number) { chooseRecipient(CommunicationRecipient(number, contact.name, number), strictProvider) })
                }
            }
            if (s.query.isNotBlank() && allowRawMagicRecipient(s.kind, prefs.provider)) add(MagicRow("raw", s.query, resources.getString(R.string.magic_raw_recipient)) { chooseRecipient(userEnteredRecipient(s.query)) })
        }
    }
    val eventTitle = stringResource(R.string.magic_event)
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    val parsed = remember(s.input.text, language) {
        CalendarPhraseEngine.parse(CalendarParseRequest(s.input.text, eventTitle, language))
    }
    val alarm = if (s.kind == MagicKind.Alarm) MagicAlarmCommand.parse(s.input.text, language) else null
    fun openCalendar(draft: CalendarDraft) { if (active() && s.claim()) handoff(actions.calendar(draft)) }
    val selectedIndex = if (s.kind == MagicKind.Note) s.selectedIndex else highlight
    val submit = {
        if (c.session === s && !s.consumed) when {
            s.kind == MagicKind.Alarm -> {
                val command = MagicAlarmCommand.parse(s.input.text, language)
                if (command == null) s.status = resources.getString(R.string.magic_alarm_status_invalid)
                else if (active() && s.claim()) {
                    if (actions.alarm(command)) {
                        Toast.makeText(context, if (command is MagicAlarmCommand.Alarm) R.string.magic_alarm_set else R.string.magic_timer_started, Toast.LENGTH_SHORT).show()
                        c.close()
                    } else handoff(false)
                }
            }
            s.kind == MagicKind.Todo || s.kind == MagicKind.Note -> {
                val selectedRow = if (s.kind == MagicKind.Note && s.selectedNote == null) rows.getOrNull(s.selectedIndex) else null
                if (selectedRow != null) selectedRow.choose()
                else if (s.input.text.isNotBlank() && s.claim()) {
                    val text = s.input.text
                    val noteId = s.selectedNote?.id
                    s.status = resources.getString(R.string.magic_saving)
                    scope.launch {
                        val saved = contactsSource.saveNoteOrTodo(text, s.kind == MagicKind.Todo, noteId)
                        if (c.session === s) {
                            if (saved) {
                                val message = resources.getString(when {
                                    s.kind == MagicKind.Todo -> R.string.magic_todo_added
                                    noteId == null -> R.string.magic_note_added
                                    else -> R.string.magic_note_updated
                                })
                                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
                                contactsSource.onCaptureToast(message)
                                c.close()
                            }
                            else { s.consumed = false; s.status = resources.getString(R.string.magic_capture_failed) }
                        }
                    }
                }
            }
            s.kind == MagicKind.Event -> {
                if (s.input.text.isNotBlank()) {
                    if (parsed is CalendarParseResult.NeedsReview || parsed is CalendarParseResult.Invalid) calendarReview = parsed.draft
                    else openCalendar(parsed.draft)
                }
            }
            s.conversation != null -> {
                val conversation = s.conversation!!
                if (active() && s.input.text.isNotBlank() && contactsSource.conversationEligible(conversation) && s.claim()) {
                    val opened = actions.messaging.openConversationShortcutDraft(conversation.target.packageName,
                        conversation.target.shortcutId, conversation.categories, s.input.text)
                    if (opened) { handoff(true); s.status = resources.getString(R.string.magic_draft_retained) }
                    else { onLaunchShortcut(conversation.shortcut); s.handedOff = true; s.status = resources.getString(R.string.magic_draft_not_transferred) }
                }
            }
            s.recipient == null -> {
                if (contacts == null) c.submitPending = true
                else rows.getOrNull(highlight.coerceIn(0, (rows.size - 1).coerceAtLeast(0)))?.choose?.invoke()
            }
            s.input.text.isNotBlank() && active() -> {
                val draft = MessageDraft(s.recipient!!, s.input.text, s.providerPackage)
                when (resolvedMessageSendRoute(
                    recipient = draft.recipient,
                    selectedConversationPackage = s.providerPackage,
                    defaultSmsPackage = actions.messaging.defaultMessagingPackage(),
                    sendAutomatically = prefs.automatic,
                    preferredPackage = prefs.provider,
                    fallbackRoute = messagingSendRoute(prefs.automatic, prefs.provider),
                )) {
                    MessagingSendRoute.PROVIDER_PICKER -> providerPicker = true
                    MessagingSendRoute.PREFERRED_DRAFT -> openProvider(s.providerPackage ?: prefs.provider)
                    MessagingSendRoute.DIRECT_SMS -> {
                        if (pendingSms == null && smsConfirmation == null) s.requestSms()?.let { request ->
                            if (request.inferred) smsConfirmation = request else continueSms(request)
                        }
                    }
                }
            }
        }
        Unit
    }
    SideEffect { c.onSubmit = submit }
    LaunchedEffect(contacts, s.input.text) { if (c.submitPending && contacts != null) { c.submitPending = false; submit() } }
    LaunchedEffect(s.query) { highlight = 0 }
    LaunchedEffect(selectedIndex, rows.map { it.key }) { if (selectedIndex in rows.indices) list.animateScrollToItem(selectedIndex) }
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    BackHandler { c.close() }
    val recipientSearch = s.searchingForRecipient
    val inputDescription = stringResource(when (s.kind) {
        MagicKind.Todo -> R.string.magic_todo_input
        MagicKind.Note -> if (s.selectedNote == null) R.string.magic_note_input else R.string.magic_note_append
        MagicKind.Event -> R.string.magic_event_input
        MagicKind.Alarm -> R.string.magic_alarm_input
        else -> if (s.recipient != null || s.conversation != null) R.string.magic_message else R.string.magic_recipient
    })

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = .45f)).testTag("magic_window")
        .onPreviewKeyEvent { event ->
            // Multiline text fields otherwise insert Tab instead of moving focus.
            if (event.key != Key.Tab) false else {
                if (event.type == KeyEventType.KeyDown) {
                    // This preview handler runs before Compose's default keyboard traversal.
                    // Buttons use SystemDefined focusability and are skipped in touch mode.
                    inputModeManager.requestInputMode(InputMode.Keyboard)
                    focusManager.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                }
                true
            }
        }) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()
            .padding(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
        // Keep a result row and the action visible above a tall IME; multiline drafts still scroll.
        val editorLines = if (maxHeight < 240.dp) 1 else 5
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("magic_results"), state = list,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
                itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                    Surface(shape = RoundedCornerShape(20.dp), color = if (index == selectedIndex) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth().semantics { selected = index == selectedIndex }) {
                        TextButton(onClick = row.choose, enabled = !s.consumed, modifier = Modifier.fillMaxWidth().testTag("magic_result:$index")) {
                            Column(Modifier.fillMaxWidth()) { Text(row.label); if (row.detail.isNotBlank()) Text(row.detail, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            val inputFocused = remember { mutableStateOf(false) }
            val focusedBorder = MaterialTheme.colorScheme.primary
            val unfocusedBorder = MaterialTheme.colorScheme.outlineVariant
            val hasAuxiliaryHeader = s.selectedNote != null || s.recipient != null || s.conversation != null ||
                (s.kind.usesContacts && !contactsGranted && s.recipient == null && s.conversation == null) ||
                (s.kind == MagicKind.Event && s.input.text.isNotBlank()) || alarm != null || s.status.isNotBlank()
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                border = null,
                modifier = Modifier.clip(RoundedCornerShape(28.dp)).drawWithContent {
                    drawContent()
                    // Observe focus only while drawing so dialog focus changes cannot recompose confirmation state.
                    val focused = inputFocused.value
                    val strokePx = (if (focused) 2.dp else 1.dp).toPx()
                    val inset = strokePx / 2f
                    drawRoundRect(
                        color = if (focused) focusedBorder else unfocusedBorder,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - strokePx, size.height - strokePx),
                        cornerRadius = CornerRadius(28.dp.toPx() - inset),
                        style = Stroke(width = strokePx),
                    )
                }) {
                Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (hasAuxiliaryHeader) {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (s.selectedNote != null || s.recipient != null || s.conversation != null) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text((s.selectedNote?.title ?: s.recipient?.displayName ?: s.conversation?.target?.label).orEmpty(),
                                        Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (s.selectedNote != null) TextButton(onClick = { s.chooseNote(null); focus.requestFocus() }, enabled = !s.consumed) {
                                        Text(stringResource(R.string.magic_clear))
                                    }
                                }
                            }
                            s.recipient?.let { Text(it.address, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("magic_recipient_number")) }
                            if (s.kind.usesContacts && !contactsGranted && s.recipient == null && s.conversation == null)
                                TextButton(onClick = { if (contactsSource.externalActionsAllowed) contactPermission.launch(Manifest.permission.READ_CONTACTS) }) { Text(stringResource(R.string.magic_grant_contacts)) }
                            if (s.kind == MagicKind.Event && s.input.text.isNotBlank()) {
                                Text(parsed.draft.title, style = MaterialTheme.typography.bodyMedium)
                                val start = parsed.draft.startMillis
                                if (start != null) Text(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault())), style = MaterialTheme.typography.bodySmall)
                                if (parsed is CalendarParseResult.NeedsReview || parsed is CalendarParseResult.Invalid || parsed.draft.warnings.isNotEmpty())
                                    Text(stringResource(R.string.magic_calendar_review), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            if (alarm != null) Text(when (alarm) {
                                is MagicAlarmCommand.Alarm -> stringResource(R.string.magic_alarm_preview, alarm.hour, alarm.minute)
                                is MagicAlarmCommand.Timer -> stringResource(R.string.magic_timer_preview, alarm.seconds / 60, alarm.seconds % 60)
                            }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("magic_alarm_preview"))
                            if (s.status.isNotBlank()) Text(s.status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("magic_status"))
                        }
                    }
                    MagicEditorInput(recipientSearch) {
                        OutlinedTextField(value = s.input, onValueChange = { if (!s.consumed) s.input = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged {
                                inputFocused.value = it.isFocused
                                if (it.isFocused) c.focused()
                            }
                                .semantics {
                                    contentDescription = inputDescription
                                    // Compose 1.12.1 excludes this virtual editor from autofill, preserving accessibility.
                                    if (recipientSearch) contentDataType = ContentDataType.None
                                }.testTag("magic_input")
                                .onPreviewKeyEvent { event ->
                                    when (event.key) {
                                        Key.Escape -> { if (event.type == KeyEventType.KeyDown) c.close(); true }
                                        Key.DirectionDown, Key.DirectionUp -> if (rows.isEmpty()) false else {
                                             if (event.type == KeyEventType.KeyDown) {
                                                 val next = (selectedIndex + if (event.key == Key.DirectionDown) 1 else -1).coerceIn(0, rows.lastIndex)
                                                 if (s.kind == MagicKind.Note) s.selectedIndex = next else highlight = next
                                             }
                                            true
                                        }
                                        Key.Enter, Key.NumPadEnter -> {
                                            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                                                 if (event.isShiftPressed && s.kind != MagicKind.Call) { if (!s.consumed) s.append("\n") } else submit()
                                            }
                                            true
                                        }
                                        else -> false
                                    }
                                }, shape = RoundedCornerShape(28.dp), maxLines = editorLines,
                              colors = OutlinedTextFieldDefaults.colors(
                                  focusedBorderColor = Color.Transparent,
                                  unfocusedBorderColor = Color.Transparent,
                                  disabledBorderColor = Color.Transparent,
                                  errorBorderColor = Color.Transparent,
                              ),
                             prefix = { Text(s.kind.prefix.toString()) },
                             placeholder = if (s.kind == MagicKind.Todo || s.kind == MagicKind.Note) ({ Text(inputDescription, maxLines = 1, overflow = TextOverflow.Ellipsis) }) else null,
                             trailingIcon = {
                                 TextButton(onClick = submit, enabled = !s.consumed && (s.input.text.isNotBlank() || (s.kind == MagicKind.Note && selectedIndex in rows.indices)), modifier = Modifier.testTag("magic_submit")) {
                                     Text(stringResource(when {
                                         s.kind == MagicKind.Todo -> R.string.magic_save
                                         s.kind == MagicKind.Note -> if (s.selectedNote == null && selectedIndex in rows.indices) R.string.magic_select else R.string.magic_save
                                         s.searchingForRecipient -> R.string.magic_select
                                         else -> R.string.magic_continue
                                     }), maxLines = 1)
                                 }
                             },
                             keyboardOptions = magicKeyboardOptions(recipientSearch), keyboardActions = KeyboardActions(onSend = { submit() }))
                    }
                }
            }
        }
        }
    }
    if (providerPicker) MagicProviderDialog(actions, onDismiss = { providerPicker = false }, onChoose = { openProvider(it.preferencePackageName) })
    callConfirmation?.let { recipient ->
        val callFocus = remember(recipient) { FocusRequester() }
        var callKeyDown by remember(recipient) { mutableStateOf<Key?>(null) }
        fun dismissCall() { callKeyDown = null; callConfirmation = null }
        fun confirmCall() {
            dismissCall()
            if (activeCall()) {
                if (if (contactsSource.externalActionsAllowed) actions.hasPermission(Manifest.permission.CALL_PHONE) else c.fakeCallPermission) placeCall(recipient)
                else if (contactsSource.externalActionsAllowed) { pendingCall = recipient; callPermission.launch(Manifest.permission.CALL_PHONE) }
            }
        }
        LaunchedEffect(recipient) { inputModeManager.requestInputMode(InputMode.Keyboard); callFocus.requestFocus() }
        AlertDialog(onDismissRequest = ::dismissCall, title = {
            val view = LocalView.current
            DisposableEffect(view) {
                 val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
                    if (!focused) callKeyDown = null
                    else {
                        // A newly shown dialog can gain window focus after the initial focus request.
                        inputModeManager.requestInputMode(InputMode.Keyboard)
                        callFocus.requestFocus()
                    }
                }
                view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
                onDispose { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
            }
            Text(stringResource(R.string.magic_confirm_call))
        },
            text = { Text("${recipient.displayName}\n${recipient.address}") },
            confirmButton = { TextButton(onClick = ::confirmCall, modifier = Modifier.onPreviewKeyEvent { event ->
                 if (event.key != Key.Enter && event.key != Key.NumPadEnter) false else {
                     if (event.nativeKeyEvent.isCanceled) callKeyDown = null
                     // Only this button's fresh press can confirm, never the opening key's repeat/release.
                     else if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) callKeyDown = event.key
                     if (event.type == KeyEventType.KeyUp) {
                         if (!event.nativeKeyEvent.isCanceled && callKeyDown == event.key) confirmCall()
                         callKeyDown = null
                    }
                    true
                }
            }.onFocusChanged { if (!it.isFocused) callKeyDown = null }.focusRequester(callFocus).testTag("magic_confirm_call")) { Text(stringResource(R.string.magic_call)) } },
            dismissButton = { Row { TextButton(onClick = { dismissCall(); if (activeCall() && s.claim()) handoff(call(recipient.address, chooser = true)) }) { Text(stringResource(R.string.magic_call_app)) }
                TextButton(onClick = ::dismissCall) { Text(stringResource(R.string.magic_cancel)) } } })
    }
    smsConfirmation?.let { request ->
        AlertDialog(onDismissRequest = { smsConfirmation = null; s.cancelSms() },
            title = { Text(stringResource(R.string.magic_confirm_sms)) },
            text = { Text("${request.draft.recipient.displayName}\n${request.draft.recipient.address}\n\n${request.draft.body}") },
            confirmButton = { TextButton(onClick = {
                if (smsConfirmation === request) {
                    smsConfirmation = null
                    if (active() && s.confirmSms(request)) continueSms(request)
                }
            }, modifier = Modifier.testTag("magic_confirm_sms")) { Text(stringResource(R.string.magic_continue)) } },
            dismissButton = { TextButton(onClick = { smsConfirmation = null; s.cancelSms() }) { Text(stringResource(R.string.magic_cancel)) } })
    }
    if (roleDisclosure) AlertDialog(onDismissRequest = { roleDisclosure = false; pendingSms = null; s.cancelSms() },
        title = { Text(stringResource(R.string.magic_direct_sms)) }, text = { Text(stringResource(R.string.magic_role_disclosure)) },
        confirmButton = { TextButton(onClick = {
            if (roleDisclosure) {
                roleDisclosure = false
                pendingSms?.let { if (validSms(it)) roleSelection.launch(actions.assistantRoleIntent()) else pendingSms = null }
            }
        }) { Text(stringResource(R.string.magic_continue)) } },
        dismissButton = { TextButton(onClick = { roleDisclosure = false; pendingSms = null; s.cancelSms(); providerPicker = true }) { Text(stringResource(R.string.magic_choose_provider)) } })
    calendarReview?.let { draft ->
        AlertDialog(onDismissRequest = { calendarReview = null }, title = { Text(stringResource(R.string.magic_calendar_review)) },
            text = { Text(stringResource(R.string.magic_calendar_review_detail)) },
            confirmButton = { TextButton(onClick = { calendarReview = null; openCalendar(draft.copy(startMillis = null, endMillis = null, allDay = false)) }) { Text(stringResource(R.string.magic_without_time)) } },
            dismissButton = { TextButton(onClick = { calendarReview = null; focus.requestFocus() }) { Text(stringResource(R.string.magic_edit)) } })
    }
}

@Composable
private fun MagicProviderDialog(actions: MagicActions, onDismiss: () -> Unit, onChoose: (MessagingProviderOption) -> Unit) {
    val providers = remember(actions) { actions.messaging.providerOptions() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.magic_choose_provider)) },
        text = { LazyColumn(Modifier.heightIn(max = 420.dp)) {
            itemsIndexed(providers, key = { _, provider -> provider.id }) { _, provider ->
                TextButton(onClick = { onChoose(provider) }, enabled = provider.installed, modifier = Modifier.fillMaxWidth().testTag("magic_provider:${provider.id}")) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(provider.label)
                        Text(stringResource(when {
                            !provider.installed -> R.string.magic_not_installed
                            provider.betaCompatibility || provider.supportTier == MessagingSupportTier.CONDITIONAL -> R.string.magic_beta_route
                            provider.supportTier == MessagingSupportTier.RECIPIENT_IN_APP -> R.string.magic_recipient_in_app
                            else -> R.string.magic_contact_and_draft
                        }), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.magic_cancel)) } })
}

@Composable
internal fun MagicAdvancedSettings() {
    val context = LocalContext.current
    val prefs = remember(context) { MagicPreferences(context) }
    val actions = remember(context) { MagicActions(context) }
    DisposableEffect(prefs) { onDispose { prefs.close() } }
    var settings by remember { mutableStateOf(false) }
    Text(stringResource(R.string.magic_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.magic_help), style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = { settings = true }) { Text(stringResource(R.string.magic_settings)) }
    if (settings) MagicSettingsDialog(prefs, actions) { settings = false }
}

@Composable
internal fun MagicSettingsDialog(prefs: MagicPreferences, actions: MagicActions, onDismiss: () -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.magic_settings)) }, text = {
        Column {
            Text(stringResource(R.string.magic_automatic_hint), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.magic_automatic), Modifier.weight(1f))
                Switch(checked = prefs.automatic, onCheckedChange = prefs::automatic, modifier = Modifier.testTag("magic_automatic"))
            }
            TextButton(onClick = { choosing = true }) { Text(prefs.provider?.let(actions::appLabel) ?: stringResource(R.string.system_messages)) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.magic_done)) } })
    if (choosing) MagicProviderDialog(actions, onDismiss = { choosing = false }, onChoose = { prefs.provider(it.preferencePackageName); choosing = false })
}
