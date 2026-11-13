package com.galaxyrio.gracelauncher.magic

import com.galaxyrio.gracelauncher.assistant.ActionInputValidation
import com.katoaapps.openminilaunch.features.calendar.engine.CalendarPhraseEngine
import com.katoaapps.openminilaunch.features.calendar.model.CalendarParseRequest
import com.katoaapps.openminilaunch.features.calendar.model.CalendarParseResult
import java.time.Instant
import java.time.ZoneId
import java.time.LocalTime

internal sealed interface MagicAlarmCommand {
    data class Alarm(val hour: Int, val minute: Int, val message: String? = null) : MagicAlarmCommand
    data class Timer(val seconds: Int) : MagicAlarmCommand

    companion object {
        private val alarmPrefix = Regex("^(?:wake me up at|wake me at|set an alarm for|set alarm for|alarm at|alarm for|alarm)\\s+(.+)$", RegexOption.IGNORE_CASE)
        private val timerPrefix = Regex("^timer\\s+(?:for\\s+)?(.+)$", RegexOption.IGNORE_CASE)
        private val fractionTime = Regex("^(half past|quarter past|quarter to)\\s+(\\d{1,2})$")

        fun parse(input: String, language: String = "en", nowMillis: Long = System.currentTimeMillis(),
            zoneId: ZoneId = ZoneId.systemDefault()): MagicAlarmCommand? {
            val text = input.trim()
            if (text.length > 1000 || text.any(Char::isISOControl)) return null
            timerPrefix.matchEntire(text)?.let {
                val duration = ActionInputValidation.durationMentionMillis("timer ${it.groupValues[1]}") ?: return null
                return ActionInputValidation.timerLengthSeconds(duration)?.let(::Timer)
            }
            val rest = alarmPrefix.matchEntire(text)?.groupValues?.get(1)?.trim() ?: return null
            val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
            fun calendar(phrase: String) = CalendarPhraseEngine.parse(CalendarParseRequest(phrase, "", language, now, zoneId))
            var parsed = calendar(rest)
            // Bare hours need Calendar's "at" rule; colon/meridian times keep their existing rules.
            if (parsed is CalendarParseResult.NoTemporalPhrase) parsed = calendar("at $rest")
            if (parsed is CalendarParseResult.Success) {
                val draft = parsed.draft
                if (draft.allDay) return null
                val time = draft.startMillis?.let { Instant.ofEpochMilli(it).atZone(zoneId) } ?: return null
                return Alarm(time.hour, time.minute, draft.title.takeIf { it.isNotBlank() && it != "New event" })
            }
            if (parsed !is CalendarParseResult.NoTemporalPhrase) return null
            ActionInputValidation.parseSpokenClockTime(rest)?.let { return Alarm(it.hour, it.minute) }
            val fraction = fractionTime.matchEntire(ActionInputValidation.normalizeNumbers(rest)) ?: return null
            val base = calendar("at ${fraction.groupValues[2]}") as? CalendarParseResult.Success ?: return null
            val start = base.draft.startMillis?.let { Instant.ofEpochMilli(it).atZone(zoneId) } ?: return null
            val offset = when (fraction.groupValues[1]) { "half past" -> 30L; "quarter past" -> 15L; else -> -15L }
            val time = LocalTime.of(start.hour, start.minute).plusMinutes(offset)
            return Alarm(time.hour, time.minute)
        }
    }
}
