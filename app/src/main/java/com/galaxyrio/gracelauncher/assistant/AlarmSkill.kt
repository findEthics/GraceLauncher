package com.galaxyrio.gracelauncher.assistant

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import java.io.ByteArrayInputStream
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener

/** Only the bundled alarm skill is executable; model output cannot select or supply JavaScript. */
internal data class AlarmSkillInvocation(val scriptName: String, val data: String) {
    companion object {
        fun fromToolCall(name: String, arguments: Map<String, Any?>): AlarmSkillInvocation {
            require(name == "run_js" && arguments.keys == setOf("scriptName", "data")) {
                "Invalid alarm skill call."
            }
            val scriptName = arguments["scriptName"] as? String ?: throw IllegalArgumentException("Invalid alarm skill call.")
            val data = arguments["data"] as? String ?: throw IllegalArgumentException("Invalid alarm skill call.")
            require(scriptName == AlarmSkillRunner.ENTRY_POINT && data.length <= AlarmSkillRunner.MAX_INPUT_CHARS) {
                "Invalid alarm skill call."
            }
            return AlarmSkillInvocation(scriptName, data)
        }
    }
}

/** Schema adapter following Agent Skills' run_js contract; it never executes a tool automatically. */
internal class AlarmSkillTools : ToolSet {
    @Tool(description = "Runs the bundled set-alarm JavaScript skill. Use only for one explicit alarm request.")
    fun run_js(
        @ToolParam(description = "Bundled entry point; exactly index.html.") scriptName: String,
        @ToolParam(description = "JSON string with time in HH:mm and optional label.") data: String,
    ): Map<String, String> = mapOf("result" to "queued")
}

/** Executes a single app-bundled JS skill in an isolated, bridge-free WebView and returns a proposal. */
internal class AlarmSkillRunner(private val context: Context) {
    suspend fun execute(invocation: AlarmSkillInvocation): MobileAction {
        require(invocation.scriptName == ENTRY_POINT) { "Invalid alarm skill call." }
        val data = normalizeInput(invocation.data)
        val html = context.assets.open(SKILL_SCRIPT).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val envelope = try {
            withTimeout(EXECUTION_TIMEOUT_MS) {
                withContext(Dispatchers.Main.immediate) { evaluate(html, data) }
            }
        } catch (_: TimeoutCancellationException) {
            throw IllegalStateException("Alarm skill failed.")
        }
        return actionFromResult(envelope)
    }

    private suspend fun evaluate(html: String, data: String): String = suspendCancellableCoroutine { continuation ->
        val webView = WebView(context)
        var closed = false
        var evaluated = false
        fun close() {
            if (closed) return
            closed = true
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
        fun fail() {
            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Alarm skill failed."))
            close()
        }

        continuation.invokeOnCancellation { webView.post { close() } }
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            blockNetworkLoads = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (request.isForMainFrame) null else WebResourceResponse(
                    "text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)),
                )

            override fun onPageFinished(view: WebView, url: String) {
                if (evaluated || !continuation.isActive) { close(); return }
                evaluated = true
                try {
                    view.evaluateJavascript("window.ai_edge_gallery_get_result(${JSONObject.quote(data)})") { raw ->
                        val output = runCatching { JSONTokener(raw ?: "null").nextValue() as? String }.getOrNull()
                        if (output == null) fail()
                        else {
                            if (continuation.isActive) continuation.resume(output)
                            close()
                        }
                    }
                } catch (_: Exception) { fail() }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) fail()
            }
        }
        try { webView.loadDataWithBaseURL("https://grace.invalid/", html, "text/html", "UTF-8", null) }
        catch (_: Exception) { fail() }
    }

    private fun normalizeInput(data: String): String {
        require(data.length <= MAX_INPUT_CHARS) { "Invalid alarm skill input." }
        val input = runCatching { JSONObject(data) }.getOrElse { throw IllegalArgumentException("Invalid alarm skill input.") }
        val keys = input.keys().asSequence().toSet()
        require("time" in keys && keys.all { it == "time" || it == "label" }) { "Invalid alarm skill input." }
        val time = input.opt("time") as? String
        val label = if (input.has("label")) input.opt("label") as? String else ""
        require(time?.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) == true && label != null &&
            label.length <= MAX_LABEL_CHARS && label.none { Character.isISOControl(it) }) {
            "Invalid alarm skill input."
        }
        return JSONObject().put("time", time).put("label", label).toString()
    }

    private fun actionFromResult(envelope: String): MobileAction {
        val outer = runCatching { JSONObject(envelope) }.getOrElse { throw IllegalArgumentException("Invalid alarm skill result.") }
        require(!outer.has("error")) { "Invalid alarm skill result." }
        val raw = outer.opt("result") as? String ?: throw IllegalArgumentException("Invalid alarm skill result.")
        val result = runCatching { JSONObject(raw) }.getOrElse { throw IllegalArgumentException("Invalid alarm skill result.") }
        val keys = result.keys().asSequence().toSet()
        require(keys == setOf("skill", "hour", "minute", "label") && result.opt("skill") == SKILL_NAME) {
            "Invalid alarm skill result."
        }
        val hour = result.opt("hour") as? Int
        val minute = result.opt("minute") as? Int
        val label = result.opt("label") as? String
        require(hour != null && minute != null && hour in 0..23 && minute in 0..59 && label != null &&
            label.length <= MAX_LABEL_CHARS && label.none { Character.isISOControl(it) }) {
            "Invalid alarm skill result."
        }
        val action = MobileAction(
            "createAlarm",
            mapOf("time" to String.format(Locale.ROOT, "%02d:%02d", hour, minute), "label" to label),
        )
        MobileActionExecutor.validate(action)
        return action
    }

    companion object {
        const val SKILL_NAME = "set-alarm"
        const val ENTRY_POINT = "index.html"
        private const val SKILL_DEFINITION = "skills/set-alarm/SKILL.md"
        private const val SKILL_SCRIPT = "skills/set-alarm/scripts/index.html"
        internal const val MAX_INPUT_CHARS = 4096
        private const val MAX_LABEL_CHARS = 80
        private const val EXECUTION_TIMEOUT_MS = 5_000L

        fun instructions(context: Context): String =
            context.assets.open(SKILL_DEFINITION).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
