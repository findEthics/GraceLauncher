/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Adapted from Google AI Edge Gallery's ActionInputValidation.kt (package renamed; shortcut helpers removed).
package com.galaxyrio.gracelauncher.assistant

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale

internal object ActionInputValidation {
  private val dateTimePattern = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}")
  private val dateTimeFormatter =
    DateTimeFormatterBuilder()
      .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
      .toFormatter(Locale.ROOT)
      .withResolverStyle(ResolverStyle.STRICT)
  private val localTimePattern = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
  private val localTimeFormatter =
    DateTimeFormatterBuilder()
      .appendPattern("HH:mm")
      .toFormatter(Locale.ROOT)
      .withResolverStyle(ResolverStyle.STRICT)
  private val durationPart =
    Regex("(\\d+)\\s*(days?|d|hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)", RegexOption.IGNORE_CASE)
  private val numberWords =
    Regex("\\b(?:(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)(?:[ -](one|two|three|four|five|six|seven|eight|nine))?|zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen)\\b")
  private val clockQualifier =
    "(?:am|pm|noon|midnight|at\\s+night|(?:in\\s+the\\s+)?(?:morning|afternoon|evening|night)|tonight)"
  private val clockMention =
    Regex("(?<![\\p{L}\\d])(?:(quarter|half)\\s+(past|to)\\s+)?([+-]?\\d+)(?:(:|\\s+)(\\d+))?(?:\\s*($clockQualifier)\\b)?|\\b(noon|midnight)\\b")
  private val impreciseTime =
    Regex("\\b(?:or|between|before|after|around|about|approximately|instead|not|except|until)\\b")
  private val unsupportedDateMention =
    Regex("\\b(?:yesterday|tonight|after|next|this|on|every|daily|weekly|yearly|repeats?|repeating|recurring|within|later|from|following|coming|fortnight|weekdays?|weekends?|days?|weeks?|months?|years?|monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun|january|february|march|april|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\\b|\\bin\\s+(?!the\\s+(?:morning|afternoon|evening)\\b)|\\bmay\\s+\\d|\\d+[./-]\\d+|\\b\\d+(?:st|nd|rd|th)\\b|\\b\\d{4}\\b|\\bthe\\s+(?:first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth|thirteenth|fourteenth|fifteenth|sixteenth|seventeenth|eighteenth|nineteenth|twentieth|thirtieth)\\b")
  private val calendarDayMention = Regex("\\b(?:today|tomorrow)\\b")
  private val calendarNeutralDateWords =
    Regex("\\bthis\\b|\\b(?:in|on)\\s+(?:(?:a|my|the|google|work|personal)\\s+)*calendar\\b")
  private val spokenNumberValues =
    mapOf(
      "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
      "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
      "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
      "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
      "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
      "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

  private fun normalizeNumbers(prompt: String): String =
    numberWords.replace(prompt.lowercase(Locale.ROOT)) { match ->
      if (match.groupValues[1].isNotEmpty()) {
        (spokenNumberValues.getValue(match.groupValues[1]) +
          (spokenNumberValues[match.groupValues[2]] ?: 0)).toString()
      } else {
        spokenNumberValues.getValue(match.value).toString()
      }
    }

  fun parseLocalDateTime(value: String): LocalDateTime? {
    val input = value.trim()
    if (!dateTimePattern.matches(input)) return null
    return runCatching { LocalDateTime.parse(input, dateTimeFormatter) }.getOrNull()
  }

  fun parseLocalTime(value: String): LocalTime? {
    val input = value.trim()
    if (!localTimePattern.matches(input)) return null
    return runCatching { LocalTime.parse(input, localTimeFormatter) }.getOrNull()
  }

  /** Dayless past alarms roll to tomorrow; explicit days must match Clock's next occurrence. */
  fun alarmOccurrence(time: LocalTime, now: LocalDateTime, prompt: String): LocalDateTime? {
    val text = normalizeNumbers(prompt)
    if (unsupportedDateMention.containsMatchIn(text)) return null
    val days = calendarDayMention.findAll(text).toList()
    if (days.size > 1) return null
    val todayOccurrence = now.toLocalDate().atTime(time)
    val tomorrow = days.singleOrNull()?.value == "tomorrow" || (days.isEmpty() && todayOccurrence.isBefore(now))
    return if (tomorrow) {
      todayOccurrence.takeIf { it.isBefore(now) }?.plusDays(1)
    } else {
      todayOccurrence.takeIf { it.isAfter(now) }
    }
  }

  /** Accepts a matching clock for a future event today by default, or tomorrow when said. */
  fun calendarEventDateTime(value: String, prompt: String, now: LocalDateTime): LocalDateTime? {
    val datetime = parseLocalDateTime(value) ?: return null
    val spokenTime = parseSpokenClockTime(prompt) ?: return null
    val text = normalizeNumbers(prompt)
    if (unsupportedDateMention.containsMatchIn(text.replace(calendarNeutralDateWords, " "))) return null
    val days = calendarDayMention.findAll(text).toList()
    if (days.size > 1) return null
    val expectedDate =
      if (days.singleOrNull()?.value == "tomorrow") now.toLocalDate().plusDays(1)
      else now.toLocalDate()
    return datetime.takeIf {
      it.toLocalDate() == expectedDate && it.toLocalTime() == spokenTime && it.isAfter(now)
    }
  }

  fun toEpochMillis(dateTime: LocalDateTime, zone: ZoneId): Long? {
    val offsets = zone.rules.getValidOffsets(dateTime)
    if (offsets.size != 1) return null
    return runCatching { dateTime.toInstant(offsets.single()).toEpochMilli() }.getOrNull()
  }

  fun parseDurationMillis(value: String): Long? {
    val input = value.trim()
    if (input.isEmpty()) return null
    var cursor = 0
    var parts = 0
    var totalMillis = 0L
    for (match in durationPart.findAll(input)) {
      if (input.substring(cursor, match.range.first).any { !it.isWhitespace() }) return null
      val amount = match.groupValues[1].toLongOrNull() ?: return null
      val multiplier =
        when (match.groupValues[2].lowercase(Locale.ROOT)) {
          "d", "day", "days" -> MILLIS_PER_DAY
          "h", "hr", "hrs", "hour", "hours" -> MILLIS_PER_HOUR
          "m", "min", "mins", "minute", "minutes" -> MILLIS_PER_MINUTE
          else -> MILLIS_PER_SECOND
        }
      totalMillis =
        try {
          Math.addExact(totalMillis, Math.multiplyExact(amount, multiplier))
        } catch (error: ArithmeticException) {
          return null
        }
      cursor = match.range.last + 1
      parts++
    }
    if (parts == 0 || input.substring(cursor).isNotBlank() || totalMillis <= 0L) return null
    return totalMillis
  }

  fun timerLengthSeconds(durationMillis: Long): Int? {
    if (durationMillis !in MILLIS_PER_SECOND..MILLIS_PER_DAY ||
      durationMillis % MILLIS_PER_SECOND != 0L
    ) return null
    return (durationMillis / MILLIS_PER_SECOND).toInt()
  }

  fun durationMentionMillis(prompt: String): Long? {
    val normalized =
      normalizeNumbers(prompt).replace(Regex("\\b(?:a|an)\\s+(?=days?\\b|hours?\\b|minutes?\\b|seconds?\\b)"), "1 ")
    if (impreciseTime.containsMatchIn(normalized)) return null
    val mentions = durationPart.findAll(normalized).toList()
    if (mentions.isEmpty()) return null
    val prefix = normalized.substring(0, mentions.first().range.first)
    if (!Regex("(?:^|\\b(?:for|in|timer|set|start|a|an|the))\\s*$").containsMatchIn(prefix)) return null
    val suffix = normalized.substring(mentions.last().range.last + 1)
    if (!Regex("^\\s*(?:$|[.!?]|\\b(?:timer|please|called|named|labelled|labeled)\\b)").containsMatchIn(suffix)) return null
    var previousUnit = MILLIS_PER_DAY + 1
    for ((index, match) in mentions.withIndex()) {
      if (normalized.getOrNull(match.range.last + 1)?.isLetter() == true) return null
      val previous = mentions.getOrNull(index - 1)
      if (previous != null) {
        val gap = normalized.substring(previous.range.last + 1, match.range.first)
        if (!Regex("\\s*(?:,\\s*)?(?:and\\s+)?").matches(gap)) return null
      }
      val unit = parseDurationMillis("1 ${match.groupValues[2]}") ?: return null
      // One compound duration, in descending units; never sum separate timer choices.
      if (unit >= previousUnit) return null
      previousUnit = unit
    }
    return parseDurationMillis(mentions.joinToString(" ") { it.value })
  }

  fun parseSpokenClockTime(prompt: String): LocalTime? {
    val text = normalizeNumbers(prompt)
      .replace(Regex("(?<!\\p{L})([ap])\\.?\\s*m\\.?(?!\\p{L})"), "$1m")
      .replace(Regex("\\b(quarter|half)\\s+(past|to)\\s+(noon|midnight)\\b")) {
        "${it.groupValues[1]} ${it.groupValues[2]} 12 ${it.groupValues[3]}"
      }
    if (impreciseTime.containsMatchIn(text)) return null
    if (Regex("\\b\\d+\\s+(?:past|to)\\s+").containsMatchIn(text)) return null
    val times = mutableListOf<LocalTime>()
    val ranges = mutableListOf<IntRange>()
    for (match in clockMention.findAll(text)) {
      val word = match.groupValues[7]
      val fraction = match.groupValues[1]
      val direction = match.groupValues[2]
      val hourText = match.groupValues[3]
      val separator = match.groupValues[4]
      val minuteText = match.groupValues[5]
      val qualifier = match.groupValues[6].replace(Regex("\\s+"), " ")
      val prefix = text.substring(0, match.range.first)
      if (Regex("\\b(?:half|quarter)\\s*$").containsMatchIn(prefix)) return null
      if (word.isEmpty() && fraction.isEmpty() && separator != ":" && qualifier.isEmpty() &&
        !Regex("\\b(?:at|for)\\s*$").containsMatchIn(prefix)
      ) continue
      if (prefix.lastOrNull() == '.' || prefix.lastOrNull() == ':') return null
      val after = text.getOrNull(match.range.last + 1)
      if (after?.isLetterOrDigit() == true || after == ':' ||
        (after == '.' && text.getOrNull(match.range.last + 2)?.isDigit() == true)
      ) return null
      if (word.isNotEmpty()) {
        times += if (word == "noon") LocalTime.NOON else LocalTime.MIDNIGHT
      } else {
        if (hourText.startsWith('+') || hourText.startsWith('-')) return null
        val hour = hourText.toIntOrNull() ?: return null
        val minute = if (minuteText.isEmpty()) 0 else minuteText.toIntOrNull() ?: return null
        if (hourText.length > 2 || minuteText.length > 2 || hour !in 0..23 || minute !in 0..59 ||
          (separator == ":" && minuteText.length != 2) ||
          (fraction.isNotEmpty() && separator.isNotEmpty())
        ) return null
        val hour24 = when {
          qualifier == "noon" -> if (hour == 12 && minute == 0) 12 else return null
          qualifier == "midnight" -> if (hour in setOf(0, 12) && minute == 0) 0 else return null
          qualifier.isEmpty() ->
            if (hour == 0 || hour > 12 || (separator == ":" && hourText.length == 2 && hourText.startsWith('0'))) hour
            else return null
          hour !in 1..12 -> return null
          qualifier == "am" || qualifier.endsWith("morning") -> hour % 12
          qualifier.endsWith("evening") && hour == 12 -> return null
          qualifier == "pm" || qualifier.endsWith("afternoon") || qualifier.endsWith("evening") -> hour % 12 + 12
          qualifier.endsWith("night") ->
            if (hour == 12) 0 else if (hour in 5..11) hour + 12 else return null
          else -> return null
        }
        var time = LocalTime.of(hour24, minute)
        if (fraction.isNotEmpty()) {
          if (direction == "to" && fraction != "quarter") return null
          val offset = if (fraction == "quarter") 15L else 30L
          time = if (direction == "to") time.minusMinutes(offset) else time.plusMinutes(offset)
        }
        times += time
      }
      ranges += match.range
    }
    // A second qualifier must not be silently discarded (e.g. "7 PM in the morning").
    if (Regex("\\b$clockQualifier\\b").findAll(text).any { qualifier ->
      ranges.none { qualifier.range.first in it && qualifier.range.last in it }
    }) return null
    return times.singleOrNull()
  }

  private const val MILLIS_PER_SECOND = 1_000L
  private const val MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND
  private const val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE
  private const val MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR
}
