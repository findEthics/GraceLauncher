package com.galaxyrio.gracelauncher.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.ai.edge.litertlm.*
import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One engine per visible Home; no service, transcript persistence, or cloud fallback. */
class AssistantController(private val context: Context, private val scope: CoroutineScope) {
    val files = ModelFiles(context)
    private val prefs = context.getSharedPreferences("offline_assistant", Context.MODE_PRIVATE)
    var enabled by mutableStateOf(prefs.getBoolean("enabled", false)); private set
    var selected by mutableStateOf(readModel()); private set
    var status by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var listening by mutableStateOf(false); private set
    var pendingAction by mutableStateOf<MobileAction?>(null); private set
    var needsSpeechSetup by mutableStateOf(false); private set
    private var recognizer: SpeechRecognizer? = null
    private var job: Job? = null
    private var generation = 0
    private var engine: Engine? = null
    private var loaded: AssistantModel? = null
    @Volatile private var conversation: Conversation? = null
    private val engineLock = Mutex()
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        enabled = prefs.getBoolean("enabled", false)
        val newModel = readModel()
        if (newModel != selected || !enabled) { cancel(); unload() }
        selected = newModel
    }
    init { prefs.registerOnSharedPreferenceChangeListener(listener) }
    private fun readModel() = AssistantModel.entries.firstOrNull { it.name == prefs.getString("model", null) } ?: AssistantModel.FunctionGemma
    fun enable(value: Boolean) { prefs.edit().putBoolean("enabled", value).apply() }
    fun select(model: AssistantModel) { cancel(); unload(); selected = model; prefs.edit().putString("model", model.name).apply() }
    fun ready(model: AssistantModel = selected) = files.ready(model)
    fun download(model: AssistantModel, token: String) {
        if (busy) return
        val id = ++generation
        busy = true
        job = scope.launch {
            try {
                files.download(model, token) { bytes -> scope.launch { if (id == generation) status = "Downloading ${bytes * 100 / model.bytes}%" } }
                status = "Model verified and ready"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = e.message?.takeIf { !it.contains("http") && !it.contains(token.takeIf { t -> t.isNotBlank() } ?: "\u0000") } ?: "Download failed; check access and storage" }
            finally { if (id == generation) busy = false }
        }
    }
    fun delete(model: AssistantModel) {
        if (busy) return
        cancel(); unload()
        busy = true
        job = scope.launch {
            try { engineLock.withLock { withContext(Dispatchers.IO) { files.delete(model) } }; status = "Model removed" }
            catch (_: Exception) { status = "Could not remove model" }
            finally { busy = false }
        }
    }
    fun startListening(): Boolean {
        if (!enabled || busy || listening) return false
        if (!ready()) { status = "Download the selected model in Advanced settings"; return false }
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            status = "On-device speech recognition is unavailable. No cloud fallback."; needsSpeechSetup = true; return false
        }
        needsSpeechSetup = false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return true
        val id = ++generation
        pendingAction = null
        captured = null
        listening = true; status = "Starting offline microphone"
        recognizer?.destroy()
        try {
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (id == generation) status = "Listening — release to process" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { if (id == generation) status = "Release to process" }
                override fun onError(error: Int) {
                    if (id != generation) return
                    listening = false; busy = false
                    recognizer?.destroy(); recognizer = null
                    status = "Offline speech failed ($error). Install an on-device language pack; no cloud fallback."
                }
                override fun onResults(results: Bundle?) {
                    if (id != generation) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    // A recognizer may finish before finger release. Keep its text only in memory.
                    captured = text
                    if (!listening) processCaptured(id)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            })
        }
        } catch (_: Exception) {
            recognizer?.destroy(); recognizer = null
            listening = false; busy = false
            status = "On-device speech could not start. No cloud fallback."
        }
        return false
    }
    private var captured: String? = null
    fun release() {
        if (!listening) return
        listening = false; busy = true; status = "Transcribing offline"
        if (captured != null) processCaptured(generation)
        else try { recognizer?.stopListening() } catch (_: Exception) {
            cancel(); status = "Offline speech could not finish"
        }
    }
    private fun processCaptured(id: Int) {
        val text = captured; captured = null
        recognizer?.destroy(); recognizer = null
        if (text.isNullOrBlank()) { busy = false; status = "No speech recognized"; return }
        job = scope.launch {
            try {
                status = "Processing locally"
                val action = infer(selected, text)
                if (id == generation) { pendingAction = action; status = "Ready: ${action.name}" }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (id == generation) status = "No valid action produced; nothing executed" }
            finally { if (id == generation) busy = false }
        }
    }
    @OptIn(ExperimentalApi::class)
    suspend fun infer(model: AssistantModel, text: String): MobileAction = engineLock.withLock {
        require(text.isNotBlank() && text.length <= 8000)
        require(files.ready(model)) { "Verified model is missing" }
        withContext(Dispatchers.IO) {
            if (loaded != model) {
                ExperimentalFlags.convertCamelToSnakeCaseInToolDescription = false
                engine?.close(); engine = null; loaded = null
                engine = startEngine(model); loaded = model
            }
            val current = engine!!.createConversation(ConversationConfig(
                tools = listOf(tool(MobileActionTools { })), automaticToolCalling = false,
                maxOutputToken = 256,
            ))
            conversation = current
            try {
                var calls: List<ToolCall> = emptyList()
                current.sendMessageAsync("Current local date and time: ${ZonedDateTime.now()}. User request: $text").collect { message ->
                    if (message.toolCalls.isNotEmpty()) calls = calls + message.toolCalls
                }
                ensureActive()
                require(calls.size == 1) { "Expected exactly one action" }
                MobileAction.fromToolCall(calls.single().name, calls.single().arguments).also { MobileActionExecutor.validate(it) }
            } finally { conversation = null; current.close() }
        }
    }
    // ponytail: Gemma 4 runs on GPU (smooth); the 270M model stays CPU. One-time CPU fallback if GPU init fails.
    private fun startEngine(model: AssistantModel): Engine {
        val backends = if (model == AssistantModel.Gemma4) listOf(Backend.GPU(), Backend.CPU()) else listOf(Backend.CPU())
        var last: Throwable? = null
        for (backend in backends) {
            val created = Engine(EngineConfig(modelPath = files.file(model).absolutePath, backend = backend, maxNumTokens = 1024, cacheDir = context.noBackupFilesDir.absolutePath))
            try { created.initialize(); return created }
            catch (e: Throwable) { runCatching { created.close() }; last = e }
        }
        throw last ?: IllegalStateException("No backend available")
    }
    // Opens the OS voice-input settings so the user can install an offline language pack; never downloads anything itself.
    fun openSpeechSettings() {
        val intent = Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = if (intent.resolveActivity(context.packageManager) != null) intent else fallback
        runCatching { context.startActivity(target) }
    }
    fun confirm(): Boolean {
        val action = pendingAction ?: return false
        if (action.needsCamera && context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return true
        pendingAction = null
        status = action.execute(context)
        return false
    }
    fun cancel() {
        ++generation
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
        captured = null
        runCatching { conversation?.cancelProcess() }
        job?.cancel(); job = null
        pendingAction = null; busy = false; listening = false; status = "Cancelled"
    }
    private fun unload() {
        scope.launch(Dispatchers.IO) { engineLock.withLock { engine?.close(); engine = null; loaded = null } }
    }
    fun close() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        cancel()
        // Independent cleanup outlives the composition's cancelled scope.
        CoroutineScope(Dispatchers.IO).launch { engineLock.withLock { engine?.close(); engine = null; loaded = null } }
    }
}
