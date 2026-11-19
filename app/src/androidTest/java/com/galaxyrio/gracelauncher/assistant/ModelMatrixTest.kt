package com.galaxyrio.gracelauncher.assistant

import android.app.Activity
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxyrio.gracelauncher.BuildConfig
import com.galaxyrio.gracelauncher.MainActivity
import com.galaxyrio.gracelauncher.SettingsActivity
import com.galaxyrio.gracelauncher.ui.LauncherViewModel
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Live, opt-in UI matrix. Evidence stays in the ignored build report, never in fixtures/logcat. */
class ModelMatrixTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val runId = System.currentTimeMillis().toString()
    private val report by lazy { File(context.filesDir, "model-matrix/$runId").apply { mkdirs() } }
    private val rows = JSONArray()
    private var scenario: ActivityScenario<out Activity>? = null
    private lateinit var assistant: AssistantController
    private var stage = ""
    private val order = listOf(AssistantModel.Qwen3_1_7B, AssistantModel.Qwen2_5_1_5B,
        AssistantModel.Gemma4E4B, AssistantModel.Gemma4)

    @Test fun fourModelMatrix() {
        assumeTrue("Opt-in live model downloads/inference", args.getString("modelMatrix") == "true")
        // Refuse accidental execution on a retained personal emulator/device.
        assertEquals("Dedicated matrix AVD required", "grace-model-matrix-api36", shell("getprop ro.boot.qemu.avd_name").trim())
        assertTrue("Web is unavailable in this debug build", BuildConfig.TAVILY_API_KEY.isNotBlank())
        val filter = args.getString("models")?.split(',')?.map(String::trim)
        require(filter == null || filter.isNotEmpty() && filter.all { name -> order.any { it.name == name } }) {
            "Invalid models filter"
        }
        val models = order.filter { filter == null || it.name in filter }
        val failures = mutableListOf<String>()
        try {
            provisionHealth()
            for (model in models) {
                var installed = false
                val switched = JSONObject().put("model", model.name).put("case", "switch")
                val start = SystemClock.elapsedRealtime()
                try {
                    switchModel(model, allowReady = filter != null)
                    installed = true
                    switched.put("pass", true)
                } catch (_: Throwable) {
                    switched.put("pass", false).put("reason", stage)
                    failures += "${model.name}:switch:$stage"
                } finally {
                    switched.put("latencyMs", SystemClock.elapsedRealtime() - start)
                    rows.put(switched); save(model)
                }
                if (!installed) {
                    (1..6).forEach { id ->
                        rows.put(JSONObject().put("model", model.name).put("case", id.toString())
                            .put("pass", false).put("reason", "Model switch precondition failed").put("latencyMs", 0))
                    }
                    save(model)
                    continue
                }
                launchHome()
                var wikiTitle = ""
                fun case(id: String, block: (JSONObject) -> Unit) {
                    val row = JSONObject().put("model", model.name).put("case", id)
                        .put("reply", "").put("cards", JSONArray()).put("healthState", "")
                    val began = SystemClock.elapsedRealtime()
                    // Persist an in-progress record so process death is visible, not a missing success.
                    rows.put(row.put("pass", false).put("reason", "Interrupted")); save(model)
                    try {
                        block(row)
                        row.put("pass", true).remove("reason")
                    } catch (_: Throwable) {
                        row.put("pass", false).put("reason", stage)
                        failures += "${model.name}:$id:$stage"
                        runCatching {
                            val pending = compose.runOnIdle { assistant.chat.turns.lastOrNull() }
                            if (pending?.request == row.optString("prompt") && row.optString("reply").isEmpty()) {
                                row.put("reply", redact(text("assistant_response:${pending.id}")))
                            }
                        }
                        // Stop a timed-out generation before the next independent case.
                        compose.runOnUiThread { assistant.cancel() }
                    } finally {
                        row.put("latencyMs", SystemClock.elapsedRealtime() - began); save(model)
                    }
                }
                case("1") { row ->
                    fresh(ChatSource.NONE)
                    contains(reply("What is 12 times 7? Reply with just the number.", row), "84")
                }
                case("2") { row ->
                    fresh(ChatSource.NONE)
                    reply("My name is Grace.", row, "introduction")
                    contains(reply("What is my name?", row), "Grace")
                }
                case("3") { row ->
                    fresh(ChatSource.WEB)
                    contains(reply("Who won the 2024 Nobel Prize in Literature?", row), "Han Kang")
                    stage = "Tavily source card required"
                    assertTrue(row.getJSONArray("cards").length() > 0)
                }
                case("4") { row ->
                    fresh(ChatSource.WIKIPEDIA)
                    val answer = reply("What is the capital of Australia?", row)
                    stage = "Wikipedia source card required"
                    val cards = row.getJSONArray("cards")
                    assertTrue(cards.length() > 0)
                    assertTrue(cards.toString().contains("wikipedia.org", ignoreCase = true))
                    wikiTitle = compose.runOnIdle { assistant.chat.turns.last().sources.first().title }
                    contains(answer, "Canberra")
                }
                case("5") { row ->
                    val answer = reply("source 1", row)
                    stage = "Previous Wikipedia source required"
                    assertTrue(wikiTitle.isNotBlank())
                    stage = "App source-N tool-free saved-source path required"
                    // Only answerFromSavedSources sets explicit sourceNumbers; that branch has no network/tools.
                    assertEquals(listOf(1), compose.runOnIdle { assistant.chat.turns.last().sourceNumbers })
                    contains(answer, wikiTitle)
                }
                case("6") { row ->
                    fresh(ChatSource.HEALTH)
                    reply("How did I sleep last night?", row)
                    stage = "Health Live or Cached card required"
                    val state = row.optString("healthState")
                    assertTrue(Regex("\\b(?:Live|Cached)\\b", RegexOption.IGNORE_CASE).containsMatchIn(state))
                }
            }
        } finally {
            shell("rm -f /data/local/tmp/grace-health.token")
            scenario?.close()
        }
        assertTrue("Matrix failures: ${failures.joinToString()}; evidence files/model-matrix/$runId", failures.isEmpty())
    }

    private inline fun <reified T : androidx.activity.ComponentActivity> launch() {
        scenario?.close()
        val next = ActivityScenario.launch(T::class.java)
        scenario = next
        next.onActivity { assistant = ViewModelProvider(it)[LauncherViewModel::class.java].assistant }
    }

    private fun setting(container: String, tag: String) {
        stage = "Navigate $tag"
        compose.onNodeWithTag(container).performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    private fun settings() {
        launch<SettingsActivity>()
        setting("settings_root", "settings_category_advanced")
        setting("settings_advanced", "settings_offline_assistant")
    }

    private fun provisionHealth() {
        stage = "Health provisioning"
        // Read the pushed secret in-process only. Never put it in assertion messages/semantics dumps.
        var token = shell("cat /data/local/tmp/grace-health.token 2>/dev/null").trim()
        try {
            settings()
            if (token.isNotBlank()) {
                compose.onNodeWithTag("assistant_health_token").performScrollTo().performTextReplacement(token)
                compose.onNodeWithTag("assistant_health_save").performScrollTo().performClick()
                compose.waitUntil(60_000) {
                    compose.onAllNodesWithTag("assistant_health_remove").fetchSemanticsNodes().isNotEmpty() &&
                        isEnabled().matches(compose.onNodeWithTag("assistant_health_remove").fetchSemanticsNode())
                }
            }
        } catch (_: Throwable) {
            throw AssertionError("Health provisioning failed (details withheld)")
        } finally {
            token = ""
            shell("rm -f /data/local/tmp/grace-health.token")
        }
    }

    private fun switchModel(model: AssistantModel, allowReady: Boolean) {
        settings()
        stage = "Enable assistant"
        if (!compose.runOnIdle { assistant.enabled }) compose.onNodeWithTag("assistant_enable").performScrollTo().performClick()
        if (!compose.runOnIdle { assistant.homeButtonVisible }) compose.onNodeWithTag("assistant_show_home_button").performScrollTo().performClick()
        if (!(allowReady && compose.runOnIdle { assistant.selected == model && assistant.ready() })) {
            stage = "Select model by UI"
            compose.onNodeWithTag("assistant_model_picker").performScrollTo().performClick()
            stage = "Tap model radio"
            compose.onNodeWithTag("assistant_model_option_${model.name}").performClick()
            stage = "Confirm model switch"
            compose.onNodeWithTag("assistant_model_confirm").performClick()
            stage = "Download and verification (30 minute limit)"
            compose.waitUntil(30 * 60_000L) { !assistant.busy && assistant.selected == model && assistant.ready() }
        }
        stage = "Selected/ready model and exact files"
        compose.onNodeWithTag("assistant_model_${model.name}", useUnmergedTree = true).assertExists()
        stage = "Verify selected model radio"
        compose.onNodeWithTag("assistant_model_picker").performScrollTo().performClick()
        compose.onNodeWithTag("assistant_model_option_${model.name}").assertIsSelected()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        stage = "Verify exactly the selected model and sidecar on disk"
        val file = ModelFiles(context).file(model)
        assertEquals(setOf(file.name, "${file.name}.sha256"), file.parentFile!!.listFiles()!!.map { it.name }.toSet())
        assertTrue(ModelFiles(context).ready(model))
        stage = "Verify only selected model engine caches remain"
        val caches = context.noBackupFilesDir.listFiles()!!.filter {
            it.isFile && (it.name.endsWith(".xnnpack_cache") || it.name.endsWith("_mldrift_weight_cache.bin"))
        }
        assertTrue(caches.all { it.name.startsWith("${model.name}-${model.filename}_") })
    }

    private fun launchHome() { launch<MainActivity>() }

    private fun fresh(source: ChatSource) {
        stage = "Clear chat through Back"
        if (compose.runOnIdle { assistant.chat.visible }) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            // The IME may consume the first Back; the next Back closes/clears the chat.
            if (compose.runOnIdle { assistant.chat.visible }) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
        compose.waitUntil(10_000) { assistant.homeShortcutEligible }
        compose.onNodeWithTag("assistant_chat_button").performClick()
        stage = "Choose $source chip"
        if (source == ChatSource.NONE) compose.onNodeWithTag("assistant_web_chip").performClick()
        else if (source != ChatSource.WEB) {
            compose.onNodeWithTag("assistant_tools").performClick()
            compose.onNodeWithTag(if (source == ChatSource.HEALTH) "assistant_health_option" else "assistant_wikipedia_option").performClick()
        }
    }

    private fun reply(prompt: String, row: JSONObject, key: String = "reply"): String {
        stage = "Submit prompt via composer/Enter"
        row.put("prompt", prompt)
        val before = compose.runOnIdle { assistant.chat.turns.lastOrNull()?.id ?: 0 }
        compose.onNodeWithTag("assistant_chat_input").performTextReplacement(prompt)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        stage = "Reply completion (10 minute limit)"
        compose.waitUntil(10 * 60_000L) {
            assistant.chat.turns.lastOrNull()?.let { it.id > before && it.complete && !assistant.busy } == true
        }
        val turn = compose.runOnIdle { assistant.chat.turns.last() }
        stage = "Read final reply semantics"
        val answer = text("assistant_response:${turn.id}")
        // Redact before writing, even when the universal leak assertion itself fails.
        row.put(key, redact(answer))
        val cards = JSONArray()
        turn.sources.forEachIndexed { index, _ ->
            val tag = if (turn.webSearch) "assistant_source_card:${turn.id}:$index" else "assistant_source:${turn.id}:$index"
            compose.onNodeWithTag(tag).performScrollTo()
            val title = if (turn.webSearch) text("assistant_source_title:${turn.id}:$index") else text(tag)
            val host = if (turn.webSearch) text("assistant_source_metadata:${turn.id}:$index") else title.substringAfter(" — ")
            cards.put(JSONObject().put("title", redact(title)).put("host", redact(host)))
        }
        row.put("cards", cards)
        if (turn.healthCard != null) row.put("healthState", redact(text("assistant_health_status:${turn.id}")))
        stage = "Universal reply: nonempty, no thinking/tool markers or token"
        assertTrue(answer.isNotBlank())
        assertFalse(Regex("<think|</think|<tool_call|</tool_call|<\\|[^>]*(?:tool|channel)|\\[start_(?:header|of_turn)|hc_", RegexOption.IGNORE_CASE).containsMatchIn(answer))
        return answer
    }

    private fun text(tag: String): String {
        compose.onNodeWithTag(tag).performScrollTo()
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        fun texts(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
            n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } + n.children.flatMap(::texts)
        return texts(node).joinToString(" ")
    }

    private fun contains(answer: String, expected: String) {
        stage = "Required factual/name/title substring absent"
        assertTrue(answer.contains(expected, ignoreCase = true))
    }

    private fun redact(text: String) = text.replace(Regex("hc_[A-Za-z0-9_-]*", RegexOption.IGNORE_CASE), "[REDACTED]")

    private fun save(model: AssistantModel) {
        File(report, "${model.name}.json").writeText(JSONObject().put("run", runId).put("model", model.name)
            .put("cases", JSONArray((0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.getString("model") == model.name }))
            .toString(2))
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
