package com.galaxyrio.gracelauncher.assistant

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.galaxyrio.gracelauncher.R

@Composable
private fun rememberAssistant(): AssistantController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(context) { AssistantController(context, scope) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) controller.cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.close() }
    }
    return controller
}

@Composable
fun AssistantHome(modifier: Modifier = Modifier) {
    val assistant = rememberAssistant()
    if (!assistant.enabled) return
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* User holds again after permission. */ }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* User confirms again after permission. */ }
    val description = stringResource(R.string.assistant_hold)
    Column(modifier, horizontalAlignment = Alignment.End) {
        if (assistant.status.isNotBlank()) Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
            Text(assistant.status, Modifier.widthIn(max = 250.dp).padding(12.dp).testTag("assistant_status"), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.size(56.dp).testTag("assistant_microphone")
                .semantics {
                    contentDescription = description
                    role = Role.Button
                    onClick(description) {
                        if (assistant.listening) assistant.release()
                        else if (assistant.startListening()) microphone.launch(Manifest.permission.RECORD_AUDIO)
                        true
                    }
                    customActions = listOf(CustomAccessibilityAction("Cancel") { assistant.cancel(); true })
                }
                .pointerInput(assistant) {
                    detectTapGestures(onPress = {
                        if (assistant.startListening()) microphone.launch(Manifest.permission.RECORD_AUDIO)
                        if (tryAwaitRelease()) assistant.release() else assistant.cancel()
                    })
                }, contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_assistant_mic), null, Modifier.size(26.dp))
            }
        }
        if (assistant.busy || assistant.listening) TextButton(onClick = { assistant.cancel() }) { Text(stringResource(R.string.assistant_cancel)) }
    }
    assistant.pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { assistant.cancel() },
            title = { Text(stringResource(R.string.assistant_confirm)) },
            text = { Text(action.name + "\n" + action.arguments.entries.joinToString("\n") { "${it.key}: ${it.value}" }) },
            confirmButton = { TextButton(onClick = { if (assistant.confirm()) camera.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.assistant_continue)) } },
            dismissButton = { TextButton(onClick = { assistant.cancel() }) { Text(stringResource(R.string.assistant_cancel)) } },
        )
    }
}

@Composable
fun AssistantSettings(modifier: Modifier = Modifier) {
    val assistant = rememberAssistant()
    var token by remember { mutableStateOf("") }
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.assistant_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.assistant_privacy), style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.assistant_enable), Modifier.weight(1f))
            Switch(checked = assistant.enabled, onCheckedChange = { assistant.enable(it) }, modifier = Modifier.testTag("assistant_enable"))
        }
        AssistantModel.entries.forEach { model ->
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = model == assistant.selected, enabled = !assistant.busy, onClick = { assistant.select(model); token = "" }, modifier = Modifier.testTag("assistant_model_${model.name}"))
                Column {
                    Text(model.label)
                    Text(if (assistant.ready(model)) stringResource(R.string.assistant_ready) else "${model.bytes / 1_000_000} MB", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (model == assistant.selected && !assistant.ready(model)) {
                if (model.tokenRequired) OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text(stringResource(R.string.assistant_token)) },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("assistant_token"),
                )
                Button(enabled = !assistant.busy && (!model.tokenRequired || token.isNotBlank()), onClick = {
                    assistant.download(model, token); token = ""
                }) { Text(stringResource(R.string.assistant_download)) }
            }
            if (assistant.ready(model)) TextButton(enabled = !assistant.busy, onClick = { assistant.delete(model) }) { Text(stringResource(R.string.assistant_delete)) }
        }
        if (assistant.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = { assistant.cancel(); token = "" }) { Text(stringResource(R.string.assistant_cancel)) } }
        Text(assistant.status, Modifier.testTag("assistant_download_status"))
        Text(stringResource(R.string.assistant_actions), style = MaterialTheme.typography.bodySmall)
    }
}
