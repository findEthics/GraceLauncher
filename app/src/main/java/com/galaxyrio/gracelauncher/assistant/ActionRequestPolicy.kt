package com.galaxyrio.gracelauncher.assistant

import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * Pure (JVM-testable) check that a structurally valid tool call is what the RAW English request asked for.
 * Conservative by design: negation, several commands, or unverifiable values are rejected, never guessed.
 * Messages are fixed strings and never echo private input.
 */
internal object ActionRequestPolicy {
    private val negation = Regex("\\b(?:don'?t|do not|dont|never|not|no|without|cancel|instead|except|unless|rather|but)\\b|n't")
    private val multi = Regex("\\b(?:then|also|after that|afterwards|next)\\b|;|\\band\\s+(?:set|start|create|save|add|call|dial|turn|switch|open|send|write|show|navigate|delete|buy|play)\\b")
    private val categories = mapOf(
        "flashlight" to Regex("\\b(?:flashlight|torch)\\b"),
        "timer" to Regex("\\btimers?\\b"),
        "alarm" to Regex("\\balarms?\\b|\\bwake me up\\b"),
        "call" to Regex("\\b(?:call|dial|ring)\\b|^(?:please\\s+)?phone\\b"),
        "contact" to Regex("\\bcontacts?\\b"),
        "email" to Regex("^(?:please\\s+)?e-?mail\\b|\\b(?:send|write|compose|draft|open)\\s+(?:an?\\s+|the\\s+)?e-?mail\\b"),
        "map" to Regex("\\b(?:maps?|directions?|navigate)\\b"),
        "wifi" to Regex("\\bwi-?fi\\b"),
        "calendar" to Regex("\\b(?:calendar|events?|appointments?|meetings?|schedule)\\b"),
    )
    private val categoryOf = mapOf(
        "turnOnFlashlight" to "flashlight", "turnOffFlashlight" to "flashlight", "createTimer" to "timer",
        "createAlarm" to "alarm", "callContact" to "call", "createContact" to "contact", "sendEmail" to "email",
        "showLocationOnMap" to "map", "openWifiSettings" to "wifi", "createCalendarEvent" to "calendar",
    )
    private val callRequest = Regex(
        "^(?:(?:please|hey|ok|okay)\\s+)*(?:(?:can|could|would|will)\\s+you\\s+)?(?:please\\s+)?(?:call|dial|ring)\\s+(.+?)(?:\\s+please)?$")
    private val callLabel = Regex(
        "^(.+?)\\s+(?:on|at|using)\\s+(?:(?:his|her|their|my|the)\\s+)?(mobile|cell|home|work|other|main)(?:\\s+(?:number|phone|line))?$")
    private val phoneInText = Regex("(?<![\\w+])\\+?\\d[\\d ().-]{4,}\\d")
    private val emailInText = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+")
    private val nameChars = Regex("[\\p{L}\\p{M}'.\\- ]+")
    private val fillerTitle = setOf("at", "on", "for", "tomorrow", "today", "pm", "am", "the", "a", "an", "and", "my", "to", "in", "of")

    fun fail(): Nothing = throw IllegalArgumentException("That request is unsupported or ambiguous. Nothing was done.")
    private fun need(ok: Boolean, message: String = "That request did not match the action. Nothing was done.") {
        if (!ok) throw IllegalArgumentException(message)
    }

    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace('\u2019', '\'').trim()

    fun tokens(value: String): List<String> = normalize(value).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** Token-boundary containment of [needle] inside [haystack] (never substring: "John" is not in "Johnson"). */
    fun containsTokens(haystack: String, needle: String): Boolean {
        val n = tokens(needle)
        if (n.isEmpty()) return false
        val h = tokens(haystack)
        return (0..h.size - n.size).any { h.subList(it, it + n.size) == n }
    }

    fun canonicalLabel(value: String): String =
        ContactCallResolver.normalizeDisplayName(value).let { if (it == "cell") "mobile" else it }

    data class CallTarget(val name: String, val label: String)

    /** Complete saved-name target and optional spoken phone label from the raw request; null when not a plain call. */
    fun callTarget(request: String): CallTarget? {
        val text = normalize(request).trimEnd('.', '!', '?', ' ')
        val rest = callRequest.matchEntire(text)?.groupValues?.get(1) ?: return null
        val labelled = callLabel.matchEntire(rest)
        val name = tokens(labelled?.groupValues?.get(1) ?: rest).joinToString(" ")
        if (name.none(Char::isLetter)) return null // numbers (e.g. emergency digits) are never saved-name targets
        return CallTarget(name, labelled?.groupValues?.get(2)?.let(::canonicalLabel).orEmpty())
    }

