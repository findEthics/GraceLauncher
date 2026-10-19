package com.galaxyrio.gracelauncher.ui.overlays

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.galaxyrio.gracelauncher.R
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.notifications.AppNotification
import com.galaxyrio.gracelauncher.data.notifications.NotificationReplyResult
import com.galaxyrio.gracelauncher.ui.components.AppIcon
import com.galaxyrio.gracelauncher.ui.components.SwipeDismissContainer
import com.galaxyrio.gracelauncher.ui.components.notificationAge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun NotificationPopupItem(
    app: LauncherApp,
    notification: AppNotification,
    onOpen: () -> Unit,
    onDismiss: () -> Boolean,
    onReply: (String, Long, String) -> NotificationReplyResult,
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val icon by produceState<ImageBitmap?>(null, notification.key, notification.icon) {
        value = withContext(Dispatchers.IO) {
            runCatching { notification.icon?.loadDrawable(context)?.toBitmap(120, 120)?.asImageBitmap() }.getOrNull()
        }
    }
    var replyRevision by remember(notification.key) { mutableStateOf<Long?>(null) }
    var draft by remember(notification.key) { mutableStateOf(TextFieldValue()) }
    var replyResult by remember(notification.key) { mutableStateOf<NotificationReplyResult?>(null) }
    var sending by remember(notification.key) { mutableStateOf(false) }
    val editingReply = replyRevision != null
    val staleReply = replyRevision != null && replyRevision != notification.revision
    val focusRequester = remember(notification.key) { FocusRequester() }
    val replyLabel = stringResource(R.string.notification_reply)
    val inputLabel = stringResource(R.string.notification_reply_input)
    val sendLabel = stringResource(R.string.notification_reply_send)
    val cancelLabel = stringResource(R.string.notification_reply_cancel)
    val resultToShow = if (staleReply) NotificationReplyResult.Unavailable else replyResult
    val error = when (resultToShow) {
        NotificationReplyResult.Unavailable -> stringResource(R.string.notification_unavailable)
        NotificationReplyResult.DeviceLocked -> stringResource(R.string.notification_reply_unlock)
        NotificationReplyResult.Failed -> stringResource(R.string.notification_reply_failed)
        else -> null
    }
    val closeReply: () -> Unit = {
        replyRevision = null
        draft = TextFieldValue()
        replyResult = null
        sending = false
        keyboard?.hide()
    }
    val sendReply = sendReply@{
        val targetRevision = replyRevision ?: return@sendReply
        if (!gesturesEnabled || draft.text.isBlank() || sending || staleReply) return@sendReply
        sending = true
        val result = onReply(notification.key, targetRevision, draft.text)
        sending = false
        replyResult = result
        if (result == NotificationReplyResult.Dispatched) closeReply()
    }
    LaunchedEffect(replyRevision, notification.revision) {
        if (replyRevision != null) focusRequester.requestFocus()
    }

    SwipeDismissContainer(
        itemKey = notification.key to notification.revision,
        onDismiss = onDismiss,
        modifier = modifier.fillMaxWidth().testTag("notification:${notification.key}"),
        enabled = notification.canDismiss && gesturesEnabled && !editingReply,
        consumeHorizontalDragsWhenDisabled = !editingReply,
    ) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)) {
            Column(
                Modifier.fillMaxWidth()
                    .clickable(enabled = notification.canOpen && gesturesEnabled && !editingReply, onClick = onOpen)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val image = icon
                    if (image != null) Image(image, null, Modifier.size(36.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    else AppIcon(app, size = 36.dp)
                    Spacer(Modifier.width(22.dp))
                    Text(
                        "${notification.title.ifBlank { app.label }} · ${notificationAge(notification.postedAt)}",
                        style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (notification.text.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(notification.text, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (notification.canReply && !editingReply) {
                TextButton(
                    enabled = gesturesEnabled,
                    onClick = {
                        draft = TextFieldValue()
                        replyResult = null
                        replyRevision = notification.revision
                    },
                    modifier = Modifier.align(Alignment.End).padding(end = 4.dp, bottom = 4.dp)
                        .testTag("notification_reply_button:${notification.key}")
                        .semantics { contentDescription = replyLabel },
                ) { Text(replyLabel) }
            }
            if (editingReply) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { if (gesturesEnabled) { draft = it; replyResult = null } },
                        enabled = gesturesEnabled,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                            .onPreviewKeyEvent { event ->
                                if (!gesturesEnabled || (event.key != Key.Enter && event.key != Key.NumPadEnter)) false
                                else {
                                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                                        if (event.isShiftPressed) {
                                            val value = draft
                                            val start = value.selection.min
                                            val end = value.selection.max
                                            draft = TextFieldValue(
                                                value.text.replaceRange(start, end, "\n"),
                                                TextRange(start + 1),
                                            )
                                            replyResult = null
                                        } else sendReply()
                                    }
                                    true
                                }
                            }
                            .testTag("notification_reply_input:${notification.key}")
                            .semantics { contentDescription = inputLabel },
                        label = { Text(inputLabel) },
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendReply() }),
                    )
                    if (error != null) {
                        Text(error, Modifier.fillMaxWidth().testTag("notification_reply_error:${notification.key}")
                            .padding(top = 4.dp), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = closeReply, enabled = gesturesEnabled,
                            modifier = Modifier.testTag("notification_reply_cancel:${notification.key}")
                                .semantics { contentDescription = cancelLabel }) {
                            Text(cancelLabel)
                        }
                        Button(
                            onClick = sendReply,
                            enabled = gesturesEnabled && draft.text.isNotBlank() && !sending && !staleReply,
                            modifier = Modifier.testTag("notification_reply_send:${notification.key}")
                                .semantics { contentDescription = sendLabel },
                        ) { Text(sendLabel) }
                    }
                }
            }
        }
    }
}
