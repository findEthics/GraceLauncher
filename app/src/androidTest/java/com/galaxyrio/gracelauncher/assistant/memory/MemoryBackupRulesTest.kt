package com.galaxyrio.gracelauncher.assistant.memory

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Written and compiled only (Gate D). Both rule files must exclude the mirror DB and its sidecar files. */
class MemoryBackupRulesTest {
    private fun excludes(resName: String, section: String?): Set<String> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parser = context.resources.getXml(context.resources.getIdentifier(resName, "xml", context.packageName))
        val found = mutableSetOf<String>(); var current: String? = null
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                if (parser.name == "cloud-backup" || parser.name == "device-transfer" || parser.name == "full-backup-content") current = parser.name
                if (parser.name == "exclude" && (section == null || section == current) && parser.getAttributeValue(null, "domain") == "database")
                    found += parser.getAttributeValue(null, "path")
            }
        }
        return found
    }

    private val expected = setOf("memory_mirror.db", "memory_mirror.db-wal", "memory_mirror.db-shm", "memory_mirror.db-journal")

    @Test fun fullBackupContentExcludesMirror() = assertEquals(expected, excludes("backup_rules", null))
    @Test fun cloudBackupExcludesMirror() = assertEquals(expected, excludes("data_extraction_rules", "cloud-backup"))
    @Test fun deviceTransferExcludesMirror() = assertEquals(expected, excludes("data_extraction_rules", "device-transfer"))
}