    fun validate(action: MobileAction, request: String, now: LocalDateTime, zone: ZoneId) {
        need(request.isNotBlank() && request.length <= 1000 && request.none(Char::isISOControl), "No request text; nothing was done.")
        val text = normalize(request)
        need(!negation.containsMatchIn(text) && !multi.containsMatchIn(text))
        val target = categoryOf[action.name] ?: fail()
        val found = categories.filterValues { it.containsMatchIn(text) }.keys
        need(found == setOf(target) || (action.name == "createContact" && found == setOf("contact")))
        val a = action.arguments
        val command = text.trimEnd('.', '!', '?').replace(Regex("^(?:(?:please|hey|ok|okay|can you|could you|would you|will you)\\s+)+"), "").removeSuffix(" please")
        when (action.name) {
            "turnOnFlashlight", "turnOffFlashlight" -> {
                val direction = if (action.name == "turnOnFlashlight") "on" else "off"
                val verb = if (direction == "on") "enable" else "disable"
                need(Regex("(?:(?:turn|switch) $direction (?:the )?(?:flashlight|torch)|(?:turn |switch )?(?:the )?(?:flashlight|torch) $direction|$verb (?:the )?(?:flashlight|torch))").matches(command))
            }
            "openWifiSettings" -> need(Regex("(?:open|show) (?:the )?wi-?fi(?: settings)?").matches(command), "Only opening Wi-Fi settings is supported. Nothing was changed.")
            "createTimer" -> {
                need(Regex("^(?:(?:set|start|create|begin)\\b|timer\\b)").containsMatchIn(command))
                val spoken = ActionInputValidation.durationMentionMillis(request)?.let(ActionInputValidation::timerLengthSeconds)
                val given = ActionInputValidation.parseDurationMillis(a.getValue("duration"))?.let(ActionInputValidation::timerLengthSeconds)
                need(spoken != null && spoken == given, "Say one explicit timer duration from 1 second to 24 hours that matches. No timer was set.")
                groundedLabel(a.getValue("label"), text)
            }
            "createAlarm" -> {
                need(Regex("^(?:(?:set|create|schedule)\\b|wake me up\\b|alarm\\b)").containsMatchIn(command))
                val given = ActionInputValidation.parseLocalTime(a.getValue("time"))
                val spoken = ActionInputValidation.parseSpokenClockTime(request)
                need(given != null && given == spoken, "Say an explicit alarm time that matches. No alarm was set.")
                need(ActionInputValidation.alarmOccurrence(given!!, now, request) != null,
                    "Alarms are only for a time ahead today, or tomorrow after that time passed today. No alarm was set.")
                groundedLabel(a.getValue("label"), text)
            }
            "callContact" -> {
                val spoken = callTarget(request) ?: fail()
                need(tokens(a.getValue("contactName")).joinToString(" ") == spoken.name, "The contact name did not match what you said. Nothing was called.")
                need(canonicalLabel(a.getValue("phoneLabel")) == spoken.label, "The phone label did not match what you said. Nothing was called.")
            }
            "createContact" -> {
                need(Regex("^(?:create|add|save)\\b").containsMatchIn(command))
                contact(a, request, text)
            }
            "sendEmail" -> need(a.getValue("to").lowercase(Locale.ROOT) in emailInText.findAll(request).map { it.value.lowercase(Locale.ROOT) }.toSet())
            "createCalendarEvent" -> {
                need(Regex("^(?:create|add|save|make|schedule)\\b").containsMatchIn(command))
                // ponytail: fixed 15-minute events; reject explicit durations rather than silently changing them.
                val duration = Regex("\\b(?:for|lasting|duration)\\s+(.+)").find(text)?.groupValues?.get(1)
                if (duration != null) need(ActionInputValidation.durationMentionMillis("timer for $duration") == 15 * 60 * 1000L,
                    "Only 15-minute calendar events are supported. No event was created.")
                else need(!Regex("\\b(?:hours?|minutes?|seconds?|hrs?|mins?|secs?)\\b").containsMatchIn(text),
                    "Use a plain 15-minute event without a duration modifier. No event was created.")
                need(!Regex("\\b(?:invite|attendees?|remind|reminders?|all.day)\\b").containsMatchIn(text),
                    "Attendees, reminders and all-day events are unsupported. No event was created.")
                val start = ActionInputValidation.calendarEventDateTime(a.getValue("datetime"), request, now)
                need(start != null && ActionInputValidation.toEpochMillis(start, zone) != null,
                    "Use a future event time today or tomorrow that matches what you said and is not affected by a clock change. No event was created.")
                val title = a.getValue("title").trim()
                need(title.length <= 200 && containsTokens(text, title) && !tokens(title).all { it in fillerTitle || it.all(Char::isDigit) },
                    "The event title must come from what you said. No event was created.")
            }
        }
    }

    private fun groundedLabel(label: String, text: String) {
        need(label.trim().length <= 80 && (label.isBlank() || containsTokens(text, label)), "The label must come from what you said.")
    }

    private fun contact(a: Map<String, String>, request: String, text: String) {
        need(!Regex("\\b(?:home|work|mobile|cell|main)\\s+(?:phone|number)\\b").containsMatchIn(text),
            "Contact phone-label selection is unsupported. Say the name and phone number only.")
        val first = a.getValue("firstName").trim()
        val last = a.getValue("lastName").trim()
        need(first.length <= 100 && last.length <= 100 && nameChars.matches("$first $last"), "The contact name must be plain text from what you said.")
        need(containsTokens(text, "$first $last"), "The contact name must come from what you said. No contact was saved.")
        val spokenPhones = phoneInText.findAll(request).map { ContactCallResolver.normalizePhoneNumber(it.value) }.toSet()
        val phone = a.getValue("phoneNumber").trim()
        need(spokenPhones == if (phone.isEmpty()) emptySet() else setOf(ContactCallResolver.normalizePhoneNumber(phone)).also { need(null !in it) },
            "The phone number must match what you said. No contact was saved.")
        val spokenEmails = emailInText.findAll(request).map { it.value.lowercase(Locale.ROOT) }.toSet()
        val email = a.getValue("email").trim().lowercase(Locale.ROOT)
        need(spokenEmails == if (email.isEmpty()) emptySet() else setOf(email), "The email must match what you said. No contact was saved.")
    }

    /** Order-insensitive normalized name key, so "Lovelace Ada" duplicates "Ada Lovelace". */
    fun nameKey(value: String): String = tokens(value).sorted().joinToString(" ")
}
