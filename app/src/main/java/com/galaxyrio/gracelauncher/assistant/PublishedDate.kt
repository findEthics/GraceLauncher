package com.galaxyrio.gracelauncher.assistant

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun formatPublishedDate(value: String?, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String? {
    if (value == null) return null
    val date = runCatching { ZonedDateTime.parse(value).withZoneSameInstant(zone).toLocalDate() }
        .recoverCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).withZoneSameInstant(zone).toLocalDate() }
        .recoverCatching { LocalDate.parse(value) }.getOrNull() ?: return null
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(date)
}
