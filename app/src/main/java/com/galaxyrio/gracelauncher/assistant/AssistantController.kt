package com.galaxyrio.gracelauncher.assistant

import android.content.Context
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.ai.edge.litertlm.*
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One retained engine owner; chat text stays in RAM and inference never has a cloud fallback. */
class AssistantController(private val context: Context, private val scope: CoroutineScope) {
    val files = ModelFiles(context)
    val chat = AssistantChatState()
    private val prefs = context.getSharedPreferences("offline_assistant", Context.MODE_PRIVATE)
    var enabled by mutableStateOf(prefs.getBoolean("enabled", false)); private set
    var homeButtonVisible by mutableStateOf(prefs.getBoolean("show_home_button", true)); private set
    val selected = AssistantModel.Gemma4
    var status by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var permissionRequest by mutableStateOf<List<String>>(emptyList()); private set
    private var permissionTurn: Long? = null
    private var job: Job? = null
    private var activeTurn: Long? = null
    private val commands = AssistantCommandSession()
    private val generation get() = commands.generation
    val canCancel get() = !commands.committed
    internal var lastTokenCount = 0; private set
    private var engine: Engine? = null
    private var usingGpu = false
    private var loaded: AssistantModel? = null
    @Volatile private var conversation: Conversation? = null
    private val engineLock = Mutex()
    internal var homeShortcutEligible = false
    internal var composerFocused = false
    private var composerEverFocused = false
    private var shortcutKey: Int? = null
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        enabled = prefs.getBoolean("enabled", false)
        homeButtonVisible = prefs.getBoolean("show_home_button", true)
        if (!enabled) { cancel(); chat.clear(); unload() }
    }
    init {
        prefs.edit().putString("model", selected.name).apply()
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }
    fun enable(value: Boolean) { prefs.edit().putBoolean("enabled", value).apply() }
    fun showHomeButton(value: Boolean) { prefs.edit().putBoolean("show_home_button", value).apply() }
    fun ready(model: AssistantModel = selected) = files.ready(model)
    var downloading = false; private set
    fun download(model: AssistantModel, token: String) {
        if (busy) return
        require(model == selected)
        val id = commands.begin() ?: return
        busy = true
        downloading = true
        job = scope.launch {
            try {
                files.download(model, token) { bytes -> scope.launch { if (id == generation) status = "Downloading ${bytes * 100 / model.bytes}%" } }
                status = "Model verified and ready"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = e.message?.takeIf { !it.contains("http") && !it.contains(token.takeIf { t -> t.isNotBlank() } ?: "\u0000") } ?: "Download failed; check access and storage" }
            finally { downloading = false; if (id == generation) busy = false }
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

    fun openChat(actionShortcut: Boolean = false) {
        if (enabled) {
            if (!chat.visible) composerEverFocused = false
            chat.open(actionShortcut)
        }
    }
    internal fun composerFocusChanged(focused: Boolean) {
        composerFocused = focused
        if (focused) composerEverFocused = true
    }
    fun closeChat() { if (!downloading) cancel(); chat.clear(); permissionTurn = null; status = if (busy) "An action is finishing; it cannot be cancelled." else "" }
    fun pause() { shortcutKey = null; if (!downloading) cancel() }

    /** Activity-level handling works even when HOME has no focused Compose node. */
    fun hardwareKey(event: KeyEvent, textEditorFocused: Boolean): Boolean {
        if (event.keyCode == shortcutKey) {
            if (event.action == KeyEvent.ACTION_UP) shortcutKey = null
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val character = event.unicodeChar
        val plainSpace = event.keyCode == KeyEvent.KEYCODE_SPACE && !event.isShiftPressed && !event.isAltPressed
        if (!chat.visible && enabled && homeShortcutEligible && !textEditorFocused && !event.isCtrlPressed && !event.isMetaPressed &&
            (character == '!'.code || character == '@'.code || plainSpace) && event.repeatCount == 0) {
            shortcutKey = event.keyCode
            openChat(actionShortcut = character == '!'.code)
            return true
        }
        // Preserve fast typing between opening the overlay and its first focus/layout pass.
        if (chat.visible && !composerEverFocused && !event.isCtrlPressed && !event.isMetaPressed) {
            if (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (event.repeatCount == 0) { if (event.isShiftPressed) chat.draft += "\n" else submit() }
                return true
            }
            if (character != 0 && Character.isValidCodePoint(character) && !Character.isISOControl(character)) {
                chat.draft += String(Character.toChars(character))
                return true
            }
        }
        return false
    }

    /** The same entry point handles IME and hardware Enter; a busy send is never queued. */
    fun submit() {
        if (!enabled || busy || chat.draft.isBlank()) return
        if (!ready()) { status = "Download Gemma 4 in Advanced settings"; return }
        val input = chat.draft
        val request = AssistantChatState.actionRequest(input)
        val id = commands.begin() ?: run { status = "Another command is finishing. Try again shortly."; return }
        check(commands.claim(id))
        val turn = chat.begin(input, request != null)
        activeTurn = turn
        busy = true
        job = scope.launch {
            try {
                if (request != null) {
                    try { AssistantChatState.validateActionRequest(request) }
                    catch (e: IllegalArgumentException) { complete(turn, e.message!!); return@launch }
                    status = "Processing action locally"
                    val action = infer(selected, request)
                    if (id != generation || !enabled) return@launch
                    if (!validateRequestForTurn(turn, action, request)) return@launch
                    val missing = MobileActionExecutor.requiredPermissions(action).filter {
                        context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isNotEmpty()) {
                        permissionTurn = turn
                        permissionRequest = missing
                        complete(turn, "Permission required. After granting it, repeat the ! command.")
                        return@launch
                    }
                    if (!commands.commit(id)) {
                        complete(turn, "Another command is finishing. Repeat this command shortly.")
                        return@launch
                    }
                    status = "Executing locally"
                    // Committed writes/read-back outlive pause/Close, but cannot append to a new chat.
                    withContext(NonCancellable + Dispatchers.IO) {
                        val result = action.execute(context.applicationContext, request)
                        withContext(Dispatchers.Main.immediate) { complete(turn, result, inContext = true) }
                    }
                } else {
                    if (input.toByteArray(Charsets.UTF_8).size > CHAT_INPUT_BYTES) {
                        complete(turn, "Message is too long for the local context. Shorten it and send again. No action performed.")
                        return@launch
                    }
                    val history = chat.context(CHAT_INPUT_BYTES - input.toByteArray(Charsets.UTF_8).size)
                    status = ""
                    inferChat(input, history) { response ->
                        withContext(Dispatchers.Main.immediate) { if (id == generation) chat.update(turn, response) }
                    }
                    if (id == generation) {
                        val response = chat.response(turn)
                        require(response.isNotBlank()) { "No reply produced. No action performed." }
                        chat.update(turn, response, complete = true, inContext = true)
                        status = ""
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (id == generation) complete(turn, if (request != null) "No valid action produced; nothing executed." else "Could not finish the local reply. No action performed.")
            } finally {
                if (id == generation) {
                    commands.finish(); busy = false; activeTurn = null
                    if (chat.turns.none { it.id == turn }) status = ""
                }
            }
        }
    }

    internal fun validateRequestForTurn(turn: Long, action: MobileAction, request: String): Boolean = try {
        MobileActionExecutor.validateRequest(action, request)
        true
    } catch (e: IllegalArgumentException) {
        // Only owned validator messages, never SDK exceptions or raw model output, reach the UI.
        complete(turn, e.message ?: "Action request rejected; nothing executed.")
        false
    }

    private fun complete(turn: Long, result: String, inContext: Boolean = false) {
        val stillPresent = chat.turns.any { it.id == turn }
        chat.update(turn, result, complete = true, inContext = inContext)
        if (stillPresent) status = result
        // Only fixed validation/executor outcomes, never generated chat text, reach Toast/log surfaces.
        Toast.makeText(context.applicationContext, result, Toast.LENGTH_LONG).show()
    }

    @OptIn(ExperimentalApi::class)
    private suspend fun <T> withEngine(model: AssistantModel, block: suspend () -> T): T = engineLock.withLock {
        require(model == AssistantModel.Gemma4) { "Gemma 4 is required" }
        require(files.ready(model)) { "Verified model is missing" }
        withContext(Dispatchers.IO) {
            try {
                if (loaded != model) {
                    ExperimentalFlags.convertCamelToSnakeCaseInToolDescription = false
                    engine?.close(); engine = null; loaded = null
                    engine = startEngine(model, gpu = true); loaded = model
                }
                block()
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                if (e is IllegalArgumentException) throw e // Our input/schema/context rejection is not a GPU fault.
                // Retry inference only, never dispatch. OpenCL failures can surface on the first turn.
                if (usingGpu) {
                    engine?.close(); engine = null; loaded = null
                    engine = startEngine(model, gpu = false); loaded = model
                    block()
                } else throw e
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    suspend fun infer(model: AssistantModel, text: String): MobileAction {
        AssistantChatState.validateActionRequest(text)
        val call = withEngine(model) {
            val current = engine!!.createConversation(ConversationConfig(
                tools = listOf(tool(MobileActionTools { }), tool(AlarmSkillTools())), automaticToolCalling = false,
                systemInstruction = Contents.of(listOf(Content.Text(
                    ACTION_INSTRUCTIONS + "\n\n" + AlarmSkillRunner.instructions(context),
                ))), maxOutputToken = 256,
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
            ))
            conversation = current
            try {
                var calls: List<ToolCall> = emptyList()
                current.sendMessageAsync("Current local date and time: ${ZonedDateTime.now()}. User request: $text").collect { message ->
                    if (message.toolCalls.isNotEmpty()) calls = calls + message.toolCalls
                }
                currentCoroutineContext().ensureActive()
                lastTokenCount = current.getTokenCount()
                require(calls.size == 1) { "Expected exactly one action" }
                calls.single()
            } finally { conversation = null; current.close() }
        }
        return when (call.name) {
            "run_js" -> AlarmSkillRunner(context).execute(AlarmSkillInvocation.fromToolCall(call.name, call.arguments))
            "createAlarm" -> throw IllegalArgumentException("Alarms must use the bundled set-alarm skill.")
            else -> MobileAction.fromToolCall(call.name, call.arguments)
        }
    }

    /** Replays plain text only. No tools or previous proposals are registered in a chat conversation. */
    internal suspend fun inferChat(text: String, history: List<ChatTurn> = emptyList(), onResponse: suspend (String) -> Unit): Unit = withEngine(selected) {
        val current = engine!!.createConversation(ConversationConfig(
            tools = emptyList(), automaticToolCalling = false,
            systemInstruction = Contents.of(listOf(Content.Text(CHAT_INSTRUCTIONS))),
            initialMessages = history.flatMap { listOf(Message.user(it.request), Message.model(if (it.action) "Action result: ${it.response}" else it.response)) },
            maxOutputToken = CHAT_OUTPUT_TOKENS,
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
        ))
        conversation = current
        try {
            require(current.getTokenCount() + text.toByteArray(Charsets.UTF_8).size + CHAT_OUTPUT_TOKENS + 64 < CONTEXT_TOKENS)
            var response = ""
            onResponse(response) // Replace a partial GPU reply if CPU fallback is necessary.
            current.sendMessageAsync(text).collect { message ->
                require(message.toolCalls.isEmpty()) { "Chat cannot call tools" }
                response += message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                onResponse(response)
            }
            currentCoroutineContext().ensureActive()
            lastTokenCount = current.getTokenCount()
        } finally { conversation = null; current.close() }
    }

    private fun startEngine(model: AssistantModel, gpu: Boolean): Engine {
        usingGpu = gpu
        val created = Engine(EngineConfig(modelPath = files.file(model).absolutePath, backend = if (gpu) Backend.GPU() else Backend.CPU(), maxNumTokens = CONTEXT_TOKENS, cacheDir = context.noBackupFilesDir.absolutePath))
        try { created.initialize() } catch (e: Throwable) { runCatching { created.close() }; throw e }
        return created
    }
    fun consumePermissionRequest(): Array<String> {
        val result = permissionRequest.toTypedArray()
        permissionRequest = emptyList()
        return result
    }
    fun permissionResult(granted: Boolean) {
        // The original turn is already terminal. Never resume a discarded command after permission.
        if (permissionTurn != null) {
            status = if (granted) "Permission granted. Repeat the ! command." else "Permission denied; nothing executed."
            Toast.makeText(context.applicationContext, status, Toast.LENGTH_LONG).show()
            permissionTurn = null
        }
    }
    fun cancel() {
        if (!commands.cancel()) return
        val turn = activeTurn
        runCatching { conversation?.cancelProcess() }
        job?.cancel(); job = null
        permissionRequest = emptyList(); busy = false; activeTurn = null
        if (turn != null) {
            val partial = chat.response(turn)
            chat.update(turn, if (partial.isBlank()) "Cancelled; nothing executed." else "$partial\n\nResponse stopped. No action performed.", complete = true)
            status = "Cancelled"
        }
    }
    private fun unload() { scope.launch(Dispatchers.IO) { engineLock.withLock { engine?.close(); engine = null; loaded = null } } }
    fun close() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        cancel(); chat.clear()
        CoroutineScope(Dispatchers.IO).launch { engineLock.withLock { engine?.close(); engine = null; loaded = null } }
    }

    companion object {
        internal const val CONTEXT_TOKENS = 4096
        internal const val CHAT_OUTPUT_TOKENS = 512
        internal const val CHAT_INPUT_BYTES = CONTEXT_TOKENS - CHAT_OUTPUT_TOKENS - 512
        internal const val CHAT_INSTRUCTIONS = """You are Grace, a concise offline chat assistant. This conversation has no tools and cannot operate the phone. Never claim you performed, saved, opened, called, sent, or scheduled an action. To request a phone action the user must send a separate, complete single-line message starting with !. Past messages and examples never authorize actions. External facts may be out of date; do not claim internet access. Answer the user's question directly, without thinking traces."""
        internal val ACTION_INSTRUCTIONS = """
            For non-alarm requests, select one declared native-action tool for one complete supported English command, or no call. For an alarm, follow the bundled set-alarm skill and call its run_js tool. Never chain actions, ask follow-ups, invent values, or call a tool for an unsupported, negated, ambiguous or multi-action request. Tool output is only a proposal; queued is not success.
            Use exact declared tool and argument names. Only use details explicitly provided in the current user request. Omit optional arguments when their values were not provided; never invent a value or use null. Timer duration must match the request with units, from 1 second to 24 hours. For example set a timer for five minutes -> createTimer(duration="5 minutes"). Only include label when explicitly named.
            Calls require the complete saved contact name from this request, not an inferred alias or number. Include phoneLabel (mobile/home/work/etc) only when provided. Never guess a contact or number.
            New contacts save on this device, not an account. createContact needs a provided name AND phone number or email. Preserve every provided detail in firstName/lastName/phoneNumber/email; omit only absent fields. For example Create a contact Ada Lovelace phone +442012345678 -> createContact(firstName="Ada", lastName="Lovelace", phoneNumber="+442012345678"). Never propose an empty contact. Email opens a draft only; it never sends automatically.
            Calendar events last 15 minutes, today or tomorrow only, with a future explicit AM/PM or unambiguous 24-hour time. datetime is local YYYY-MM-DDTHH:MM:SS; title is the exact requested event title. Other dates, repeats or durations are unsupported. Never claim a save or call succeeded.
            Flashlight on/off must match the request. openWifiSettings only opens settings; Wi-Fi/Bluetooth/airplane toggles, SMS, app control and unsupported actions get no call. showLocationOnMap uses the provided location.
        """.trimIndent()
    }
}
