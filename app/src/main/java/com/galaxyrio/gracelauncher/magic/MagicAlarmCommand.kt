package com.galaxyrio.gracelauncher.magic

import com.galaxyrio.gracelauncher.assistant.ActionInputValidation
import com.katoaapps.openminilaunch.features.calendar.engine.CalendarPhraseEngine
import com.katoaapps.openminilaunch.features.calendar.language.CalendarLanguageRegistry
import com.katoaapps.openminilaunch.features.calendar.model.CalendarParseRequest
import com.katoaapps.openminilaunch.features.calendar.model.CalendarParseResult
import java.time.Instant
import java.time.ZoneId
import java.time.LocalTime
import java.time.LocalDate
import java.time.ZonedDateTime

internal sealed interface MagicAlarmCommand {
    data class Alarm(val hour: Int, val minute: Int, val message: String? = null, val tomorrow: Boolean = false) : MagicAlarmCommand
    data class WrongDay(val hour: Int, val minute: Int, val tomorrow: Boolean) : MagicAlarmCommand
    data class Timer(val seconds: Int) : MagicAlarmCommand

    companion object {
        private val alarmPrefix = Regex("^(?:wake me up at|wake me at|set an alarm for|set alarm for|alarm at|alarm for|alarm)\\s+(.+)$", RegexOption.IGNORE_CASE)
        private val timerPrefix = Regex("^timer\\s+(?:for\\s+)?(.+)$", RegexOption.IGNORE_CASE)
        private val fractionTime = Regex("^(half past|quarter past|quarter to)\\s+(\\d{1,2})$")
        private val literalQualifier = Regex("(?<![a-z])[ap]\\.?\\s*m\\.?(?![a-z])|\\b(noon|midnight|morning|afternoon|evening|tonight)\\b")
        private val hourMention = Regex("\\b\\d{1,2}\\b")

        fun next(t: LocalTime, now: ZonedDateTime): ZonedDateTime {
            val today = ZonedDateTime.of(now.toLocalDate(), t, now.zone)
            return if (today.isAfter(now)) today else today.plusDays(1)
        }

        fun parse(input: String, nowMillis: Long = System.currentTimeMillis(),
            zoneId: ZoneId = ZoneId.systemDefault()): MagicAlarmCommand? {
            val text = input.trim()
            if (text.length > 1000 || text.any(Char::isISOControl)) return null
            timerPrefix.matchEntire(text)?.let {
                val duration = ActionInputValidation.durationMentionMillis("timer ${it.groupValues[1]}") ?: return null
                return ActionInputValidation.timerLengthSeconds(duration)?.let(::Timer)
            }
            val rest = alarmPrefix.matchEntire(text)?.groupValues?.get(1)?.trim() ?: return null
            val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
            val normalized = ActionInputValidation.normalizeNumbers(rest)
            val temporal = CalendarLanguageRegistry.reference().parse(normalized)
            val clockSpan = temporal.times.singleOrNull()?.span
            val clockText = clockSpan?.let {
                normalized.substring(it.startInclusive, it.endExclusive)
            } ?: normalized
            val typedHour = hourMention.findAll(clockText).firstOrNull { match ->
                clockSpan != null || temporal.dates.none {
                    match.range.first >= it.span.startInclusive && match.range.first < it.span.endExclusive
                }
            }?.value
            val ambiguous = !literalQualifier.containsMatchIn(normalized) &&
                typedHour?.toIntOrNull() in 1..12 && !typedHour.orEmpty().startsWith('0')
            fun resolve(time: LocalTime, day: LocalDate? = null, message: String? = null): MagicAlarmCommand? {
                if (!ambiguous && time.hour == now.hour && time.minute == now.minute) return null
                val candidates = if (ambiguous) listOf(LocalTime.of(time.hour % 12, time.minute),
                    LocalTime.of(time.hour % 12 + 12, time.minute)) else listOf(time)
                val upcoming = candidates.map { next(it, now) }.sorted()
                val chosen = upcoming.firstOrNull { day == null || it.toLocalDate() == day }
                if (chosen == null) {
                    val first = upcoming.first()
                    return WrongDay(first.hour, first.minute, first.toLocalDate() != now.toLocalDate())
                }
                return Alarm(chosen.hour, chosen.minute, message, chosen.toLocalDate() != now.toLocalDate())
            }
            // ! commands are English-only; no German Calendar language exists.
            fun calendar(phrase: String) = CalendarPhraseEngine.parse(CalendarParseRequest(phrase, "", "en", now, zoneId))
            var parsed = calendar(rest)
            // Bare hours need Calendar's "at" rule; colon/meridian times keep their existing rules.
            if (parsed is CalendarParseResult.NoTemporalPhrase) parsed = calendar("at $rest")
            if ((parsed is CalendarParseResult.Success && parsed.draft.allDay) || parsed is CalendarParseResult.NoTemporalPhrase) {
                val bare = hourMention.findAll(normalized).singleOrNull { match ->
                    temporal.dates.none { match.range.first >= it.span.startInclusive && match.range.first < it.span.endExclusive }
                }
                if (bare != null && (temporal.dates.isNotEmpty() || normalized == bare.value))
                    parsed = calendar(normalized.replaceRange(bare.range, "at ${bare.value}"))
            }
            if (parsed is CalendarParseResult.Success) {
                val draft = parsed.draft
                if (draft.allDay) return null
                val time = draft.startMillis?.let { Instant.ofEpochMilli(it).atZone(zoneId) } ?: return null
                // Calendar's prefixed-hour heuristic also shifts 07:00; its leading zero is literal here.
                val hour = if (!literalQualifier.containsMatchIn(normalized) && typedHour?.startsWith('0') == true)
                    typedHour.toInt() else time.hour
                return resolve(LocalTime.of(hour, time.minute), time.toLocalDate().takeIf { temporal.dates.isNotEmpty() },
                    draft.title.takeIf { it.isNotBlank() && it != "New event" })
            }
            if (parsed !is CalendarParseResult.NoTemporalPhrase) return null
            ActionInputValidation.parseSpokenClockTime(rest)?.let { return resolve(it) }
            val fraction = fractionTime.matchEntire(ActionInputValidation.normalizeNumbers(rest)) ?: return null
            val base = calendar("at ${fraction.groupValues[2]}") as? CalendarParseResult.Success ?: return null
            val start = base.draft.startMillis?.let { Instant.ofEpochMilli(it).atZone(zoneId) } ?: return null
            val offset = when (fraction.groupValues[1]) { "half past" -> 30L; "quarter past" -> 15L; else -> -15L }
            val time = LocalTime.of(start.hour, start.minute).plusMinutes(offset)
            return resolve(time)
        }
    }
}
