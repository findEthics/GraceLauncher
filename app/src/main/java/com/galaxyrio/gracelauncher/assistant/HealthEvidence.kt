package com.galaxyrio.gracelauncher.assistant

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

/** How a Health reply's data was obtained; rendered by the app, never written by the model. */
internal sealed class HealthFreshness {
    data class Live(val retrievedAt: Long) : HealthFreshness()
    data class Cached(val retrievedAt: Long, val start: String?, val end: String?, val latest: String?) : HealthFreshness()
    data class Unavailable(val reason: String) : HealthFreshness()
}

/** [details] is the app-built expanded card text (dates, flags, boundary note). */
internal class HealthCard(val freshness: HealthFreshness, val details: String = "")

internal class HealthEvidenceText(val text: String, val details: String)

/** Pure evidence builder, urgent-symptom guard and keyword routing for Health turns. */
internal object HealthEvidence {
    const val MAX_TOKENS = 1800
    const val DAY_NOTE = "Daily totals use the dashboard's stored day boundaries (mostly UTC); timezone only sets window dates."
    const val URGENT_MESSAGE = "This may need urgent care. Call your local emergency number (112 in the EU) or get medical help now."
    const val CRISIS_MESSAGE = URGENT_MESSAGE + " If you are thinking about harming yourself, please reach out now. In Germany: Telefonseelsorge 0800 111 0 111 (free, 24/7)."
    private const val NO_WORKOUTS = "No workout records are provided."

    private val urgent = Regex(
        "chest (pain|pressure|tightness)|pain in (my )?chest|pressure (in|on) (my )?chest|(can['’]?t|cannot|can not|unable to) breathe?|short(ness)? of breath|" +
            "difficulty breathing|trouble breathing|heart attack|faint(ed|ing)?|passed out|pass(ing)? out|blacked out|unconscious|" +
            "stroke|face (is )?droop|drooping|slurred speech|weakness on (one|my) side|one[- ]sided weakness|numb(ness)? on one side|" +
            "severe bleeding|bleeding (heavily|badly|won['’]?t stop)|overdos|" +
            "brustschmerz|schmerzen in der brust|druck (auf|in) der brust|brustenge|atemnot|keine luft|nicht (mehr )?atmen|" +
            "ohnmä|ohnmacht|bewusstlos|schlaganfall|herzinfarkt|starke blutung|stark(e)? blut|lähmung|undeutlich(es)? sprechen",
        setOf(RegexOption.IGNORE_CASE))
    private val selfHarm = Regex(
        "suicid|kill myself|end my life|want to die|self[- ]harm|hurt myself|harm myself|take my (own )?life|" +
            "selbstmord|suizid|umbringen|nicht mehr leben|mich (selbst )?(töten|verletzen)|mir (et)?was antun|selbstverletz",
        setOf(RegexOption.IGNORE_CASE))
    private val weekly = Regex(
        "(?<![\\p{L}])(weeks?|weekly|trends?|compar(e|ed|ing|ison)|plans?|training|trains?|train|workouts?|gym|lifting|weights|" +
            "wochen?|wöchentlich\\w*|vergleich\\w*|pläne?|trainier\\w*)(?![\\p{L}])", setOf(RegexOption.IGNORE_CASE))

    /** Fixed emergency/crisis text on a match (false positives are acceptable), else null. */
    fun urgentMessage(text: String): String? = when {
        selfHarm.containsMatchIn(text) -> CRISIS_MESSAGE
        urgent.containsMatchIn(text) -> URGENT_MESSAGE
        else -> null
    }
    fun isUrgent(text: String) = urgentMessage(text) != null
    fun wantsWeekly(text: String) = weekly.containsMatchIn(text)

    private val markers = Regex("<[^>]*>?|\\|>|tool_call|call:\\w*|(system|assistant|user|model)\\s*:", RegexOption.IGNORE_CASE)

    /** Untrusted free text: controls and tool/role markers stripped, quotes neutralised, truncated by characters. */
    fun clean(text: String, max: Int = 300): String {
        var s = text.map { if (it.isISOControl()) ' ' else it }.joinToString("")
        repeat(3) { s = markers.replace(s, " ") } // repeat: stripping can splice a new marker together
        s = s.replace(Regex("[<>|\"`]"), "'").replace(Regex("\\s+"), " ").trim()
        return if (s.length > max) s.take(max).trimEnd() + "…" else s
    }

