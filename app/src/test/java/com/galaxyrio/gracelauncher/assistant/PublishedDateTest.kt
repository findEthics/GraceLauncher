package com.galaxyrio.gracelauncher.assistant

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class PublishedDateTest {
    @Test fun formatsSupportedDatesInLocalZone() {
        val zone = ZoneId.of("America/Los_Angeles")
        for (value in listOf("2026-11-03T02:00:00+01:00", "2026-11-03T01:00:00Z",
            "Mon, 2 Nov 2026 17:00:00 -0800", "2026-11-03T02:00:00+01:00[Europe/Berlin]")) {
            assertEquals(value, "11/2/26", formatPublishedDate(value, Locale.US, zone))
        }
        assertEquals("11/3/26", formatPublishedDate("2026-11-03", Locale.US, zone))
        assertNull(formatPublishedDate("not a date", Locale.US, zone))
        assertNull(formatPublishedDate(null, Locale.US, zone))
    }
}
