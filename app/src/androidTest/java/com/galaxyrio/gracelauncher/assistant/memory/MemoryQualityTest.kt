package com.galaxyrio.gracelauncher.assistant.memory

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxyrio.gracelauncher.assistant.AssistantController
import com.galaxyrio.gracelauncher.assistant.AssistantModel
import com.galaxyrio.gracelauncher.assistant.estimateTokens
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in (`-e memoryQuality true`), report-only memory answer-quality run on the real local model.
 * Synthetic inputs only; in-memory store and no RPC (local-only sync), so nothing is written anywhere live.
 * Fails only on infrastructure errors (model not ready, warm-up crash), never on quality.
 */
class MemoryQualityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext

    private class MemStore : MemoryLocalStore {
        val rows = linkedMapOf<String, MemoryEntry>()
        val ops = mutableListOf<OutboxOp>()
        private var cursor = 0L
        private var syncedAt: Long? = null
        override fun entries() = rows.values.toList()
        override fun upsert(items: List<MemoryEntry>) { items.forEach { rows[it.id] = it } }
        override fun delete(ids: Set<String>) { ids.forEach(rows::remove) }
        override fun outbox() = ops.toList()
        override fun enqueue(op: OutboxOp) { if (ops.none { it.key == op.key }) ops += op }
        override fun removeOp(key: String) { ops.removeAll { it.key == key } }
        override fun bumpAttempts(key: String) {}
        override fun loadCursor() = cursor
        override fun saveCursor(value: Long) { cursor = value }
        override fun lastSyncAt() = syncedAt
        override fun setLastSyncAt(value: Long) { syncedAt = value }
    }

    private data class RememberCase(val id: String, val lang: String, val kind: String, val text: String, val expected: MemoryType)
    private data class AskCase(val id: String, val lang: String, val question: String, val keywords: List<String>, val grounded: Boolean)

    private val noisy = "so ok sooo i was thinking... (typing this on the bus lol) the thing is, the garage door remote battery " +
        "is a CR2032 and the spare ones live in the top shelf of the hallway cabinet!!! also not important but whatever. ".repeat(6)

    private val rememberCases = listOf(
        RememberCase("r01", "en", "fact", "My dog's name is Biscuit and he is a beagle.", MemoryType.FACT),
        RememberCase("r02", "en", "preference", "I prefer window seats on long flights.", MemoryType.PREFERENCE),
        RememberCase("r03", "en", "place", "The spare key is at my sister's flat in the kitchen drawer.", MemoryType.PLACE),
        RememberCase("r04", "en", "event", "Dentist appointment with Dr. Weber tomorrow at 3pm", MemoryType.EVENT),
        RememberCase("r05", "en", "event", "Team offsite on December 12 at 9am in Hamburg", MemoryType.EVENT),
        RememberCase("r06", "en", "fact", "Shoe size is 43.", MemoryType.FACT),
        RememberCase("r07", "de", "fact", "Mein Hausarzt heißt Dr. Müller und die Praxis ist in der Lindenstraße.", MemoryType.FACT),
        RememberCase("r08", "de", "preference", "Ich mag meinen Kaffee mit Hafermilch und ohne Zucker.", MemoryType.PREFERENCE),
        RememberCase("r09", "de", "place", "Das Fahrrad steht im Keller hinter der Waschmaschine.", MemoryType.PLACE),
        RememberCase("r10", "de", "date-phrase", "Zahnarzttermin am 12. Dezember um 15 Uhr", MemoryType.FACT), // German dates: expected NOT an event
        RememberCase("r11", "en", "long-noisy", noisy.trim(), MemoryType.FACT),
        RememberCase("r12", "en", "short-noisy", "um... so yeah!! remember the thing: locker code is in the blue notebook lol ???", MemoryType.FACT),
    )

    private val askCases = listOf(
        AskCase("q1", "en", "Where did I park my car?", listOf("level 3", "b12"), true),
        AskCase("q2", "en", "How do I like my coffee?", listOf("oat"), true),
        AskCase("q3", "en", "When is my dentist appointment?", listOf("{eventDate}", "{dayName}", "10:00", "10 am", "10am"), true),
        AskCase("q4", "de", "Wo liegt mein Ersatzschlüssel?", listOf("köhler", "nachbarin"), true),
        AskCase("q5", "de", "Welchen Tee trinke ich gerne?", listOf("pfefferminz"), true),
        AskCase("q6", "en", "What is my sister's phone number?", emptyList(), false),
    )

    @Test fun memoryQuality() {
        assumeTrue("Opt-in live model inference", args.getString("memoryQuality") == "true")
        assertEquals("Dedicated matrix AVD required", "grace-model-matrix-api36", shell("getprop ro.boot.qemu.avd_name").trim())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = AssistantController(context, scope)
        val expectedModel = args.getString("model") ?: "Gemma4E4B"
        assertEquals("Selected model", expectedModel, controller.selected.name)
        assertTrue("Selected model must be ready", controller.ready())

        val zone = ZoneId.systemDefault()
        val clock = System.currentTimeMillis()
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(clock), zone)
        val runId = clock.toString()
        val report = File(context.filesDir, "memory-quality/$runId").apply { mkdirs() }
        val rememberRows = JSONArray()
        val askRows = JSONArray()
        try {
            runBlocking {
                // Warm-up so engine load is not counted against the app's 8 s subject timeout.
                controller.inferChat("Say OK.", emptyList(), { }, AssistantController.CHAT_INSTRUCTIONS)

                // (1) /remember: the app's own parseRemember path (MemoryTurns.start), tool-free greedy subject call.
                for (c in rememberCases) {
                    val store = MemStore()
                    val turns = MemoryTurns(store, MemorySync(store, { null }, { clock }), { clock }, zone)
                    var raw: String? = null
                    var inferMs = 0L
                    val infer: suspend (String) -> String? = { prompt ->
                        val t0 = SystemClock.elapsedRealtime()
                        var out: String? = null
                        try {
                            controller.inferChat(prompt, emptyList(), { out = it }, AssistantController.MEMORY_SUBJECT_INSTRUCTIONS)
                        } finally { inferMs = SystemClock.elapsedRealtime() - t0 }
                        out.also { raw = it }
                    }
                    val t0 = SystemClock.elapsedRealtime()
                    val row = JSONObject().put("id", c.id).put("lang", c.lang).put("kind", c.kind).put("inputChars", c.text.length)
                    try {
                        val card = turns.start(MemoryCommand.Remember(c.text), emptyList(), infer) as MemoryCard.Save
                        val rules = classifyMemory(c.text, now)
                        val valid = raw?.let { validateSubjectJson(it, rules.type) }
                        row.put("expectedType", c.expected.wire).put("ruleType", rules.type.wire)
                            .put("ruleTypeMatchesExpected", rules.type == c.expected)
                            .put("finalType", card.draft.type.wire)
                            .put("rawReplyNull", raw == null).put("rawReply", raw ?: JSONObject.NULL)
                            .put("jsonValid", valid != null).put("fallbackUsed", valid == null)
                            .put("finalSubject", card.draft.subject).put("hasOccurredAt", card.draft.occurredAt != null)
                    } catch (e: Exception) {
                        row.put("error", e.javaClass.simpleName)
                    }
                    row.put("inferMs", inferMs).put("latencyMs", SystemClock.elapsedRealtime() - t0)
                    rememberRows.put(row)
                    save(report, runId, controller, rememberRows, askRows)
                }

                // (2) Grounded chat: the same tool-free path as AssistantController.submit (system + memory block, history empty).
                val store = MemStore()
                val turns = MemoryTurns(store, MemorySync(store, { null }, { clock }), { clock }, zone)
                val eventAt = now.plusDays(3).withHour(10).withMinute(0).withSecond(0).withNano(0)
                val eventDate = eventAt.toLocalDate().toString()
                val dayName = eventAt.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
                fun e(id: String, type: MemoryType, subject: String, body: String, occurred: Long? = null, age: Long = 0) =
                    MemoryEntry(id, type, subject, subjectKey(subject), body, occurredAt = occurred, validFrom = clock - age * 86_400_000L)
                store.upsert(listOf(
                    e("m1", MemoryType.FACT, "Car parking", "My car is parked on level 3, spot B12.", age = 1),
                    e("m2", MemoryType.PREFERENCE, "Coffee", "I prefer oat milk in my coffee.", age = 5),
                    e("m3", MemoryType.EVENT, "Dentist appointment", "Dentist appointment with Dr. Weber.", eventAt.toInstant().toEpochMilli(), age = 2),
                    e("m4", MemoryType.FACT, "Ersatzschlüssel", "Der Ersatzschlüssel liegt bei der Nachbarin Frau Köhler.", age = 9),
                    e("m5", MemoryType.PREFERENCE, "Tee", "Ich bevorzuge Pfefferminztee ohne Zucker.", age = 4),
                    e("m6", MemoryType.FACT, "Shoe size", "Shoe size is 43.", age = 20),
                    e("m7", MemoryType.PLACE, "Bicycle", "The bicycle is in the basement behind the washing machine.", age = 12),
                    e("m8", MemoryType.FACT, "Dog", "My dog's name is Biscuit and he is a beagle.", age = 30),
                ))
                for (c in askCases) {
                    val row = JSONObject().put("id", c.id).put("lang", c.lang).put("question", c.question).put("grounded", c.grounded)
                    val t0 = SystemClock.elapsedRealtime()
                    try {
                        val historyBudget = AssistantController.historyContextTokens(estimateTokens(c.question), false)
                        val block = turns.block(c.question, minOf(MEMORY_BLOCK_MAX_TOKENS, historyBudget / 2))
                        var reply = ""
                        controller.inferChat(c.question, emptyList(), { reply = it }, systemWithMemory(AssistantController.CHAT_INSTRUCTIONS, block))
                        row.put("blockEmpty", block.isEmpty()).put("blockTokens", estimateTokens(block)).put("reply", reply)
                        if (c.grounded) {
                            val folded = foldText(reply)
                            val hit = c.keywords.map { it.replace("{eventDate}", eventDate).replace("{dayName}", dayName) }.any { folded.contains(foldText(it)) }
                            row.put("keywordHit", hit)
                        } else {
                            val digits = Regex("\\d{5,}").containsMatchIn(reply)
                            val admits = Regex("(don't|do not|doesn't|no |not |nicht|kein|unable|cannot|can't|haven't|isn't)", RegexOption.IGNORE_CASE).containsMatchIn(reply)
                            row.put("fabricatedDigits", digits).put("admitsUnknown", admits).put("noFabricationHeuristic", !digits && admits)
                        }
                    } catch (e: Exception) {
                        row.put("error", e.javaClass.simpleName)
                    }
                    row.put("latencyMs", SystemClock.elapsedRealtime() - t0)
                    askRows.put(row)
                    save(report, runId, controller, rememberRows, askRows)
                }
            }
        } finally {
            scope.cancel()
        }
        save(report, runId, controller, rememberRows, askRows, markdown = true)
    }

    private fun median(values: List<Long>): Long = values.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }

    private fun save(dir: File, runId: String, controller: AssistantController, remember: JSONArray, ask: JSONArray, markdown: Boolean = false) {
        fun rows(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
        val r = rows(remember)
        val q = rows(ask)
        val grounded = q.filter { it.optBoolean("grounded") }
        val summary = JSONObject()
            .put("subjectValidPct", if (r.isEmpty()) 0 else 100 * r.count { it.optBoolean("jsonValid") } / r.size)
            .put("fallbackPct", if (r.isEmpty()) 0 else 100 * r.count { it.optBoolean("fallbackUsed", true) } / r.size)
            .put("ruleTypeMatchesExpected", "${r.count { it.optBoolean("ruleTypeMatchesExpected") }}/${r.size}")
            .put("groundedHits", "${grounded.count { it.optBoolean("keywordHit") }}/5 grounded questions (${grounded.size} run); 6th question is not-in-memory")
            .put("nonFabricationHeuristic", q.firstOrNull { !it.optBoolean("grounded") }?.opt("noFabricationHeuristic") ?: JSONObject.NULL)
            .put("medianRememberLatencyMs", median(r.map { it.optLong("latencyMs") }))
            .put("medianChatLatencyMs", median(q.map { it.optLong("latencyMs") }))
        val json = JSONObject().put("run", runId).put("model", controller.selected.name).put("summary", summary)
            .put("remember", remember).put("grounded", ask)
        File(dir, "report.json").writeText(json.toString(2))
        if (!markdown) return
        val md = StringBuilder("# Memory quality (report-only)\n\nModel: ${controller.selected.name}  \nRun: $runId\n\n## Summary\n\n")
        summary.keys().forEach { md.append("- $it: ${summary.get(it)}\n") }
        md.append("\n## /remember cases\n\n| id | lang | kind | expected | rule | final | json valid | fallback | subject | ms |\n|---|---|---|---|---|---|---|---|---|---|\n")
        r.forEach {
            md.append("| ${it.optString("id")} | ${it.optString("lang")} | ${it.optString("kind")} | ${it.optString("expectedType")} | ${it.optString("ruleType")} | " +
                "${it.optString("finalType")} | ${it.opt("jsonValid")} | ${it.opt("fallbackUsed")} | ${it.optString("finalSubject").replace("|", "/")} | ${it.optLong("latencyMs")} |\n")
        }
        md.append("\n## Grounded questions\n\n| id | lang | question | hit | ms | reply |\n|---|---|---|---|---|---|\n")
        q.forEach {
            val verdict = if (it.optBoolean("grounded")) it.opt("keywordHit") else "nofab=${it.opt("noFabricationHeuristic")}"
            md.append("| ${it.optString("id")} | ${it.optString("lang")} | ${it.optString("question")} | $verdict | ${it.optLong("latencyMs")} | " +
                "${it.optString("reply").replace("\n", " ").replace("|", "/")} |\n")
        }
        File(dir, "report.md").writeText(md.toString())
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