    fun time(ms: Long, zone: ZoneId): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).format(Instant.ofEpochMilli(ms).atZone(zone))
    fun clock(ms: Long, zone: ZoneId): String = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).format(Instant.ofEpochMilli(ms).atZone(zone))

    /** Reporting dates of the 7-day window and the latest metric date, for the Cached card. */
    fun cachedFreshness(retrievedAt: Long, status: JSONObject, contexts: Map<String, JSONObject>): HealthFreshness.Cached {
        val range = contexts["chat_reply"]?.optJSONObject("windows")?.optJSONObject("7d")?.optJSONObject("range")
        return HealthFreshness.Cached(retrievedAt, range?.text("start"), range?.text("end"), status.text("latest_metric_date"))
    }

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else opt(key)?.toString()
    private fun JSONObject.num(key: String): Double? = if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }
    private fun f1(v: Double) = String.format(Locale.ROOT, "%.1f", v)
    private fun i0(v: Double) = Math.round(v).toString()
    private fun safeDate(s: String?) = s?.takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) }
    private fun safeWord(s: String?) = (s ?: "?").filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(40)

    /** One metric window; [flags] collects deterministic data-quality notes (values are flagged, never removed). */
    private fun window(label: String, w: JSONObject, flags: MutableList<String>): String {
        val range = w.optJSONObject("range")
        val days = w.optInt("days", 0)
        val dwd = w.optInt("days_with_data", 0)
        val head = StringBuilder("$label window ${safeDate(range?.text("start")) ?: "?"}..${safeDate(range?.text("end")) ?: "?"}")
        if (w.optBoolean("today_is_partial", false)) {
            head.append(" (includes today, partial; do not compare as complete)")
            flags += "$label window includes today (partial); do not compare as complete"
        }
        head.append(": days with data $dwd/$days")
        if (days > 0 && dwd * 2 < days) { head.append(" [flag: sparse, low confidence]"); flags += "$label window sparse (days with data $dwd/$days)" }
        val coverage = w.optJSONObject("coverage_by_metric")
        val latest = w.optJSONObject("latest_date_by_metric")
        // body returns the value text and an optional data-quality flag; the flag goes after "latest <date>".
        fun metric(name: String, key: String, body: () -> Pair<String, String>?): String {
            val (text, flag) = body() ?: return "$name no data"
            val cov = coverage?.optInt(key, 0) ?: 0
            val sparse = if (days > 0 && cov * 2 < days) { flags += "$label $name sparse (coverage $cov/$days)"; " [flag: sparse, low confidence]" } else ""
            return "$text, latest ${safeDate(latest?.text(key)) ?: "unknown"}$flag$sparse"
        }
        val steps = metric("steps", "steps") {
            w.optJSONObject("steps")?.let { s ->
                if (s.optInt("count") == 0) return@let null
                val avg = s.num("avg") ?: return@let null
                val min = s.num("min")
                val flag = if (min != null && min <= 0.0) " [flag: a recorded 0-step day, possibly not worn]".also { flags += "$label steps: a recorded 0-step day, possibly not worn" } else ""
                val extrema = listOfNotNull(min?.let { " min ${i0(it)}" }, s.num("max")?.let { " max ${i0(it)}" }).joinToString("")
                "steps avg ${i0(avg)}$extrema over ${s.optInt("count")} days" to flag
            }
        }
        val sleepObj = w.optJSONObject("sleep_hours")
        val sleep = metric("sleep", "sleep") {
            sleepObj?.optJSONObject("nightly_stats")?.let { s ->
                if (s.optInt("count") == 0) return@let null
                val avg = s.num("avg") ?: return@let null
                val min = s.num("min")
                val max = s.num("max")
                val flag = if ((min != null && min < 1) || (max != null && max > 16)) " [flag: suspicious nightly sleep value]".also { flags += "$label sleep: suspicious nightly value (<1 h or >16 h)" } else ""
                val rolling = sleepObj.num("rolling_avg")?.let { ", rolling 7d avg ${f1(it)} h" } ?: ""
                val extrema = listOfNotNull(min?.let { "min ${f1(it)}" }, max?.let { "max ${f1(it)}" }).joinToString(" ")
                    .let { if (it.isEmpty()) "" else "$it, " }
                "sleep nightly avg ${f1(avg)} h ($extrema${s.optInt("count")} nights)$rolling" to flag
            }
        }
        val hr = metric("resting HR", "resting_hr") {
            w.optJSONObject("resting_hr")?.let { s ->
                if (s.optInt("count") == 0) return@let null
                val avg = s.num("avg") ?: return@let null
                val min = s.num("min")
                val max = s.num("max")
                val flag = if ((min != null && min < 30) || (max != null && max > 200)) " [flag: data-quality problem, do not interpret]".also { flags += "$label resting HR: data-quality problem, do not interpret" } else ""
                val extrema = listOfNotNull(min?.let { "min ${i0(it)}" }, max?.let { "max ${i0(it)}" }).joinToString(" ")
                    .let { if (it.isEmpty()) "" else "$it, " }
                "resting HR avg ${i0(avg)} bpm ($extrema${s.optInt("count")} days)" to flag
            }
        }
        val weight = metric("weight", "weight") {
            w.optJSONObject("weight")?.let { s ->
                val value = s.num("latest") ?: return@let null
                val flag = if (value < 25 || value > 350) " [flag: suspicious value]".also { flags += "$label weight: suspicious value" } else ""
                val change = s.num("change")?.let { " (change ${if (it >= 0) "+" else ""}${f1(it)} kg over window)" } ?: ""
                "weight latest ${f1(value)} kg$change" to flag
            }
        }
        return "$head; $steps; $sleep; $hr; $weight"
    }

    private fun connections(status: JSONObject) = status.optJSONArray("connections")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.joinToString(",") { "${safeWord(it.text("provider"))}=${safeWord(it.text("status"))}" }
    }.orEmpty().ifEmpty { "none" }

    private fun statusLine(status: JSONObject, tz: String?, tzSource: String?): String {
        val connections = connections(status)
        val behind = if (status.isNull("days_behind")) "unknown" else "${status.optInt("days_behind")} day(s) behind"
        fun yn(key: String) = if (status.optBoolean(key, false)) "yes" else "no"
        return "Status: latest metric date ${safeDate(status.text("latest_metric_date")) ?: "unknown"} ($behind); fresh enough: ${yn("fresh_enough_for_current_coaching")}; " +
            "today partial: ${yn("today_is_partial")}; connections: $connections; reauthorization may be required: ${yn("reauthorization_may_be_required")}" +
            if (tz != null) "; timezone ${safeWord(tz).replace('-', '/')} (${safeWord(tzSource)})." else "."
    }

    private fun profileBody(profile: JSONObject?): String? {
        if (profile == null) return null
        fun list(key: String): String? {
            val value = profile.opt(key) ?: return null
            val s = when (value) {
                is org.json.JSONArray -> if (value.length() == 0) return null else (0 until value.length()).joinToString("; ") { value.opt(it).toString() }
                is JSONObject -> if (value.length() == 0) return null else value.toString()
                JSONObject.NULL -> return null
                else -> value.toString()
            }
            return clean(s)
        }
        val parts = listOfNotNull(
            list("short_term_goals")?.let { "short-term goals: \"$it\"" },
            list("long_term_goals")?.let { "long-term goals: \"$it\"" },
            list("weekly_schedule")?.let { "weekly schedule: \"$it\"" },
            profile.num("sleep_goal_hours")?.let { "sleep goal ${f1(it)} h" },
            profile.text("wake_time")?.let { "wake ${clean(it, 20)}" },
            profile.text("bedtime")?.let { "bed ${clean(it, 20)}" },
            profile.num("step_goal")?.let { "step goal ${i0(it)}" })
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /** Compact, dated evidence of at most [MAX_TOKENS] estimated tokens; details feed the status card. */
    fun build(status: JSONObject, contexts: Map<String, JSONObject>, freshness: HealthFreshness, zone: ZoneId): HealthEvidenceText {
        val flags = mutableListOf<String>()
        val chat = contexts["chat_reply"]
        val weeklyCtx = contexts["weekly_coach"]
        val primary = chat ?: weeklyCtx
        val windows = chat?.optJSONObject("windows")
        val header = when (freshness) {
            is HealthFreshness.Live -> "HEALTH DATA (Live, retrieved ${time(freshness.retrievedAt, zone)} ${zone.id})"
            is HealthFreshness.Cached -> "HEALTH DATA (Cached, retrieved ${time(freshness.retrievedAt, zone)} ${zone.id}; data ${freshness.start ?: "?"}..${freshness.end ?: "?"}; may be outdated)"
            is HealthFreshness.Unavailable -> error("no evidence when unavailable")
        }
        val core = mutableListOf(header, statusLine(status, primary?.text("timezone") ?: status.text("timezone"), primary?.text("timezone_source") ?: status.text("timezone_source")),
            "Day boundaries: dashboard daily totals (mostly UTC days); timezone only sets window dates.")
        windows?.optJSONObject("7d")?.let { core += window("7d", it, flags) }
        val thirty = windows?.optJSONObject("30d")?.let { window("30d", it, flags) }
        windows?.let { w ->
            val night = w.optJSONObject("last_night")
            core += if (night == null) "Last night: no sleep record dated today." else {
                val h = night.num("duration_hours")
                val flag = if (h != null && (h < 1 || h > 16)) " [flag: suspicious value]".also { flags += "last night sleep: suspicious value" } else ""
                "Last night ${safeDate(night.text("date")) ?: ""}: sleep ${h?.let { f1(it) } ?: "no data"} h$flag."
            }
            val today = w.optJSONObject("today")
            core += if (today == null || today.length() == 0) "Today (partial): no data yet." else {
                val steps = today.num("steps_total")?.let { "steps ${i0(it)} so far" } ?: "steps no data"
                val hrv = today.num("resting_heart_rate_avg")
                val hr = if (hrv == null) "resting HR no data" else "resting HR ${i0(hrv)} bpm" +
                    if (hrv < 30 || hrv > 200) " [flag: data-quality problem, do not interpret]".also { flags += "today resting HR: data-quality problem, do not interpret" } else ""
                "Today (partial) ${safeDate(today.text("metric_date")) ?: ""}: $steps; $hr."
            }
        }
        weeklyCtx?.optJSONObject("windows")?.let { w ->
            val parts = mutableListOf<String>()
            w.optJSONObject("14d")?.let { parts += window("14d", it, flags) }
            w.optJSONObject("streaks")?.let { s ->
                parts += if (s.isNull("steps_goal_streak_days")) "no confirmed step goal" else "step-goal streak ${s.optInt("steps_goal_streak_days")} days (goal ${s.optInt("goal")})"
            }
            val anomalies = w.optJSONArray("anomalies")
            parts += if (anomalies == null || anomalies.length() == 0) "anomalies: none" else "anomalies: " + (0 until anomalies.length()).mapNotNull { anomalies.optJSONObject(it) }.joinToString("; ") {
                "${safeDate(it.text("date")) ?: "?"} ${safeWord(it.text("type"))} ${it.num("value")?.let { v -> i0(v) } ?: "?"} vs avg ${it.num("avg")?.let { v -> i0(v) } ?: "?"}"
            }
            core += "[weekly_coach] " + parts.joinToString("; ")
        }
        core += NO_WORKOUTS
        var lines: List<String> = core.take(core.size - 1) + listOfNotNull(thirty) + core.last()
        // The 30-day window is the lowest priority; drop it before the budget gets tight.
        if (estimateTokens(lines.joinToString("\n")) > MAX_TOKENS - 200) lines = core
        val body = profileBody(primary?.optJSONObject("user_profile"))
        val room = MAX_TOKENS - estimateTokens(lines.joinToString("\n")) - 24
        val profile = when {
            body == null -> "Profile: no data."
            room >= 40 -> "<untrusted_profile>" + truncateToTokens(body, room) + "</untrusted_profile>"
            else -> "Profile: omitted (budget)."
        }
        val text = (lines + profile).joinToString("\n")

        val latest = windows?.optJSONObject("7d")?.optJSONObject("latest_date_by_metric")
        val details = buildList {
            add("Latest data: steps ${safeDate(latest?.text("steps")) ?: "none"}, sleep ${safeDate(latest?.text("sleep")) ?: "none"}, " +
                "resting HR ${safeDate(latest?.text("resting_hr")) ?: "none"}, weight ${safeDate(latest?.text("weight")) ?: "none"}")
            add("Days behind: ${if (status.isNull("days_behind")) "unknown" else status.optInt("days_behind").toString()}; today is partial: ${if (status.optBoolean("today_is_partial", false)) "yes" else "no"}")
            add("Connections: ${connections(status)}; reauthorization may be required: ${if (status.optBoolean("reauthorization_may_be_required", false)) "yes" else "no"}")
            add(DAY_NOTE)
            if (flags.isNotEmpty()) add("Data-quality flags: " + flags.distinct().joinToString("; "))
        }.joinToString("\n")
        return HealthEvidenceText(text, details)
    }

    const val UNAVAILABLE_PREFIX = "HEALTH DATA UNAVAILABLE: "
    fun unavailableText(reason: String) = "$UNAVAILABLE_PREFIX$reason. Do not state any personal health findings."
}
